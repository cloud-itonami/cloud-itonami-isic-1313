(ns finishingops.phase
  "Phase 0->3 staged rollout for the textile-finishing plant-operations
  coordination actor.

    Phase 0  read-only          -- no writes, still governor-gated.
    Phase 1  assisted-intake    -- production-batch logging allowed,
                                    every write needs human approval.
    Phase 2  assisted-coordinate -- adds safety-concern flags and
                                    shipment-coordination proposals,
                                    still approval.
    Phase 3  supervised-auto    -- adds maintenance scheduling (still
                                    always approval -- see below);
                                    governor-clean, high-confidence
                                    `:log-production-batch` (no
                                    physical/financial risk) may auto-
                                    commit.

  `:schedule-maintenance` is deliberately ABSENT from every phase's
  `:auto` set, including phase 3 -- a permanent structural fact, not a
  rollout milestone still to come. Scheduling real maintenance against
  a piece of equipment is the one act in this domain with physical
  consequence (a dyeing/printing/bleaching/mercerizing-line maintenance
  window means production downtime, and the equipment ends up actually
  touched); it is always a human plant supervisor's call.
  `finishingops.governor`'s `line-operate-blocked-violations` HARD-
  blocks direct-operate attempts unconditionally, and the confidence/
  high-stakes gate independently never lets `:flag-safety-concern`
  auto-commit either -- multiple independent layers agree on where
  this actor's authority ends. Like every prior sibling's phase-3
  `:auto` set, this domain has only ONE member (`:log-production-
  batch`) -- no separate no-risk lifecycle distinct from ordinary
  record logging.")

(def write-ops
  #{:log-production-batch :schedule-maintenance
    :flag-safety-concern :coordinate-shipment
    ;; ガーメント装飾（捺染）の工程
    :register-decoration-order :attach-plate-plan :request-proof-approval
    :log-print-run :log-decoration-inspection
    ;; Prepress craft (shirohan)
    :prepress/plan :prepress/approve-plates})

(def decoration-auto-ops
  "装飾・prepress 工程のうち **phase 3 で自動確定してよい** op。

  この repo の既存の線引きは『物理的・金銭的リスクを負わない記録だけが
  auto-eligible』で、装飾工程にもそのまま当てる:

  | op | auto | 理由 |
  |---|---|---|
  | `:attach-plate-plan` | ○ | 決定論エンジンの出力への**参照を貼るだけ**。governor が既に『刷れる版か』を HARD で検査済みで、これ単体からは物理も金銭も動かない |
  | `:prepress/plan` | ○ | shirohan を一度呼んで **input hash + summary だけ**を記録。geometry は持たない。blocking は governor HARD。物理も金銭も動かない |
  | `:register-decoration-order` | ✕ | 受注は**金銭的な約束**。枚数・納期を actor が独断で確定しない |
  | `:log-print-run` | ✕ | 『N 枚刷った』は材料消費と請求の主張。物理的実体についての claim |
  | `:log-decoration-inspection` | ✕ | 合否が出荷を決める |
  | `:request-proof-approval` | ✕ | 記録ではなく**未来の確定**。生地は刷り直しでは戻らない |
  | `:prepress/approve-plates` | ✕ | 版の人的承認。actor は自承認できない（high-stakes + phase） |

  `:request-proof-approval` / `:prepress/approve-plates` は governor の
  high-stakes 側でも常に人に回るが、**層を 2 つにしておく**。"
  #{:attach-plate-plan :prepress/plan})

;; NOTE the invariant: `:schedule-maintenance` is a member of
;; `write-ops` (governor-gated like any write) but is NEVER a member of
;; any phase's `:auto` set below. Do not add it there.
(def phases
  "phase -> {:label .. :writes <ops allowed to write> :auto <ops allowed
  to auto-commit when governor-clean>}."
  {0 {:label "read-only"           :writes #{}                                            :auto #{}}
   1 {:label "assisted-intake"     :writes #{:log-production-batch}                        :auto #{}}
   2 {:label "assisted-coordinate" :writes #{:log-production-batch :flag-safety-concern
                                             :coordinate-shipment
                                             :register-decoration-order
                                             :attach-plate-plan
                                             :request-proof-approval
                                             :prepress/plan
                                             :prepress/approve-plates}                       :auto #{}}
   3 {:label "supervised-auto"     :writes write-ops
      :auto (conj decoration-auto-ops :log-production-batch)}})

(def default-phase 3)

(defn gate
  "Adjust a governor disposition for the rollout phase. Returns
  {:disposition kw :reason kw|nil}.

  - a governor HOLD always stays HOLD (compliance wins).
  - a write op not yet enabled in this phase -> HOLD (:phase-disabled).
  - a write op enabled but not auto-eligible -> ESCALATE (:phase-approval),
    even if the governor was clean.
  - `:schedule-maintenance` is never auto-eligible at any phase, so it
    always escalates once the governor clears it (or holds if the
    governor doesn't)."
  [phase {:keys [op]} governor-disposition]
  (let [{:keys [writes auto]} (get phases phase (get phases default-phase))]
    (cond
      (= :hold governor-disposition)       {:disposition :hold :reason nil}
      (not (contains? writes op))          {:disposition :hold :reason :phase-disabled}
      (and (= :commit governor-disposition)
           (not (contains? auto op)))      {:disposition :escalate :reason :phase-approval}
      :else                                {:disposition governor-disposition :reason nil})))

(defn verdict->disposition
  "Map a Textile Finishing Operations Governor verdict to a base
  disposition before the phase gate."
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))
