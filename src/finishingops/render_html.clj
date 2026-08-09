(ns finishingops.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300):
  this repo previously had NO demo page and no generator at all. This
  namespace drives the REAL actor stack (`finishingops.operation` ->
  `finishingops.governor` -> `finishingops.store`) through a scenario
  adapted from this repo's own `finishingops.sim` demo driver
  (`clojure -M:dev:run`, confirmed against
  `finishingops.store/sample-batches`/`sample-equipment` seeded ids
  before this file was written), trimmed to a representative subset
  (the one clean phase-3 auto-commit this domain has, the maintenance-
  scheduling / safety-concern-flagging / shipment-coordination lifecycle
  -- all three of which ALWAYS escalate, never auto, at any phase --
  the shirohan prepress craft path when wired -- and distinct HARD-hold
  reasons that never reach a human) and rendered deterministically --
  no invented numbers, no timestamps in the page content, byte-identical
  across reruns against the same seed.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [jp-go-dds.skin]
            [clojure.string :as str]
            [finishingops.store :as store]
            [finishingops.operation :as op]
            [langgraph.graph :as g]))

;; ----------------------------- harness (unchanged across every repo
;; in this cluster -- do not rewrite, only copy) -----------------------

(def ^:private operator
  {:actor-id "op-1" :actor-role :plant-coordinator :phase 3})

(defn- exec! [actor tid request]
  (g/run* actor {:request request :context operator} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}}
          {:thread-id tid :resume? true}))

;; SVG fixtures match `test/finishingops/prepress_test.cljc` so the
;; build-time page exercises the same shirohan paths the tests pin.

(def ^:private clean-svg
  (str "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 100 100\">"
       "<rect x=\"10\" y=\"10\" width=\"80\" height=\"80\" fill=\"#FF2D95\"/>"
       "</svg>"))

(def ^:private dirty-svg
  "SVG that yields blocking findings (:text-not-outlined + :no-art)."
  (str "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 100 100\">"
       "<text x=\"10\" y=\"20\" fill=\"#000000\">hello</text>"
       "</svg>"))

(defn run-demo!
  "Runs a fresh seeded store through a scenario mixing every disposition
  this actor can reach, using ONLY real ids from
  `finishingops.store` sample data and the prepress fixtures above:

  batch-001 (dyeing cotton-poplin, verified+registered, 20000.0 yards,
  5000.0 already shipped): a `:log-production-batch` clean patch is a
  phase-3, no-physical/financial-risk auto-commit (governor clean,
  `:log-production-batch` is the ONLY classic op in phase 3's `:auto`
  set -- `finishingops.phase`; prepress/plan may also auto when clean).

  mnt-1 on equip-001 (verified+registered dyeing-line):
  `:schedule-maintenance` is NEVER a member of any phase's `:auto` set,
  including phase 3 -- ALWAYS escalates and is approved by a human plant
  supervisor.

  concern-1 on equip-001: `:flag-safety-concern` ALWAYS carries `:stake
  :coordination/safety-concern`, in `finishingops.governor/high-stakes`
  -- ALWAYS escalates regardless of confidence, approved by a human plant
  supervisor.

  ship-1 on batch-001 (5000.0 yards claim; 5000.0 already shipped +
  5000.0 claimed = 10000.0, within batch-001's own recorded 20000.0
  yards): `:coordinate-shipment` is also never auto-eligible at any
  phase -- ALWAYS escalates, approved by a human shipping approver.

  Prepress craft (shirohan / garment plate separation), when wired:
    - job-clean: clean SVG → `:prepress/plan` commits summary only
      (input hash + plate labels; no geometry).
    - job-dirty: text-only SVG → HARD hold
      `:prepress-blocking-findings` (shirohan blocking? kinds), never
      reaches a human and never writes a prepress record.

  Then three DISTINCT classic HARD-hold reasons, none of which ever
  reach a human (a human approver cannot override a HARD violation):
    - mnt-2 on equip-002 (seeded `:verified? false :registered? false`
      mercerizing-line): `:schedule-maintenance` HARD-holds on
      `:equipment-not-verified` -- the advisor's own report of
      verified?/registered? is never trusted, the governor independently
      re-derives it from equip-002's own stored fields.
    - ship-2 on batch-003 (seeded `:verified? false :registered? false`
      bleaching batch): `:coordinate-shipment` HARD-holds on
      `:batch-not-verified` -- same independent-verification discipline,
      applied to the batch side.
    - ship-3 on batch-002 (own recorded 6000.0 yards, 5700.0 already
      shipped; a further 1000.0 yards claim would total 6700.0):
      `:coordinate-shipment` HARD-holds on `:shipment-volume-exceeded` --
      the governor independently recomputes shipped-to-date + claimed
      against the batch's own recorded yardage, never trusts the claim.

  Returns the resulting store -- every field `render` below reads is
  real governor/store output, not a hand-typed copy."
  []
  (let [db (-> (store/mem-store) (store/sample-data!))
        actor (op/build db)]

    ;; batch-001: clean production-batch log patch -- phase-3 auto-commit,
    ;; no physical/financial risk yet.
    (exec! actor "b1-log" {:op :log-production-batch :effect :propose :subject "batch-001"
                            :patch {:colorfastness-grade :grade-5 :last-assessed "2026-07-16"}})

    ;; mnt-1: maintenance scheduling against equip-001 (verified+registered
    ;; dyeing-line) -- ALWAYS escalates, approved by a human plant
    ;; supervisor.
    (exec! actor "m1-schedule" {:op :schedule-maintenance :effect :propose :subject "mnt-1"
                                 :value {:equipment-id "equip-001" :maintenance-type :roller-recalibration
                                         :scheduled-date "2026-08-01" :direct-operate? false}})
    (approve! actor "m1-schedule")

    ;; concern-1: chemical-handling concern flagged against equip-001 --
    ;; ALWAYS escalates regardless of confidence, approved by a human plant
    ;; supervisor.
    (exec! actor "c1-flag" {:op :flag-safety-concern :effect :propose :subject "concern-1"
                             :value {:equipment-id "equip-001" :concern-type :chemical-handling
                                     :severity :moderate
                                     :description "染色排水ラインで規格外のpH値を確認"}})
    (approve! actor "c1-flag")

    ;; ship-1: shipment coordination against batch-001 (within its own
    ;; recorded capacity) -- ALWAYS escalates, approved by a human
    ;; shipping approver.
    (exec! actor "s1-coordinate" {:op :coordinate-shipment :effect :propose :subject "ship-1"
                                   :value {:batch-id "batch-001" :volume-yards 5000.0
                                           :destination "customer-acme-warehouse"}})
    (approve! actor "s1-coordinate")

    ;; Prepress craft (shirohan): clean plan commits; blocking job HARD-holds.
    (exec! actor "pp-clean" {:op :prepress/plan :effect :propose :subject "job-clean"
                              :value {:svg clean-svg}})
    (exec! actor "pp-dirty" {:op :prepress/plan :effect :propose :subject "job-dirty"
                              :value {:svg dirty-svg}})

    ;; mnt-2: maintenance scheduling against equip-002 (seeded
    ;; UNVERIFIED/unregistered) -> HARD hold :equipment-not-verified,
    ;; never reaches a human.
    (exec! actor "m2-schedule" {:op :schedule-maintenance :effect :propose :subject "mnt-2"
                                 :value {:equipment-id "equip-002" :maintenance-type :tension-calibration
                                         :scheduled-date "2026-08-01" :direct-operate? false}})

    ;; ship-2: shipment coordination against batch-003 (seeded
    ;; UNVERIFIED/unregistered) -> HARD hold :batch-not-verified, never
    ;; reaches a human.
    (exec! actor "s2-coordinate" {:op :coordinate-shipment :effect :propose :subject "ship-2"
                                   :value {:batch-id "batch-003" :volume-yards 1000.0
                                           :destination "customer-hakusen-warehouse"}})

    ;; ship-3: shipment coordination against batch-002 (1000.0 yards claim
    ;; would push 5700.0 already-shipped past its own recorded 6000.0
    ;; yards) -> HARD hold :shipment-volume-exceeded, independently
    ;; recomputed, never reaches a human.
    (exec! actor "s3-coordinate" {:op :coordinate-shipment :effect :propose :subject "ship-3"
                                   :value {:batch-id "batch-002" :volume-yards 1000.0
                                           :destination "customer-nishijin-warehouse"}})

    db))

;; ----------------------------- rendering -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- kw-str [k]
  "Render a keyword with its namespace (e.g. prepress/plan), not bare name."
  (cond
    (nil? k) "n-a"
    (keyword? k) (if-let [ns (namespace k)]
                   (str ns "/" (name k))
                   (name k))
    :else (str k)))

(defn- last-fact-for [ledger subject-id]
  (last (filter #(= (:subject %) subject-id) ledger)))

(defn- status-cell [ledger subject-id]
  (let [f (last-fact-for ledger subject-id)]
    (cond
      (nil? f) "<span class=\"muted\">no activity</span>"
      (= :committed (:t f)) "<span class=\"ok\">committed</span>"
      (= :approval-granted (:t f)) "<span class=\"ok\">approved &amp; committed</span>"
      (= :governor-hold (:t f))
      (let [rule (or (-> f :violations first :rule)
                     (first (:basis f)))]
        (str "<span class=\"critical\">HARD hold &middot; " (esc (kw-str (or rule :unknown))) "</span>"))
      (= :approval-requested (:t f)) "<span class=\"warn\">awaiting approval</span>"
      :else "<span class=\"muted\">in progress</span>")))

(defn- batch-row [ledger {:keys [id process-type fabric colorfastness-grade volume-yards
                                  shrinkage-percent verified? registered? shipped-volume-yards]}]
  (format "        <tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc id) (esc (kw-str (or process-type :n-a))) (esc fabric)
          (esc (kw-str (or colorfastness-grade :n-a)))
          (esc volume-yards) (esc shrinkage-percent)
          (if verified? "<span class=\"ok\">verified</span>" "<span class=\"critical\">unverified</span>")
          (if registered? "<span class=\"ok\">registered</span>" "<span class=\"critical\">unregistered</span>")
          (esc shipped-volume-yards)
          (status-cell ledger id)))

(defn- equipment-row [ledger {:keys [id kind verified? registered? last-maintenance-date]}]
  (format "        <tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc id) (esc (kw-str (or kind :n-a)))
          (if verified? "<span class=\"ok\">verified</span>" "<span class=\"critical\">unverified</span>")
          (if registered? "<span class=\"ok\">registered</span>" "<span class=\"critical\">unregistered</span>")
          (esc (or last-maintenance-date "—"))
          (status-cell ledger id)))

(defn- concern-row [ledger {:keys [id equipment-id concern-type severity description]}]
  (format "        <tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc id) (esc (or equipment-id "—")) (esc (kw-str (or concern-type :n-a)))
          (esc (kw-str (or severity :n-a))) (esc description)
          (status-cell ledger id)))

(defn- prepress-row [ledger id rec]
  (let [status (if rec
                 (str "<span class=\"ok\">" (esc (kw-str (or (:status rec) :planned))) "</span>")
                 (status-cell ledger id))]
    (format "        <tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
            (esc id)
            (esc (or (:plate-count rec) "—"))
            (esc (or (some->> (:plate-labels rec) (str/join ", ")) "—"))
            (esc (or (some-> rec :source kw-str) "—"))
            (esc (or (some-> (:input-hash rec) (subs 0 (min 12 (count (:input-hash rec))))) "—"))
            status)))

(defn- draft-record-row [kind {:strs [record_id maintenance_id shipment_id equipment_id immutable]}]
  (format "        <tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc kind) (esc record_id) (esc (or maintenance_id shipment_id))
          (esc (or equipment_id "—"))
          (if immutable "<span class=\"ok\">immutable draft</span>" "<span class=\"warn\">draft</span>")))

(defn- ledger-row [{:keys [t op subject disposition basis]}]
  (format "        <tr><td>%s</td><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
          (esc (kw-str t)) (esc (kw-str (or op :n-a))) (esc subject)
          (esc (or (some->> basis (map kw-str) (str/join ", "))
                    (some-> disposition kw-str) ""))))

(def ^:private action-gate-rows
  ;; Static description of this actor's own op contract
  ;; (`finishingops.governor`/`finishingops.phase`) -- documentation of
  ;; fixed behavior, not runtime telemetry, so it is legitimately
  ;; hand-described rather than derived from a live run.
  ["        <tr><td><code>:log-production-batch</code></td><td><span class=\"ok\">phase-3 auto-commit when clean, no physical/financial risk yet -- classic auto-eligible op</span></td></tr>"
   "        <tr><td><code>:schedule-maintenance</code></td><td><span class=\"warn\">ALWAYS human approval &middot; equipment verified?/registered? independently re-derived from its own stored record, never trusted from the advisor &middot; `:direct-operate? true` is a PERMANENT, unconditional block</span></td></tr>"
   "        <tr><td><code>:flag-safety-concern</code></td><td><span class=\"warn\">ALWAYS human approval regardless of confidence &middot; chemical-handling/effluent concerns are never an effluent-permit decision (`:permit-decision? true` is PERMANENT block)</span></td></tr>"
   "        <tr><td><code>:coordinate-shipment</code></td><td><span class=\"warn\">ALWAYS human approval &middot; batch verified?/registered? independently re-derived &middot; claimed yards independently recomputed against the batch's own logged shipped-to-date + volume-yards</span></td></tr>"
   "        <tr><td><code>:prepress/plan</code></td><td><span class=\"ok\">shirohan plan summary (hash + plate labels only) &middot; HARD hold on blocking findings &middot; no plate geometry on the ledger</span></td></tr>"
   "        <tr><td><code>:prepress/approve-plates</code></td><td><span class=\"warn\">ALWAYS human approval (plate sign-off) &middot; actor cannot self-fill `:approved-by`</span></td></tr>"])

(defn render
  "Renders the full operator-console.html document from a store `db`
  that has already run `run-demo!` (or any other real scenario)."
  [db]
  (let [ledger (vec (store/ledger db))
        batches (store/all-batches db)
        equipment (store/all-equipment db)
        concerns (store/safety-concerns db)
        pp-clean (store/prepress-record db "job-clean")
        batch-rows (str/join "\n" (map (partial batch-row ledger) batches))
        equipment-rows (str/join "\n" (map (partial equipment-row ledger) equipment))
        concern-rows (str/join "\n" (map (partial concern-row ledger) concerns))
        prepress-rows (str/join "\n" [(prepress-row ledger "job-clean" pp-clean)
                                      (prepress-row ledger "job-dirty" (store/prepress-record db "job-dirty"))])
        maintenance-rows (str/join "\n" (map (partial draft-record-row "maintenance-schedule")
                                              (store/maintenance-history db)))
        shipment-rows (str/join "\n" (map (partial draft-record-row "shipment-coordination")
                                           (store/shipment-history db)))
        ledger-rows (str/join "\n" (map ledger-row ledger))]
    (str
     "<html><head><meta charset=\"utf-8\"><title>cloud-itonami-isic-1313 &middot; finishing of textiles</title><style>"
   (jp-go-dds.skin/dds+skin)
   "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Finishing of textiles (ISIC 1313) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · maintenance/safety/shipment always human-approved · prepress via shirohan</span>\n"
     "</header>\n"
     "<main>\n"
     "  <section class=\"card\">\n"
     "    <h2>Production batches</h2>\n"
     "    <p class=\"muted\">Demo snapshot — build-time-generated from <code>finishingops.store</code> via <code>finishingops.render-html</code> (<code>clojure -M:dev:render-html</code>), regenerated nightly.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Batch</th><th>Process</th><th>Fabric</th><th>Colorfastness</th><th>Volume (yd)</th><th>Shrinkage (%)</th><th>Verified</th><th>Registered</th><th>Shipped (yd)</th><th>Last op status</th></tr></thead>\n"
     "      <tbody>\n"
     batch-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Equipment</h2>\n"
     "    <table>\n"
     "      <thead><tr><th>Equipment</th><th>Kind</th><th>Verified</th><th>Registered</th><th>Last maintenance date</th><th>Last op status</th></tr></thead>\n"
     "      <tbody>\n"
     equipment-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Safety concerns</h2>\n"
     "    <p class=\"muted\">Append-only chemical-handling/effluent concern log — always human-reviewed; this actor never decides an effluent-discharge permit.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Concern</th><th>Equipment</th><th>Type</th><th>Severity</th><th>Description</th><th>Last op status</th></tr></thead>\n"
     "      <tbody>\n"
     concern-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Prepress (shirohan)</h2>\n"
     "    <p class=\"muted\">Garment plate-separation craft — plan summary only (input hash + plate labels). Geometry stays in the pure engine; blocking findings HARD-hold and never write SSoT.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Job</th><th>Plates</th><th>Labels</th><th>Source</th><th>Input hash</th><th>Status</th></tr></thead>\n"
     "      <tbody>\n"
     prepress-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Draft maintenance-schedule / shipment-coordination records</h2>\n"
     "    <p class=\"muted\">Unsigned drafts only — the plant supervisor's/shipping approver's own act of signing is outside this actor's authority (see README <code>What this actor does NOT do</code>).</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Kind</th><th>Record id</th><th>Subject</th><th>Equipment</th><th>Status</th></tr></thead>\n"
     "      <tbody>\n"
     maintenance-rows (when (seq maintenance-rows) "\n")
     shipment-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Action gate (Textile Finishing Operations Governor)</h2>\n"
     "    <p class=\"muted\">HARD holds cannot be overridden by a human approver. Equipment/batch verification+registration status, shipment-yardage arithmetic, colorfastness/shrinkage plausibility, and shirohan blocking findings are independently recomputed, never trusted from the advisor's proposal; maintenance scheduling, safety-concern flagging and shipment coordination are always a human plant supervisor's/shipping approver's call, at every rollout phase.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Gate</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" action-gate-rows) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Audit ledger (this run)</h2>\n"
     "    <p class=\"muted\">Append-only decision-fact log — every hold and commit this scenario produced.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Fact</th><th>Op</th><th>Subject</th><th>Basis</th></tr></thead>\n"
     "      <tbody>\n"
     ledger-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "</main>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        db (run-demo!)
        html (render db)
        f (java.io.File. out)]
    (when-let [parent (.getParentFile f)]
      (.mkdirs parent))
    (spit f html)
    (println "wrote" out "(" (count (store/ledger db)) "ledger facts,"
             (count (store/maintenance-history db)) "maintenance drafts,"
             (count (store/shipment-history db)) "shipment drafts,"
             (count (store/safety-concerns db)) "safety concerns,"
             (count (store/all-prepress-records db)) "prepress records )")))
