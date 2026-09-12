param([string]$MobileRoot = 'C:\Dev\StreamDekMobile')
$ErrorActionPreference = 'Stop'
$tvRoot = Split-Path $PSScriptRoot -Parent
$mobileSource = Join-Path $MobileRoot 'android/app/src/main/java/net/streamdek/mobile/nativeapp'
$api = [IO.File]::ReadAllText((Join-Path $mobileSource 'Api.kt'))
$models = [IO.File]::ReadAllText((Join-Path $mobileSource 'Models.kt'))
function Extract-Block([string]$text, [string]$start, [string]$end) {
    $first = $text.IndexOf($start)
    $last = $text.IndexOf($end, [Math]::Max(0, $first))
    if ($first -lt 0 -or $last -le $first) { throw "Mobile source boundary changed: $start" }
    return $text.Substring($first, $last - $first)
}
# Compile the real Mobile parser/model in an isolated test package, without editing Mobile.
$parser = Extract-Block $api 'private fun parseStreamsResponse(' 'private fun parseDebridAccount('
$model = Extract-Block $models 'data class AddonStream(' 'data class DebridAccount('
$generated = Join-Path $tvRoot 'android/app/build/mobile-stream-parity'
New-Item -ItemType Directory -Force -Path $generated | Out-Null
$test = @'
package parity.mobile
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Test
import org.junit.Assert.assertEquals
import com.streamdek.tv.nativeapp.data.parseAddonStreamsPayload
import com.streamdek.tv.nativeapp.data.isPlayableAddonStream
import com.streamdek.tv.nativeapp.data.streamAggregationKey
import com.streamdek.tv.nativeapp.ui.detail.buildStreamListEntries
import com.streamdek.tv.nativeapp.ui.detail.StreamListEntry

class MobileParserParityTest {
    @Test fun identicalResponses() {
        for (count in listOf(1, 87, 10000)) {
            val rows = (0 until count).joinToString(",") { i ->
                val link = when (i % 3) {
                    0 -> "\"url\":\"https://fixture.test/$i.m3u8\""
                    1 -> "\"url\":{\"href\":\"https://fixture.test/$i.mkv\"}"
                    else -> "\"infoHash\":\"0123456789012345678901234567890123456789\",\"fileIdx\":$i"
                }
                """{"addonId":"provider-${i % 3}","addonName":"provider-${i % 3}","title":"Result $i",$link}"""
            }
            for (key in listOf("streams", "results", "items", "__array")) {
                val raw = """{"$key":[$rows]}"""
                val mobile = parseStreamsResponse(JSONObject(raw))
                val tv = parseAddonStreamsPayload(raw)
                assertEquals(mobile.map { listOf(it.addonId, it.title, it.url, it.infoHash, it.fileIdx) },
                    tv.map { listOf(it.addonId, it.title, it.url, it.infoHash, it.fileIdx) })
                val displayed = buildStreamListEntries(tv.distinctBy(::streamAggregationKey).filter(::isPlayableAddonStream), "best", true)
                    .filterIsInstance<StreamListEntry.Result>()
                assertEquals(mobile.map { it.title }.toSet(), displayed.map { it.stream.title }.toSet())
                val oldTvVisible = tv.count { stream ->
                    val url = stream.url
                    !(url != null && stream.infoHash == null && stream.size == null && url.substringBefore('?').lowercase().endsWith(".m3u8"))
                }
                assertEquals(count - (count + 2) / 3, oldTvVisible)
                println("envelope=$key returned=$count mobileParsed=${mobile.size} tvParsed=${tv.size} oldTvRows=$oldTvVisible tvDisplayRows=${displayed.size}")
            }
        }
    }
}
'@
[IO.File]::WriteAllText((Join-Path $generated 'MobileParserParityTest.kt'), $test + "`n" + $model + "`n" + $parser)
$init = @'
gradle.beforeProject { p ->
    p.plugins.withId('com.android.application') {
        p.android.sourceSets.test.java.srcDir(new File(p.buildDir, 'mobile-stream-parity'))
    }
}
'@
$initPath = Join-Path $generated 'parity.init.gradle'
[IO.File]::WriteAllText($initPath, $init)
Push-Location (Join-Path $tvRoot 'android')
try {
    & .\gradlew.bat -I $initPath :app:testDebugUnitTest --tests 'parity.mobile.MobileParserParityTest'
    if ($LASTEXITCODE -ne 0) { throw 'Mobile/TV stream parity failed' }
} finally { Pop-Location }
