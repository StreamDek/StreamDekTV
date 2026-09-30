import assert from 'node:assert/strict';
import test from 'node:test';
import { scanSource } from './check-hardcoded-strings.mjs';

test('JSON annotations and regex calls are not mistaken for enum labels', () => {
  assert.deepEqual(scanSource(`
    @SerializedName("AccessToken") val token: String? = null
    @SerializedName("ServerName") val name: String? = null
    Regex("(1080p?|full hd)")
    @Deprecated("Use the profile settings") fun old() {}
  `), []);
});

test('plain, named and positional UI copy still fails the check', () => {
  const hits = scanSource(`
    Text("Connect your server")
    Text(text = "Choose a library")
    Button(contentDescription = "Open settings")
    InputGuideText("Server address")
    SettingsNavRow("PLY", Color(0), "Player settings", "Choose a player")
  `);
  for (const text of ['Connect your server', 'Choose a library', 'Open settings',
    'Server address', 'Player settings', 'Choose a player']) assert.ok(hits.includes(text), text);
});

test('enum display labels are checked while search keywords are not', () => {
  assert.deepEqual(scanSource(`
    enum class Destination(val label: String, val terms: String) {
      Home("Home screen", "home movies shows"),
      Library("Your library", "library films collections"),
    }
  `), ['Home screen', 'Your library']);
});

test('resource labels and internal locale tags do not create enum findings', () => {
  assert.deepEqual(scanSource(`
    enum class Destination(@StringRes val labelRes: Int, val terms: String) {
      Home(R.string.home, "home movies shows"),
    }
    enum class Language(val tag: String, val metadataTag: String) {
      English("en", "en-US"),
      French("fr", "fr-FR"),
    }
  `), []);
});

test('enum labels after nested arguments are still checked and named labels count once', () => {
  assert.deepEqual(scanSource(`
    enum class Choice(val icon: Icon, val title: String) {
      First(Icon(Color(0), listOf(1, 2)), "First choice"),
      Second(Icon(Color(0), listOf(3, 4)), title = "Second choice"),
    }
  `).sort(), ['First choice', 'Second choice']);
});

test('enum constructors are checked regardless of formatting', () => {
  assert.deepEqual(scanSource(`enum class Style(val id: String, val label: String) {
Wide("wide", "Wide layout"), Compact("compact", "Compact layout");
}`), ['Wide layout', 'Compact layout']);
});

test('when-arm prose is retained while API paths are excluded', () => {
  assert.deepEqual(scanSource(`
    when (state) {
      Ready -> "Ready to watch"
      Views -> "/Users/$user/Views"
      Items -> "/Users/\${user}/Items/"
    }
  `), ['Ready to watch']);
});

test('throwing custom exceptions does not count diagnostics as screen labels', () => {
  assert.deepEqual(scanSource(`
    throw Rejected("Invalid publication time")
    throw JsonParseException("MediaItem missing required field")
    Text("Please try again")
  `).filter(text => text !== 'Please try again'), []);
  assert.ok(scanSource('Text("Please try again")').includes('Please try again'));
});
