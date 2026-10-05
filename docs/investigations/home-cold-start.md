# Home on a cold start: what was wrong and what changed

**Status: implemented, not yet built or run on a device.** The findings come from reading the
code path end to end; the timings and counts under "To measure" still need a real box.

## What the viewer saw

Home drew, was replaced by its skeleton, drew again with the highlight back on the first card,
and often did that a third time. Continue Watching arriving looked like the cause because it was
usually the last thing to land before one of those redraws.

## The cause

`HomeScreen` built one string, `loadKey`, from seven things: the account, the profile, the add-on
set, the CloudStream provider version, the Fuse switch and the media server revision. It then used
that string for two unrelated jobs.

1. **As the request to load.** A new key meant "fetch Home again".
2. **As the key for the screen's own state.** `initialArtworkReady`, `entryRowId` and
   `handledLibraryRevision` were all `remember(loadKey)`.

The second is the fault. Four of the seven inputs settle one after another in the first seconds
of a cold start:

| Input | When it changes at startup |
| --- | --- |
| Media server revision | Bumped by every `publishServers` / `publishJellyfin`, and Home itself calls `mediaServers.refreshInBackground()` on entry. Typically several bumps. |
| CloudStream provider version | Bumped each time the set of loaded providers or their rows changes, a few seconds in. |
| Fuse switch | Read from disk, can be corrected by the account. |
| Add-on set / profile | The bootstrap is read from disk and then refreshed from the account. |

Every one of those changes did all of this:

- `initialArtworkReady` reset to `false`. The screen's `when` then took the branch
  `content != null && !initialArtworkReady -> HomeFirstLoad`, so **the finished page was removed
  from composition and the skeleton drawn in its place** until the artwork had been "warmed" again.
- Because the whole content branch left composition, everything remembered inside it was lost:
  `openingFocusApplied`, the restore state, the row list states. When the page came back it
  **placed its opening focus again**, on the first card.
- `entryRowId` reset, so the entry requester could move to a different row.
- `handledLibraryRevision` reset to zero, so the current library revision looked unseen and
  **a second forced refresh** was requested on top of the one the key change had already started.
- Each forced refresh **cancelled the read in flight and started a full one** - catalogue,
  library, progress, add-ons, media servers.

So "load, reload, reload again" was literal: one page teardown and two full re-reads per late
input. Nothing was wrong with how Continue Watching was fetched or slotted; the earlier work on
reserved slots and the priority hold was sound and is kept.

Two smaller things added to it:

- A cold process had **no cached Home at all**. `homeCache` is in memory, so a returning viewer
  always started from a skeleton.
- `prefetchHeroCandidates` updated the screen state once per title logo it found - up to eight
  whole-screen recompositions in the second after Home appeared.

## What changed

### One owner of Home's state: `HomeViewModel`

The screen no longer decides when to load. It tells the view model two things and asks for
refreshes; the view model decides what happens.

- `bind(identity, layout, sources)`
  - **identity** = account + profile. The only thing that starts Home over.
  - **layout** = add-ons and built-in catalogues: which rows exist.
  - **sources** = CloudStream version, Fuse, media server revision: what settles late.
- `requestRefresh(reason, immediate)` for the 15 s poll, library writes, re-entering Home and
  viewer actions.

Rules it applies:

- A change of **sources** during the first load does not cancel it. The load finishes and exactly
  one refresh follows.
- A change of **layout** before any confirmed page is up restarts the first load (nobody has seen
  it). After that it is a refresh.
- **Refreshes are coalesced.** Requests are gathered for 600 ms; while a read is in flight further
  requests set one flag; at most one read runs and at most one follows it. A read in flight is
  never cancelled by a refresh request.
- A refresh is applied **as one step** when it is complete, never row by row over a page someone
  is browsing.
- **Rows that did not change keep their object identity** (`sharingRowsWith`), so with strong
  skipping only the rows that differ recompose. A read that changed nothing publishes nothing.

### The screen keys its state on identity only

`initialArtworkReady`, `entryRowId` and `handledLibraryRevision` are `remember(identityKey)`. Home
is revealed once per identity and never un-revealed. `handledLibraryRevision` is seeded with the
current revision instead of zero. The screen also refuses to draw content whose identity is not
the current one, so a profile switch shows the skeleton, not the previous profile's shelves.

### Cached, then fresh

The last complete Home per profile is written to `files/home-snapshots/` (`HomeSnapshot.kt`) and
shown immediately on the next cold start; the fresh read replaces it in one step.

- Dropped if written by another app version (row titles are resource ids), older than 14 days,
  for another profile, or unparseable.
- Capped at 24 rows of 20 cards; six profiles kept.
- **Not written:** live rows, live channels, and any card carrying a direct stream URL, request
  headers or DRM keys. Media-server artwork URLs carry no token (it travels as a header).
- Cleared on sign-out. Adult-content policy is applied on read.

### Smaller

- Hero logo prefetch publishes once, not once per title.
- The view model logs every decision under the `HomeVm` tag: `bind`, each `load#n` with whether it
  is the first load or a refresh and why, and each publish with `changedRows`.

## Startup requests, for reference

One Home read is: catalogue manifest (cached for hours), catalogue rows, the library group
(`/sync/library`, `/sync/progress`, service playback, media-server Continue Watching - in
parallel), add-on catalogues (one per add-on), media-server rows, CloudStream rows, the Fuse row.
Around it: bootstrap refresh, media-server refresh, up to eight hero detail prefetches, and the
hand-off poll every 3 s.

Before: that whole read ran once, plus twice more for every late input. After: once, plus one
coalesced refresh if any source settled during it.

## Not changed, worth doing next

- **The 15 s poll is still a forced read of everything.** Only the library changes that often;
  a library-only poll would cut steady-state traffic substantially.
- The view model's scheduling is not unit-tested, because it takes the concrete repository.
  Putting `homeContentStream` / `loadHomeSnapshot` behind a small interface would allow that.

## How this was verified

- `HomeViewModel.kt` and `HomeSnapshot.kt` type-check with no errors (against Gson's sources and
  signature-matching stand-ins for coroutines and lifecycle).
- `HomeSnapshotTest` type-checks but **was not run** here (no Gson binary available).
- `HomeScreen.kt` was syntax-checked only; Compose is not available to type-check against.

## To measure on a device

Filter logcat on `HomeVm` and `StreamDekPerf` (debug builds).

- [ ] Cold start, returning viewer: saved page appears, one `publish ... overSaved=true`, no skeleton after first paint
- [ ] Cold start, first run / after update: skeleton, then one reveal; late rows fill reserved slots
- [ ] Exactly one `first load` and at most one `refresh (queued)` in the first 15 s
- [ ] Highlight stays put while Continue Watching, media-server and CloudStream rows land
- [ ] Navigate during hydration: focus is not taken back
- [ ] Profile switch: skeleton or that profile's saved page, never the previous profile's rows
- [ ] Remove a Continue Watching card: updates promptly
- [ ] Sign out: `files/home-snapshots/` is empty
- [ ] Time to first useful content and to interactive, before and after; recomposition counts in Layout Inspector
