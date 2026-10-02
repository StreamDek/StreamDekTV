# TV settings persistence repair

2026-10-02. TV was affected by the same class of failure as Mobile: preference state was not restored from a durable account/profile cache, failed bootstrap reads could publish null/defaults, and full-section saves could race or disappear offline.

The repair adds a disk-backed cache and sparse pending-write journal, revision-aware acknowledgements, serialized upload/hydration, bounded retry, account-scoped remembered profiles, guarded device-setting reconciliation, and checked local preference writes. Fuse stays local to the TV. The live seekbar follows the profile playback preference. Audio and subtitle language Settings now feed the same effective values used by stream selection and playback.

- [TV setting inventory](settings-persistence-inventory.md): 92 model fields and separate local stores.
- [Full Mobile/TV/backend lifecycle audit](../../StreamDekMobile/docs/settings-persistence-audit.md): causes, history, scope, test matrix, diagnostics, rollout and device-check commands.

Validation: 640 TV unit tests passed; Android instrumentation compiles; localization and hard-coded-string checks passed. The Android settings fixture can be run in write/read phases around force-stop or reboot. No connected Fire TV was changed or rebooted during this task.

The shared backend repair is also required for concurrent-device protection: new clients negotiate sparse merging; older backend deployments retain their original section-level merge race. No backend deployment was performed.
