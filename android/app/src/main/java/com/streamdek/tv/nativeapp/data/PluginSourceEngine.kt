package com.streamdek.tv.nativeapp.data

import android.content.Context
import com.dokar.quickjs.binding.function
import com.dokar.quickjs.quickJs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Number of plugin providers allowed to run at once. TV boxes have far less headroom than a
 * phone, and each provider spins up its own QuickJS context plus network calls.
 */
// Match mobile's fan-out. Five keeps first-result latency low without overwhelming TV hardware.
private const val MAX_CONCURRENT_PLUGIN_PROVIDERS = 5

/** Matches the mobile app's per-provider budget for a stream lookup. */
private const val PLUGIN_PROVIDER_TIMEOUT_MS = 60_000L

/** Reading a settings schema runs no scraping, so it gets a much shorter budget. */
private const val PLUGIN_SETTINGS_TIMEOUT_MS = 15_000L

/** One field a provider asks the viewer to fill in — an API key, a region, a toggle. */
data class PluginSettingField(
    val type: String,
    val key: String?,
    val label: String,
    val description: String? = null,
    val placeholder: String? = null,
    val defaultValue: String? = null,
    val isPassword: Boolean = false,
    val options: List<PluginSettingOption> = emptyList(),
)

data class PluginSettingOption(val label: String, val value: String)

/**
 * Rewrites the ES module syntax plugin sources are authored in into the CommonJS shape the
 * sandbox's `require`/`module.exports` boilerplate expects. Kept byte-for-byte in step with the
 * mobile implementation so a provider that runs on the phone runs identically here.
 */
internal fun normalizePluginJavaScript(source: String): String {
    var normalized = source
    normalized = normalized.replace(
        Regex("(?m)^\\s*import\\s+([A-Za-z_$][\\w$]*)\\s+from\\s+(['\"][^'\"]+['\"])\\s*;?\\s*$"),
        "const $1 = require($2);",
    )
    normalized = normalized.replace(
        Regex("(?m)^\\s*import\\s+\\*\\s+as\\s+([A-Za-z_$][\\w$]*)\\s+from\\s+(['\"][^'\"]+['\"])\\s*;?\\s*$"),
        "const $1 = require($2);",
    )
    normalized = normalized.replace(
        Regex("(?m)^\\s*import\\s+\\{([^}]+)\\}\\s+from\\s+(['\"][^'\"]+['\"])\\s*;?\\s*$"),
    ) { match ->
        val bindings = match.groupValues[1].split(',').joinToString(",") { binding ->
            val parts = binding.trim().split(Regex("\\s+as\\s+"), limit = 2)
            if (parts.size == 2) "${parts[0]}: ${parts[1]}" else parts[0]
        }
        "const {$bindings} = require(${match.groupValues[2]});"
    }
    normalized = normalized.replace(Regex("(?m)^\\s*export\\s+\\{([^}]+)\\}\\s*;?\\s*$")) { match ->
        val bindings = match.groupValues[1].split(',').joinToString(",") { binding ->
            val parts = binding.trim().split(Regex("\\s+as\\s+"), limit = 2)
            if (parts.size == 2) "${parts[1]}: ${parts[0]}" else parts[0]
        }
        "module.exports = {$bindings};"
    }
    normalized = normalized.replace(Regex("(?m)^\\s*export\\s+default\\s+"), "module.exports.default = ")
    return normalized
}

/** Resolves a manifest's `filename` entry against the collection URL it was listed in. */
internal fun resolvePluginProviderUrl(repositoryUrl: String, filename: String): String {
    val trimmed = filename.trim()
    require(trimmed.isNotBlank()) { "Provider filename is missing." }
    if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) return trimmed
    val manifest = URI(repositoryUrl)
    val resolved = manifest.resolve(trimmed.trimStart('/'))
    if (resolved.query != null || manifest.query == null) return resolved.toString()
    return URI(resolved.scheme, resolved.userInfo, resolved.host, resolved.port, resolved.path, manifest.query, resolved.fragment).toString()
}

/**
 * Whether a source exports `onSettings`, whatever its manifest claims.
 *
 * `hasSettings` is advisory and collections forget it. A source that needs an API token or a
 * cookie but is listed without the flag leaves nowhere to type one in, and the only symptom is a
 * source that returns no streams -- so trust the code over the listing.
 */
internal fun pluginDeclaresSettings(code: String): Boolean =
    Regex("""(?:\bfunction\s+onSettings\b)|(?:\bonSettings\s*[:=])""").containsMatchIn(code)

/** `series`/`show` are the same thing as `tv` to a plugin source. */
internal fun normalizePluginType(value: String): String =
    if (value.trim().lowercase(Locale.US) in setOf("series", "show")) "tv" else value.trim().lowercase(Locale.US)

/**
 * Providers this profile's synced state says should answer a lookup of [type].
 *
 * Same rule the mobile app applies: the whole plugin feature has to be on, the collection the
 * provider came from has to be enabled, and the provider itself has to be enabled and claim the
 * requested type.
 */
internal fun eligiblePluginProviders(state: ProfilePluginState?, type: String): List<ProfilePluginProvider> {
    if (state == null || !state.enabled) return emptyList()
    val normalized = normalizePluginType(type)
    val enabledRepos = state.repos.filter { it.enabled }.mapTo(mutableSetOf()) { it.url }
    return state.providers.filter { provider ->
        provider.enabled &&
            provider.repoUrl in enabledRepos &&
            provider.types.any { candidate -> normalizePluginType(candidate) == normalized }
    }
}

private val CHEERIO_COMPAT_SHIM = """
  function __sdIds(raw){try{return JSON.parse(raw||'[]')}catch(e){return []}}
  function __sdToken(id){return {__sd_node:Number(id)}}
  function __sdWrap(ids){
    ids=(ids||[]).map(Number).filter(function(id){return id>0});
    var api={__sd_ids:ids};
    Object.defineProperty(api,'length',{get:function(){return ids.length}});
    api.get=function(index){if(index===undefined)return ids.map(__sdToken);var i=Number(index);if(i<0)i=ids.length+i;return i>=0&&i<ids.length?__sdToken(ids[i]):undefined};
    api.toArray=function(){return api.get()};
    api.first=function(){return __sdWrap(ids.length?[ids[0]]:[])};
    api.last=function(){return __sdWrap(ids.length?[ids[ids.length-1]]:[])};
    api.eq=function(index){var item=api.get(index);return __sdWrap(item?[item.__sd_node]:[])};
    api.find=function(selector){var out=[];ids.forEach(function(id){out=out.concat(__sdIds(__sd_dom_select(id,String(selector))))});return __sdWrap(out.filter(function(id,index,list){return list.indexOf(id)===index}))};
    api.filter=function(test){if(typeof test==='function')return __sdWrap(ids.filter(function(id,index){return !!test(index,__sdToken(id))}));return __sdWrap(ids.filter(function(id){return !!__sd_dom_matches(id,String(test))}))};
    api.each=function(callback){ids.forEach(function(id,index){callback(index,__sdToken(id))});return api};
    api.map=function(callback){var values=[];ids.forEach(function(id,index){var value=callback(index,__sdToken(id));if(value!==undefined&&value!==null)values.push(value)});return {get:function(){return values},toArray:function(){return values}}};
    api.attr=function(name){return ids.length?__sd_dom_attr(ids[0],String(name)):undefined};
    api.text=function(){return ids.map(function(id){return __sd_dom_text(id)}).join('')};
    api.html=function(){return ids.length?__sd_dom_html(ids[0]):null};
    api.children=function(selector){var out=[];ids.forEach(function(id){out=out.concat(__sdIds(__sd_dom_children(id)))});var result=__sdWrap(out);return selector?result.filter(selector):result};
    api.parent=function(){var out=[];ids.forEach(function(id){var parent=Number(__sd_dom_parent(id));if(parent>0&&out.indexOf(parent)<0)out.push(parent)});return __sdWrap(out)};
    return api;
  }
  function __sdCheerioLoad(html){
    var root=Number(__sd_dom_load(String(html||'')));
    function query(selector,context){
      if(selector&&selector.__sd_node)return __sdWrap([selector.__sd_node]);
      if(selector&&selector.__sd_ids)return selector;
      var contexts=context?(context.__sd_ids||[context.__sd_node||Number(context)]):[root];
      var out=[];contexts.forEach(function(id){out=out.concat(__sdIds(__sd_dom_select(id,String(selector||'*'))))});
      return __sdWrap(out.filter(function(id,index,list){return id>0&&list.indexOf(id)===index}));
    }
    query.root=function(){return __sdWrap([root])};
    return query;
  }
  var __sd_cheerio={load:__sdCheerioLoad};
  function __sdB64Encode(bytes){var chars='ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';var out='';for(var i=0;i<bytes.length;i+=3){var a=bytes[i]&255,b=i+1<bytes.length?bytes[i+1]&255:0,c=i+2<bytes.length?bytes[i+2]&255:0;var n=(a<<16)|(b<<8)|c;out+=chars[(n>>18)&63]+chars[(n>>12)&63]+(i+1<bytes.length?chars[(n>>6)&63]:'=')+(i+2<bytes.length?chars[n&63]:'=')}return out}
  function __sdB64Bytes(value){
    var chars='ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';var clean=String(value||'').replace(/[^A-Za-z0-9+/]/g,'');var out=[];var buffer=0,bits=0;
    for(var i=0;i<clean.length;i++){var n=chars.indexOf(clean.charAt(i));if(n<0)continue;buffer=(buffer<<6)|n;bits+=6;if(bits>=8){bits-=8;out.push((buffer>>bits)&255)}}return out;
  }
  function __sdBytesToWords(bytes){var words=[];for(var i=0;i<bytes.length;i++)words[i>>>2]=(words[i>>>2]||0)|(bytes[i]<<(24-(i%4)*8));return words}
  function __sdWordsToBytes(words,count){var out=[];for(var i=0;i<count;i++)out.push((words[i>>>2]>>>(24-(i%4)*8))&255);return out}
""".trimIndent()

/**
 * What FebBox and the other hosts these sources scrape expect to see. The old "StreamDek/1.0"
 * was enough for a bot filter to answer with a challenge page instead of JSON, which reaches the
 * provider as an unparseable body and leaves it reporting no streams rather than an error.
 */
private const val PLUGIN_USER_AGENT =
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

/** The plugin sandbox: DOM and crypto shims, a browser-ish global scope, and the module loader. */
private val PLUGIN_RUNTIME_SOURCE = CHEERIO_COMPAT_SHIM + "\n" + PLUGIN_POLYFILLS + "\n" + "globalThis.window=globalThis;globalThis.self=globalThis;globalThis.global=globalThis;globalThis.console={log:function(){__sd_log([].slice.call(arguments).map(String).join(' '))},warn:function(){__sd_log([].slice.call(arguments).map(String).join(' '))},info:function(){__sd_log([].slice.call(arguments).map(String).join(' '))},debug:function(){__sd_log([].slice.call(arguments).map(String).join(' '))},error:function(){__sd_log([].slice.call(arguments).map(String).join(' '))}};var __sd_timers={};var __sd_timer_seq=0;globalThis.setTimeout=function(fn){if(typeof fn!=='function')return 0;var id=++__sd_timer_seq;__sd_timers[id]=1;Promise.resolve().then(function(){if(__sd_timers[id]){delete __sd_timers[id];fn()}});return id};globalThis.clearTimeout=function(id){delete __sd_timers[id]};globalThis.setInterval=function(){return 0};globalThis.clearInterval=function(){};globalThis.Buffer={from:function(v,e){e=String(e||'utf8').toLowerCase();var b=e==='base64'?__sdB64Bytes(v):e==='binary'||e==='latin1'?String(v||'').split('').map(function(c){return c.charCodeAt(0)&255}):JSON.parse(__sd_utf8_encode(String(v||'')));return {__bytes:b,toString:function(enc){enc=String(enc||'utf8').toLowerCase();if(enc==='base64')return __sdB64Encode(b);if(enc==='hex')return b.map(function(n){return ('0'+n.toString(16)).slice(-2)}).join('');return __sd_utf8_decode(JSON.stringify(b))}}}};" +
  "var __sd_types=new Proxy({isArrayBuffer:function(v){return v instanceof ArrayBuffer},isTypedArray:function(v){return ArrayBuffer.isView(v)}},{get:function(t,k){return t[k]||function(){return false}}});" +
  "function __sd_emitter(){this._events={}};__sd_emitter.prototype.on=function(n,f){(this._events[n]||(this._events[n]=[])).push(f);return this};__sd_emitter.prototype.once=function(n,f){var s=this;function w(){s.removeListener(n,w);return f.apply(s,arguments)}return this.on(n,w)};__sd_emitter.prototype.emit=function(n){var a=[].slice.call(arguments,1);(this._events[n]||[]).slice().forEach(function(f){f.apply(null,a)});return true};__sd_emitter.prototype.removeListener=function(n,f){this._events[n]=(this._events[n]||[]).filter(function(x){return x!==f});return this};" +
  "function require(n){if(n==='cheerio-without-node-native'||n==='cheerio')return __sd_cheerio;if(n==='crypto-js')return __sdCrypto;if(n==='axios')return __sdAxios;if(n==='util'||n==='util/types')return n==='util/types'?__sd_types:{types:__sd_types,inherits:function(c,p){c.prototype=Object.create(p.prototype);c.prototype.constructor=c},promisify:function(f){return function(){var a=[].slice.call(arguments);return new Promise(function(ok,no){a.push(function(e,v){e?no(e):ok(v)});f.apply(null,a)})}},inspect:function(v){try{return JSON.stringify(v)}catch(e){return String(v)}}};if(n==='events')return {EventEmitter:__sd_emitter};if(n==='querystring')return {escape:encodeURIComponent,unescape:decodeURIComponent,stringify:function(o){return Object.keys(o||{}).map(function(k){return encodeURIComponent(k)+'='+encodeURIComponent(o[k])}).join('&')}};if(n==='url')return {URL:globalThis.URL,URLSearchParams:globalThis.URLSearchParams};throw new Error('Module not available in sandbox: '+n)};" +
  "globalThis.fetch=async function(u,o){o=o||{};var h=o.headers||{};if(h&&typeof h.forEach==='function'){var m={};h.forEach(function(v,k){m[k]=String(v)});h=m}var r=JSON.parse(__sd_fetch(String(u),String(o.method||\"GET\"),JSON.stringify(h),String(o.body||\"\"),o.redirect!=='manual'));return {ok:r.ok,status:r.status,statusText:r.statusText||'',url:r.url,headers:{get:function(n){return r.headers[String(n).toLowerCase()]||null}},text:function(){return Promise.resolve(r.body)},json:function(){try{return Promise.resolve(JSON.parse(r.body))}catch(e){return Promise.resolve(null)}}}};" +
  "async function __sdAxios(o){if(typeof o==='string')o={url:o};o=o||{};var u=String(o.url||'');if(o.params){var q=Object.keys(o.params).map(function(k){return encodeURIComponent(k)+'='+encodeURIComponent(o.params[k])}).join('&');if(q)u+=(u.indexOf('?')>=0?'&':'?')+q}var body=o.data;if(body&&typeof body!=='string')body=JSON.stringify(body);var r=await fetch(u,{method:String(o.method||'GET').toUpperCase(),headers:o.headers||{},body:body});var t=await r.text();var data;try{data=JSON.parse(t)}catch(e){data=t}var response={data:data,status:r.status,statusText:'',headers:r.headers,config:o,request:null};if(!r.ok){var error=new Error('Request failed with status code '+r.status);error.response=response;throw error}return response};__sdAxios.get=function(u,o){return __sdAxios(Object.assign({},o||{},{url:u,method:'GET'}))};__sdAxios.post=function(u,d,o){return __sdAxios(Object.assign({},o||{},{url:u,data:d,method:'POST'}))};__sdAxios.request=__sdAxios;__sdAxios.create=function(defaults){var client=function(o){return __sdAxios(Object.assign({},defaults||{},o||{}))};client.get=__sdAxios.get;client.post=__sdAxios.post;client.request=client;return client};__sdAxios.default=__sdAxios;"

/**
 * Reads a DOM node handle out of a QuickJS argument.
 *
 * The same JS integer arrives boxed differently depending on how it got there: the handle
 * returned straight out of `__sd_dom_load` came through as a Long, while handles that had been
 * round-tripped through JSON and `Number()` came through as a Double. Parsing with
 * `toString().toIntOrNull()` worked for the first and returned null for the second -- "2.0" is not
 * an Int -- so a root selector matched but every nested `.find()` and `.attr()` off it silently
 * resolved to nothing, and a source that leaned on cheerio just reported no streams.
 */
private fun domNodeHandle(raw: Any?): Int? = when (raw) {
    null -> null
    is Number -> raw.toInt()
    else -> raw.toString().trim().toDoubleOrNull()?.toInt()
}

/**
 * Runs the profile's synced plugin sources on the TV.
 *
 * Plugin sources are JavaScript scrapers installed from the mobile app or the control center.
 * Only their *configuration* travels through the account — the clients strip each provider's
 * source before syncing, because it is a few hundred kilobytes of code per provider — so this
 * engine downloads the scraper for every enabled provider from the collection manifest it was
 * listed in, caches it on disk, and executes it in the same QuickJS sandbox the mobile app uses.
 *
 * Nothing here mutates the synced state: installing, removing and reordering collections stays a
 * mobile/control-center job. The TV is a consumer.
 */
class PluginSourceEngine(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("streamdek_tv_plugins", Context.MODE_PRIVATE)
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Where a provider's QuickJS context runs.
     *
     * Not [Dispatchers.Default], which is what this used to be. A scraper spends nearly all of its
     * time inside `__sd_fetch`, which blocks its thread on the HTTP call — and Default is sized to
     * the core count, so on a two-core TV box two parked scrapers were the whole pool. The third
     * and fourth provider could not start until one of them came back, which is exactly the
     * "everything appears at once, at the end" the fan-out is meant to avoid. Backing it with the
     * IO pool means a provider waiting on the network is parked on a thread that exists to be
     * parked, and the four that the semaphore admits genuinely run at the same time.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val pluginDispatcher = Dispatchers.IO.limitedParallelism(MAX_CONCURRENT_PLUGIN_PROVIDERS)

    /** Scraper source per provider id, populated from the disk cache or the collection manifest. */
    private val codeCache = ConcurrentHashMap<String, String>()

    /** In-flight manifest reads, so ten providers from one collection cause one download. */
    private val manifestJobs = ConcurrentHashMap<String, Deferred<Map<String, String>>>()

    /**
     * Compiled bytecode per provider, keyed by source hash so a collection refresh that changes a
     * scraper invalidates it. Compiling the shim plus provider source is the expensive part of a
     * lookup; re-opening a detail page then only pays for the call itself.
     */
    private val providerBytecodeCache = ConcurrentHashMap<String, ByteArray>()

    /** The shim is identical for every provider, so it compiles once for the whole app. */
    @Volatile
    private var runtimeBytecode: ByteArray? = null

    /** Signature of the state the scraper cache was last warmed for, so a refresh is a no-op. */
    @Volatile
    private var warmedSignature: String? = null

    /**
     * Which profile's provider settings to read and write. Two people on one account can hold
     * different keys for the same source, so these are scoped the way mobile scopes them.
     */
    @Volatile
    private var profileKey: String = "default"

    fun selectProfile(profileId: String?) {
        profileKey = profileId?.takeIf { it.isNotBlank() } ?: "default"
    }

    /**
     * The settings a provider asks for, by running its `onSettings` export.
     *
     * Gated on the scraper actually exporting `onSettings` rather than on the manifest's
     * `hasSettings`, which is advisory and routinely missing — see [pluginDeclaresSettings].
     */
    suspend fun settingsSchema(
        state: ProfilePluginState?,
        provider: ProfilePluginProvider,
    ): Result<List<PluginSettingField>> = runCatching {
        val repoVersion = state?.repos.orEmpty().firstOrNull { it.url == provider.repoUrl }?.version.orEmpty()
        val code = providerCode(provider, repoVersion)
        require(provider.hasSettings || pluginDeclaresSettings(code)) { "This source does not expose any settings." }
        parseSettingsSchema(executeProvider(provider, code, null, null, null, null, settingsOnly = true))
    }.onFailure { TvDebugLogger.w("Plugins", "settings schema failed name=${provider.name}", it) }

    /**
     * Whether the settings entry should be offered for [provider], without touching the network.
     *
     * The scraper is normally already cached by [warmUp] on bootstrap, so the code can be
     * consulted directly. Before it is, the manifest flag is all there is to go on — and it only
     * ever under-reports, so a source that turns out to have settings gains the row once its
     * source has landed rather than being wrongly offered one it cannot fill.
     */
    fun declaresSettings(provider: ProfilePluginProvider): Boolean {
        if (provider.hasSettings) return true
        val code = provider.code?.takeIf { it.isNotBlank() }
            ?: codeCache[provider.id]
            ?: prefs.getString(codeKey(provider.id), null)?.takeIf { it.isNotBlank() }
            ?: return false
        return pluginDeclaresSettings(code)
    }

    /** Values the viewer has entered for one provider on this device, for this profile. */
    fun providerSettings(providerId: String): Map<String, String> {
        val raw = prefs.getString(settingsKey(providerId), "{}") ?: "{}"
        val root = runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
        return buildMap {
            root.keys().forEach { key ->
                root.opt(key)?.takeUnless { it == JSONObject.NULL }?.let { put(key, it.toString()) }
            }
        }
    }

    fun saveProviderSettings(providerId: String, values: Map<String, String>) {
        val root = JSONObject()
        values.forEach { (key, value) -> if (key.isNotBlank()) root.put(key, value) }
        prefs.edit().putString(settingsKey(providerId), root.toString()).apply()
    }

    private fun settingsKey(providerId: String) = "settings:$profileKey:$providerId"

    private fun parseSettingsSchema(raw: String): List<PluginSettingField> {
        val array = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val options = item.optJSONArray("options")
                add(
                    PluginSettingField(
                        type = item.optString("type").lowercase(Locale.US),
                        key = item.optString("key").ifBlank { null },
                        label = item.optString("label").ifBlank { item.optString("key") },
                        description = item.optString("description").ifBlank { null },
                        placeholder = item.optString("placeholder").ifBlank { null },
                        defaultValue = item.opt("defaultValue")?.takeUnless { it == JSONObject.NULL }?.toString(),
                        isPassword = item.optBoolean("isPassword", false),
                        options = buildList {
                            if (options != null) for (optionIndex in 0 until options.length()) {
                                val option = options.optJSONObject(optionIndex) ?: continue
                                add(PluginSettingOption(option.optString("label"), option.optString("value")))
                            }
                        },
                    ),
                )
            }
        }
    }

    fun eligibleProviders(state: ProfilePluginState?, type: String): List<ProfilePluginProvider> =
        eligiblePluginProviders(state, type)

    /**
     * Downloads the scraper for every enabled provider in the background, so the first stream
     * lookup of a session does not pay for it. Safe to call on every bootstrap: once a collection
     * is cached this returns without touching the network.
     */
    fun warmUp(state: ProfilePluginState?) {
        if (state == null || !state.enabled) return
        val enabledRepos = state.repos.filter { it.enabled }.mapTo(mutableSetOf()) { it.url }
        val providers = state.providers.filter { it.enabled && it.repoUrl in enabledRepos }
        if (providers.isEmpty()) return
        val repoVersions = state.repos.associate { it.url to it.version }
        val signature = providers.joinToString("|") { "${it.id}@${repoVersions[it.repoUrl].orEmpty()}" }
        if (signature == warmedSignature) return
        warmedSignature = signature
        engineScope.launch {
            val gate = Semaphore(MAX_CONCURRENT_PLUGIN_PROVIDERS)
            providers.map { provider ->
                async {
                    gate.withPermit {
                        runCatching { providerCode(provider, repoVersions[provider.repoUrl].orEmpty()) }
                            .onFailure { TvDebugLogger.w("Plugins", "could not preload ${provider.name}", it) }
                    }
                }
            }.awaitAll()
        }
    }

    /**
     * Streams for one title from every eligible provider.
     *
     * [onProviderResults] is invoked as each provider finishes — including with an empty list, so a
     * caller counting outstanding sources can retire one that found nothing rather than leaving the
     * list looking like it is still waiting on it.
     */
    suspend fun streams(
        state: ProfilePluginState?,
        id: String,
        type: String,
        season: Int? = null,
        episode: Int? = null,
        onProviderResults: suspend (List<AddonStream>) -> Unit = {},
    ): List<AddonStream> {
        val providers = eligibleProviders(state, type)
        if (providers.isEmpty() || id.isBlank()) return emptyList()
        val normalizedType = normalizePluginType(type)
        val repoVersions = state?.repos.orEmpty().associate { it.url to it.version }
        TvDebugLogger.i("Plugins", "lookup type=$normalizedType id=$id providers=${providers.size}")
        return supervisorScope {
            val gate = Semaphore(MAX_CONCURRENT_PLUGIN_PROVIDERS)
            providers.map { provider ->
                async(pluginDispatcher) {
                    gate.withPermit {
                        val streams = runCatching {
                            val code = providerCode(provider, repoVersions[provider.repoUrl].orEmpty())
                            parseStreams(
                                raw = executeProvider(provider, code, id, normalizedType, season, episode),
                                provider = provider,
                            )
                        }.onFailure {
                            TvDebugLogger.w("Plugins", "provider failed name=${provider.name}", it)
                        }.getOrDefault(emptyList())
                        TvDebugLogger.i("Plugins", "provider ${provider.name} returned ${streams.size} streams")
                        // Reported from inside the permit, so the result of a provider that has
                        // finished reaches the screen before the next one is let through rather
                        // than queueing behind the whole fan-out.
                        onProviderResults(streams)
                        streams
                    }
                }
            }.awaitAll().flatten()
        }
    }

    /** How many providers a lookup of [type] would fan out to, for a caller counting sources. */
    fun eligibleProviderCount(state: ProfilePluginState?, type: String): Int =
        eligibleProviders(state, type).size

    // --- scraper source ---------------------------------------------------------------------

    private suspend fun providerCode(provider: ProfilePluginProvider, repoVersion: String): String {
        // A snapshot that already carries the source (an older client, or a control-center import)
        // is used as-is rather than re-downloading it.
        provider.code?.takeIf { it.isNotBlank() }?.let { return it }
        codeCache[provider.id]?.let { return it }
        readCachedCode(provider.id, repoVersion)?.let {
            codeCache[provider.id] = it
            return it
        }
        val sourceUrl = providerSourceUrls(provider.repoUrl)[provider.id]
            ?: throw IllegalStateException("Provider ${provider.name} is not listed in its collection manifest.")
        val code = withContext(Dispatchers.IO) { text(sourceUrl) }
        require(code.isNotBlank()) { "Provider ${provider.name} returned an empty scraper." }
        writeCachedCode(provider.id, repoVersion, code)
        codeCache[provider.id] = code
        return code
    }

    /** Provider id to scraper URL for one collection, read once per process. */
    private suspend fun providerSourceUrls(repoUrl: String): Map<String, String> {
        val job = manifestJobs.getOrPut(repoUrl) { engineScope.async { loadProviderSourceUrls(repoUrl) } }
        return runCatching { job.await() }
            .onFailure { manifestJobs.remove(repoUrl, job) }
            .getOrThrow()
    }

    private fun loadProviderSourceUrls(repoUrl: String): Map<String, String> {
        val manifest = JSONObject(text(repoUrl))
        val entries = manifest.optJSONArray("scrapers") ?: throw IllegalStateException("No providers in collection.")
        return buildMap {
            for (index in 0 until entries.length()) {
                val item = entries.optJSONObject(index) ?: continue
                val key = item.optString("id")
                val file = item.optString("filename")
                if (key.isBlank() || file.isBlank()) continue
                // Provider ids are minted as "<collection url lowercased>:<manifest id>" by the
                // client that installed them, and that is what the synced snapshot carries.
                put("${repoUrl.lowercase(Locale.US)}:$key", resolvePluginProviderUrl(repoUrl, file))
            }
        }
    }

    private fun codeKey(providerId: String) = "code:$providerId"
    private fun codeVersionKey(providerId: String) = "codeVersion:$providerId"

    private fun readCachedCode(providerId: String, repoVersion: String): String? {
        if (prefs.getString(codeVersionKey(providerId), null) != repoVersion) return null
        return prefs.getString(codeKey(providerId), null)?.takeIf { it.isNotBlank() }
    }

    private fun writeCachedCode(providerId: String, repoVersion: String, code: String) {
        prefs.edit()
            .putString(codeKey(providerId), code)
            .putString(codeVersionKey(providerId), repoVersion)
            .apply()
    }

    private fun text(url: String): String =
        http.newCall(Request.Builder().url(url).header("User-Agent", "StreamDek/1.0").build()).execute().use {
            require(it.isSuccessful) { "HTTP ${it.code} while loading ${runCatching { URI(url).path.substringAfterLast('/') }.getOrDefault("plugin file")}" }
            it.body?.string() ?: throw IllegalStateException("Empty response.")
        }

    // --- sandbox ----------------------------------------------------------------------------

    private suspend fun executeProvider(
        provider: ProfilePluginProvider,
        code: String,
        id: String?,
        type: String?,
        season: Int?,
        episode: Int?,
        settingsOnly: Boolean = false,
    ): String = withTimeout(if (settingsOnly) PLUGIN_SETTINGS_TIMEOUT_MS else PLUGIN_PROVIDER_TIMEOUT_MS) {
        val deferred = CompletableDeferred<String>()
        val domNodes = mutableMapOf<Int, Element>()
        var nextDomNodeId = 1
        fun registerDomNode(element: Element): Int {
            domNodes.entries.firstOrNull { it.value === element }?.let { return it.key }
            val nodeId = nextDomNodeId++
            domNodes[nodeId] = element
            return nodeId
        }
        quickJs(pluginDispatcher) {
            function("__sd_log") { args: Array<Any?> ->
                TvDebugLogger.d("Plugins", "[${provider.name}] " + args.getOrNull(0)?.toString().orEmpty())
                null
            }
            function("__sd_dom_load") { args: Array<Any?> ->
                val html = args.getOrNull(0)?.toString().orEmpty()
                registerDomNode(Jsoup.parse(html)).also {
                    TvDebugLogger.d("Plugins", "[${provider.name}] dom.load ${html.length} chars -> node $it")
                }
            }
            function("__sd_dom_select") { args: Array<Any?> ->
                val root = domNodeHandle(args.getOrNull(0))?.let(domNodes::get)
                val selector = args.getOrNull(1)?.toString().orEmpty()
                val ids = if (root == null || selector.isBlank()) {
                    emptyList()
                } else {
                    runCatching { root.select(selector).map(::registerDomNode) }.getOrDefault(emptyList())
                }
                TvDebugLogger.d("Plugins", "[${provider.name}] dom.select $selector -> ${ids.size}")
                JSONArray(ids).toString()
            }
            function("__sd_dom_matches") { args: Array<Any?> ->
                val node = domNodeHandle(args.getOrNull(0))?.let(domNodes::get)
                val selector = args.getOrNull(1)?.toString().orEmpty()
                node != null && selector.isNotBlank() && runCatching { node.`is`(selector) }.getOrDefault(false)
            }
            function("__sd_dom_attr") { args: Array<Any?> ->
                val node = domNodeHandle(args.getOrNull(0))?.let(domNodes::get)
                node?.attr(args.getOrNull(1)?.toString().orEmpty()).orEmpty()
            }
            function("__sd_dom_text") { args: Array<Any?> ->
                domNodeHandle(args.getOrNull(0))?.let(domNodes::get)?.text().orEmpty()
            }
            function("__sd_dom_html") { args: Array<Any?> ->
                domNodeHandle(args.getOrNull(0))?.let(domNodes::get)?.html().orEmpty()
            }
            function("__sd_dom_children") { args: Array<Any?> ->
                val node = domNodeHandle(args.getOrNull(0))?.let(domNodes::get)
                JSONArray(node?.children()?.map(::registerDomNode).orEmpty()).toString()
            }
            function("__sd_dom_parent") { args: Array<Any?> ->
                domNodeHandle(args.getOrNull(0))?.let(domNodes::get)?.parent()?.let(::registerDomNode) ?: 0
            }
            function("__sd_utf8_encode") { args: Array<Any?> ->
                JSONArray(args.getOrNull(0)?.toString().orEmpty().toByteArray(Charsets.UTF_8).map { it.toInt() and 0xff }).toString()
            }
            function("__sd_utf8_decode") { args: Array<Any?> ->
                runCatching {
                    val source = JSONArray(args.getOrNull(0)?.toString().orEmpty())
                    String(ByteArray(source.length()) { index -> source.optInt(index).toByte() }, Charsets.UTF_8)
                }.getOrDefault("")
            }
            function("__capture_result") { args: Array<Any?> ->
                deferred.complete(args.getOrNull(0)?.toString() ?: "[]")
                null
            }
            function("__capture_error") { args: Array<Any?> ->
                deferred.completeExceptionally(IllegalStateException(args.getOrNull(0)?.toString() ?: "Plugin execution failed"))
                null
            }
            function("__sd_fetch") { args: Array<Any?> ->
                val url = args.getOrNull(0)?.toString().orEmpty()
                val method = args.getOrNull(1)?.toString()?.uppercase(Locale.US) ?: "GET"
                val headerJson = runCatching { JSONObject(args.getOrNull(2)?.toString() ?: "{}") }.getOrDefault(JSONObject())
                val body = args.getOrNull(3)?.toString().orEmpty()
                val followRedirects = args.getOrNull(4) as? Boolean ?: true
                require(url.startsWith("http://") || url.startsWith("https://")) { "Only HTTP(S) is allowed." }
                val request = Request.Builder().url(url)
                // Scrapers copy browser header dumps wholesale, Accept-Encoding included. Setting it by
                // hand switches OkHttp out of transparent gzip, so the body arrives still compressed and
                // every JSON.parse in the provider fails on binary. Drop it and let OkHttp negotiate.
                headerJson.keys().asSequence().filterNot { it.equals("Accept-Encoding", true) }.toList()
                    .forEach { key -> headerJson.optString(key).takeIf { it.isNotBlank() }?.let { request.header(key, it) } }
                if (!headerJson.keys().asSequence().any { it.equals("User-Agent", true) }) request.header("User-Agent", PLUGIN_USER_AGENT)
                val requestBody = if (method == "GET" || method == "HEAD") null else body.toRequestBody(headerJson.optString("Content-Type").toMediaTypeOrNull())
                // `redirect: "manual"` is how a source reads the Location of a 302 rather than following
                // it, which is the usual way these hosts hand back a signed download URL.
                val client = if (followRedirects) http else http.newBuilder().followRedirects(false).followSslRedirects(false).build()
                runBlocking(Dispatchers.IO) {
                    client.newCall(request.method(method, requestBody).build()).execute().use {
                        TvDebugLogger.d(
                            "Plugins",
                            "[${provider.name}] $method ${url.substringBefore("?").take(120)} -> ${it.code} " +
                                "${it.body?.contentLength() ?: -1L}b" +
                                if (headerJson.keys().asSequence().any { name -> name.equals("Cookie", true) }) " +cookie" else "",
                        )
                        val responseHeaders = JSONObject()
                        // names() is a unique set and header(name) answers with the last value only, so a
                        // response carrying several Set-Cookie lines arrived as one. A provider that
                        // collects a session across two cookies then sent back half of it.
                        it.headers.names().forEach { name ->
                            val values = it.headers.values(name)
                            responseHeaders.put(name.lowercase(Locale.US), if (values.size > 1) values.joinToString(", ") else values.firstOrNull().orEmpty())
                        }
                        JSONObject()
                            .put("ok", it.isSuccessful)
                            .put("status", it.code)
                            .put("statusText", it.message)
                            .put("url", it.request.url.toString())
                            .put("headers", responseHeaders)
                            .put("body", it.body?.string().orEmpty())
                            .toString()
                    }
                }
            }
            installPluginCryptoBridge()
            // Compiling the shim to bytecode is the expensive part of a lookup, and it is the same blob
            // every time, so it is compiled once per process rather than once per provider.
            evaluate<Any?>(runtimeBytecode ?: compile(PLUGIN_RUNTIME_SOURCE, "runtime.js", false).also { runtimeBytecode = it })
            // Whatever the viewer entered under the source's settings cog, in the same global the
            // mobile app exposes. Providers with no settings simply see an empty object.
            //
            // These go in before the provider body runs: a source that reads SCRAPER_SETTINGS at module
            // scope rather than inside getStreams — a FebBox token, say — saw undefined and returned nothing.
            val settingsJson = JSONObject(providerSettings(provider.id) as Map<*, *>).toString()
            evaluate<Any?>("globalThis.SCRAPER_SETTINGS=$settingsJson;globalThis.global.SCRAPER_SETTINGS=globalThis.SCRAPER_SETTINGS;")
            // Cached separately, keyed by content, so a refreshed provider recompiles and the shim does not.
            val providerBytecode = providerBytecodeCache.getOrPut("${provider.id}:${code.hashCode()}") {
                compile("var module={exports:{}};var exports=module.exports;(function(){" + normalizePluginJavaScript(code) + "})();", "provider.js", false)
            }
            evaluate<Any?>(providerBytecode)
            val invocation = if (settingsOnly) {
                "var f=module.exports.onSettings||globalThis.onSettings;" +
                    "if(typeof f!=='function')throw new Error('Plugin does not export onSettings');var r=await f();"
            } else {
                "var f=module.exports.getStreams||globalThis.getStreams;" +
                    "if(typeof f!=='function')throw new Error('Plugin does not export getStreams');" +
                    "var r=await f(" + JSONObject.quote(id) + "," + JSONObject.quote(type) + "," +
                    (season?.toString() ?: "undefined") + "," + (episode?.toString() ?: "undefined") + ");"
            }
            val call = "(async function(){${invocation}__capture_result(JSON.stringify(r||[]));})()" +
                ".catch(function(e){__sd_log(String(e&&e.stack||e));__capture_error(String(e&&e.message||e));})"
            evaluate<Any?>(call)
            deferred.await()
        }
    }

    private fun parseStreams(raw: String, provider: ProfilePluginProvider): List<AddonStream> {
        val array = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val url = when (val value = item.opt("url")) {
                    is JSONObject -> value.optString("url").ifBlank { null }
                    else -> value?.toString()?.takeIf { it.isNotBlank() && it != "null" }
                } ?: item.optString("externalUrl").ifBlank { null }
                val infoHash = item.optString("infoHash").ifBlank { null }
                if (url == null && infoHash == null) continue
                val headers = item.optJSONObject("headers")
                add(
                    AddonStream(
                        addonId = "plugin:" + provider.id,
                        addonName = provider.name,
                        name = item.optString("name").ifBlank { provider.name },
                        title = item.optString("title").ifBlank { provider.name },
                        description = item.optString("description").ifBlank { null },
                        url = url,
                        infoHash = infoHash,
                        fileIdx = item.optInt("fileIdx").takeIf { item.has("fileIdx") },
                        filename = item.optString("filename").ifBlank { null },
                        quality = item.optString("quality").ifBlank { null },
                        size = item.optString("size").ifBlank { null },
                        source = provider.name,
                        requestHeaders = buildMap {
                            headers?.keys()?.forEach { key ->
                                headers.optString(key).takeIf { it.isNotBlank() }?.let { put(key, it) }
                            }
                        },
                    ),
                )
            }
        }
    }
}
