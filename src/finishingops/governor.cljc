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
  (:require [finishingops.decoration :as deco]
            [finishingops.registry :as registry]
            [finishingops.store :as store]))

(def confidence-floor 0.6)

(def allowed-ops
  "The closed allowlist of coordination proposals this actor may ever
  route -- see README `What this actor does`."
  #{:log-production-batch :schedule-maintenance
    :flag-safety-concern :coordinate-shipment
    ;; ガーメント装飾（Tシャツ等への捺染）の工程。ISIC 1313 の includes に
    ;; `printing on textiles and clothing` が明記されており、ここがその置き場所。
    ;; 受注 → 版下 → 校正 → 刷り → 検品（`finishingops.decoration/stages`）。
    :register-decoration-order :attach-plate-plan :request-proof-approval
    :log-print-run :log-decoration-inspection})

(def allowed-proposal-effects
  "The closed allowlist of SSoT-mutation effects a proposal may declare
  -- all four are propose-shaped drafts, NEVER a direct dyeing/
  printing-line-equipment-control effect and NEVER an effluent-
  discharge-permit-decision effect."
  #{:batch/upsert :maintenance/schedule
    :safety-concern/flag :shipment/propose
    :decoration-order/upsert :plate-plan/attach :proof/request-approval
    :print-run/log :decoration-inspection/log})

(def high-stakes
  "Stakes grave enough to always require a human, even when clean.
  Safety concerns (chemical-handling/effluent-discharge) are the one
  op in this domain that always demands human eyes regardless of
  confidence."
  #{:coordination/safety-concern
    ;; 校正の承認は『この版で N 枚のボディに刷る』という**戻せない**確定。
    ;; 生地は刷り直しでは戻らないので、confidence がいくら高くても人が見る。
    :coordination/proof-approval})

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
      (cond
        ;; No batch, no recorded capacity, or no stated amount: the headroom
        ;; cannot be computed, so it is not headroom. This used to fall
        ;; through as "not over capacity" and ship.
        (not (registry/shipment-volume-exceeded-checkable? b volume-yards))
        [{:rule :shipment-volume-exceeded
          :detail "生産量/既存出荷実績/申請量のいずれかが数値として確定できない -- 空き容量を検算できないため出荷しない"}]

        (registry/shipment-volume-exceeded? b volume-yards)
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


;; ------------------- ガーメント装飾（捺染）の工程 -------------------
;;
;; 受注 → 版下 → 校正 → 刷り → 検品。順序・版の同一性・人の承認の 3 点を
;; HARD で守る。**戻せないのは生地**であって記録ではない、というのがこれらの
;; 不変条件の根拠。

(defn- decoration-op? [op]
  (some? (deco/next-stage op)))

(defn- job-of [request st]
  (when (decoration-op? (:op request))
    (store/decoration-job st (:subject request))))

(defn- stage-order-violations
  "HARD: 工程を飛ばせない。

  飛ばせるようにすると『校正していない版で刷る』が起こりうる。刷り直しは
  記録を直すことであって、潰れたボディは戻らない。"
  [{:keys [op subject] :as request} st]
  (when (decoration-op? op)
    (let [want (deco/next-stage op)
          need (deco/required-predecessor want)
          job (job-of request st)]
      (cond
        (nil? need) nil                       ; 受注は前提を持たない
        (nil? job) [{:rule :decoration-job-unknown
                     :detail (str subject " という装飾ジョブがまだ無い（受注が先）")}]
        (not (deco/at-least? job need))
        [{:rule :decoration-stage-order
          :detail (str (name want) " に進むには " (name need)
                       " が済んでいる必要がある（現在: "
                       (name (or (deco/stage-of job) :unknown)) "）")}]))))

(defn- plate-plan-violations
  "HARD: 版下は engine（cloud-itonami/shirohan）が出したものだけを受ける。

  - digest が無い／形が違う → 同一性を後から示せない
  - engine が blocking 所見を出した版 → **刷れないと判定されたもの**を
    『とりあえず校正へ』と通さない。所見は刷る前に潰すためにあり、
    素通しできるなら出す意味が無い。"
  [{:keys [op]} proposal]
  (when (= op :attach-plate-plan)
    (let [pp (get-in proposal [:value :plate-plan])]
      (cond
        (nil? pp)
        [{:rule :plate-plan-missing
          :detail "版下の参照（:plate-plan）が提案に無い"}]

        (not (deco/digest-well-formed? (:digest pp)))
        [{:rule :plate-plan-digest-malformed
          :detail (str "版一式の digest が hex 16〜128 桁でない: " (pr-str (:digest pp)))}]

        (not (deco/plate-plan-printable? pp))
        [{:rule :plate-plan-blocking-findings
          :detail (str "製版エンジンが『刷れない』と判定した所見が "
                       (:blocking pp) " 件ある。図案を直して版を作り直す")}]))))

(defn- proof-decision-violations
  "HARD, PERMANENT: この actor は校正の承認を**自分で確定できない**。

  提案できるのは『人に承認を求める』ところまで。`:proof/approved-by` を
  自分で埋めた提案は、人が見たことにして先へ進めようとしているのと同じ。"
  [{:keys [op]} proposal]
  (when (= op :request-proof-approval)
    (when (seq (str (get-in proposal [:value :proof :approved-by] "")))
      [{:rule :proof-approval-self-decided
        :detail "校正の承認者をこの actor が埋めることはできない（人の専権）"}])))

(defn- print-run-violations
  "HARD: 刷る前に、(1) 校正が**人によって**承認済みで、(2) 刷る版が
  **承認された版と同一**であること。

  (2) がこの vertical の中心。engine が決定論であることは『同じ入力から同じ版が
  出る』ことしか保証しない —— 『校正で承認したのが本当にこの版か』は、承認時と
  刷り時の digest を突き合わせて初めて言える。"
  [{:keys [op] :as request} proposal st]
  (when (= op :log-print-run)
    (let [job (job-of request st)
          presented (get-in proposal [:value :plate-digest])]
      (cond
        (nil? job) nil                        ; stage-order 側が既に止めている
        (not (deco/proof-approved? job))
        [{:rule :print-run-before-proof-approval
          :detail "校正が人によって承認されていない版では刷れない"}]

        (not (deco/digest-matches? job presented))
        [{:rule :print-run-plate-mismatch
          :detail (str "刷ろうとしている版の digest が、承認された版と一致しない"
                       "（申告: " (pr-str presented) "）")}]

        (deco/run-size-exceeded? (get proposal :value))
        [{:rule :print-run-size-exceeded
          :detail (str "1 run " (get-in proposal [:value :garments])
                       " 枚は上限 " deco/max-run-garments " 枚を超える")}]))))

(defn- press-actuation-violations
  "HARD, PERMANENT: 刷り機を動かすのはこの actor ではない。

  `:log-print-run` は**実績の記録**であって起動指示ではない。`:actuate?` の
  ような起動を含む提案は、記録の顔をした作動。"
  [{:keys [op]} proposal]
  (when (= op :log-print-run)
    (when (or (true? (get-in proposal [:value :actuate?]))
              (some? (get-in proposal [:value :press-command])))
      [{:rule :press-actuation-blocked
        :detail "刷り機の起動・制御はこの actor の範囲外（記録のみ）"}])))

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
                           (invalid-shrinkage-rate-violations request proposal)
                           (stage-order-violations request st)
                           (plate-plan-violations request proposal)
                           (proof-decision-violations request proposal)
                           (print-run-violations request proposal st)
                           (press-actuation-violations request proposal)))
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
