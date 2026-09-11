(ns finishingops.decoration-test
  "ガーメント装飾（Tシャツ等への捺染）の工程 —— 受注 → 版下 → 校正 → 刷り → 検品。

  ここで守るのは 3 点で、どれも **戻せないのは生地であって記録ではない** ことが根拠:

  1. 工程を飛ばせない（校正していない版で刷れない）
  2. 版は製版エンジンが『刷れる』と言ったものだけ
  3. **承認した版と刷った版が同じ**（digest 一致）"
  (:require [clojure.test :refer [deftest is testing]]
            [finishingops.decoration :as deco]
            [finishingops.governor :as governor]
            [finishingops.advisor :as advisor]
            [finishingops.operation :as operation]
            [langgraph.graph :as g]
            [finishingops.store :as store]))

(def ^:private digest-a "9f2b7c1d4e6a8b03f5c7d9e1a3b5c7d9")
(def ^:private digest-b "1111222233334444555566667777888")

(defn- ctx [] {:actor-id "finishing-actor" :role :coordinator :phase :phase-3})

(defn- req [op subject value]
  {:op op :effect :propose :subject subject :value value})

(defn- job-store [jobs]
  (let [s (store/mem-store)]
    (store/with-decoration-jobs s jobs)
    s))

(defn- verdict [st op subject value]
  (let [request (req op subject value)
        proposal (advisor/-advise (advisor/mock-advisor) st request)]
    (governor/check request (ctx) proposal st)))

;; ---------------------------------------------------------------- 工程の順序

(deftest stages-are-ordered-and-cannot-be-skipped
  (is (= [:ordered :plated :proofed :printed :inspected] deco/stages))
  (is (= :plated (deco/next-stage :attach-plate-plan)))
  (is (= :proofed (deco/required-predecessor :printed)))
  (is (nil? (deco/required-predecessor :ordered)) "受注は前提を持たない"))

(deftest at-least-compares-by-position-not-equality
  (let [job {:stage :printed}]
    (is (deco/at-least? job :ordered))
    (is (deco/at-least? job :proofed))
    (is (not (deco/at-least? job :inspected)))))

(deftest cannot-attach-a-plate-plan-to-a-job-that-was-never-ordered
  (let [v (verdict (job-store {}) :attach-plate-plan "job-x"
                   {:plate-plan {:digest digest-a :blocking 0 :plate-count 3 :choke-mm 0.2}})]
    (is (:hard? v))
    (is (= [:decoration-job-unknown] (mapv :rule (:violations v))))))

(deftest cannot-print-before-the-proof-stage
  (let [st (job-store {"job-1" {:id "job-1" :stage :plated
                                :plate-plan {:digest digest-a :blocking 0}}})
        v (verdict st :log-print-run "job-1" {:plate-digest digest-a :garments 50})]
    (is (:hard? v))
    (is (contains? (set (mapv :rule (:violations v))) :decoration-stage-order))))

;; ---------------------------------------------------------------- 版下

(deftest plate-plan-must-carry-a-well-formed-digest
  (is (deco/digest-well-formed? digest-a))
  (is (not (deco/digest-well-formed? "not-a-digest")))
  (is (not (deco/digest-well-formed? nil)))
  (let [st (job-store {"job-1" {:id "job-1" :stage :ordered}})
        v (verdict st :attach-plate-plan "job-1"
                   {:plate-plan {:digest "zzz" :blocking 0}})]
    (is (:hard? v))
    (is (contains? (set (mapv :rule (:violations v))) :plate-plan-digest-malformed))))

(deftest a-plate-plan-the-engine-called-unprintable-cannot-move-forward
  (testing "所見は刷る前に潰すためにある —— 素通しできるなら出す意味が無い"
    (let [st (job-store {"job-1" {:id "job-1" :stage :ordered}})
          v (verdict st :attach-plate-plan "job-1"
                     {:plate-plan {:digest digest-a :blocking 2 :plate-count 3}})]
      (is (:hard? v))
      (is (contains? (set (mapv :rule (:violations v))) :plate-plan-blocking-findings)))))

(deftest a-clean-plate-plan-passes
  (let [st (job-store {"job-1" {:id "job-1" :stage :ordered}})
        v (verdict st :attach-plate-plan "job-1"
                   {:plate-plan {:digest digest-a :blocking 0 :plate-count 3 :choke-mm 0.2}})]
    (is (:ok? v) (str "想定外の violation: " (pr-str (:violations v))))))

;; ---------------------------------------------------------------- 校正

(deftest the-actor-can-never-approve-a-proof-itself
  (let [st (job-store {"job-1" {:id "job-1" :stage :plated
                                :plate-plan {:digest digest-a :blocking 0}}})
        request (req :request-proof-approval "job-1"
                     {:proof {:approved-by "actor-forged-this"}})
        ;; advisor は承認者を落とすので、**governor 側の HARD も直接試す**
        proposal {:effect :proof/request-approval
                  :value {:proof {:approved-by "actor-forged-this"}}
                  :confidence 0.99 :cites [] :summary "x"}
        v (governor/check request (ctx) proposal st)]
    (is (:hard? v))
    (is (contains? (set (mapv :rule (:violations v))) :proof-approval-self-decided))))

(deftest proof-approval-always-needs-a-human-even-when-clean
  (let [st (job-store {"job-1" {:id "job-1" :stage :plated
                                :plate-plan {:digest digest-a :blocking 0}}})
        v (verdict st :request-proof-approval "job-1" {:proof {:note "色校"}})]
    (is (not (:hard? v)) "HARD ではない —— 人に回す")
    (is (:escalate? v))
    (is (:high-stakes? v))))

(deftest proof-approved-requires-a-named-approver
  (is (not (deco/proof-approved? {:stage :proofed})))
  (is (not (deco/proof-approved? {:stage :proofed :proof {:approved-by ""}})))
  (is (deco/proof-approved? {:stage :proofed :proof {:approved-by "supervisor-01"}})))

;; ---------------------------------------------------------------- 刷り

(defn- proofed-job [digest]
  {"job-1" {:id "job-1" :stage :proofed
            :plate-plan {:digest digest :blocking 0 :plate-count 3 :choke-mm 0.2}
            :proof {:approved-by "supervisor-01"}}})

(deftest printing-requires-the-same-plate-that-was-approved
  (testing "承認した版と刷った版が同じであることを、後から示せる"
    (let [st (job-store (proofed-job digest-a))
          v (verdict st :log-print-run "job-1" {:plate-digest digest-b :garments 50})]
      (is (:hard? v))
      (is (contains? (set (mapv :rule (:violations v))) :print-run-plate-mismatch)))))

(deftest printing-with-the-approved-plate-passes
  (let [st (job-store (proofed-job digest-a))
        v (verdict st :log-print-run "job-1" {:plate-digest digest-a :garments 50})]
    (is (:ok? v) (str "想定外の violation: " (pr-str (:violations v))))))

(deftest printing-without-a-human-approval-is-blocked
  (let [st (job-store {"job-1" {:id "job-1" :stage :proofed
                                :plate-plan {:digest digest-a :blocking 0}}})
        v (verdict st :log-print-run "job-1" {:plate-digest digest-a :garments 50})]
    (is (:hard? v))
    (is (contains? (set (mapv :rule (:violations v)))
                   :print-run-before-proof-approval))))

(deftest an-oversized-run-is-blocked
  (let [st (job-store (proofed-job digest-a))
        v (verdict st :log-print-run "job-1"
                   {:plate-digest digest-a :garments (inc deco/max-run-garments)})]
    (is (:hard? v))
    (is (contains? (set (mapv :rule (:violations v))) :print-run-size-exceeded))))

(deftest the-actor-never-actuates-the-press
  (let [st (job-store (proofed-job digest-a))
        request (req :log-print-run "job-1" {:plate-digest digest-a :garments 10 :actuate? true})
        proposal {:effect :print-run/log :confidence 0.99 :cites [] :summary "x"
                  :value {:plate-digest digest-a :garments 10 :actuate? true}}
        v (governor/check request (ctx) proposal st)]
    (is (:hard? v))
    (is (contains? (set (mapv :rule (:violations v))) :press-actuation-blocked))))

;; ---------------------------------------------------------------- 通し

(deftest a-whole-job-walks-the-stages-in-order
  (testing "受注→版下→校正。**版下だけが自動で確定**し、受注と校正は人が承認する"
    (let [st (store/mem-store)
          actor (operation/build st)
          run (fn [tid op subject value]
                (g/run* actor {:request (req op subject value) :context (ctx)}
                        {:thread-id tid}))
          approve! (fn [tid]
                     (g/run* actor {:approval {:status :approved :by "supervisor-01"}}
                             {:thread-id tid :resume? true}))]

      ;; 受注 —— 金銭的な約束なので自動確定しない
      (let [r (run "t-order" :register-decoration-order "job-9"
                   {:garment "tee-black" :garments 80 :placement :chest-center})]
        (is (= :escalate (get-in r [:state :disposition]))
            "受注は人が見る（枚数・納期は金銭的な約束）"))
      (approve! "t-order")
      (is (= :ordered (:stage (store/decoration-job st "job-9"))))

      ;; 版下 —— 決定論エンジンの出力への参照を貼るだけなので自動確定
      (let [r (run "t-plate" :attach-plate-plan "job-9"
                   {:plate-plan {:digest digest-a :blocking 0
                                 :plate-count 3 :choke-mm 0.2}})]
        (is (= :commit (get-in r [:state :disposition]))))
      (is (= :plated (:stage (store/decoration-job st "job-9"))))
      (is (= digest-a (get-in (store/decoration-job st "job-9") [:plate-plan :digest])))

      ;; 校正 —— 常に人。承認しても **承認者は commit が書かない**
      (let [r (run "t-proof" :request-proof-approval "job-9" {:proof {:note "色校"}})]
        (is (= :escalate (get-in r [:state :disposition]))))
      (approve! "t-proof")
      (is (= :proofed (:stage (store/decoration-job st "job-9"))))
      (is (not (deco/proof-approved? (store/decoration-job st "job-9")))
          "承認者は human-in-the-loop の resume が入れるもので、commit は書かない"))))

(deftest commit-never-writes-a-proof-approver
  (testing "承認は human-in-the-loop の resume で入るもので、commit が書くものではない"
    (let [st (job-store {"job-1" {:id "job-1" :stage :plated
                                  :plate-plan {:digest digest-a :blocking 0}}})]
      (store/commit-record! st {:effect :proof/request-approval
                                :path ["job-1"]
                                :value {:proof {:note "色校" :approved-by "forged"}}})
      (is (= :proofed (:stage (store/decoration-job st "job-1"))))
      (is (nil? (get-in (store/decoration-job st "job-1") [:proof :approved-by]))))))

;; ---------------------------------------------------------------- allowlist

(deftest the-new-ops-are-on-the-closed-allowlist
  (doseq [op [:register-decoration-order :attach-plate-plan :request-proof-approval
              :log-print-run :log-decoration-inspection]]
    (is (contains? governor/allowed-ops op) (str op " が allowlist に無い")))
  (testing "allowlist は閉じている —— 未知の op は HARD で落ちる"
    (let [v (verdict (job-store {}) :print-directly-somehow "job-1" {})]
      (is (:hard? v))
      (is (contains? (set (mapv :rule (:violations v))) :unknown-op)))))
