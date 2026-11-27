# Optimizing TG Player with the LLM_Tails Wiki

How this project uses the LLM_Tails wiki (`wiki/`) to optimize code **efficiently
and safely** — understanding before changing, smallest change that holds, every
claim backed by a file:line, nothing invented. This doc is both the method and
the concrete, ranked plan it produced for TG Player.

---

## 1. Why a wiki makes optimization efficient

Optimizing a working app blind is how you break it. The wiki turns "optimize the
app" from an open-ended rewrite into a bounded, evidence-driven task:

1. **Understand first, once.** The codebase is read and distilled into
   `wiki/` (overview, architecture, entities, conventions). Later sessions read
   the wiki instead of re-reading 10k lines — cheap context, consistent mental
   model. (LLM_Tails pillar 1.)
2. **Evidence travels with the claim.** Every hotspot in
   `wiki/synthesis/optimization-targets.md` cites `file:line`. No "this is
   probably slow" — you can open the exact code. (Pillar 3.)
3. **The ladder keeps changes minimal.** Each target names the *lowest* rung that
   fixes it (cache a field, not a new caching framework). Deletion/boring over
   clever. (Pillar 2.)
4. **Consistency is pre-decided.** `wiki/conventions.md` records the house style
   (Kotlin+Compose, globals not ViewModels, `tails:` comments). Optimizations
   match it instead of introducing a second pattern. (Pillar 4.)
5. **It compounds.** Each landed optimization updates its `status` in the wiki and
   appends to `wiki/log.md`, so the next session starts from current truth.

## 2. The workflow (what was actually done)

```
tails-wiki-init            → scaffold wiki/ (three-layer Karpathy pattern)
  │
  ├─ read build config     → gradle, manifest, libs.versions, jniLibs  (direct)
  └─ 3 parallel Explore maps→ player-engine · ui-compose · data-integrations
         │                     (each reports architecture + hotspots + file:line)
  ▼
synthesis/optimization-targets.md  → rank by impact × risk × ladder rung
  ▼
propose → approve → implement smallest-correct fix → verify → update wiki status
```

Parallel subagents keep the main context clean (LLM_Tails "context discipline":
never pull large raw output into context — filter at the source). The maps return
*conclusions and citations*, not file dumps.

## 3. How a target becomes a change (the safety gate)

For each optimization, before editing:

1. **Re-read the code it touches in full** — the map located it; you still read
   it. Never write into unread code.
2. **Confirm the ladder rung** — is there a smaller fix? Is it even needed (YAGNI)?
3. **Keep behaviour** — runtime optimizations must not change what the app does;
   note any deliberate simplification with a `tails:` comment.
4. **Leave one runnable check** — a unit test (pure logic like the range store or
   the equalizer) or a measurable before/after. "Done" means verified, with
   evidence, or it says "not verified".
5. **Respect the not-negotiables** — never optimize away input validation at trust
   boundaries (network sources, subtitle/URL parsing), data-loss handling,
   security (`allowBackup=false`, secret masking), or accessibility.

## 4. Ranked plan for TG Player

Full detail and citations: `wiki/synthesis/optimization-targets.md`. Summary:

| ID | Target | Area | Impact | Risk | Status |
|----|--------|------|--------|------|--------|
| OPT-1 | Telegram grid: `@Immutable`/`@Stable` models + off-main mini-thumb decode | Runtime/Compose | High | Med | **done** (stability + decode; progress-state scoping deferred) |
| OPT-2 | Read-ahead range store O(n log n)→O(1) via `RangeSet` + running total | Runtime/player | High | Low-Med | **done** (+ unit test) |
| OPT-3 | Cache parsed drives/Jellyfin config (stop per-item JSON re-parse) | Runtime/data | Med | Low | **done** |
| OPT-4 | Library scan: memoize genres, bounded-parallel TMDb, safe cache writes | Runtime/data | Med | Low-Med | **done** |
| OPT-5 | Equalizer per-sample loop → bulk `ShortArray`/`FloatArray` | Runtime/audio | Med | Med | **done** (identical output, test-guarded) |
| OPT-6 | Player screens: scope state / key the queue | Runtime/Compose | Med | Med | **deferred** — queue key unsafe (duplicate songs); `version++` rewrite needs on-device check |
| OPT-7 | CastServer: cache VTT, bound thread pool | Runtime | Med | Low-Med | **deferred** — cast-only, not verifiable without a receiver |
| OPT-8 | **R8 minify + resource shrinking** | APK size | High | High | **deferred** — needs on-device release test |
| OPT-9 | Trim `material-icons-extended` | APK size | Med | Low-Med | **not feasible** — ~90 icons, many extended-only; R8 (OPT-8) is the real fix |
| OPT-10 | `gradle.properties` build-speed flags | Build time | Low | Low | **done** (parallel/caching/config-cache; build verified) |

**Verified:** `./gradlew :app:testDebugUnitTest :app:assembleDebug` — BUILD SUCCESSFUL, all unit
tests pass (incl. the new `RangeSetTest`, 6/6, and the `EqualizerProcessorTest`, 3/3), APK assembles.
On-device smoke test still owed (playback, scrolling the Telegram grid, a library scan, the equalizer).

**Deliberately not targets (YAGNI):** ABI splits (already two ARM ABIs, both
needed), the DropIt /24 sweep cadence (by design — routers drop multicast; see
project memory), and rewriting the already-minimal TDLib bridge / DataSource
routing.

## 5. Recommended order

1. **Low-risk, high-value first:** OPT-2 (add a unit test), OPT-3, OPT-4
   (genre memoization part), OPT-10. Self-contained, verifiable, no UI risk.
2. **Compose runtime:** OPT-1, OPT-6 — biggest felt improvement (scroll), needs
   on-device recomposition check.
3. **Audio:** OPT-5 — guard with the existing equalizer unit test.
4. **Size, separately:** OPT-9, then OPT-8 (R8) as its own change with a full
   release build + device smoke test, because keep-rule mistakes surface only at
   runtime (TDLib JNI, reflective SSH/SMB libs).

Each landed item: update its `status` in the wiki and append to `wiki/log.md`.
