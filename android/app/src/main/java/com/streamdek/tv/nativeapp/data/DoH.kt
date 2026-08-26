package com.streamdek.tv.nativeapp.data

import android.content.Context
import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps

data class DoHProvider(val id: String, val label: String, val endpoint: String?)

val StreamDekDoHProviders = listOf(
    DoHProvider("cloudflare", "Cloudflare", "https://cloudflare-dns.com/dns-query"),
    DoHProvider("google", "Google", "https://dns.google/dns-query"),
    DoHProvider("adguard", "AdGuard", "https://dns.adguard-dns.com/dns-query"),
    DoHProvider("quad9", "Quad9", "https://dns.quad9.net/dns-query"),
    DoHProvider("dnssb", "DNS.SB", "https://doh.dns.sb/dns-query"),
    DoHProvider("canadian-shield", "Canadian Shield", "https://private.canadianshield.cira.ca/dns-query"),
    DoHProvider("custom", "Custom", null),
)

class DoHSettings(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = preferences.getBoolean(KEY_ENABLED, false)
        set(value) { preferences.edit().putBoolean(KEY_ENABLED, value).apply() }

    var providerId: String
        get() = preferences.getString(KEY_PROVIDER, "cloudflare") ?: "cloudflare"
        set(value) { preferences.edit().putString(KEY_PROVIDER, value).apply() }

    var customEndpoint: String
        get() = preferences.getString(KEY_CUSTOM_ENDPOINT, "").orEmpty()
        set(value) { preferences.edit().putString(KEY_CUSTOM_ENDPOINT, value.trim()).apply() }

    fun endpoint(): String? = StreamDekDoHProviders.firstOrNull { it.id == providerId }?.endpoint
        ?: customEndpoint.takeIf { providerId == "custom" && it.isNotBlank() }

    companion object {
        private const val PREFERENCES = "streamdek_network"
        private const val KEY_ENABLED = "doh_enabled"
        private const val KEY_PROVIDER = "doh_provider"
        private const val KEY_CUSTOM_ENDPOINT = "doh_custom_endpoint"

        fun validateEndpoint(raw: String): String? {
            val url = raw.trim().toHttpUrlOrNull() ?: return "Enter a valid URL."
            if (!url.isHttps) return "DNS over HTTPS requires an HTTPS URL."
            if (url.host.isBlank()) return "The endpoint must include a host."
            return null
        }
    }
}

/**
 * Application-scoped DNS selector. DoH failures are deliberately surfaced as lookup failures;
 * an enabled privacy setting never silently falls back to unencrypted system DNS.
 */
class StreamDekDns(context: Context) : Dns {
    private val settings = DoHSettings(context)
    @Volatile private var cachedEndpoint: String? = null
    @Volatile private var cachedResolver: DnsOverHttps? = null

    override fun lookup(hostname: String): List<InetAddress> {
        if (!settings.enabled) return Dns.SYSTEM.lookup(hostname)
        val endpoint = settings.endpoint()
            ?: throw UnknownHostException("DNS over HTTPS is enabled but no valid endpoint is configured")
        val resolver = (if (cachedEndpoint == endpoint) cachedResolver else null)
            ?: synchronized(this) {
                (if (cachedEndpoint == endpoint) cachedResolver else null) ?: buildResolver(endpoint).also {
                    cachedEndpoint = endpoint
                    cachedResolver = it
                }
            }
        return resolver.lookup(hostname)
    }

    private fun buildResolver(endpoint: String): DnsOverHttps {
        val url = endpoint.toHttpUrlOrNull()
            ?.takeIf { it.isHttps }
            ?: throw UnknownHostException("Invalid DNS over HTTPS endpoint")
        val bootstrap = OkHttpClient.Builder().dns(Dns.SYSTEM).build()
        return DnsOverHttps.Builder().client(bootstrap).url(url).post(true).build()
    }
}
