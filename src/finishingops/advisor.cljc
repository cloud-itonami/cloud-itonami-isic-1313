(ns finishingops.advisor
  "FinishingAdvisor -- the *contained intelligence node* for the
  textile-finishing plant-operations coordination actor (dyeing,
  printing, bleaching, mercerizing lines operating on customer-
  supplied greige fabric -- contract processing).

  It normalizes production-batch patches (colorfastness-grade/finished-
  volume/shrinkage-rate), drafts a dyeing/printing/bleaching/
  mercerizing-line maintenance scheduling proposal against a piece of
  equipment, drafts a chemical-handling/effluent-discharge concern
  flag, and drafts an outbound finished-fabric shipment coordination
  proposal back to the customer against a production batch. CRITICAL:
  it is a smart-but-untrusted advisor. It returns a *proposal* (with a
  rationale + the fields it cited), never a committed record and NEVER
  a real dyeing/printing-line-equipment actuation, an effluent-
  discharge-permit decision, or a freight dispatch. Every output is
  censored downstream by `finishingops.governor` before anything
  touches the SSoT -- see README `What this actor does NOT do`.

  Like every sibling actor's advisor, this is a deterministic mock so
  the actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- informational only, NOT trusted
                                 ; by the governor for any ground-truth
                                 ; check (see `finishingops.governor`)
     :cites      [kw|str ..]    ; fields the advisor used
     :effect     kw             ; how a commit would mutate the SSoT --
                                 ; ALWAYS one of the closed
                                 ; #{:batch/upsert :maintenance/schedule
                                 ; :safety-concern/flag
                                 ; :shipment/propose} propose-shaped
                                 ; effects, NEVER a direct dyeing/
                                 ; printing-line-equipment-control or
                                 ; effluent-discharge-permit-decision
                                 ; effect
     :stake      kw|nil         ; :coordination/safety-concern | nil
     :confidence 0..1}

  CRITICAL invariant this advisor upholds: every request it is asked to
  route MUST itself carry `:effect :propose` (the request-level
  contract every caller of this actor agrees to) --
  `finishingops.governor` HARD-holds any request that doesn't, so a
  mis-wired caller can never reach a commit path even if this advisor
  were compromised."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [finishingops.decoration :as deco]
            [finishingops.registry :as registry]
            [finishingops.store :as store]
            [langchain.model :as model]))

(defn- log-production-batch
  "Production-batch intake upsert -- the advisor only normalizes/
  validates the patch; it does not invent the batch's colorfastness-
  grade, finished-volume or shrinkage-rate. High confidence, low
  stakes -- administrative logging, not an operational decision."
  [_db {:keys [patch]}]
  {:summary    (str "生産バッチ記録更新: " (pr-str (keys patch)))
   :rationale  "入力patchの正規化のみ。新規事実の生成なし。"
   :cites      (vec (keys patch))
   :effect     :batch/upsert
   :value      patch
   :stake      nil
   :confidence 0.95})

(defn- schedule-maintenance
  "Draft a dyeing/printing/bleaching/mercerizing-line maintenance-
  window scheduling proposal against a piece of equipment. The advisor
  reports what it can see (equipment verified?/registered?) in its
  rationale, but `finishingops.governor` NEVER trusts this report -- it
  independently re-derives verified?/registered? from the equipment's
  own stored fields before any commit is possible."
  [db {:keys [subject value]}]
  (let [equipment-id (:equipment-id value)
        eq (store/equipment-unit db equipment-id)
        ready? (and eq (registry/equipment-ready? eq))]
    {:summary    (str subject " 向け保守作業予定提案 (" (:maintenance-type value) ")"
                      (when eq (str " equipment=" equipment-id)))
     :rationale  (if eq
                   (str "equipment-verified?=" (registry/equipment-verified? eq)
                        " equipment-registered?=" (registry/equipment-registered? eq)
                        " direct-operate?=" (boolean (:direct-operate? value)))
                   (str equipment-id " が見つかりません"))
     :cites      (if eq [equipment-id] [])
     :effect     :maintenance/schedule
     :value      value
     :stake      nil
     :confidence (if (and ready? (not (:direct-operate? value))) 0.9 0.3)}))

(defn- flag-safety-concern
  "Draft a chemical-handling/effluent-discharge concern flag. ALWAYS
  `:stake :coordination/safety-concern` -- a safety concern is NEVER a
  proposal the advisor may quietly downgrade to low-stakes, and it is
  never gated on the referenced equipment/batch being verified (a
  concern can be raised about ANY equipment or batch, verified or not
  -- see README `What this actor does NOT do` re: never blocking
  safety-relevant reporting on an administrative technicality). This
  drafts a FLAG only -- it never sets `:permit-decision? true`
  (`finishingops.governor` permanently blocks any proposal that does).
  See `finishingops.phase`: no phase ever adds this op to a phase's
  `:auto` set; `finishingops.governor` also always escalates on
  `:coordination/safety-concern`. Two independent layers agree,
  deliberately."
  [db {:keys [subject value]}]
  (let [equipment-id (:equipment-id value)
        eq (and equipment-id (store/equipment-unit db equipment-id))]
    {:summary    (str subject " 向け安全懸念報告 (" (:concern-type value) "/" (:severity value) ")"
                      (when eq (str " equipment=" equipment-id)))
     :rationale  (str "concern-type=" (:concern-type value)
                      " severity=" (:severity value)
                      " description=" (:description value))
     :cites      (if eq [equipment-id] [])
     :effect     :safety-concern/flag
     :value      value
     :stake      :coordination/safety-concern
     :confidence 0.9}))

(defn- coordinate-shipment
  "Draft an outbound finished-fabric shipment coordination proposal
  back to the customer against a production batch. The advisor passes
  through the caller's own claimed yardage -- it does NOT invent one,
  and `finishingops.governor` NEVER trusts it: it independently
  recomputes whether the batch's own cumulative-shipped yardage plus
  this claim would exceed the batch's own recorded finished-fabric
  yardage before any commit is possible."
  [db {:keys [subject value]}]
  (let [batch-id (:batch-id value)
        b (store/batch db batch-id)
        ready? (and b (registry/batch-ready? b))
        over-volume? (and b (registry/shipment-volume-exceeded?
                             b (:volume-yards value)))]
    {:summary    (str subject " 向け出荷調整提案 ("
                      (:volume-yards value) " ヤード)"
                      (when b (str " batch=" batch-id)))
     :rationale  (if b
                   (str "batch-verified?=" (registry/batch-verified? b)
                        " batch-registered?=" (registry/batch-registered? b)
                        " over-volume?=" over-volume?)
                   (str batch-id " が見つかりません"))
     :cites      (if b [batch-id] [])
     :effect     :shipment/propose
     :value      value
     :stake      nil
     :confidence (if (and ready? (not over-volume?)) 0.9 0.3)}))


;; ----------------------------- ガーメント装飾（捺染）の工程 -----------------------------
;;
;; どの提案も **draft しか作らない**。版を作るのは `cloud-itonami/shirohan`
;; （決定論の純関数）、校正を承認するのは人、刷り機を動かすのは現場。
;; ここが出すのは『次の 1 stage へ進める提案』とその根拠だけ。

(defn- register-decoration-order
  [_db {:keys [subject value]}]
  {:summary    (str "装飾ジョブ " subject " の受注を登録する draft")
   :rationale  (str "ボディ " (pr-str (:garment value))
                    " × " (:garments value) " 枚、刷り位置 " (pr-str (:placement value)))
   :cites      [:request/value]
   :effect     :decoration-order/upsert
   :value      value
   :stake      nil
   :confidence 0.9})

(defn- attach-plate-plan
  [db {:keys [subject value]}]
  (let [job (store/decoration-job db subject)
        pp (:plate-plan value)
        printable? (deco/plate-plan-printable? pp)]
    {:summary   (str "装飾ジョブ " subject " に版一式を紐づける draft")
     :rationale (if printable?
                  (str "製版エンジンの所見 0 件、版 " (:plate-count pp) " 枚、"
                       "choke " (:choke-mm pp) "mm")
                  (str "製版エンジンが刷れないと判定した所見が " (:blocking pp) " 件ある"))
     :cites     [:job/stage :plate-plan/digest :plate-plan/blocking]
     :effect    :plate-plan/attach
     :value     value
     :stake     nil
     ;; 刷れない版に高い確信を付けない。governor が HARD で止めるが、
     ;; **確信度の側でも嘘をつかない**（人が見たときに読み取れる）。
     :confidence (if (and job printable?) 0.9 0.2)}))

(defn- request-proof-approval
  [db {:keys [subject value]}]
  (let [job (store/decoration-job db subject)]
    {:summary   (str "装飾ジョブ " subject " の校正承認を人に求める draft")
     :rationale (str "承認されると『この版で刷る』が確定する。"
                     (deco/summarize job))
     :cites     [:job/stage :plate-plan/digest]
     :effect    :proof/request-approval
     ;; **承認者は決して埋めない。** 埋めた提案は governor が HARD で落とす。
     :value     (update value :proof dissoc :approved-by)
     :stake     :coordination/proof-approval
     :confidence 0.9}))

(defn- log-print-run
  [db {:keys [subject value]}]
  (let [job (store/decoration-job db subject)
        ok? (and job
                 (deco/proof-approved? job)
                 (deco/digest-matches? job (:plate-digest value))
                 (not (deco/run-size-exceeded? value)))]
    {:summary   (str "装飾ジョブ " subject " の刷り実績を記録する draft")
     :rationale (if ok?
                  (str "承認済みの版と digest が一致、" (:garments value) " 枚")
                  "校正未承認、版の digest 不一致、または 1 run の枚数超過")
     :cites     [:job/proof :plate-plan/digest]
     :effect    :print-run/log
     :value     value
     :stake     nil
     :confidence (if ok? 0.9 0.2)}))

(defn- log-decoration-inspection
  [db {:keys [subject value]}]
  (let [job (store/decoration-job db subject)]
    {:summary   (str "装飾ジョブ " subject " の検品結果を記録する draft")
     :rationale (str "刷り上がりの検品。" (deco/summarize job))
     :cites     [:job/stage :job/print-runs]
     :effect    :decoration-inspection/log
     :value     value
     :stake     nil
     :confidence (if job 0.9 0.2)}))

(defn infer
  "Route a request to the right proposal generator.
  request: {:op kw :effect :propose :subject id ...op-specific...}"
  [db {:keys [op] :as request}]
  (case op
    :log-production-batch      (log-production-batch db request)
    :schedule-maintenance      (schedule-maintenance db request)
    :flag-safety-concern       (flag-safety-concern db request)
    :coordinate-shipment       (coordinate-shipment db request)
    :register-decoration-order (register-decoration-order db request)
    :attach-plate-plan         (attach-plate-plan db request)
    :request-proof-approval    (request-proof-approval db request)
    :log-print-run             (log-print-run db request)
    :log-decoration-inspection (log-decoration-inspection db request)
    {:summary "未対応の操作" :rationale (str op) :cites []
     :effect :noop :stake nil :confidence 0.0}))

;; ----------------------------- Advisor protocol -----------------------------

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

(defn mock-advisor
  "The deterministic advisor (the `infer` logic above). Default everywhere."
  [] (reify Advisor (-advise [_ st req] (infer st req))))

(def ^:private system-prompt
  (str "あなたはテキスタイル仕上げ加工工場(染色・捺染・漂白・シルケット加工)プラント運用"
       "コーディネーターの助言者です。"
       "与えられた事実のみに基づき、提案を1つだけEDNマップで返します。"
       "説明や前置きは一切書かず、EDNだけを出力します。\n"
       "キー: :summary(人向けドラフト) :rationale(根拠/必ず事実から) "
       ":cites(使った事実キーのベクタ) "
       ":effect(:batch/upsert|:maintenance/schedule|"
       ":safety-concern/flag|:shipment/propose) "
       ":stake(:coordination/safety-concern か nil) :confidence(0..1)。\n"
       "重要: 未検証または未登録の設備・バッチに対する作業を提案してはいけません。"
       "染色・捺染・漂白・シルケット加工ラインの設備の直接操作を絶対に提案してはいけません"
       "(この actor は提案のみを行い、実行は一切行いません)。"
       "排水放流許可(effluent-discharge-permit)の決定を絶対に提案してはいけません"
       "(懸念の報告のみ許可、決定は人間の規制当局/工場責任者の専権)。"
       "出荷量を偽って報告してはいけません。"))

(defn- facts-for [st {:keys [op subject value]}]
  (case op
    :log-production-batch       {:batch (store/batch st subject)}
    :schedule-maintenance       {:equipment (store/equipment-unit st (:equipment-id value))}
    :flag-safety-concern        {:equipment (and (:equipment-id value)
                                                  (store/equipment-unit st (:equipment-id value)))}
    :coordinate-shipment        {:batch (store/batch st (:batch-id value))}
    ;; 装飾ジョブ系はすべて job そのものが事実（工程の現在地・版・校正）
    (:register-decoration-order :attach-plate-plan :request-proof-approval
     :log-print-run :log-decoration-inspection)
    {:job (store/decoration-job st subject)}
    {}))

(defn- parse-proposal
  "Parse the model's EDN proposal defensively. Any parse/shape failure
  yields a safe low-confidence noop so `finishingops.governor`
  escalates/holds -- an LLM hiccup can never auto-schedule maintenance,
  auto-flag a concern, or auto-coordinate a shipment."
  [content]
  (let [p (try (edn/read-string (str/trim (str content)))
               (catch #?(:clj Exception :cljs :default) _ nil))]
    (if (map? p)
      (-> p
          (update :cites #(vec (or % [])))
          (update :confidence #(if (number? %) (double %) 0.0))
          (update :effect #(or % :noop)))
      {:summary "LLM応答を解釈できませんでした" :rationale (str content)
       :cites [] :effect :noop :stake nil :confidence 0.0})))

(defn llm-advisor
  "An advisor backed by a `langchain.model/ChatModel` (real inference)."
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-advise [_ st req]
       (let [msgs [{:role :system :content system-prompt}
                   {:role :user :content (str "操作: " (:op req)
                                              "\n対象: " (:subject req)
                                              "\n事実: " (pr-str (facts-for st req)))}]
             resp (model/-generate chat-model msgs gen-opts)]
         (parse-proposal (:content resp)))))))

(defn trace
  "Decision-grounded audit record -- persisted to the :audit channel."
  [request proposal]
  {:t          :finishing-advisor-proposal
   :op         (:op request)
   :subject    (:subject request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :cites      (:cites proposal)
   :confidence (:confidence proposal)})
