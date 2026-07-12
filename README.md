# gftd-avatar-actor

A **VRM avatar compositing** loop actor for
[`network-isekai`](https://github.com/gftdcojp/network-isekai), gftdcojp's
fifth of seven per-modality asset actors (ADR-2607122400). Persona: **ソウ
(Sō)**, 衣装師 (costumer) — "重ね着の間合いを整える衣装師。着崩れない一着を
選ぶ — 派手さより着ている本人の自然さを優先する" (see `resources/persona.edn`).
Sibling actors: `gftd-illust-actor` (illustration), `gftd-sculpt-actor` (3D),
`gftd-rig-actor` (auto-rig), `gftd-motion-actor` (motion clips),
`gftd-audio-actor` (music+SFX), `gftd-voice-actor` (TTS).

Built on the same "sealed intelligence ⊣ independent governor ⊣ append-only
ledger" containment pattern as this workspace's other actors
(`gftd-talent-actor`, `wami-actor`, `cloud-itonami`, `gftd-illust-actor`) —
here it is **co-scientist tournament ⊣ AssetGovernor**, run by a **durable
outer loop** (not a StateGraph — murakumo generation jobs are async,
minutes-scale, and this workspace's CLAUDE.md is explicit that long-running
work belongs in a lease/tick/budget loop, not a StateGraph interrupt).

## Not text-to-X: this actor composites, it does not conjure

`gftd-illust-actor`'s `:image` engine is a pure text-prompt generator — a
prompt alone is a complete request. `:vrm-compose` (engine `:kisekae`) is
**not** that: per cloud-murakumo's `murakumo.edn`, kisekae is a Mac-mini
worker that reconnects skin/mesh, applies material/expression, and exports a
VRM from a set of **existing input reference CIDs** (a base body + costume/
material parts). So `avatar.generate/round-candidates` varies the **compose
configuration** across candidates — `:expression` / `:material-variant` /
`:accessory` — instead of subject/style/lighting text, and every candidate's
`:params` carries `:refs`, the input part CIDs to composite, resolved once
per call from `AVATAR_PARTS_CIDS` (see HONEST LIMITS below). `avatar.generate`
is a plain `.clj` (not `.cljc` like `illust.generate`) because it needs
`System/getenv` up front for that — same judgment `illust.persona` made once
a JVM-only call was needed (root CLAUDE.md `.cljc`/`.kotoba` runtime-priority
note).

## The core contract

```
avatar.generate            murakumo fleet (async gen.job)      avatar.judge
 (closed compose-config,  ──▶  submit via cloud-murakumo.gen + ──▶ (persona-fit
  persona-flavored,             queue-kotoba, poll for :done)      compose-config
  :refs from                                                       score)
  AVATAR_PARTS_CIDS)
                                    │
                                    ▼
                        avatar.cosci/run-round
              (Reflection=HARD gate, Ranking=Elo on judge score,
                    Proximity, Evolution, Meta-review)
                                    │
                              round winner
                                    ▼
                          avatar.governor/violations
                    (license-free? format-ok? safe? titled?
                          write-kind is :asset only)
                          │                    │
                        ok?                  hard
                          ▼                    ▼
          avatar.datalad + avatar.aozora    avatar.ledger
          (save to assets/, datalad push,   (:held — no binary
           publish to net.avatar.asset)      is ever saved)
```

**The actor never commits/publishes an asset the AssetGovernor would
reject**, and it never writes anything but `:kind :asset` — it does not
touch network-isekai's game logic or canon, it only produces free material
for games to consume.

**HONEST LIMITS** (state these, do not pretend otherwise):
- **This actor cannot compose an avatar from nothing** — it needs
  `AVATAR_PARTS_CIDS` set to existing base+costume asset CIDs (comma-
  separated, e.g. `"cid-base-body,cid-costume-jacket"` — perhaps sourced from
  `gftd-sculpt-actor`'s own accepted output). If that env var is unset or
  blank, `avatar.generate/round-candidates` returns **zero candidates** for
  that round — this loop never submits a broken compose job with no input
  refs. **Wiring an automatic hand-off from other actors' accepted output
  into `AVATAR_PARTS_CIDS` is explicit follow-up work, not done here.**
- `avatar.judge` scores the candidate's **compose-configuration text**
  (expression/material/accessory choice) for persona-fit, not the actual
  visual/mesh result of the compositing job. A real perceptual judge (a
  vision-capable critique call against the exported VRM's rendered preview)
  is follow-up.
- Whether a submitted job ever leaves `:queued` depends on a murakumo fleet
  worker (Mac-mini / `gad`) being up and consuming the `gftd-murakumo` kotoba
  queue — this actor only submits/polls, it never runs GPU inference itself.
- `avatar.murakumo/artifact-url`'s CID→URL resolution is a best-effort guess
  (`KOTOBASE_ARTIFACT_BASE_URL` overrides it), not a confirmed contract.

## This repo IS its own DataLad dataset

Unlike a typical actor repo, `assets/` here is **git-annex + Backblaze B2**
(`-c text2git`: code/EDN stay plain git, binaries get annexed) — accepted
assets are saved straight into this repo and pushed to B2, so "actor's own
git repo" and "asset storage" are the same thing (ADR-2607122400 §5).
`assets/<id>.edn` is written in the `network-isekai` `isekai.asset` manifest
shape so a later Asset Hub import needs no conversion.

```sh
datalad get assets/            # fetch real bytes from B2 (skeleton clones without them)
datalad push --to b2           # push new bytes after a local save
```

## Running

```sh
AVATAR_PARTS_CIDS="cid-base-body,cid-costume-jacket" clojure -M:run tick
                         # one durable-loop step (cron/launchd) — see HONEST
                         # LIMITS: without AVATAR_PARTS_CIDS, tick still
                         # returns :submitted but submits zero candidates
clojure -M:run run       # stay resident, tick on an interval
clojure -M:run status    # print ledger tail + loop state
clojure -M:test          # offline, fully faked (no network) — see test/avatar/loop_test.clj
clojure -M:lint          # clj-kondo, errors fail
```

Env: `AVATAR_PARTS_CIDS` (comma-separated input part CIDs — see HONEST
LIMITS; no default, required for any candidate to be generated),
`ASSET_ACTOR_DAILY_BUDGET` (default 8 gen jobs/day),
`MURAKUMO_KOTOBA_URL`/`MURAKUMO_KOTOBA_GRAPH`/`MURAKUMO_KOTOBA_TOKEN`
(queue-kotoba auth), `MURAKUMO_GATEWAY_URL` (judge's chat-completions
gateway).

CACAO identity is self-minted to `.avatar/identity.edn` on first run
(gitignored — never commit a private key). aozora collection:
`net.avatar.asset.publish`.

## Design

ADR-2607122400 (`network-isekai 向け murakumo 生成アセット持続ループ actor
群`) is the SSoT for this actor and its six siblings. Direct code ancestry:
`cloud-itonami`'s `src/cloud_itonami/media/{murakumo,aozora,cacao,publisher,
publish}.clj(c)` (murakumo→governor→aozora pipeline), `cloud-murakumo`'s
`src/cloud_murakumo/cosci.cljc` (co-scientist tournament shape), and
`gftd-illust-actor` (the reference implementation of this same actor shape,
sibling #1 of 7 — this repo is a faithful port of it with the compose-config/
`:refs` difference described above).
