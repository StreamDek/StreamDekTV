#!/usr/bin/env node
/**
 * Stops new hard-coded interface text from being added.
 *
 * StreamDek was written in English with the strings inline, and moving every one of them into
 * resources is the work of many changes rather than one. A check that simply failed on the first
 * one it found would therefore have to be switched off, and a check that is switched off protects
 * nothing. So this one is a ratchet: it counts what is left, compares that against the committed
 * baseline, and fails only when the number goes *up*. Migrating strings makes the number go down,
 * and `--update` writes the smaller number back so it can never climb again.
 *
 * It is deliberately narrow. Only literals in positions that are unambiguously read by a person are
 * counted - the text of a `Text(...)`, a `contentDescription`, a settings row's title - because a
 * detector that flags log lines, provider ids and HTTP headers is a detector people learn to ignore.
 * The consequence is that it under-reports, which is the right way for this to be wrong: everything
 * it does flag is genuinely a string a viewer can read in the wrong language.
 *
 *   node scripts/check-hardcoded-strings.mjs            fail if the count has gone up
 *   node scripts/check-hardcoded-strings.mjs --list     show what is left, worst file first
 *   node scripts/check-hardcoded-strings.mjs --update   accept the current count as the new ceiling
 */
import { readFileSync, writeFileSync, existsSync, readdirSync } from "node:fs";
import { join, dirname, relative, sep, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const sourceRoot = join(root, "android", "app", "src", "main", "java");
const baselinePath = join(root, "scripts", "hardcoded-strings-baseline.json");

const list = process.argv.includes("--list");
const update = process.argv.includes("--update");

/** Parameter names whose value is read by a person rather than by a machine. */
const USER_FACING_PARAMS = [
  "contentDescription",
  "title",
  "subtitle",
  "description",
  "label",
  "placeholder",
  "message",
  "headline",
  "confirmLabel",
  "dismissLabel",
  "emptyMessage",
  "errorMessage",
];

/**
 * A literal is exempt when it cannot be read as prose. Single words that are obviously identifiers,
 * anything that looks like a URL, a MIME type, a header name or a format token, and anything with no
 * letters in it at all.
 */
function isProse(text) {
  if (text.length < 2) return false;
  if (!/[A-Za-z]/.test(text)) return false;
  if (/^[a-z0-9_.:\-]+$/.test(text)) return false;           // ids, keys, tags
  if (/^[A-Z0-9_]+$/.test(text)) return false;               // CONSTANTS
  if (/^[a-z]+([A-Z][a-z]+)+$/.test(text)) return false;     // camelCase
  if (/^(https?|content|file|android|market):/.test(text)) return false;
  if (/^[\w.-]+\/[\w.+-]+$/.test(text)) return false;        // mime types, paths
  // Absolute API paths, including Kotlin interpolation, are protocol values like URLs.
  if (/^\/(?:[A-Za-z0-9_{}$.-]+\/)*[A-Za-z0-9_{}$.-]*$/.test(text)) return false;
  if (/^%[sd\d]/.test(text)) return false;
  return true;
}

function kotlinFiles(dir) {
  const out = [];
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) out.push(...kotlinFiles(path));
    else if (entry.name.endsWith(".kt")) out.push(path);
  }
  return out;
}

/**
 * Every pattern below spells a string literal as `"((?:[^"\\\n]|\\.)*)"`, and the `\n` in the
 * character class is load-bearing: a Kotlin single-quoted string cannot contain a raw newline, so a
 * pattern that allows one stops matching a string and starts matching the gap between two of them.
 * Without it, `Torrent("torrent", ...),` followed on the next line by `Usenet("usenet", ...)` was
 * reported as one finding whose text was `"),\r\n    Usenet("` - the code in between. Those are
 * unactionable, and there were enough of them to pad the ceiling by a tenth.
 */
const paramPattern = new RegExp(`\\b(?:${USER_FACING_PARAMS.join("|")})\\s*=\\s*"((?:[^"\\\\\\n]|\\\\.)*)"`, "g");
// Text("...") and Text(text = "..."), the single most common way a string reaches a screen.
const textPattern = /\bText\s*\(\s*(?:text\s*=\s*)?"((?:[^"\\\n]|\\.)*)"/g;

/**
 * Settings builders whose headings and subtitles are passed positionally.
 *
 * These were the detector's blind spot: `SettingsNavRow("PLY", Color(...), "Player", "Player engine,
 * audio language, ...")` has its two most visible strings in argument positions with no parameter
 * name to key off, so nothing above matched them - and a whole settings screen can therefore sit in
 * English while the count reads zero. Every string literal in one of these calls is checked.
 */
const POSITIONAL_BUILDERS = [
  "SettingsSection",
  "SettingsNavRow",
  "SettingsSwitchRow",
  "SettingsChoiceRow",
  "SettingsDropdownRow",
  "SettingsToggleRow",
  "SettingsActionRow",
  "LanguageChoiceRow",
];
const builderPattern = new RegExp(`\\b(?:${POSITIONAL_BUILDERS.join("|")})\\s*\\(`, "g");
const literalPattern = /"((?:[^"\\\n]|\\.)*)"/g;

/**
 * Three routes English took to the screen without passing any of the checks above.
 *
 * Every settings page kept an English heading long after its contents were translated, because the
 * heading came from a `when` returning a string; the caption under a Home card stayed English
 * because its enum constant carried a `label`; and every text field's placeholder stayed English
 * because it was written `InputGuideText("...")` rather than `placeholder = "..."`. None of those
 * is exotic, and all three are invisible to a detector that only knows `Text(...)`, a named
 * parameter, and the settings builders. So they are checked too.
 */
// `-> "Some words"`: a `when` arm handing back a sentence.
const whenArmPattern = /->\s*"((?:[^"\\\n]|\\.)*)"/g;
// Enum labels are matched against their constructor parameters below.
// `SomeComposable("Some words", ...)`: words in the first position of a call.
const positionalCallPattern = /\b([A-Z]\w+)\s*\(\s*"((?:[^"\\\n]|\\.)*)"/g;

/**
 * Calls whose first argument is an id, key, pattern, annotation value or log message,
 * rather than text a viewer reads. Without this the positional rule buries the real findings under `Regex(...)`,
 * `Color(...)` and every exception constructor in the codebase.
 */
const NOT_INTERFACE_CALLS = new Set([
  "Regex", "Color", "Locale", "Uri", "File", "Intent", "Log", "Json", "Bundle", "Pair",
  "SimpleDateFormat", "URL", "URI", "String", "StringBuilder", "Modifier", "Icons", "Request",
  "Builder", "MediaItem", "HomeCatalogRow", "Exception", "RuntimeException", "Throwable", "Error",
  "IllegalStateException", "IllegalArgumentException", "UnknownHostException", "IOException",
  "SaveableStateProvider", "CsTestMedia", "ExternalRating", "OAEPParameterSpec",
  "DebridNotReadyException", "SerializedName", "Deprecated",
]);


/**
 * The argument list of a call starting at `open`, balanced.
 *
 * A regex cannot do this: these calls routinely contain `Color(0xFF22C55E)` and lambdas, and
 * stopping at the first `)` would cut the argument list off before the strings that matter.
 * Skips over string literals so a bracket inside one cannot unbalance the count.
 */
function argumentsOf(src, open) {
  let depth = 0;
  for (let i = open; i < src.length; i += 1) {
    const ch = src[i];
    if (ch === '"') {
      i += 1;
      while (i < src.length && src[i] !== '"') i += src[i] === "\\" ? 2 : 1;
      continue;
    }
    if (ch === "(") depth += 1;
    else if (ch === ")") {
      depth -= 1;
      if (depth === 0) return src.slice(open, i);
    }
  }
  return src.slice(open);
}

/** Split Kotlin arguments without treating commas inside nested calls or strings as separators. */
function splitArguments(src) {
  const parts = [];
  let start = 0;
  let depth = 0;
  for (let i = 0; i < src.length; i += 1) {
    const ch = src[i];
    if (ch === '"') {
      i += 1;
      while (i < src.length && src[i] !== '"') i += src[i] === "\\" ? 2 : 1;
    } else if ("([{<".includes(ch)) depth += 1;
    else if (")]}>".includes(ch)) depth -= 1;
    else if (ch === "," && depth === 0) { parts.push(src.slice(start, i)); start = i + 1; }
  }
  parts.push(src.slice(start));
  return parts;
}

/**
 * Only an enum constructor's display parameters are interface text. The former line regex also
 * counted JSON annotations, Regex calls, locale tags, and search keywords as enum labels.
 */
function scanEnums(src) {
  const hits = [];
  const entries = new Set();
  for (const declaration of src.matchAll(/\benum\s+class\s+\w+\s*\(/g)) {
    const open = declaration.index + declaration[0].length - 1;
    const constructor = argumentsOf(src, open);
    const parameters = splitArguments(constructor.slice(1)).map(part => /\b(?:val|var)\s+(\w+)\s*:/.exec(part)?.[1]);
    let cursor = open + constructor.length + 1;
    // Optional interfaces between the constructor and body contain no enum entries.
    cursor = src.indexOf("{", cursor) + 1;
    if (!cursor) continue;
    while (cursor < src.length) {
      const entry = /^\s*,?\s*([A-Z]\w*)\s*\(/.exec(src.slice(cursor));
      if (!entry) break;
      entries.add(cursor + entry[0].indexOf(entry[1]));
      const entryOpen = cursor + entry[0].length - 1;
      const args = argumentsOf(src, entryOpen);
      splitArguments(args.slice(1)).forEach((arg, index) => {
        const named = /^\s*(\w+)\s*=/.exec(arg);
        // Named display arguments are already counted by paramPattern.
        if (named) return;
        const parameter = parameters[index];
        if (!USER_FACING_PARAMS.includes(parameter)) return;
        const literal = /^\s*(?:\w+\s*=\s*)?"((?:[^"\\\n]|\\.)*)"\s*$/.exec(arg);
        if (literal && isProse(literal[1])) hits.push(literal[1]);
      });
      cursor = entryOpen + args.length + 1;
    }
  }
  return { hits, entries };
}

export function scanSource(src) {
  // Comments are not interface text, and the codebase has a great many of them.
  src = src.replace(/\/\*[\s\S]*?\*\//g, "").replace(/^[ \t]*\/\/.*$/gm, "");

  const enums = scanEnums(src);
  const hits = [];
  for (const pattern of [paramPattern, textPattern]) {
    pattern.lastIndex = 0;
    for (const match of src.matchAll(pattern)) {
      if (isProse(match[1])) hits.push(match[1]);
    }
  }
  builderPattern.lastIndex = 0;
  for (const call of src.matchAll(builderPattern)) {
    const args = argumentsOf(src, call.index + call[0].length - 1);
    for (const literal of args.matchAll(literalPattern)) {
      if (isProse(literal[1])) hits.push(literal[1]);
    }
  }
  for (const pattern of [whenArmPattern]) {
    pattern.lastIndex = 0;
    for (const match of src.matchAll(pattern)) {
      if (isProse(match[1])) hits.push(match[1]);
    }
  }
  positionalCallPattern.lastIndex = 0;
  for (const match of src.matchAll(positionalCallPattern)) {
    if (NOT_INTERFACE_CALLS.has(match[1]) || enums.entries.has(match.index)) continue;
    // Custom exception classes have the same non-interface role as standard exceptions.
    if (/\bthrow\s+$/.test(src.slice(0, match.index))) continue;
    if (isProse(match[2])) hits.push(match[2]);
  }
  hits.push(...enums.hits);
  return hits;
}

function main() {
  const perFile = new Map();
  let total = 0;
  for (const path of kotlinFiles(sourceRoot)) {
    const hits = scanSource(readFileSync(path, "utf8"));
    if (hits.length) {
      perFile.set(relative(root, path).split(sep).join("/"), hits);
      total += hits.length;
    }
  }

  if (list) {
    const ordered = [...perFile.entries()].sort((a, b) => b[1].length - a[1].length);
    for (const [file, hits] of ordered) {
      console.log(`${String(hits.length).padStart(5)}  ${file}`);
      if (process.argv.includes("--verbose")) {
        for (const hit of hits.slice(0, 20)) console.log(`         ${JSON.stringify(hit)}`);
      }
    }
    console.log("");
  }

  const baseline = existsSync(baselinePath) ? JSON.parse(readFileSync(baselinePath, "utf8")) : null;

  if (update || !baseline) {
    writeFileSync(
      baselinePath,
      `${JSON.stringify({ ceiling: total, note: "Upper bound on hard-coded interface strings. Only ever lower this - see check-hardcoded-strings.mjs." }, null, 2)}\n`,
    );
    console.log(`Baseline set to ${total}.`);
    process.exit(0);
  }

  console.log(`${total} hard-coded interface string(s); ceiling is ${baseline.ceiling}.`);

  if (total > baseline.ceiling) {
    console.error(
      `\nThat is ${total - baseline.ceiling} more than the ceiling. New interface text belongs in ` +
        `res/values/strings.xml and reaches the screen through stringResource(R.string.…).\n` +
        `Run with --list to see where they are.`,
    );
    process.exit(1);
  }

  if (total < baseline.ceiling) {
    console.log(`${baseline.ceiling - total} fewer than the ceiling - run with --update to lock the gain in.`);
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main();
