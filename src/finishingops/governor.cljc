(ns finishingops.governor
  "Textile Finishing Operations Governor -- the independent compliance
  layer that earns the FinishingAdvisor the right to commit. The
  advisor has no notion of whether a piece of equipment it wants to
  schedule maintenance against has actually been inspected/registered,
  whether a batch it wants to coordinate a shipment against has
  actually been QC-verified/registered, whether a maintenance proposal
  secretly tries to DIRECTLY OPERATE (rather than merely draft-
  schedule) a dyeing/printing/bleaching/mercerizing line, whether a
  safety-concern proposal secretly tries to DECIDE an effluent-
  discharge-permit outcome (rather than merely flag the concern for a
  human), whether a shipment proposal's own claimed yardage would blow
  through the batch's own logged finished-fabric yardage, or when an
  act stops being a coordination proposal and becomes direct dyeing/
  printing-line-equipment control or a regulatory permit decision, so
  this MUST be a separate system able to *reject* a proposal and fall
  back to HOLD.

  `:itonami.blueprint/governor` is
  `:textile-finishing-operations-governor` (see docs/adr/0001-
  architecture.md).

  Checks below, ALL HARD violations except the confidence/high-stakes
  gate (SOFT -- asks a human to look, and the human may approve):

    1. Request-level propose-only  -- did the CALLER's own request
                                       actually declare `:effect
                                       :propose`? Any other value is a
                                       mis-wired/compromised caller
                                       trying to bypass proposal-only
                                       mode -- HARD, unconditional,
                                       evaluated BEFORE anything else.
    2. Closed op allowlist         -- is `:op` one of the four ops this
                                       actor is authorized to coordinate?
                                       Anything else -- HARD hold.
    3. Closed effect allowlist     -- is the PROPOSAL's own `:effect`
                                       (what would actually commit) one
                                       of the four propose-shaped
                                       effects? A proposal effect
                                       outside this set (e.g. a
                                       hallucinated `:dyeing-line/
                                       actuate` or `:effluent-permit/
                                       decide`) is the 'direct dyeing/
                                       printing-line-equipment control
                                       or effluent-discharge-permit
                                       decision' scope violation this
                                       actor must NEVER perform --
                                       HARD, PERMANENT, unconditional.
    4. Line-operate blocked        -- for `:schedule-maintenance`, does
                                       the proposal's own `:value`
                                       declare `:direct-operate? true`?
                                       Directly operating a dyeing/
                                       printing/bleaching/mercerizing
                                       line (rather than merely
                                       scheduling a DRAFT maintenance
                                       window) is this actor's other
                                       permanent scope boundary (see
                                       README `What this actor does
                                       NOT do`) -- HARD, PERMANENT,
                                       unconditional. NO phase and NO
                                       human approval can ever override
                                       this (see `finishingops.phase`:
                                       this op is never a member of any
                                       phase's `:auto` set either -- two
                                       independent layers agree).
    5. Effluent-permit-decision
       blocked                     -- for ANY op, does the proposal's
                                       own `:value` declare
                                       `:permit-decision? true`? This
                                       actor may FLAG an effluent-
                                       discharge concern for a human
                                       plant supervisor/regulator to
                                       review, but it may NEVER itself
                                       decide or issue an effluent-
                                       discharge-permit outcome -- a
                                       regulatory authority act, not a
                                       plant-operations coordination
                                       proposal (see README `What this
                                       actor does NOT do`) -- HARD,
                                       PERMANENT, unconditional. NO
                                       phase and NO human approval can
                                       ever override this.
    6. Equipment not verified/
       registered                  -- for `:schedule-maintenance`,
                                       INDEPENDENTLY verify the
                                       referenced equipment's own
                                       `:verified?` AND `:registered?`
                                       are both true
                                       (`finishingops.registry/
                                       equipment-ready?`) -- never trust
                                       the advisor's own rationale about
                                       verification/registration
                                       status. Grounded in this
                                       blueprint's own HARD invariant
                                       ('plant/batch record must be
                                       independently verified/
                                       registered before any action'):
                                       maintenance must never be
                                       scheduled against equipment
                                       whose own conditions have not
                                       actually been inspected or
                                       whose registration is not
                                       actually on file.
    7. Already scheduled           -- for `:schedule-maintenance`,
                                       refuses to schedule the SAME
                                       maintenance record twice, off a
                                       dedicated `:scheduled?` fact
                                       (never a `:status` value).
    8. Batch not verified/
       registered                  -- for `:coordinate-shipment`,
                                       INDEPENDENTLY verify the
                                       referenced batch's own
                                       `:verified?` AND `:registered?`
                                       are both true
                                       (`finishingops.registry/batch-
                                       ready?`) -- never trust the
                                       advisor's own rationale. Also
                                       part of the 'plant/batch record'
                                       HARD invariant: a batch's own
                                       verified/registered status is
                                       as much a ground-truth fact as
                                       an equipment unit's own.
    9. Shipment volume exceeded    -- for `:coordinate-shipment`,
                                       INDEPENDENTLY recompute whether
                                       the batch's own recorded
                                       `:shipped-volume-yards` plus the
                                       proposal's own claimed
                                       `:volume-yards` would exceed the
                                       batch's own recorded
                                       `:volume-yards`
                                       (`finishingops.registry/
                                       shipment-volume-exceeded?`) --
                                       ground truth from the batch's
                                       own permanent fields, never a
                                       self-reported yardage claim.
   10. Invalid grade               -- for `:log-production-batch`, if
                                       the patch declares a
                                       `:colorfastness-grade` outside
                                       the closed known set
                                       (`finishingops.registry/grade-
                                       valid?`), the batch record is
                                       rejected rather than let a
                                       fabricated grade through.
   11. Invalid shrinkage rate      -- for `:log-production-batch`, if
                                       the patch declares a
                                       `:shrinkage-percent` that is
                                       not a physically plausible
                                       reading
                                       (`finishingops.registry/
                                       shrinkage-rate-valid?`), the
                                       batch record is rejected rather
                                       than let fabricated/inspection-
                                       error data through.
   12. Confidence floor / high-
       stakes gate                  -- LLM confidence below threshold,
                                       OR the proposal's own `:stake` is
                                       in `high-stakes`
                                       (`:coordination/safety-concern`,
                                       ALWAYS set for `:flag-safety-
                                       concern`) -- escalate to a human
                                       plant supervisor. SOFT: the
                                       human may approve."
  (:require [finishingops.registry :as registry]
            [finishingops.store :as store]))

(def confidence-floor 0.6)

(def allowed-ops
  "The closed allowlist of coordination proposals this actor may ever
  route -- see README `What this actor does`."
  #{:log-production-batch :schedule-maintenance
    :flag-safety-concern :coordinate-shipment})

(def allowed-proposal-effects
  "The closed allowlist of SSoT-mutation effects a proposal may declare
  -- all four are propose-shaped drafts, NEVER a direct dyeing/
  printing-line-equipment-control effect and NEVER an effluent-
  discharge-permit-decision effect."
  #{:batch/upsert :maintenance/schedule
    :safety-concern/flag :shipment/propose})

(def high-stakes
  "Stakes grave enough to always require a human, even when clean.
  Safety concerns (chemical-handling/effluent-discharge) are the one
  op in this domain that always demands human eyes regardless of
  confidence."
  #{:coordination/safety-concern})

;; ----------------------------- checks -----------------------------

(defn- no-propose-effect-violations
  "HARD, unconditional, evaluated first: the caller's own request MUST
  declare `:effect :propose` -- any other value is a mis-wired or
  compromised caller trying to bypass proposal-only mode."
  [{:keys [effect]}]
  (when (not= effect :propose)
    [{:rule :not-propose-effect
      :detail (str "request :effect は :propose のみ許可 (受信値: " (pr-str effect) ")")}]))

(defn- unknown-op-violations
  "HARD: `:op` must be one of the closed allowlist this actor
  coordinates -- never route an unrecognized operation."
  [{:keys [op]}]
  (when-not (contains? allowed-ops op)
    [{:rule :unknown-op
      :detail (str op " はこの actor が扱う操作の許可リストに無い")}]))

(defn- equipment-control-blocked-violations
  "HARD, PERMANENT: the proposal's own `:effect` -- what would actually
  commit -- must be within the closed propose-shaped effect allowlist.
  Anything else (direct dyeing/printing/bleaching/mercerizing-line-
  equipment control, an effluent-discharge-permit-decision effect, a
  hallucinated actuation effect) is this actor's central scope
  boundary."
  [proposal]
  (when-not (contains? allowed-proposal-effects (:effect proposal))
    [{:rule :equipment-control-blocked
      :detail (str "proposal :effect (" (pr-str (:effect proposal))
                   ") は染色・捺染・漂白・シルケット加工ライン等の設備の直接操作、"
                   "または排水放流許可の決定に該当する可能性があり、恒久的に禁止")}]))

(defn- line-operate-blocked-violations
  "HARD, PERMANENT, unconditional: a `:schedule-maintenance` proposal
  whose own `:value` declares `:direct-operate? true` is attempting to
  directly operate a dyeing/printing/bleaching/mercerizing line --
  this actor may only ever propose/schedule a DRAFT maintenance
  window, never operate the line directly. No override, ever."
  [{:keys [op]} proposal]
  (when (and (= op :schedule-maintenance)
             (true? (:direct-operate? (:value proposal))))
    [{:rule :line-operate-blocked
      :detail "染色・捺染・漂白・シルケット加工ライン設備の直接操作提案は恒久的に禁止 -- 保守作業予定(draft)のみ許可"}]))

(defn- effluent-permit-decision-blocked-violations
  "HARD, PERMANENT, unconditional: ANY proposal whose own `:value`
  declares `:permit-decision? true` is attempting to decide/issue an
  effluent-discharge-permit outcome -- a regulatory authority act this
  actor may never perform. This actor may only ever FLAG an effluent-
  discharge concern for a human plant supervisor/regulator to decide
  (see README `What this actor does NOT do`). No override, ever."
  [proposal]
  (when (true? (:permit-decision? (:value proposal)))
    [{:rule :effluent-permit-decision-blocked
      :detail "排水放流許可(effluent-discharge-permit)の決定提案は恒久的に禁止 -- 懸念の報告(flag)のみ許可、決定は人間の規制当局/工場責任者の専権"}]))

(defn- equipment-not-verified-violations
  "For `:schedule-maintenance`, INDEPENDENTLY verify the referenced
  equipment exists and is both `:verified?` AND `:registered?` --
  never trust the advisor's own report. This is the HARD invariant
  ('plant/batch record must be independently verified/registered
  before any action')."
  [{:keys [op]} proposal st]
  (when (= op :schedule-maintenance)
    (let [equipment-id (:equipment-id (:value proposal))
          eq (and equipment-id (store/equipment-unit st equipment-id))]
      (when-not (and eq (registry/equipment-ready? eq))
        [{:rule :equipment-not-verified
          :detail (str equipment-id " は未検証または未登録、もしくは存在しない -- 検証済み・登録済み設備記録が無い状態での保守作業予定提案")}]))))

(defn- already-scheduled-violations
  "For `:schedule-maintenance`, refuses to schedule the SAME
  maintenance record twice, off a dedicated `:scheduled?` fact (never
  a `:status` value)."
  [{:keys [op subject]} st]
  (when (= op :schedule-maintenance)
    (when (store/maintenance-already-scheduled? st subject)
      [{:rule :already-scheduled
        :detail (str subject " は既にスケジュール済み")}])))

(defn- batch-not-verified-violations
  "For `:coordinate-shipment`, INDEPENDENTLY verify the referenced
  batch's own `:verified?` AND `:registered?` are both true -- never
  trust the advisor's own report. Also part of the 'plant/batch record
  must be independently verified/registered before any action' HARD
  invariant."
  [{:keys [op]} proposal st]
  (when (= op :coordinate-shipment)
    (let [batch-id (:batch-id (:value proposal))
          b (and batch-id (store/batch st batch-id))]
      (when-not (and b (registry/batch-ready? b))
        [{:rule :batch-not-verified
          :detail (str batch-id " は未検証または未登録、もしくは存在しない -- 検証済み・登録済みバッチ記録が無い状態での出荷調整提案")}]))))

(defn- shipment-volume-exceeded-violations
  "For `:coordinate-shipment`, INDEPENDENTLY recompute whether the
  batch's own recorded shipped-to-date yardage plus the proposal's own
  claimed yardage would exceed the batch's own recorded
  `:volume-yards` -- ground truth from the batch's own permanent
  fields, never a self-reported yardage claim."
  [{:keys [op]} proposal st]
  (when (= op :coordinate-shipment)
    (let [{:keys [batch-id volume-yards]} (:value proposal)
          b (and batch-id (store/batch st batch-id))]
      (when (and b (registry/shipment-volume-exceeded? b volume-yards))
        [{:rule :shipment-volume-exceeded
          :detail (str batch-id " の記録済み仕上がり数量(" (:volume-yards b)
                       "ヤード)を、既存出荷実績(" (:shipped-volume-yards b 0.0)
                       "ヤード)+今回申請(" volume-yards "ヤード)が超過")}]))))

(defn- invalid-grade-violations
  "For `:log-production-batch`, if the patch declares a
  `:colorfastness-grade` outside the closed known set, reject rather
  than let a fabricated grade through."
  [{:keys [op]} proposal]
  (when (= op :log-production-batch)
    (let [grade (:colorfastness-grade (:value proposal))]
      (when (and (some? grade) (not (registry/grade-valid? grade)))
        [{:rule :invalid-grade
          :detail (str grade " は既知の colorfastness-grade 値ではない")}]))))

(defn- invalid-shrinkage-rate-violations
  "For `:log-production-batch`, if the patch declares a
  `:shrinkage-percent` that is not a physically plausible reading,
  reject rather than let fabricated/inspection-error data through."
  [{:keys [op]} proposal]
  (when (= op :log-production-batch)
    (let [sr (:shrinkage-percent (:value proposal))]
      (when (and (some? sr) (not (registry/shrinkage-rate-valid? sr)))
        [{:rule :invalid-shrinkage-rate
          :detail (str sr "% は物理的に妥当な収縮率の範囲外")}]))))

(defn check
  "Censors a FinishingAdvisor proposal against the governor rules.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :high-stakes? bool :hard? bool}."
  [request _context proposal st]
  (let [hard (into []
                   (concat (no-propose-effect-violations request)
                           (unknown-op-violations request)
                           (equipment-control-blocked-violations proposal)
                           (line-operate-blocked-violations request proposal)
                           (effluent-permit-decision-blocked-violations proposal)
                           (equipment-not-verified-violations request proposal st)
                           (already-scheduled-violations request st)
                           (batch-not-verified-violations request proposal st)
                           (shipment-volume-exceeded-violations request proposal st)
                           (invalid-grade-violations request proposal)
                           (invalid-shrinkage-rate-violations request proposal)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (high-stakes (:stake proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
