# Plan: bounded thread snapshots for Trefoil

Status: research plan, revised 2026-11-25 after review (see "Review log"). Sources: Trefoil worktree `t3-9973a13d` (the
containment fix is already on main as `90aff0b`), T3 checkout `/Users/mtajchert/coding/priv/t3code` @ `54ba2408f4`.
T3 paths are relative to that checkout; Trefoil paths are relative to the repo root. "Verified" means read in source.
"Unverified" means inferred and needs a check.

## 1. Findings that shape the plan

1. **The bounded path needs one flag.** The server switches on `acceptBoundedSnapshot === true` alone (`apps/server/src/ws.ts:823-828`),
   loads `getThreadSnapshotWindow(rowLimit, userTurnLimit = 10, deferWork)` (`ws.ts:1009-1018`) and builds the item with
   `buildBoundedThreadStreamSnapshot` (`ws.ts:1029-1035`; `orchestration-v2/ThreadStream.ts:68-93`). The other four flags only
   change encoding: `compactTurnItems` (`ThreadStream.ts:58`), `dedupeBodies` (`*Omitted` markers, `orchestrationV2.ts:998-1003`),
   `compactIds` (ID table, `orchestrationV2IdTable.ts:4-8`) and `deferWork` (item 9).
2. **The size bound.** `THREAD_HISTORY_PAGE_POLICY` = 10 user turns, 75 items, 500 turn items, `maxEncodedBytes` 1 MiB
   (`orchestration-v2/threadHistoryPaging.ts:16-21`; window sizing `:661-715`, plan/handoff stubs `:747-775`, messages `:594-620`).
   **Control state (runs, attempts, nodes, providerTurns, subagents…) is not paged**, so a bounded frame is ≈1–2 MiB in practice,
   not guaranteed below 16 MiB (unverified for pathological threads).
3. **Snapshot item shape** (`packages/contracts/src/orchestrationV2.ts:4440-4462`): `{kind:"snapshot", snapshotSequence, projection,
   historyCursor?: string|null, hasMoreHistory?: bool, latestLocalTurnOrdinal?: int|null, turnItemsOmitLocalVisible?: true, idTable?}`.
   Without the encoding flags `projection` is a normal thread projection whose `visibleTurnItems` are renumbered from 0
   (`threadHistoryPaging.ts:719`); Trefoil's JSON parsing needs no change. The cursor is opaque.
4. **Trefoil today** subscribes with only `{threadId, requestCompletionMarker:true}` (`android/core/session/.../T3Client.kt:1011`), the
   unbounded branch (`ws.ts:1019`, `:1036-1043`). The thread that failed was ~30 MB even after wire slimming (`WireProjection.ts:28-30`, `:158-201`).
5. **The per-event refetch is already bounded on the server, and that silently truncates history.** `orchestration.getThreadProjection`
   returns `buildBoundedThreadProjection(...).projection`: the recent window, no cursor (`ws.ts:2207-2228`, comment "Bound compatibility
   snapshots so pre-pagination clients cannot materialize unlimited transcripts"). True since at least Trefoil's pin `46449b5379`
   (ws.ts:2195 there) and v0.1.11 (ws.ts:2162). Trefoil calls it on every event (`T3Client.kt:995-1008`) and replaces the projection
   (`applyProjection`, `:1225`), so a long thread shows full history until the first live event, then shrinks to ~10 turns (from code,
   not observed on a device). Each refetch is up to ~1 MiB per conflated burst.
6. **Older history is HTTP only.** `GET /api/orchestration/threads/:threadId/history?cursor=…[&throughEntryId][&view=conversation|activity|work][&deferWork=1][&compactIds=1]`
   (`environmentHttp.ts:758-764`, `:807-815`; handler `orchestration-v2/http.ts:277-316`). It needs header `x-t3-orchestration-protocol: 2`
   plus bearer or DPoP (`environmentHttp.ts:106-110`). Response `OrchestrationV2ThreadHistoryPage` = `{snapshotSequence, items: ProjectedTurnItem[],
   nextCursor, hasMoreHistory, subagents?, idTable?}` (`orchestrationV2.ts:4376-4393`). Errors: 400 `invalid_history_cursor`, 404
   `thread_not_found` (`http.ts:282-289`, `:302-312`). `GET …/bounded` also exists; not used (§8).
7. **The web client** (`packages/client-runtime/src/state/threads.ts`) sends the bounded flags only once the server advertises paging
   (`:1176-1191`), restores with `boundedSnapshotProjection` (`:701-718`), applies live events with a local reducer and never refetches
   (`:614-640`). Paging is `loadEarlier` (`:785-946`): GET history, `mergeOlderHistoryIntoProjection` / `mergeOlderSubagentsIntoProjection`
   (`state/threadHistoryMerge.ts:86-213`), `applyHistoryPageMeta` (`:215-230`); it pages until a page has a turn start (`:869-877`) and fails
   on a cursor that did not advance. An expanded timeline is never persisted (`:~1010-1015`).
8. **Bodies that load on demand already work.** `loadActivity` calls `orchestration.getTurnItem` (`T3Client.kt:1275-1297`; contract
   `orchestrationV2.ts:4663-4673`), output capped at 256 KiB (`WireProjection.ts:30`), for any held item including history-page items.
   Tool images use `assets.createUrl` (`T3Client.kt:1545-1555`), independent of the snapshot.
9. **Deferred work.** With `acceptDeferredWork`, work rows carry `item.deferredWork {cursor, itemCount, lastOrdinal}`
   (`orchestrationV2.ts:1774-1782`) and expand through `/history?view=work&throughEntryId=…` (`threads.ts:776-830`). Wave 3.
10. **Server support.** All five flags exist at tag `v0.1.11` (59651c1761); `server.getConfig` advertises `threadSnapshotPagination: true`
    (v0.1.11 `ws.ts:1907`; current checkout `ws.ts:1951`; contract `server.ts:743-744`). That a server ignores extra struct keys is
    unverified (Effect Schema's default ignores excess properties).
11. **Trefoil HTTP.** `authorizedFetch` (`T3Client.kt:254-271`) handles bearer, DPoP and renewal, but `parseFetched` reads `body.string()`
    with **no size cap** (`:238-241`); history reads must be capped. The derived client already disables redirects (`T3Client.kt:80`;
    asserted at `T3ClientTest.kt:30`).
12. **Rendering paged rows (verified).** `presentationItems` (`PresentationProjection.kt:~175-187`) reads each `visibleTurnItems` row's
    embedded `row.item` plus `position`/`sourceThreadId`/`sourceItemId`, never top-level `turnItems`, when `visibleTurnItems` exists.
    `conversationMessages` (`:~265-282`) falls back to item text when `messages` lacks the id. So paged rows render without `messages`
    or `turnItems` entries. `turnItems` is still read raw by `BackgroundWorkProjection.kt:42,64`, `ThreadQueueProjection.kt:138` and
    `PresentationProjection.kt:27`; those need only the **fresh** window, so `turnItems` is taken from `fresh`, never unioned with held rows.

## 2. Decisions

| Flag | Adopt | Why |
|---|---|---|
| `acceptBoundedSnapshot` | **Now** | It alone bounds the frame. It needs no decoding changes. |
| `acceptCompactTurnItems` | Later (wave 3) | It only shrinks the frame (the server already charges local items twice, `threadHistoryPaging.ts:117-127`). Restoring it is trivial (prepend local visible-row items). |
| `acceptDeferredWork` | Later (wave 3) | It needs "N work steps" expansion UI and `view=work` paging. |
| `acceptDedupedBodies` | No (revisit) | The client must keep restore state across the live tail (`ws.ts:936-940`). There is little gain with the refetch model. |
| `acceptCompactIds` | No | It needs ID-table expansion on every record (`orchestrationV2IdTable.ts`). It only saves bytes. |

- Gate the flag on `server.getConfig.threadSnapshotPagination == true`, matching the web client. Trefoil already reads
  `server.getConfig` at `T3Client.kt:548`.
- Keep the conflated `getThreadProjection` refetch for now. **Merge it into the held window instead of replacing it**
  (§3.3), because it is authoritative, simple, and already bounded. Porting T3's incremental reducer
  (`client-runtime/src/state/orchestrationV2Projection.ts`, 480 lines) is a separate later stream.
- Keep the containment path (`THREAD_TOO_LARGE`, `T3Client.kt:828-841`, `:2144`, landed in `90aff0b`) as the last-resort
  guard for unbounded control state, old servers, and oversized refetches.

## 3. Design

### 3.1 State (core/session, T3Client)

Add per-selection `ThreadHistory(cursor: String?, hasMore: Boolean, loading: Boolean, error: String?, expanded: Boolean,
latestLocalTurnOrdinal: Int?, heldPageBytes: Long, replaceNextSnapshot: Boolean)`. It is guarded by `lifecycleLock` and
reset in `selectThreadNow`, `clearThreadSelection`, `resubscribeThread` and `fail`.

Expose it in `ClientState` (core/model `Client.kt`) as `historyHasMore`, `historyLoading` and `historyError`. Add
`suspend fun loadEarlierHistory()` to `TrefoilClient` (default `error("History is unavailable")`, like `loadActivity`
at `core/model/.../Client.kt:380`). These two additions are the wave-0 commit (§6).

### 3.2 Subscribe

`subscribeThread` input = `{threadId, requestCompletionMarker:true}`, plus `acceptBoundedSnapshot:true` when paging is
advertised. Do not send `afterSequence`; that stays as today.

**Every `snapshot` item goes through `applySnapshot`, not a bare replace** (today `T3Client.kt:1011` replaces; the
server also re-sends snapshots on stream resume, and the cache strips paged rows, so a replace would drop loaded
history and scroll position on every reconnect or foreground):
- `replaceNextSnapshot` set (first subscribe with nothing held, or after `resubscribeThread`) → replace, then set history
  from `historyCursor ?? null`, `hasMoreHistory ?? false`, `latestLocalTurnOrdinal ?? null`; clear the flag.
- Otherwise (same thread, rows held, from cache or an earlier snapshot) → `mergeLiveWindow(held, snapshot.projection)`
  (§3.3). On **overlap**: if `expanded`, keep the held (older) cursor and `hasMore`; if not expanded, take the
  snapshot's cursor and meta. On a **gap** (no shared row key): replace, take the snapshot's meta, reset `expanded`,
  `heldPageBytes` and `error`. A snapshot never triggers a resubscribe (it is already the fresh baseline).
- A snapshot that lacks all three meta fields means a full timeline: `hasMore = false`, as in web `threads.ts:701-718`.
- Unrequested `turnItemsOmitLocalVisible` or `idTable` → **close only this thread**, not the session: new
  `threadProtocolMismatch()` next to `threadTooLarge()` (`T3Client.kt:838`): `clearThreadSelection()` plus a thread-level
  error "This thread uses a newer format than this app reads. Open it in T3 Code on your computer." The connection,
  shell and other environments stay up. The same handler covers these fields on a refetch or history page.

### 3.3 Merging the live refetch (replaces the wholesale replace)

New pure file `core/session/.../ThreadHistoryMerge.kt`. It adapts `threadHistoryMerge.ts` (MIT attribution header).
`mergeLiveWindow(held, fresh)`:
- The key is `sourceThreadId:sourceItemId` (`threadHistoryMerge.ts:44-46`).
- Control fields come from `fresh` (thread, runs, attempts, nodes, providerTurns, runtimeRequests, plans,
  contextHandoffs, subagents, queue).
- `visibleTurnItems` = held rows strictly older than the first row shared with `fresh`, then all `fresh` rows,
  renumbered.
- `turnItems` = `fresh` only (§1.12). `messages` = `fresh` messages; older rows render from their embedded item text.
- Drop retained rows whose `runId` is now `rolled_back` (`threadHistoryMerge.ts:146-147`).
- **Gap rule:** if `held` has rows and no key overlaps `fresh` (the window moved past everything held, for example after
  more than 10 new turns while backgrounded), `mergeLiveWindow` returns `Gap`. The refetch path then calls
  `resubscribeThread` below.
- **Stale-row rule (decision for held rows above the fresh window).** The refetch only refreshes rows inside the fresh
  window; a held row above it (an older page, or a row that slid off the 75-row window inside one very long turn) is
  never refreshed, so a tool row that was running when it left the window would show a spinner forever. Choice:
  `mergeLiveWindow` returns `Stale` when any held-only row is non-terminal (reuse the existing activity-status helper),
  and the caller resubscribes. Why this over "accept and document": the resubscribe path exists anyway for the gap rule,
  the check is a few lines, and the failure it prevents (a permanently wrong "running" row) is visible and unfixable
  without reopening the thread. Cost: the user loses loaded older pages in that rare case. It cannot loop, because
  after a resubscribe `held` is only the fresh window, so no held-only rows exist until new rows slide off again.
- When `hasMore == false` and nothing was paged (a complete timeline from an old server), merge the same way; the fresh
  window is a suffix.

**`resubscribeThread()` (dedicated path; do not reuse `selectThreadNow`).** `selectThreadNow` returns early when the same
thread is already subscribed (`T3Client.kt:977`), so "reselect" is a no-op. The new method, under `lifecycleLock`:
1. Capture `threadId = selectedThreadId`; return unless connected, shell ready and `threadSubscription != null`.
2. Interrupt the old subscription (`streams.remove`, `send(EffectRpc.signal("Interrupt", id))`), `threadSubscription = null`,
   close `refreshSignals`, cancel `refreshJob`, `threadReady = false`, `++threadGeneration` (so in-flight refetches,
   `loadEarlierHistory` and `loadActivity` results are dropped by their generation checks).
3. Reset `ThreadHistory` to empty with `replaceNextSnapshot = true`. Keep the displayed projection (no empty flash), set
   `canWrite = false` until the new `synchronized` marker.
4. Re-run the subscribe/refetch setup. Extract lines `T3Client.kt:~981-1024` of `selectThreadNow` (after its same-thread
   early return) into `startThreadSubscription(threadId)`; `selectThreadNow` and `resubscribeThread` both call it.
Loop guard: a resubscribe is only requested from the refetch path, never from the snapshot path; the first snapshot after
it replaces (`replaceNextSnapshot`), so the fresh `held` is exactly the server window and the next refetch merges with
full overlap. At most one resubscribe is in flight (`threadSubscription` is replaced synchronously and the stale
refetch is dropped by generation).

`retainHydratedActivities` (`PresentationProjection.kt:226-244`) must run after the merge, so loaded bodies survive.
Writes stay gated exactly as now: `threadReady` and revision (`T3Client.kt:1001-1003`, `:1011-1017`). History has no
effect on `canWrite`.

### 3.4 Loading older history

`loadEarlierHistory()`:
1. Under the lock, capture `(generation, threadGeneration, threadId, cursor)`. Return early if `cursor == null`,
   `!hasMore` or `loading`. Set `loading = true`.
2. GET `/history?cursor=<cursor>` through `authorizedFetch` with the `x-t3-orchestration-protocol: 2` header.
   - Add a header parameter and a **4 MiB body cap** (same as the total paged budget, §3.5): read the source with a limit;
     throw a `HistoryTooLarge` like `FrameTooLarge`.
   - Go to the connection's current route base (`activeBase`). Every Trefoil route kind (`Lan`, `Tailnet`, `Public`, `Ssh`,
     `Tunnel`, `Routes.kt:8`) is plain HTTP(S) to the server origin that already serves `/api/auth/*` and `/api/connect/*`;
     Trefoil has no relay route (§8).
3. Under the lock, if the captured identity is stale, drop the result.
   Otherwise `mergeOlderPage(projection, page.items)` (a port of `mergeOlderHistoryIntoProjection`, non-work branch:
   `interleavePage`, hidden live items stay hidden, rolled-back rows skipped). Then merge subagents (held records win),
   set `cursor = nextCursor`, `hasMore = hasMoreHistory`, `expanded = true`, add the body size to `heldPageBytes`, and call
   `applyProjection(..., authoritative = false)` so `projectionRevision` does not invalidate writes.
4. Like the web client, keep paging while the page has no turn start and `hasMore`, within the byte cap. Fail with
   "History did not advance" if `nextCursor == cursor`.
5. Errors set `historyError` (400 `invalid_history_cursor` means "Reload thread", which calls `resubscribeThread`; 404
   means the thread was deleted and the shell handles it). There is **no automatic retry loop**; only the user's tap
   retries. GET is idempotent, so a retry is safe.

### 3.5 Memory and CPU bounds

- The live window is ≈1–2 MiB JSON (§1.2).
- **Hard cap on paged history: 4 MiB encoded** (sum of page body sizes, `heldPageBytes`), about 4 pages, **no eviction**.
  Past the cap, set `hasMore = false` with `historyError = "Older history: open in T3 Code on your computer"`.
  Raise only after the F3 measurement below passes.
- **Cost per event burst.** `applyProjection` runs `retainHydratedActivities`, `presentationItems` (sort plus map of every
  row) and the screen derivations over the whole merged projection on each refetch (`T3Client.kt:1225-1260`,
  `PresentationProjection.kt:~175,226`). Nothing here is incremental, so cost grows with held rows. Mitigations to build:
  compute `presentationItems(merged)` once per `applyProjection` and reuse it; keep `retainHydratedActivities` iterating
  only previously hydrated rows (it already filters).
  **Measurement step (blocks raising the cap or enabling auto-load):** a JVM benchmark test that applies a synthetic
  4 MiB projection (window plus 4 pages) 20 times, plus the same on a mid-range Android device (for example a Pixel 6a-class
  or older, release build) via an on-device timing log of `applyProjection`. Target: p95 ≤ 50 ms with the cap at 4 MiB;
  record the numbers in the stream report. Raise the cap (to 8 MiB) only if p95 stays under that target at the higher size.
- `threadProjections` is written in **two places**: `applyProjection` (`T3Client.kt:~1232-1235`) and the new-conversation
  seed (`T3Client.kt:~1728-1729`). Replace both with one `cacheThreadProjection(key, value)` helper that (a) strips paged
  rows when `expanded` (keeps only the live window, as the web client does), (b) records an estimated size (the frame's
  character count when known, else `toString().length` computed once per write and covered by the measurement above), and
  (c) enforces a **byte budget** (16 MiB total, LRU) in addition to the existing 12-entry count. A test covers both call
  sites.
- A cached entry opened offline has `hasMore = true` and `cursor = null`, so it shows "Connect to load earlier".
- The shell cache (`ShellCache.kt`, encrypted on disk, shell rows only) is unaffected; thread content is never
  persisted to disk.

### 3.6 UI (app)

`ConversationUi.kt:272-367` and `ConversationWindow.kt` already window the loaded timeline 10 turns at a time with
"Load earlier turns".
- Extend the row: when the client-side window has no hidden turns left and `historyHasMore`, the tap calls
  `loadEarlierHistory()`. Keep the anchor-preserving scroll (`ConversationUi.kt:314-327`).
- States: idle, loading (progress), error with retry, and capped (text plus the existing "open on computer" copy).
- v1 is an **explicit tap** (default, user may override). Auto-load when the row becomes visible is a follow-up after the
  F3 measurement passes; it must be debounced and never retry automatically after an error.

## 4. Safety (AGENTS.md)

- History and turn-item reads are read-only GET/RPC. Mutations are untouched and still never replayed. Unknown outcomes
  still fail the session (SPEC §8).
- Stale results are dropped by checking generation, selection, environment and connection, the same way `loadActivity`
  does (`T3Client.kt:1287-1291`).
- Do not log page content, cursors (the payload encodes item ids, `threadHistoryPaging.ts:46-53`), or response bodies.
  Error strings stay generic.
- History fetches use only the verified environment base URL and the existing URL policy (`validatedEnvironmentUrl`).
  Redirects are already disabled on the derived client (`T3Client.kt:80`, `T3ClientTest.kt:30`), so a history GET never
  follows a redirect to another origin; S1b adds one assertion that the history request goes through that client.
- Isolate failures by environment: a history error or protocol mismatch never fails the session or the shell (§3.2, §3.4).

## 5. Testing

- **Pure unit tests** (`:core:session:test`, `ThreadHistoryMergeTest.kt`): live merge keeps older rows; gap returns `Gap`; a
  non-terminal held-only row returns `Stale` (a terminal one does not); rolled-back run drops rows; hydrated bodies survive;
  `turnItems`/`messages` come from fresh only and older rows render without them; page interleave and dedupe; subagent merge;
  cursor-not-advancing error; byte cap. Fixtures are JSON shaped from `orchestrationV2.ts:4376-4393`, `:4440-4462`; real captures
  come from the live check, never from user threads.
- **Session tests** (`T3ClientTest.kt`, `OversizedFrameTest.kt` patterns, fake socket):
  - subscribe payload has `acceptBoundedSnapshot` only when the config advertises it; snapshot meta is stored;
  - the refetch is merged, not replaced; **a second same-thread snapshot (reconnect) merges, keeps older rows and the held
    cursor, and replaces only on a gap**;
  - **`resubscribeThread` on an already-subscribed thread sends an Interrupt, issues a new subscribe, bumps `threadGeneration`
    (a late refetch from the old subscription is dropped), and cannot loop: after the replacing snapshot a refetch with overlap
    issues no second subscribe, and two consecutive gap refetches before the new snapshot issue exactly one**;
  - a `Stale` held-only running row triggers exactly one resubscribe;
  - unrequested `idTable` / `turnItemsOmitLocalVisible` close the thread, leave `connection == Connected` and the shell intact;
  - `loadEarlierHistory` against an HTTP stub: header present, stale result dropped, 400/404 handling, redirect not followed;
  - `loadActivity` on a paged item; the cache stores the window only and both write sites use the byte-budgeted helper;
    `THREAD_TOO_LARGE` still fires on an oversized bounded frame.
- **Live check** (`scripts/validate-t3-wire.mjs`, disposable server): subscribe with `acceptBoundedSnapshot` and assert
  `hasMoreHistory`/`historyCursor` exist (`null`/`false` for a small thread); assert `threadSnapshotPagination === true`; a bogus
  cursor gives 400 `invalid_history_cursor` and a missing protocol header gives 400. One manual call of the history endpoint over
  the Tunnel route (cloudflared) and over the SSH route (§8). Real paging (>10 turns) = fixtures plus **one manual check on a disposable server**; not blocked on
  provider replay (default, user may override).
- **Benchmark:** the F3 measurement (§3.5).
- **App:** `:app:testDebugUnitTest` for row state logic; screenshot tests (`--tests '*ConversationScreenshotsKt'`) for loading,
  error, capped and server-has-more states; re-record and commit the PNGs.
- **Docs:** update `protocol/SPEC.md:13` (full snapshots requested) and `:19` ("server bounded-history paging is not used"); note
  at `docs/superpowers/specs/2026-11-06-trefoil-design.md:47`. Release notes state the §1.5 truncation bug is fixed.

## 6. Work split

The containment fix is already on main (`90aff0b`). `T3Client.kt` has one writer at a time.

**Wave 0 (orchestrator, ≤ 20 lines, one commit before any agent).** In `core/model/.../Client.kt`: add `historyHasMore: Boolean = false`,
`historyLoading: Boolean = false` and `historyError: String? = null` to `ClientState`, and
`suspend fun loadEarlierHistory() = error("History is unavailable")` to `TrefoilClient`. Run `:core:model:test`. This fixes the
interface S3 and S1b build against, so neither waits on `Client.kt`.

**Wave 1 (parallel, three worktrees)**
- **S1a, Opus: pure merge.** Owns `ThreadHistoryMerge.kt` (`mergeLiveWindow` → `Merged | Gap | Stale`, `mergeOlderPage`, subagent
  merge, byte-cap helpers) and `ThreadHistoryMergeTest.kt`; no `T3Client.kt` edits. Gate: `:core:session:test`.
- **S2, Haiku/Sonnet: live check and docs.** Owns `scripts/validate-t3-wire.mjs`, `protocol/SPEC.md`, the design-spec note. Runs the
  one-time Tunnel/SSH history-endpoint check (§8).
- **S3, Sonnet: app UI.** Owns `ConversationUi.kt`, `ConversationWindow.kt`, screenshot tests and PNGs, against the wave-0 fields
  with a fake client. Gate: `:app:testDebugUnitTest :app:lintDebug`, screenshot validate.

**Wave 2 — S1b, Opus: session wiring.** After S1a merges. Owns `T3Client.kt` (subscribe,
`applySnapshot`, refetch merge, `resubscribeThread` / `startThreadSubscription`, history state, `loadEarlierHistory`, HTTP header and
body cap, `cacheThreadProjection`, `threadProtocolMismatch`, benchmark) and its session tests. Gate: `:core:model:test
:core:session:test`; Opus review before merge.

**Wave 3 (later goal):** `acceptCompactTurnItems`; `acceptDeferredWork` with "N work steps" expansion (`view=work`); incremental
reducer replacing the per-event refetch; auto-load if the F3 measurement passes. Final gate: the full AGENTS.md check list once.

## 7. Risks

- **Merge correctness** (interleave, gap, stale rules) is the riskiest logic: port T3's algorithm closely, test rollback, hidden
  items and subagents; Opus review.
- **Per-burst CPU** grows with held rows (§3.5); bounded by the 4 MiB cap and the measurement gate.
- **Unbounded control state** could still exceed 16 MiB, so containment stays; no telemetry, so only user reports reveal it.
- **Refetch bandwidth** (≤ ~1 MiB per burst) is unchanged from today; wave 3 fixes it.
- **Stale cursor after rollback/compaction** returns an overlapping or gapped page silently, not an error (§8); handled by id
  dedupe and dropping paged history on history-changing events.

## 8. Open questions and defaults

Defaults (default, user may override):
1. **Auto-load on scroll?** Explicit tap for v1; auto-load only after the F3 measurement passes.
2. **Paged-history cap.** Hard cap at 4 MiB, no eviction; revisit upward after measurement.
3. **Live paging test.** Fixtures plus one manual check on a disposable server; do not block on provider replay.
4. **HTTP cold open (`/bounded`).** Skip; `server.getConfig` is read before any thread subscribes.
5. **Release notes.** State that the truncation bug (§1.5) is fixed.

**Resolved (agreed with the user):**
- **(a) Cursor after rollback or compaction.** The cursor is stateless base64url JSON `{v, seq, st, si, p}`
  (`threadHistoryPaging.ts:46-53`); the server re-anchors by source item id and, when that item is gone, falls back to the position
  (`:424-440`). It never expires; the only error is 400 for a malformed cursor or empty range. A stale cursor can therefore return
  an overlapping or gapped page silently. Treat the cursor as a hint and never repair: (1) `mergeOlderPage` dedupes by item id, so
  overlap is harmless; (2) on a rollback or compaction event, or a snapshot that does not overlap held rows, drop paged history and
  the cursor and `resubscribeThread()`, taking the new cursor from the fresh snapshot; (3) on 400/404 from `/history`, drop paged
  history, keep the window, show "Load earlier" again; (4) one fixture test: load a page, simulate a rollback, assert old pages
  are gone. Unverified: whether the server validates `seq` against current state; the handling holds either way.
- **(b) Route reachability.** Trefoil has no relay route; all route kinds are plain HTTP(S) to the same origin that already serves
  `/api/auth/*`, `/api/connect/link-state` and `/api/preview/tunnel`. Not a blocker: S2 makes one manual history call (with
  `x-t3-orchestration-protocol: 2`) over the Tunnel and SSH routes. Fallback: if `/history` fails on a connection with 404, 405 or
  a network error, hide "Load earlier" for that connection; the bounded window still opens the thread.

## Review log

Fable 5.2 medium review, verdict **REVISE**. Each finding verified against the code before editing.
- **F1** resubscribe via `selectThreadNow` is a no-op (early return, `T3Client.kt:977`): dedicated `resubscribeThread()` + generation bump + no-loop test (§3.3, §5).
- **F2** snapshots replaced the projection and dropped loaded history: same-thread snapshots go through `mergeLiveWindow`, keep the held cursor on overlap, replace only on a gap (§3.2).
- **F3** per-burst `applyProjection` cost: cap 4 MiB, compute `presentationItems` once, device measurement before raising the cap (§3.5).
- **F4** S3 blocked on S1-owned `Client.kt`: wave-0 orchestrator commit; pure merge split out as S1a (§6).
- **F5** stale held rows above the window: resubscribe when a held-only row is non-terminal (reuses F1 path, avoids a permanent false "running" row) (§3.3).
- **F6** unrequested `idTable` / `turnItemsOmitLocalVisible` close only the thread via `threadProtocolMismatch()` (§3.2).
- **F7** two LRU write sites (`T3Client.kt:~1232`, `~1728`): one byte-budgeted `cacheThreadProjection` helper (§3.5).
- **F8** containment is on main as `90aff0b`: removed the "uncommitted / must land first" notes.
- **F9** redirects already disabled (`T3Client.kt:80`, `T3ClientTest.kt:30`): dropped "unverified" (§1.11, §4).
- **F10** paged rows render from embedded `row.item` plus the text fallback: `turnItems` union dropped, fresh only (§1.12, §3.3).
- Fable comment fix: v0.1.11 comment is `ws.ts:2162`; `threadSnapshotPagination` is `ws.ts:1951` in the current checkout.
