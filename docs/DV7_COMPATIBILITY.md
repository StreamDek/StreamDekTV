# DV7 compatibility on Mobile and TV

## Behavior

The existing **DV7 - HEVC Fallback** setting remains in its current playback settings section. It defaults to enabled when the device has no saved value. Existing enabled/disabled choices remain unchanged. Mobile and TV keep separate device-local preferences.

The Media3 handoff now requires a confirmed Dolby Vision Profile 7 video format. Missing profile metadata is not treated as Profile 7; other Dolby Vision profiles and ordinary HEVC retain normal behavior.

Native playback is retained when the current display advertises Dolby Vision and an available Dolby Vision decoder advertises the exact Profile 7 profile and supports the stream format. Otherwise the enabled setting requests the existing mpv/FFmpeg playback path. A decoding failure can also request this handoff after native playback was initially accepted. The owner accepts at most one automatic Media3-to-mpv fallback per source attempt. Declined handoffs do not swallow the original error.

Protected formats stay on the existing DRM-capable path. Disabling the setting disables this DV7 override; normal engine recovery remains available according to its existing policy. Selecting mpv already uses its FFmpeg decoding path and does not require a Media3 handoff.

The handoff retains the existing playback resume state. Mobile already restores explicitly selected audio/subtitle tracks by language and title and reapplies external subtitles. TV now also captures audio/subtitle metadata for this handoff, restores matching tracks using the new engine's IDs, and preserves subtitles-off. Ambiguous matches are not guessed.

Descriptions are updated in English, French, German, Spanish, Italian, Dutch, Polish, and Portuguese.

## Output limits

This change does not relabel the Media3 MIME type, implement a new HEVC extractor, modify mpv's renderer, or claim full FEL reconstruction. It uses the existing FFmpeg path. The app currently configures mpv's GPU renderer; HDR10 passthrough cannot be inferred from successful HEVC decoding. Correct HDR/SDR output depends on the actual renderer, surface, device, and source.

Android capability reports are advisory. An incorrectly advertised decoder that reports successful frames but displays black cannot be reliably identified from those callbacks. This change handles unsupported capability reports and reported decoding failures; it does not claim to detect visually black frames or prove native MEL/FEL correctness.

## Validation

- Six focused fallback-policy tests passed in an isolated Kotlin/JUnit run across Mobile and TV. They cover native support, unsupported native playback, decoder failure, disabled setting, unknown/other profiles, and protected content.
- Three TV track-restoration tests passed in a separate isolated Kotlin/JUnit run using the app's compiled track and language classes. They cover changing engine IDs, language aliases, missing titles, and ambiguous matches.
- Both translation consistency checks pass.
- Full Mobile Gradle validation encountered missing intermediate class files, then a JVM native-memory allocation failure. That failed invocation is not evidence of a successful Mobile build.
- TV's initial Kotlin compile passed before the final track-restoration changes. Its test phase encountered the newly added tests before the corresponding source had been compiled. The final-source build retry was stopped under sustained system memory pressure; the isolated tests do not replace a successful final full build.
- Existing repository-wide hard-coded-string checks exceed their ratchets. The updated DV7 descriptions use localized resources; no ratchet was raised.

## Device validation still required

Use identified Profile 7 MEL and FEL fixtures, including both MKV and MP4 where supported, over local and remote sources. For each case record the actual video codec/profile, selected decoder, output surface, engine, and display HDR mode without logging source credentials.

1. On native DV7 hardware, verify the native engine remains selected and inspect visible video and colors.
2. On HEVC/HDR10 hardware without native DV7, verify one handoff and inspect video plus the display's actual HDR mode.
3. On an SDR display, inspect tone mapping and colors; successful decoder initialization is insufficient.
4. Exercise a native video-decoder failure, ensure a single handoff, and confirm the original playback position.
5. Before that handoff, choose non-default audio, embedded subtitles, external subtitles, or subtitles-off; verify the choice afterwards. Also test seeking, pause, speed, and subtitle delay.
6. With the setting disabled, verify normal engine policy. Test Profile 5, Profile 8, ordinary HEVC, and unknown-profile input for unintended DV7 overrides.
7. Test directly selected mpv, failed fallback playback, and devices unable to sustain HEVC software decoding. Confirm a useful playback error and no engine retry loop.

No MEL/FEL playback, visible-output, HDR, or device-matrix result is claimed by the automated policy tests.
