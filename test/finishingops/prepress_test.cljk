(ns finishingops.prepress-test
  "Wire shirohan craft into finishingops prepress ops.

  (a) clean simple SVG plan path commits (phase 3 auto)
  (b) SVG that produces blocking findings HARD-holds with rule visible
  (c) mutation: prepress ops must stay on the closed allowlist"
  (:require [clojure.test :refer [deftest is testing]]
            [finishingops.advisor :as advisor]
            [finishingops.governor :as governor]
            [finishingops.operation :as operation]
            [finishingops.prepress :as prepress]
            [finishingops.store :as store]
            [langgraph.graph :as g]
            [shirohan.core :as shirohan]))

(def ^:private clean-svg
  (str "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 100 100\">"
       "<rect x=\"10\" y=\"10\" width=\"80\" height=\"80\" fill=\"#FF2D95\"/>"
       "</svg>"))

(def ^:private dirty-svg
  "SVG that yields blocking findings (:text-not-outlined + :no-art)."
  (str "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 100 100\">"
       "<text x=\"10\" y=\"20\" fill=\"#000000\">hello</text>"
       "</svg>"))

(defn- ctx []
  {:actor-id "finishing-actor" :role :coordinator :phase 3})

(defn- req [op subject value]
  {:op op :effect :propose :subject subject :value value})

(defn- fresh []
  (let [db (store/mem-store)]
    [db (operation/build db)]))

(deftest content-hash-is-stable-sha256-hex
  (let [h (prepress/content-hash clean-svg)]
    (is (string? h))
    (is (re-matches #"^[0-9a-f]{64}$" h))
    (is (= h (prepress/content-hash clean-svg)))))

(deftest run-plan-summary-has-no-geometry
  (let [{:keys [summary job]} (prepress/run-plan {:svg clean-svg})
        engine (shirohan/summary job)]
    (is (prepress/printable? summary))
    (is (zero? (:blocking summary)))
    (is (seq (:plate-labels summary)))
    (is (= (:plate-count engine) (:plate-count summary)))
    (is (every? (fn [p] (not (contains? p :contours))) (:plates summary))
        "summary plates must not carry contours")))

(deftest clean-svg-plan-commits-with-hash-and-summary
  (testing "(a) clean simple SVG plan path commits; stores input hash + plan summary only"
    (let [[db actor] (fresh)
          r (g/run* actor
                    {:request (req :prepress/plan "job-clean" {:svg clean-svg})
                     :context (ctx)}
                    {:thread-id "prepress-clean"})
          rec (store/prepress-record db "job-clean")
          led (store/ledger db)]
      (is (= :commit (get-in r [:state :disposition])))
      (is (some? rec))
      (is (= (prepress/content-hash clean-svg) (:input-hash rec)))
      (is (zero? (:blocking rec)))
      (is (pos? (:plate-count rec)))
      (is (seq (:plate-labels rec)))
      (is (= :shirohan (:source rec)))
      (is (= :planned (:status rec)))
      (is (every? (fn [p] (not (contains? p :contours))) (:plates rec)))
      (is (some #{:committed} (map :t led)))
      (is (not-any? (fn [p] (contains? p :contours)) (:plates rec))))))

(deftest blocking-findings-hard-hold-with-rule-visible
  (testing "(b) input that produces findings holds with rule visible"
    (let [[db actor] (fresh)
          r (g/run* actor
                    {:request (req :prepress/plan "job-dirty" {:svg dirty-svg})
                     :context (ctx)}
                    {:thread-id "prepress-dirty"})
          led (store/ledger db)
          hold (first (filter #(= :governor-hold (:t %)) led))]
      (is (= :hold (get-in r [:state :disposition])))
      (is (nil? (store/prepress-record db "job-dirty"))
          "blocking plan must not commit to SSoT")
      (is (some? hold))
      (is (some #{:prepress-blocking-findings} (:basis hold)))
      (is (some (fn [v] (= :prepress-blocking-findings (:rule v)))
                (:violations hold))))))

(deftest approve-plates-always-escalates
  (testing "human plate approval never auto-commits"
    (let [[db actor] (fresh)
          ;; seed a clean plan summary as if a prior plan committed
          _ (store/commit-record! db
                                  {:effect :prepress/plan-record
                                   :path ["job-ok"]
                                   :value {:prepress {:input-hash (prepress/content-hash clean-svg)
                                                      :digest (prepress/content-hash clean-svg)
                                                      :blocking 0
                                                      :plate-count 2
                                                      :plate-labels ["白版（下地）" "スポット版 #FF2D95"]
                                                      :source :shirohan}}})
          r (g/run* actor
                    {:request (req :prepress/approve-plates "job-ok" {:note "版OK"})
                     :context (ctx)}
                    {:thread-id "prepress-approve"})]
      (is (= :interrupted (:status r))
          "phase + high-stakes both force human eyes")
      (is (= :escalate (get-in r [:state :disposition]))))))

(deftest self-filled-approver-is-hard-held
  (let [st (store/mem-store)
        request (req :prepress/approve-plates "job-x" {:approved-by "forged"})
        proposal {:effect :prepress/approve-plates :confidence 0.99 :cites []
                  :summary "x" :value {:approved-by "forged"}
                  :stake :coordination/plate-approval}
        v (governor/check request (ctx) proposal st)]
    (is (:hard? v))
    (is (some #{:prepress-approval-self-decided} (map :rule (:violations v))))))

(deftest missing-svg-is-hard-held
  (let [[db actor] (fresh)
        r (g/run* actor
                  {:request (req :prepress/plan "job-empty" {})
                   :context (ctx)}
                  {:thread-id "prepress-empty"})
        led (store/ledger db)]
    (is (= :hold (get-in r [:state :disposition])))
    (is (some #{:prepress-svg-missing}
              (mapcat :basis (filter #(= :governor-hold (:t %)) led))))))

(deftest prepress-ops-on-closed-allowlist-mutation
  (testing "(c) mutation: break allowed-ops → red"
    (doseq [op [:prepress/plan :prepress/approve-plates]]
      (is (contains? governor/allowed-ops op)
          (str op " missing from allowed-ops — wiring regressed")))
    (doseq [fx [:prepress/plan-record :prepress/approve-plates]]
      (is (contains? governor/allowed-proposal-effects fx)
          (str fx " missing from allowed-proposal-effects")))
    (is (contains? governor/high-stakes :coordination/plate-approval))
    (let [v (let [st (store/mem-store)
                  request (req :prepress/not-a-real-op "x" {})
                  proposal (advisor/-advise (advisor/mock-advisor) st request)]
              (governor/check request (ctx) proposal st))]
      (is (:hard? v))
      (is (some #{:unknown-op} (map :rule (:violations v)))))))
