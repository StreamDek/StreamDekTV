# Crash investigation, September 2026

## Confirmed deserialization failure

Gson bypasses Kotlin constructor defaults. `MediaItem.requestHeaders` could therefore be null
after parsing an API response or stored item that omitted it. Calling `copy` then throws
`NullPointerException: ... MediaItem.copy, parameter requestHeaders`.

The regression test reproduces that exception with the model adapter disabled and passes with it
enabled. Commit `44ba9e6` introduced the non-null headers field. Local favourite loading already
repaired it, but that repair did not cover all API and storage deserialization paths.

One exposed path is cloud favourite refresh: `refreshFavouriteChannelsFromCloud` decodes an
envelope, merges favourites and calls `withCloudStreamChannelSource`. An account-only item with
no local match can reach that function's `copy` with missing headers. The source-enrichment helper
was introduced in `2f1d6ad`. This is a concrete reachable path, not proof that it caused the exported
development-build crash; that report omitted its caller and exception message.

The model's Gson adapter now defaults missing/null headers at every Gson decoding boundary,
preserves supplied headers and DRM data, and rejects missing required identity fields during decode.

## Remote navigation

Two 0.3.6 reports and one development report recorded `MainActivity.dispatchKeyEvent:38`.
The available local development mapping maps that line to `super.dispatchKeyEvent`; the matching
0.3.6 mapping was not available for verification. The previous long-press focus mitigation is
already present, and inspected explicit focus requests in Home/player are guarded. The remaining
data does not identify the throwing Compose/framework function or establish a safe navigation fix.

Dispatch still rethrows failures unchanged. Future reports attach the navigation key category,
action and repeat count only to the same exception chain. They do not record typed characters.

## Reporting and rollout

TV reports up to four causes with 24 frames each, retaining obfuscated and framework locations,
version code and Android SDK. Exception messages and source file paths are excluded. Native
reports add exit status, importance and memory figures; they still do not contain a native backtrace.
The release workflow preserves R8 mapping output for 90 days, named by tag, version code and commit.

Backend changes preserve known crashed versions and allow crash/ANR timestamps up to 45 days old;
ordinary telemetry retains the six-hour bound. Unknown native versions are explicitly marked in
metadata, with the reporting version retained as a fallback. Previously stored timestamps and
release attribution are not migrated.

Admin groups by exception and location and exposes the latest report's diagnostics. Install the new
TV build for richer future reports and deploy Backend/Admin changes for ingestion and display.
These changes do not reconstruct missing historical stacks or prove device navigation is fixed.
