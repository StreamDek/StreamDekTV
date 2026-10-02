package com.streamdek.tv.nativeapp.data

import android.content.Context
import android.content.ContextWrapper
import com.google.gson.JsonParser

/** Real AuthSessionStore and journal, isolated from the viewer's files. */
internal class SettingsDeviceChecks(context: Context) {
  private val isolated = object : ContextWrapper(context) {
    override fun getApplicationContext(): Context = this
    override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("settings_fixture_$name", mode)
  }
  fun run(phase: String): String {
    val local = AuthSessionStore(isolated)
    val disk = isolated.durableTvPreferences("journal")
    val journal = TvSettingsJournal({ disk.getString("state", null) }, { disk.edit().putString("state", it).commit() })
    val owner = TvSettingsOwner("fixture-account", "fixture-profile")
    if (phase == "write") {
      check(disk.edit().clear().commit())
      repeat(50) { index ->
        local.setFuseEnabled(index % 2 != 0)
        local.setLiveCaptionsEnabled(false)
        local.saveSubtitleFontSize(64)
        local.saveSubtitlePosition(85)
        check(journal.enqueue(owner, JsonParser.parseString("""{"playback":{"liveProgressBarEnabled":false}}""").asJsonObject))
      }
    } else check(phase == "read")
    check(local.fuseEnabled())
    check(!local.liveCaptionsEnabled())
    check(local.subtitleFontSize() == 64 && local.subtitlePosition() == 85)
    check(!journal.snapshot(owner)!!.getAsJsonObject("preferences").getAsJsonObject("playback").get("liveProgressBarEnabled").asBoolean)
    check(!journal.pending(owner).empty)
    return "PASS settings $phase: Fuse, captions, seekbar, subtitles and pending sync; fixture only\n"
  }
}
