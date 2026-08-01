(ns finishingops.decoration
  "ガーメント装飾（Tシャツ等への捺染）の**工程**。純関数のみ — store も I/O も持たない。

  ISIC 1313 (Finishing of textiles) の includes に
  `printing on textiles and clothing` が明記されており、ここがその工程の置き場所。

  ## 工程

  ```
  受注 ─▶ 版下 ─▶ 校正 ─▶ 刷り ─▶ 検品
  :ordered  :plated  :proofed  :printed  :inspected
  ```

  順序は飛ばせない。飛ばせるようにすると『校正していない版で 300 枚刷った』が
  起こりうるし、それは刷り直しでは戻らない（生地が戻らない）。

  ## 版は engine が出したものであって、この actor が作るものではない

  版下（白版・白抜き・スポットカラー版分解）は `cloud-itonami/shirohan` が
  **決定論的な純関数**として出す。この actor が持つのはその結果への参照だけ:

  - `:plate-plan/digest` —— 版一式のハッシュ。**同一性の証拠**
  - `:plate-plan/blocking` —— engine が『刷れない』と判定した所見の数
  - `:plate-plan/plate-count` / `:plate-plan/choke-mm` —— 人が読むための要約

  なぜ digest を持つのか: **承認した版と刷った版が同じであることを、後から示せる
  ようにするため。** engine が純関数であることは「同じ入力から同じ版が出る」こと
  しか保証しない —— 「校正で人が承認したのが本当にこの版か」は、承認時と刷り時の
  digest を突き合わせて初めて言える。`shirohan` を決定論にした意味はここで回収する。

  ## blocking 所見のある版は先へ進めない

  engine が `:choke-erases-feature`（白版から図案が消える）や `:no-art` を出した
  版を『とりあえず校正に回す』ことを許さない。所見は刷る前に潰すためにあり、
  素通しできるなら出す意味が無い。"
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------- 工程

(def stages
  "工程の順序。**この順序が契約**で、飛び級は governor が HARD で止める。"
  [:ordered :plated :proofed :printed :inspected])

(def stage-index (into {} (map-indexed (fn [i s] [s i]) stages)))

(defn stage-of
  "job の現在地。job が無ければ nil。"
  [job]
  (:stage job))

(defn at-least?
  "job が `stage` 以上まで進んでいるか。"
  [job stage]
  (let [cur (stage-index (stage-of job))
        want (stage-index stage)]
    (boolean (and cur want (>= cur want)))))

(defn next-stage
  "op が job をどの stage へ進めるか。進めない op なら nil。"
  [op]
  (case op
    :register-decoration-order :ordered
    :attach-plate-plan :plated
    :request-proof-approval :proofed
    :log-print-run :printed
    :log-decoration-inspection :inspected
    nil))

(defn required-predecessor
  "その stage に進むために、直前に済んでいなければならない stage。
  最初の stage は nil。"
  [stage]
  (when-let [i (stage-index stage)]
    (when (pos? i) (nth stages (dec i)))))

;; ---------------------------------------------------------------- 版下

(def ^:private digest-re #"^[0-9a-f]{16,128}$")

(defn digest-well-formed?
  "版一式のハッシュとして受け取れる形か（小文字 hex、16〜128 桁）。

  形だけを見る —— 中身が本当にその版のハッシュかは、この actor には確かめようが
  ない（engine の出力を持っているのは呼び出し側）。**確かめられないものを
  確かめたふりで通さない**ために、形式検査であることを名前と doc に書いておく。"
  [d]
  (boolean (and (string? d) (re-matches digest-re (str/lower-case d)))))

(defn plate-plan-printable?
  "engine が『刷れる』と言った版か。blocking 所見が 0 のときだけ真。"
  [{:keys [blocking]}]
  (and (number? blocking) (zero? blocking)))

(defn plate-plan-of
  "job に紐づいた版一式（無ければ nil）。"
  [job]
  (:plate-plan job))

(defn digest-matches?
  "刷り時に申告された digest が、承認済みの版のものと一致するか。"
  [job presented]
  (boolean (and presented
                (some-> (plate-plan-of job) :digest)
                (= (str/lower-case (str presented))
                   (str/lower-case (str (:digest (plate-plan-of job))))))))

;; ---------------------------------------------------------------- 校正

(defn proof-approved?
  "校正が**人によって**承認済みか。

  `:proof/approved-by` が無い承認は承認ではない —— この actor は校正を
  自分で確定できない（governor が always-escalate で止める）ので、承認者の
  名前が入っていることが『人が見た』ことの唯一の証拠になる。"
  [job]
  (boolean (and (at-least? job :proofed)
                (seq (str (get-in job [:proof :approved-by] ""))))))

;; ---------------------------------------------------------------- 数量

(def max-run-garments
  "1 回の刷り run で扱える枚数の上限。これを超える提案は人が見る。

  根拠は物流ではなく**取り返しのつかなさ**: 手刷り〜中規模の自動機で 1 run
  1000 枚を超えると、版ずれや色間違いに気付いたときには既に大量のボディが
  潰れている。上限そのものは運用で調整してよいが、**上限が無い状態にはしない**。"
  1000)

(defn run-size-exceeded?
  [{:keys [garments]}]
  (and (number? garments) (> garments max-run-garments)))

;; ---------------------------------------------------------------- 要約

(defn summarize
  "job の現在地を人が読める 1 行に。監査台帳と UI の両方が使う。"
  [job]
  (let [pp (plate-plan-of job)]
    (str (:id job)
         " / " (name (or (stage-of job) :unknown))
         (when pp (str " / 版 " (:plate-count pp) " 枚"
                       " choke " (:choke-mm pp) "mm"
                       " digest " (subs (str (:digest pp)) 0 (min 12 (count (str (:digest pp)))))))
         (when (proof-approved? job)
           (str " / 校正承認 " (get-in job [:proof :approved-by]))))))
