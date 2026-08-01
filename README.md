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

### ガーメント装飾（Tシャツ等への捺染）の工程

ISIC 1313 の includes に `printing on textiles and clothing` が明記されており、
衣類への捺染はこの vertical の担当。工程は **受注 → 版下 → 校正 → 刷り → 検品**
（`finishingops.decoration/stages`）で、順序は飛ばせない。

- `:register-decoration-order` — 受注（ボディ・枚数・刷り位置・納期）
- `:attach-plate-plan` — 版下。製版エンジン [`cloud-itonami/shirohan`](https://github.com/cloud-itonami/shirohan)
  が出した版一式への**参照**（digest / blocking 所見数 / 版数 / choke）を紐づける
- `:request-proof-approval` — 校正。**常に人**（`:coordination/proof-approval`）
- `:log-print-run` — 刷りの実績記録（起動指示ではない）
- `:log-decoration-inspection` — 検品

守っている不変条件は 3 つで、どれも **戻せないのは生地であって記録ではない** が根拠:

| 不変条件 | rule |
|---|---|
| 工程を飛ばせない（校正していない版で刷れない） | `:decoration-stage-order` / `:print-run-before-proof-approval` |
| エンジンが『刷れない』と判定した版は先へ進めない | `:plate-plan-blocking-findings` |
| **承認した版と刷った版が同じ**（digest 一致） | `:print-run-plate-mismatch` |

3 つ目がこの工程の中心。製版エンジンが決定論であることは『同じ入力から同じ版が
出る』ことしか保証しない —— 『校正で人が承認したのが本当にこの版か』は、承認時と
刷り時の digest を突き合わせて初めて言える。

## What this actor does NOT do

**CRITICAL SCOPE BOUNDARY** (dyeing lines, printing lines, bleaching lines, mercerizing lines; chemical-handling and effluent-discharge hazards):

- Does NOT control dyeing, printing, bleaching, or mercerizing-line equipment directly
- Does NOT make plant-safety, labor-safety, or chemical-handling-safety decisions (that's the plant supervisor's exclusive human authority)
- Does NOT decide or issue an effluent-discharge-permit outcome — that is a regulatory authority's / human plant supervisor's exclusive act; this actor may only FLAG a concern
- Does NOT directly operate dyeing/printing-line equipment under any proposal (permanently blocked, see Architecture)
- ONLY proposes/coordinates operations back-office; all actuation requires explicit human approval
- Safety-concern flagging ALWAYS escalates — never auto-decided, no confidence threshold or phase below escalation
- Does NOT make the plates. 版下は `cloud-itonami/shirohan`（決定論の純関数）が作る。
  この actor が持つのはその結果への参照だけで、幾何には一切触れない
- Does NOT approve a proof. 校正の承認は人の専権で、`:proof/approved-by` を
  自分で埋めた提案は HARD で落ちる（`:proof-approval-self-decided`）。commit も
  承認者を書かない —— 承認は human-in-the-loop の resume でしか入らない
- Does NOT actuate the press. `:log-print-run` は実績の記録であって起動指示ではない
  （`:press-actuation-blocked`）

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
