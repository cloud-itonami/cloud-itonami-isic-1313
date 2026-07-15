# ADR-0001: FinishingAdvisor ⊣ Textile Finishing Operations Governor architecture

## Status

Accepted. `cloud-itonami-isic-1313` promoted from `:spec` to
`:implemented` in the `kotoba-lang/industry` registry, following the
verified fresh-scaffold protocol established by prior actors in this
fleet.

## Context

`cloud-itonami-isic-1313` publishes an OSS blueprint for textile-
finishing **contract-processing operations coordination**
(dyeing/printing/bleaching/mercerizing production-batch colorfastness/
shrinkage-test data logging, dyeing/printing-line-equipment maintenance
scheduling, chemical-handling/effluent-discharge concern flagging, and
outbound finished-fabric shipment coordination back to the customer).
Like every actor in this fleet, the blueprint alone is not an
implementation: this ADR records the governed-actor architecture that
promotes it to real, tested code, following the same langgraph
StateGraph + independent Governor + Phase 0->3 rollout pattern
established across the cloud-itonami fleet.

The closest domain analog is `cloud-itonami-isic-1391` (Manufacture of
knitted and crocheted fabrics): both are back-office plant-operations-
coordination actors with real physical-safety-relevant equipment
(dyeing/printing/bleaching/mercerizing lines vs. circular/flat-knitting
machines and crocheting lines), production-batch tracking with
quality/grade data, and equipment maintenance scheduling with a
permanent block on directly operating that equipment. This build
mirrors 1391's module shape (advisor ⊣ governor ⊣ phase ⊣ store, four
ops, `MemStore`-only backend) closely, substituting textile-finishing-
specific ground truth: `colorfastness-grade` in place of `quality-
grade`, `shrinkage-percent` in place of `defect-rate-percent`, and a
permanent `:direct-operate?` block in place of 1391's own
`:direct-operate?` block -- both are a proposal-level field that, if
set true, attempts to bypass "propose/schedule a DRAFT" and reach
actual equipment operation.

This vertical is distinct from 1391 in one structural way that this
ADR calls out explicitly: **1313 is contract processing** (dyeing/
printing/bleaching/mercerizing customer-SUPPLIED greige fabric, ISIC
Rev.5 wording), not primary fibre/yarn/fabric manufacturing. The
`:coordinate-shipment` op therefore coordinates a shipment BACK TO THE
CUSTOMER (not to a generic buyer-warehouse), and every `batch` record
carries a `:customer-id` -- the finishing plant does not own the
fabric it processes, it processes it under contract and returns it.

This vertical also has a second permanent scope boundary 1391 does not
need: **`:flag-safety-concern` covers effluent-discharge concerns**
(dyeing/printing/bleaching/mercerizing plants generate chemical
process wastewater subject to discharge-permit regimes), and this
actor must NEVER itself decide or issue an effluent-discharge-permit
outcome -- only flag the concern for a human plant supervisor /
regulatory authority to decide. This is modeled as a dedicated,
symmetric HARD-PERMANENT governor check
(`effluent-permit-decision-blocked-violations`, gated on a proposal's
own `:permit-decision? true` field) alongside the existing
`:direct-operate? true` line-operate block -- two independent,
unconditional scope boundaries, neither overridable by phase or human
approval.

This vertical has NO pre-existing `kotoba-lang/textile-finishing`-style
capability library to wrap (verified: no such repo exists). This
build therefore uses self-contained domain logic -- pure functions in
`finishingops.registry` (equipment/batch verification, shipment-
yardage recompute, colorfastness-grade validation, shrinkage-rate
plausibility validation) are re-verified independently by the
governor, the same "ground truth, not self-report" discipline
established across prior actors (most directly
`cloud-itonami-isic-1391`'s `knittingops.registry`).

This blueprint's own `:itonami.blueprint/governor` keyword,
`:textile-finishing-operations-governor`, is grep-verified UNIQUE
fleet-wide (`gh search code "textile-finishing-operations-governor"`,
zero hits before this repo was created).

Regulatory context (informational, not enforced in code): textile
dyeing/printing/finishing wastewater discharge is subject to
environmental regimes in multiple jurisdictions -- e.g. the US Clean
Water Act NPDES effluent-discharge permitting (40 CFR Part 410 textile
mills point source category), the EU Industrial Emissions Directive
2010/75/EU, and Japan's 水質汚濁防止法 (Water Pollution Prevention
Act) covering textile-finishing effluent. `:flag-safety-concern`'s
`:chemical-handling`/`:effluent-discharge` concern-types exist to
surface exactly this class of issue to a human for review -- this
actor does not itself adjudicate compliance or decide a permit
outcome (see the dedicated `effluent-permit-decision-blocked`
governor check above).

## Decision

### Decision 1: Self-contained domain logic (no external textile-finishing capability library to wrap)

Unlike actors that delegate to pre-existing domain libraries, this
textile-finishing vertical has NO pre-existing capability library to
wrap. The equipment/batch-verification / shipment-yardage /
colorfastness-grade / shrinkage-rate validation functions live as pure
functions in `finishingops.registry` and are re-verified independently
by `finishingops.governor` -- the same "ground truth, not self-report"
discipline established across prior actors (most directly
`cloud-itonami-isic-1391`'s `knittingops.registry`).

### Decision 2: Coordination, not control — scope boundary at the back-office

This actor is **strictly back-office coordination** of textile-
finishing-plant operations. It does NOT:
- Control dyeing, printing, bleaching, or mercerizing-line equipment directly
- Make plant-safety, labor-safety, or chemical-handling-safety decisions (exclusive to the human plant supervisor)
- Decide or issue an effluent-discharge-permit outcome (exclusive to the human plant supervisor / regulatory authority)
- Directly operate dyeing/printing-line equipment under any proposal

All proposals are `:effect :propose` only. The advisor proposes; the
governor validates; escalation paths funnel to human plant-supervisor
approval. This is not a replacement for the supervisor's authority —
it is a proposal-screening and documentation layer.

**CRITICAL SAFETY BOUNDARY**: textile-finishing manufacturing carries
real physical-safety, chemical-handling, and environmental-compliance
dimensions (chemical exposure risk from dye baths and bleach/
mercerizing caustic, moving-fabric entanglement risk on finishing
lines, effluent-discharge environmental-permit risk). Safety-concern
flagging NEVER auto-commits, and a proposal may never smuggle an
actual permit DECISION through the flagging path. All safety concerns
escalate immediately to human review.

### Decision 3: Safety-concern escalation — always human sign-off

`:flag-safety-concern` (chemical-handling or effluent-discharge
concern) ALWAYS escalates, never auto-commits. This is not a
"low-stakes proposal" — it is a circuit-breaker that must reach human
authority, deliberately broad enough to cover both a chemical-handling
finding and an effluent-discharge finding simultaneously. Independent
of escalation, the governor's own `effluent-permit-decision-blocked`
check ensures that even an APPROVED safety-concern flag can never
carry an actual permit-decision payload (`:permit-decision? true` is
a permanent, unconditional block — the human approver is signing off
on FLAGGING the concern, never on deciding the permit outcome).

### Decision 4: Two independent verified/registered gates (equipment AND batch), not one

Unlike a single-entity-gated vertical, this vertical has TWO entity
kinds each gating a different op: `:schedule-maintenance`
independently verifies the referenced **equipment** unit's own
`:verified?`/`:registered?` fields; `:coordinate-shipment`
independently verifies the referenced **batch**'s own
`:verified?`/`:registered?` fields. Both are the same "plant/batch
record must be independently verified/registered before any action"
HARD invariant applied to the two distinct record kinds this domain
actually has. `:coordinate-shipment` additionally independently
recomputes whether a batch's own recorded shipped-to-date yardage plus
the proposal's own claimed yardage would exceed the batch's own
recorded finished-fabric yardage -- never taken on the advisor's
self-report.

### Decision 5: HARD invariants (no override)

Four HARD governor invariants (elaborated into eleven concrete checks
in `finishingops.governor`, mirroring `cloud-itonami-isic-1391`'s own
elaboration of its HARD invariants into concrete checks) block
proposals and cannot be overridden by human approval:
1. Plant/batch record (equipment for maintenance, batch for shipment) must be independently verified/registered before any action is taken against it, and a shipment's yardage must independently recompute within the batch's own logged finished-fabric yardage
2. Proposals must be `:effect :propose` only (never direct equipment control)
3. Direct dyeing/printing-line-equipment control (`:direct-operate? true`) OR an effluent-discharge-permit decision (`:permit-decision? true`) is permanently blocked
4. The op allowlist is closed — `:log-production-batch`/`:schedule-maintenance`/`:flag-safety-concern`/`:coordinate-shipment` only

## Consequences

(+) Textile-finishing-plant operations back-office now has a
documented, governed, auditable coordination layer that funnels all
decisions through independent validation before human approval.

(+) The "coordination, not control" boundary is explicit in code: all
`:effect :propose`, all real-world actuation requires human plant-
supervisor sign-off, and a regulatory permit decision can never be
smuggled through a proposal.

(+) Scope is bounded and verifiable: four HARD invariants (elaborated
into eleven concrete governor checks) protect against scope creep into
unauthorized equipment operation or unauthorized regulatory decisions.
Safety concerns are a circuit-breaker, not a threshold.

(+) Safety-critical discipline is explicit: safety-concern flagging
cannot be rate-limited, suppressed, auto-decided by phase gate, or
upgraded into an actual permit decision.

(-) Still a simulation/proposal layer, not a real plant-operations
control system. Equipment actuation and permit decisions remain
human-controlled via external channels.

(-) No integration with real plant-management databases (equipment
telemetry, batch tracking, freight dispatch) — this is a standalone
coordinator blueprint.

## Verification

- `cloud-itonami-isic-1313`: `clojure -M:test` green (all tests pass;
  see the superproject ADR and `kotoba-lang/industry` registry entry
  for the exact `Ran N tests containing M assertions, 0 failures, 0
  errors` output, verified from an independent fresh clone), `clojure
  -M:lint` clean, `clojure -M:dev:run` demo narrative exercises
  proposal submission, escalation, and every HARD-hold scenario
  directly (not-propose-effect, unknown-op, equipment-not-verified,
  batch-not-verified, shipment-volume-exceeded, direct-operate-
  blocked, effluent-permit-decision-blocked, already-scheduled,
  invalid-grade, invalid-shrinkage-rate).
- All source is `.cljc` (portable ClojureScript / JVM / nbb) — no
  JVM-only interop; the actor graph is invoked exclusively via
  `langgraph.graph/run*` (not `.invoke`, which is not cljs-portable).
- Audit ledger is append-only, all decisions are traced; every settled
  request (commit or hold) leaves exactly one ledger fact.
- `deps.edn` pins `io.github.kotoba-lang/langgraph` and
  `io.github.kotoba-lang/langchain` via `:local/root` directly in the
  top-level `:deps` (not only under a `:dev` alias), so a bare
  `clojure -M:test` resolves offline inside the monorepo checkout.
