(ns finishingops.prepress
  "Prepress craft ops that *consume* `shirohan.core/plan` results.

  This actor never owns plate geometry. It calls the pure prepress engine
  once, then stores only:

    - input content hash (SVG bytes)
    - plan summary (plate labels, finding counts, choke/size)

  Full plate contours / films stay out of the SSoT — recompute from the
  same SVG + hash when a film is needed. Governor HARD-holds any plan
  whose findings include a blocking kind (`shirohan.core/blocking?`).

  Ops (closed allowlist in `finishingops.governor`):

    :prepress/plan            — SVG → shirohan/plan → summary proposal
    :prepress/approve-plates  — human plate approval (always escalate)

  See ADR-2608090800 / ADR-2608011100."
  (:require [clojure.string :as str]
            [shirohan.core :as shirohan]
            #?(:clj [clojure.java.io :as io]))
  #?(:clj (:import (java.security MessageDigest)
                   (java.nio.charset StandardCharsets))))

;; ---------------------------------------------------------------- hash

(defn content-hash
  "SHA-256 hex of the UTF-8 SVG (or other) input string.

  Identity of a plan is identity of its input: shirohan is pure, so the
  same SVG + same spec always yields the same plates. We store this hash
  rather than re-serializing geometry."
  [s]
  #?(:clj
     (let [md (MessageDigest/getInstance "SHA-256")
           bs (.digest md (.getBytes (str s) StandardCharsets/UTF_8))]
       (apply str (map #(format "%02x" (bit-and % 0xff)) bs)))
     :cljs
     ;; Browser path not exercised by JVM tests; keep a stable pure fallback
     ;; so the ns loads. Prefer SubtleCrypto at the cljs host boundary.
     (let [x (str s)]
       (str "cljs-" (count x) "-" (hash x)))))

;; ---------------------------------------------------------------- resolve input

(defn resolve-svg
  "Pull an SVG string from a request `:value`.

  Accepts:
    :svg       — inline string (preferred; portable)
    :svg-path  — filesystem path (JVM only; reads once)
    :path      — alias of :svg-path

  Returns nil when nothing usable is present."
  [{:keys [svg svg-path path]}]
  (cond
    (and (string? svg) (seq (str/trim svg))) svg
    #?@(:clj
        [(or (string? svg-path) (string? path))
         (let [p (or svg-path path)]
           (when (and p (.exists (io/file p)))
             (slurp p)))]
        :cljs [])
    :else nil))

;; ---------------------------------------------------------------- plan → summary (no geometry)

(defn plan-summary
  "Reduce a shirohan job to the audit/SSoT summary.

  Never copies `:plates` contours — only labels / colors / order / area
  from `shirohan/summary`, plus the input hash and finding tallies."
  [svg-string spec job]
  (let [sum (shirohan/summary job)
        findings (:findings job [])
        blocking-fs (filterv shirohan/blocking? findings)
        ih (content-hash svg-string)]
    {:input-hash       ih
     ;; digest reuses input-hash so decoration's print-run matching can
     ;; treat a prepress plan as a plate-plan identity if wired later.
     :digest           ih
     :blocking         (count blocking-fs)
     :findings-count   (count findings)
     :finding-kinds    (mapv :kind findings)
     :blocking-kinds   (mapv :kind blocking-fs)
     :plate-count      (:plate-count sum)
     :plate-labels     (mapv :label (:plates sum))
     :plates           (:plates sum)          ; summary rows only (id/label/color/order/area)
     :choke-mm         (:choke-mm sum)
     :print-width-mm   (:print-width-mm sum)
     :size             (:size sum)
     :spec             (select-keys (merge shirohan/default-spec spec)
                                    [:choke-mm :print-width-mm :min-line-mm
                                     :white-underbase? :knockout-fill])
     :source           :shirohan}))

(defn run-plan
  "Call `shirohan.core/plan` and return `{:summary .. :job ..}` or
  `{:error :svg-missing}` when no SVG can be resolved.

  The full `:job` is returned only for the advisor's one-shot use; the
  commit path must take `:summary` alone (no geometry on the ledger)."
  [value]
  (if-let [svg (resolve-svg value)]
    (let [spec (or (:spec value) {})
          job (shirohan/plan svg spec)]
      {:svg svg :spec spec :job job :summary (plan-summary svg spec job)})
    {:error :svg-missing}))

(defn printable?
  "True only when blocking finding count is zero."
  [summary]
  (and (map? summary)
       (number? (:blocking summary))
       (zero? (:blocking summary))
       (string? (:input-hash summary))
       (seq (:input-hash summary))))

(defn blocking-findings?
  [summary]
  (and (map? summary)
       (number? (:blocking summary))
       (pos? (:blocking summary))))
