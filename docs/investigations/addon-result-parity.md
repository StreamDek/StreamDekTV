# Addon result parity investigation — 12 September 2026

Reported: AIOStreams on TV 0.3.4 versus Mobile 2.1.16. The affected movie/episode and counts could not be recalled. TV baseline: `10ce180`; canonical Mobile inspected: `0ce7352`, VERSION_NAME 2.1.16. Backend sources were inspected in `C:/Dev/StreamDekBackend/streamdek-backend`.

## Confirmed loss and repair

`PlaybackStreamsScreen` filtered non-live results with a `.m3u8` URL when both `infoHash` and `size` were absent. These are optional torrent/file metadata, not requirements for HLS playback. The repository could receive and store every result, while the picker silently removed valid HLS rows. Mobile's picker has no equivalent HLS exclusion.

This rule originated in `9274150` (22 June 2026, “feat: introduce playback stream selection and player screens for media playback”). `44ba9e6b` later exempted live media; it did not introduce the original exclusion. `8053307b` simplified the URL expression without changing that behavior.

The picker now applies only the existing playable-source policy. Archive rejection, usenet/torrent support, adult-content policy, source selection, quality grouping, size ordering, and provider formatting remain. No numerical stream limit was increased.

Secondary parser differences were also corrected:

- Mobile recognizes `streams`, `results`, `items`, and `__array` response arrays. TV previously accepted only an object with `streams`. TV now also accepts a top-level array.
- Backend responses previously went through generic Gson model deserialization, unlike direct responses. The per-addon and aggregate backend stream routes now use the same tolerant row parser as direct requests, retaining nested URLs and provider request headers.
- Invalid envelopes and failed HTTP requests are diagnosed as failures. Non-object rows are counted as rejected rather than silently obscuring the count difference. An invalid individual row does not discard neighboring rows.

These secondary differences are demonstrated code paths, not proof that AIOStreams used a nonstandard envelope in the reported incident.

## End-to-end comparison

| Stage | TV / Mobile findings |
|---|---|
| Addon selection | Enabled profile addons, supported resource/type, provider preference order. TV uses bootstrap addon snapshot if populated; Mobile uses current UI addon state. Stale installed manifests/configuration remain a live comparison variable. |
| Direct request | Both construct `GET {configured-base}/stream/{Uri.encode(type)}/{Uri.encode(videoId)}.json`, User-Agent `Stremio/4.4.168`. No stream limit, offset, page size, or pagination parameter. No request body. |
| ID mapping | TV builds episode IDs from detail IMDb/media ID, and refuses non-IMDb direct VOD IDs. Mobile additionally calls `/addons/resolve-id` when needed. This can explain an empty lookup for missing detail metadata, but not selected rows disappearing from a successful single-addon response. |
| Backend mode | Both use `/addons/streams/single/{addonId}/{type}/{videoId}` with auth/profile headers when entitled. TV keeps server/direct modes isolated; Mobile may fall back to direct on backend empty/failure. This transport difference was retained, not silently changed as part of a display fix. |
| Platform headers | Backend API headers identify Android TV versus Mobile; direct addon requests use the same Stremio UA. No platform-dependent stream cap found in the inspected backend route. |
| Timeouts | Direct read/call timeouts match: 120/150 seconds. Backend clients differ: TV read/call 25/45 seconds, Mobile 25/30 seconds. Backend addon fetch defaults to 30 seconds, configurable. These fail a request rather than trim a parsed array. |
| Backend resource safeguard | Addon HTTP transport defaults to 24 MiB maximum response size. Oversized bodies fail; they are not sliced into partial stream arrays. Same backend implementation serves both platforms. |
| Backend processing | `addonManager.fetchStreamsFromUrl` reads the entire `streams` array; single-addon cached route enriches results and checks cache status. Route applies profile quality/file-size filters. No numeric per-addon result truncation found. Backend logs include upstream and pre-route-filter counts. |
| Parsing | Full arrays are traversed. TV now shares its tolerant parser between direct and backend results; response envelope compatibility aligns with Mobile. |
| Concurrency | TV permits four simultaneous addon requests; remaining providers wait rather than being discarded. `supervisorScope` joins provider work. No “first N results” completion trigger found. |
| Cancellation | TV explicitly cancels discovery on a new lookup, leaving the picker, or selecting a source. Existing global discovery ownership and Mobile's request-generation checks differ. This is not a demonstrated cause of truncation within one successful addon response. New per-addon failure handlers rethrow cancellation. |
| Aggregation | TV deduplicates using addon identity plus playback/descriptive fields, including file index and headers. Mobile's identity is narrower. Existing serialized publication and append-only picker merging protect against stale partial snapshots. Tests cover late snapshots and same-labelled torrent files. |
| Cache decoration | TV account cache checks annotate the final set; they do not cap it. Existing tests protect addon cache attribution and avoid duplicate decoration rows. |
| Ranking/filtering | TV adult policy can exclude rows; other preferences primarily affect ranking. Mobile additionally filters by maximum file size. Those policies can explain intentional count differences; this change does not rewrite them. |
| State | Picker collects every progressive emission and stores the accumulated candidate. No stream-list StateFlow/LiveData size cap found. A cached candidate does not end discovery early. |
| UI | Source tabs select subsets; `buildStreamListEntries` groups every selected result by source/quality. `LazyColumn` virtualizes composition while retaining all entries. The removed HLS rule was a real loss between state and these entries. |
| Memory/performance | Removed `CopyOnWriteArrayList` for insertion order: all accesses already hold the same mutex, so copying the entire list for each row caused unnecessary quadratic work. The replacement retains the full list. |
| Catalog search | Separate from stream lookup: TV queries searchable addon catalogs, normalizes metadata, applies query-match ranking and deduplication. Home rail previews have limits; these are not applied to the stream picker. Catalog pagination was not exercised live in this investigation. |

## Diagnostics

New `Streams` log records expose:

- `stage=request`: transport mode, addon, type, hashed media/request keys, network-refresh flag. Configured URLs and tokens are not emitted by these new records.
- `stage=http`: status, HTTP-cache presence, declared body length (may be unknown).
- `stage=response`: returned array length, parsed count, rejected row count; explicit failure class on errors.
- `stage=merge`: incoming, added, duplicate, and retained counts.
- `stage=aggregate`: retained versus ranked counts, policy exclusions, pending providers, completion.
- Existing lookup-progress records: stored state count and pending providers.
- `stage=display`: stored, playable, selected-tab count, and result-entry count.

“Display rows” counts the complete LazyColumn data model, not the number of simultaneously composed viewport cells. Direct mode has no backend stream response stage; the backend only supplies account/configuration and metadata. Backend mode's returned count is after server preferences; compare upstream backend telemetry separately.

## Reproducible validation

`scripts/check-mobile-stream-parity.ps1` reads the actual parser and AddonStream model from the canonical Mobile checkout and compiles them unchanged into an isolated JVM test package using TV's test runtime. It does not edit Mobile. Its extraction boundaries fail explicitly if Mobile's source layout changes.

The same generated response is passed to both real parsers, comparing addon identity, title, URL, hash, and file index. TV then deduplicates, applies playability, and builds the actual UI entry list. Fixtures have three synthetic providers with direct HLS, nested file URLs, and torrent results. All fields and URLs are fabricated; these are not captured live addon responses.

| Fixture returned | Mobile parsed | TV parsed | Old TV display rule | Fixed TV result entries |
|---:|---:|---:|---:|---:|
| 1 | 1 | 1 | 0 | 1 |
| 87 | 87 | 87 | 58 | 87 |
| 10,000 | 10,000 | 10,000 | 6,666 | 10,000 |

These cases repeat across four Mobile-supported response envelopes. Separate committed regression tests cover top-level arrays, malformed rows, empty/error envelopes, preserved archive filtering, late partial snapshots, multiple file indices, and provider cache decoration.

Run from the TV root:

```powershell
.\scripts\check-mobile-stream-parity.ps1
cd android
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

## Validation boundary

The defect is reproduced and repaired at code/fixture level. The original AIOStreams request is unknown; no phone was connected at the initial inspection, and both TVs reported development builds rather than the reported 0.3.4 installation. No same-account live AIOStreams response pair, live multi-addon comparison, playback, or on-TV scrolling result is claimed. The user-visible incident cannot yet be attributed exclusively to the HLS rule.

The user subsequently requested stopping tests and installing the build on both TVs, with runtime validation handled personally. The pending final test rerun was interrupted; only compilation/assembly and installation were continued. Earlier runs passed 32 focused regression tests and the actual Mobile parser comparison. They preceded the final diagnostic-only adjustments; do not describe them as a completed final full-suite run.

To close live acceptance, select a known title/episode on both reported versions with the same active profile and addon configuration, then compare the response/state/display diagnostics through completion. Repeat with at least one large AIOStreams response and another real addon. Match direct versus backend mode, resolved ID/type, configuration, and cache freshness before interpreting count differences. Do not share unredacted configured URLs or existing raw Mobile/backend logs.

Translation resources pass. The pre-existing hardcoded-string check remains at 108 against ceiling 107; this change adds no UI copy and does not raise that ceiling.
