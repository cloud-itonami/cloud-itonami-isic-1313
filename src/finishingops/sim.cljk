(ns finishingops.sim
  "Demo driver -- `clojure -M:dev:run`. Walks a clean finishing plant
  through intake -> maintenance scheduling (escalate/approve) ->
  safety-concern flag (escalate/approve) -> shipment coordination
  (escalate/approve), then shows HARD-hold scenarios: a mis-wired
  request whose own `:effect` is not `:propose`, an unrecognized op,
  maintenance scheduled against an UNVERIFIED/unregistered equipment
  unit, a shipment coordinated against an UNVERIFIED/unregistered
  batch, a shipment proposal that would exceed the batch's own logged
  finished-fabric yardage, a proposal that tries to directly operate a
  dyeing/printing/bleaching/mercerizing line (permanently blocked, no
  override), a safety-concern proposal that tries to decide an
  effluent-discharge-permit outcome (permanently blocked, no
  override), a double-schedule of the same maintenance window, a
  production-batch patch with a fabricated colorfastness grade, and a
  production-batch patch with an implausible shrinkage-rate reading.

  Like every sibling actor's own demo, each check is exercised directly
  and independently below, one request per HARD-hold scenario, the SAME
  'exercise the failure mode directly, never only via a happy-path
  actuation' discipline established across the fleet."
  (:require [langgraph.graph :as g]
            [finishingops.store :as store]
            [finishingops.operation :as op]))

(def coordinator {:actor-id "coord-1" :actor-role :plant-coordinator :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "coord-1"}} {:thread-id tid :resume? true}))

(defn -main [& _args]
  (let [db (-> (store/mem-store) (store/sample-data!))
        actor (op/build db)]

    (println "== log-production-batch batch-001 (clean patch -> phase-3 auto-commit) ==")
    (println (exec-op actor "t1"
                       {:op :log-production-batch :effect :propose :subject "batch-001"
                        :patch {:colorfastness-grade :grade-5 :last-assessed "2026-07-16"}}
                       coordinator))

    (println "== schedule-maintenance mnt-1 on equip-001 (verified, registered, dyeing-line -- escalates, approve) ==")
    (let [r (exec-op actor "t2"
                      {:op :schedule-maintenance :effect :propose :subject "mnt-1"
                       :value {:equipment-id "equip-001" :maintenance-type :roller-recalibration
                               :scheduled-date "2026-08-01" :direct-operate? false}}
                      coordinator)]
      (println r)
      (println "-- human plant supervisor approves --")
      (println (approve! actor "t2")))

    (println "== flag-safety-concern concern-1 on equip-001 (always escalates -- approve) ==")
    (let [r (exec-op actor "t3"
                      {:op :flag-safety-concern :effect :propose :subject "concern-1"
                       :value {:equipment-id "equip-001" :concern-type :chemical-handling
                               :severity :moderate
                               :description "染色排水ラインで規格外のpH値を確認"}}
                      coordinator)]
      (println r)
      (println "-- human plant supervisor approves --")
      (println (approve! actor "t3")))

    (println "== coordinate-shipment ship-1 on batch-001 (verified, registered, within volume -- escalates, approve) ==")
    (let [r (exec-op actor "t4"
                      {:op :coordinate-shipment :effect :propose :subject "ship-1"
                       :value {:batch-id "batch-001" :volume-yards 5000.0
                               :destination "customer-acme-warehouse"}}
                      coordinator)]
      (println r)
      (println "-- human shipping approver approves --")
      (println (approve! actor "t4")))

    (println "\n-- HARD-hold scenarios --\n")

    (println "== log-production-batch with :effect other than :propose -> HARD hold (structural) ==")
    (println (exec-op actor "t5"
                       {:op :log-production-batch :effect :direct-write :subject "batch-001"
                        :patch {:colorfastness-grade :grade-5}}
                       coordinator))

    (println "== unrecognized op -> HARD hold ==")
    (println (exec-op actor "t6"
                       {:op :actuate-dyeing-line :effect :propose :subject "batch-001"}
                       coordinator))

    (println "== schedule-maintenance mnt-2 on equip-002 (UNVERIFIED/unregistered mercerizing line -> HARD hold) ==")
    (println (exec-op actor "t7"
                       {:op :schedule-maintenance :effect :propose :subject "mnt-2"
                        :value {:equipment-id "equip-002" :maintenance-type :tension-calibration
                                :scheduled-date "2026-08-01" :direct-operate? false}}
                       coordinator))

    (println "== coordinate-shipment ship-2 on batch-003 (UNVERIFIED/unregistered batch -> HARD hold) ==")
    (println (exec-op actor "t8"
                       {:op :coordinate-shipment :effect :propose :subject "ship-2"
                        :value {:batch-id "batch-003" :volume-yards 1000.0
                                :destination "customer-hakusen-warehouse"}}
                       coordinator))

    (println "== coordinate-shipment ship-3 on batch-002 (1000 yards would exceed volume 6000 vs shipped 5700 -> HARD hold) ==")
    (println (exec-op actor "t9"
                       {:op :coordinate-shipment :effect :propose :subject "ship-3"
                        :value {:batch-id "batch-002" :volume-yards 1000.0
                                :destination "customer-nishijin-warehouse"}}
                       coordinator))

    (println "== schedule-maintenance mnt-3 on equip-001 with :direct-operate? true -> HARD hold, PERMANENT, never reaches a human ==")
    (println (exec-op actor "t10"
                       {:op :schedule-maintenance :effect :propose :subject "mnt-3"
                        :value {:equipment-id "equip-001" :maintenance-type :emergency-run
                                :scheduled-date "2026-09-01" :direct-operate? true}}
                       coordinator))

    (println "== flag-safety-concern concern-2 with :permit-decision? true -> HARD hold, PERMANENT, never reaches a human ==")
    (println (exec-op actor "t11"
                       {:op :flag-safety-concern :effect :propose :subject "concern-2"
                        :value {:equipment-id "equip-001" :concern-type :effluent-discharge
                                :severity :high :permit-decision? true
                                :description "排水放流許可を継続と決定"}}
                       coordinator))

    (println "== schedule-maintenance mnt-1 AGAIN (double-schedule -> HARD hold) ==")
    (println (exec-op actor "t12"
                       {:op :schedule-maintenance :effect :propose :subject "mnt-1"
                        :value {:equipment-id "equip-001" :maintenance-type :roller-recalibration
                                :scheduled-date "2026-08-01" :direct-operate? false}}
                       coordinator))

    (println "== log-production-batch batch-001 with a fabricated colorfastness grade -> HARD hold ==")
    (println (exec-op actor "t13"
                       {:op :log-production-batch :effect :propose :subject "batch-001"
                        :patch {:colorfastness-grade :premium-plus-select}}
                       coordinator))

    (println "== log-production-batch batch-001 with an implausible shrinkage-rate reading -> HARD hold ==")
    (println (exec-op actor "t14"
                       {:op :log-production-batch :effect :propose :subject "batch-001"
                        :patch {:shrinkage-percent 250.0}}
                       coordinator))

    (println "\n== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "\n== draft maintenance records ==")
    (doseq [r (store/maintenance-history db)] (println r))

    (println "\n== draft shipment records ==")
    (doseq [r (store/shipment-history db)] (println r))))
