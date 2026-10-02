package com.streamdek.tv.nativeapp.data
import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test
/** apply() deliberately does NOT reach disk: recreating a process exposes accidental async writes. */
internal class SettingsTestPreferences(private val disk: MutableMap<String, Any?> = linkedMapOf()) : SharedPreferences {
  private val memory = disk.toMutableMap()
  var failWrites = false
  fun restart() = SettingsTestPreferences(disk)
  override fun getAll(): MutableMap<String, *> = memory.toMutableMap()
  override fun contains(key: String) = memory.containsKey(key)
  override fun getString(key: String, defValue: String?) = memory[key] as? String ?: defValue
  override fun getStringSet(key: String, defValues: MutableSet<String>?) = (memory[key] as? Set<*>)?.filterIsInstance<String>()?.toMutableSet() ?: defValues
  override fun getInt(key: String, defValue: Int) = memory[key] as? Int ?: defValue
  override fun getLong(key: String, defValue: Long) = memory[key] as? Long ?: defValue
  override fun getFloat(key: String, defValue: Float) = memory[key] as? Float ?: defValue
  override fun getBoolean(key: String, defValue: Boolean) = memory[key] as? Boolean ?: defValue
  override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
  override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
  override fun edit(): SharedPreferences.Editor {
    val changes = linkedMapOf<String, Any?>()
    var clear = false
    return object : SharedPreferences.Editor {
      override fun putString(key: String, value: String?) = apply { changes[key] = value }
      override fun putStringSet(key: String, values: MutableSet<String>?) = apply { changes[key] = values?.toSet() }
      override fun putInt(key: String, value: Int) = apply { changes[key] = value }
      override fun putLong(key: String, value: Long) = apply { changes[key] = value }
      override fun putFloat(key: String, value: Float) = apply { changes[key] = value }
      override fun putBoolean(key: String, value: Boolean) = apply { changes[key] = value }
      override fun remove(key: String) = apply { changes[key] = null }
      override fun clear() = apply { clear = true }
      override fun apply() {
        if (clear) memory.clear()
        changes.forEach { (key, value) -> if (value == null) memory.remove(key) else memory[key] = value }
      }
      override fun commit(): Boolean {
        apply()
        if (failWrites) return false
        disk.clear(); disk.putAll(memory)
        return true
      }
    }
  }
}

class TvSettingsStorageTest {
    @Test fun `local fuse false and subtitle size survive immediate restart`() {
        val disk = SettingsTestPreferences()
        val store = DurableTvPreferences(disk)
        repeat(50) { store.edit().putBoolean("mediaHubEnabled", it % 2 == 0).putFloat("subtitleSize", 1.25f).apply() }
        assertFalse(disk.restart().getBoolean("mediaHubEnabled", true))
        assertEquals(1.25f, disk.restart().getFloat("subtitleSize", 0f))
    }
    @Test fun `failed local save reports failure`() {
        val disk = SettingsTestPreferences().apply { failWrites = true }
        var failed = false
        assertFalse(DurableTvPreferences(disk) { failed = true }.edit().putBoolean("fuse", true).commit())
        assertTrue(failed)
    }
}
