# TV interface performance pass — 19 September 2026

Baseline: `823a443`. Device: Fire TV AFTKM, `192.168.0.15:5555`.

## What the player did

The player changes in `823a443` animate reveal, translation, alpha and emphasis in
graphics layers. Reading animation state there avoids recomposing and measuring
the control tree for each frame. The player also has device-aware reductions for
translucent effects. These are useful principles; the source alone does not
establish how much each contributed to the reported improvement.

## Changes in this pass

- Decode typed API responses on `Dispatchers.Default`. Previously `executeRaw`
  performed network I/O in the background, but JSON decoding resumed on the
  caller's dispatcher, including Main.
- Map add-on catalogue results and remove diagnostic entries on Default, keeping
  ordering, direct-source fallback and returned data unchanged.
- Compile the adult filter's Unicode separator expression once. Matching rules,
  current policy and fail-closed behavior remain unchanged; results are not cached.
- Remember media-card artwork selection and formatted metadata by their inputs,
  including configuration changes. Reuse the artwork URL expression.
- Observe remote-input activity inside the idle coroutine using `snapshotFlow`
  and `collectLatest`. Key presses still reset the timeout but no longer trigger
  root composition solely to restart it. Player and update-prompt exclusions remain.
- Read navigation width during measurement and its animated colors during draw.
  Read label opacity in a graphics layer; only label visibility crosses composition.
  Existing geometry, focus rules and timing remain.
- Draw loading-placeholder alpha directly with a cached outline. Home shelf-title
  opacity also no longer recomposes the row every animation frame.

No new UI text, feature changes or animation-duration changes were introduced.

## Verification

- Kotlin compilation, debug APK assembly and all 538 unit tests passed.
- Translation consistency passed for all seven translated locales.
- The hard-coded-string ratchet fails at 117 against a ceiling of 107. Running the
  same checker against source archived from baseline `823a443` gives the same 117.
- `git diff --check` passed with the repository's normal line-ending configuration.
- The final debug APK installed successfully on `.15` after Android cache trimming
  resolved an insufficient-storage error. App data was preserved.
- Device smoke checks exercised profile selection, Home horizontal/vertical focus,
  rail navigation, Library loading into its 30-title watchlist, card navigation,
  detail entry (including an automatically playing trailer), and Back navigation.
  Search entry/loading was also checked on the initial optimized build. No
  StreamDek crash appeared in the inspected device crash buffer. These checks do
  not establish that every transition is visually smooth.

## Measurement limits

`scripts/benchmark-home-navigation.sh` alternates Right/Left six times. It must
start on the first card of a populated row with at least two cards, after profile
selection and loading. The early broad traversal was discarded because it landed
on the one-card Fuse row and then entered the navigation rail.

Same-row debug samples before cache trimming:

| Build/sample | Rendered frames | Median | p90 | p99 |
| --- | ---: | ---: | ---: | ---: |
| Baseline, warmed run 1 | 114 | 85 ms | 109 ms | 200 ms |
| Baseline, warmed run 2 | 108 | 81 ms | 105 ms | 113 ms |
| Initial optimized build, run 1 | 102 | 101 ms | 150 ms | 200 ms |
| Initial optimized build, run 2 | 104 | 89 ms | 125 ms | 150 ms |
| Initial optimized build, run 3 | 103 | 85 ms | 121 ms | 150 ms |

These samples do **not** demonstrate an overall frame-time improvement. They were
collected before the final shelf-title and placeholder-drawing refinement. Debug
compilation, warm-up and background work are confounders. Later explicit package
compilation and cache trimming changed device conditions, so their results must
not be combined with these samples as a controlled comparison.

All these samples reported 100% janky rendered frames, while the device's GPU
histogram reported implausible 4950 ms values. Janky-frame percentage is not a
dropped-frame percentage, and these histograms must not be converted into an FPS
claim. A release/profileable comparison with fixed compilation and warmed caches
is needed to quantify the end-to-end gain. Playback, long-duration idle expiry,
and every provider/error path were not exercised by this UI pass.

Local diagnostic outputs and screenshots are in
`android/build/performance-2026-09-19/` (untracked build artifacts).
