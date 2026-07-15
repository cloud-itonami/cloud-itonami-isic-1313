# cloud-itonami-isic-1313: Finishing of textiles

Open Business Blueprint for **ISIC Rev.5 1313**: finishing of textiles — an autonomous "actor" (LLM advisor behind an independent Governor, langgraph-clj StateGraph, append-only audit ledger) that coordinates back-office textile-finishing-plant **operations**: production-batch data logging (dyeing/printing/bleaching/mercerizing output, colorfastness and shrinkage test data), dyeing/printing-line-equipment maintenance scheduling, chemical-handling/effluent-discharge concern flagging, and outbound finished-fabric shipment coordination back to the customer.

This repository designs a forkable OSS business for textile-finishing
**contract processing** — a finishing plant that dyes, prints,
bleaches, or mercerizes fabric supplied by a customer (a service/
contract-processing activity distinct from primary fibre/yarn/fabric
manufacturing) — run by a qualified operator so a finishing plant
keeps its own operating records instead of renting a closed SaaS.

## What this actor does

Proposes **plant operations coordination**, not machine operation:
- `:log-production-batch` — dyeing/printing/finishing batch, colorfastness/shrinkage-test data logging (administrative, not an operational decision)
- `:schedule-maintenance` — dyeing/printing-line-equipment maintenance scheduling proposal
- `:flag-safety-concern` — surface a chemical-handling/effluent-discharge concern (always escalates)
- `:coordinate-shipment` — finished-fabric shipment coordination proposal back to the customer

## What this actor does NOT do

**CRITICAL SCOPE BOUNDARY** (dyeing lines, printing lines, bleaching lines, mercerizing lines; chemical-handling and effluent-discharge hazards):

- Does NOT control dyeing, printing, bleaching, or mercerizing-line equipment directly
- Does NOT make plant-safety, labor-safety, or chemical-handling-safety decisions (that's the plant supervisor's exclusive human authority)
- Does NOT decide or issue an effluent-discharge-permit outcome — that is a regulatory authority's / human plant supervisor's exclusive act; this actor may only FLAG a concern
- Does NOT directly operate dyeing/printing-line equipment under any proposal (permanently blocked, see Architecture)
- ONLY proposes/coordinates operations back-office; all actuation requires explicit human approval
- Safety-concern flagging ALWAYS escalates — never auto-decided, no confidence threshold or phase below escalation

## Architecture

Classic governed-actor pattern (`finishingops.operation/build`, a langgraph-clj StateGraph):
1. **`finishingops.advisor`** (sealed intelligence node, `FinishingAdvisor`): proposes decisions only, never commits
2. **`finishingops.governor`** (independent, `Textile Finishing Operations Governor`): validates against domain rules, re-derived from `finishingops.registry`'s pure functions and `finishingops.store`'s SSoT -- never trusts the advisor's own self-report
   - HARD invariants (always `:hold`, no override):
     - Plant/batch record must be independently verified/registered (`:verified?` AND `:registered?`) before any action is taken against it (equipment before maintenance scheduling, batch before shipment coordination)
     - The request's own `:effect` must be `:propose` (never a direct-write bypass)
     - `:op` must be in the closed four-op allowlist
     - The proposal's own `:effect` must be one of the four propose-shaped effects (no direct dyeing/printing-line-equipment control)
     - Directly operating dyeing/printing-line equipment (`:direct-operate? true`) is a PERMANENT, unconditional block
     - Deciding/issuing an effluent-discharge-permit outcome (`:permit-decision? true`) is a PERMANENT, unconditional block — this actor may only flag the concern
     - A shipment may not push a batch's own recorded shipped yardage past its own logged finished-fabric yardage (independently recomputed)
     - No double-scheduling the same maintenance record
     - No fabricated `:colorfastness-grade` value on a production-batch patch
     - No physically implausible `:shrinkage-percent` value on a production-batch patch
   - ESCALATE (always human sign-off, overridable by a human):
     - `:flag-safety-concern` always escalates, regardless of confidence
     - Low-confidence proposals
3. **`finishingops.phase`** (Phase 0->3 rollout): `:schedule-maintenance`/`:flag-safety-concern`/`:coordinate-shipment` are NEVER in any phase's `:auto` set (permanent, matching the governor's own posture); only `:log-production-batch` may auto-commit at phase 3 when clean
4. **`finishingops.store`** (append-only audit ledger + SSoT): a single `MemStore` backend behind a `Store` protocol (see ns docstring for why a second Datomic-backed backend is out of scope for this build)

## Development

```bash
# Run tests (top-level deps.edn already pins langgraph+langchain local/root)
clojure -M:test

# Run tests via the workspace :dev override alias (equivalent, kept for sibling-repo parity)
clojure -M:dev:test

# Run the demo
clojure -M:dev:run

# Lint
clojure -M:lint
```

## Status

`:implemented` — `governor.cljc`/`store.cljc`/`advisor.cljc`/`registry.cljc` + `deps.edn` complete the module set; tests green, demo runnable, langgraph-clj integration verified.

## License

AGPL-3.0-or-later
