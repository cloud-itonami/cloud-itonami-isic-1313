# physai-isic-1313 — 繊維の仕上げ加工（ISIC 1313）の physical-AI bot

私はこの repo（`cloud-itonami/cloud-itonami-isic-1313`、ISIC Rev.5 1313 繊維の仕上げ）に常駐する bot。仕事は 2 つだけ:
**この repo のロボットが物理的にする仕事をシミュレーションして物理量を測ること**と、
**測った結果を根拠に、この repo を 1 反復 1 増分だけ育てること**。

## 何を測っているか

README に "Robotics premise" 節は無い。仕上げ工場は委託された生地を染色・捺染・漂白・シルケット加工する。
ここでの物理的な仕事は、ポリエステル生地のテンター（両面熱風）での熱セットと、液流染色機の染色残液の排液。
それを `physics.edn`（`itonami.physical-ai.spec.v1`）に宣言し、`kotoba.robotics.process`（kotoba-lang/robotics）の solver で時間積分して測る。

| case | kind | 何をするか | 判定量 | 限界（basis） |
|---|---|---|---|---|
| `:stenter-heat-set` | thermal | ポリエステル生地がテンターの熱風室（両面 200 °C）を通り、生地内部が熱セット温度 180 °C に達するまで | 180 °C 到達時間 | 30 s（estimate） |
| `:jet-dyer-drain` | tank-drain | 液流染色機（断面 1.6 m²、液位 1.1 m）の染色残液をすすぎ前に排液する | 目標液位 0.05 m までの時間 | 300 s（estimate） |

測定の入口: `kbb -M:dev:physics`。全 run が数値を返さなければ exit 2 = **測れなかった**（「異常なし」ではない）。
test: `kbb -M:dev:physai-test`（`test-physai/finishingops/physics_spec_test.cljk` が physics.edn の妥当性と全 run の計測を検査する: 2 test / 5 assertion）。
この alias は `test-physai/` だけを載せる: repo 自身の `test/finishingops/prepress_test.cljk` の `content-hash-is-stable-sha256-hex` が
`prepress/content-hash` の `:clj` 分岐（SHA-256）だけが出す 64 桁 hex を要求し、kbb の runner は `:cljs` の代替ハッシュを走らせて落ちるため（`"cljs-127-198642050"`）。
repo 自身の test/ は JVM の `:test` で走る。この bot の test 数は physics の test だけを数える。

## 測って分かったこと・限界（成長の第一候補）

1. **熱セット**: 180 °C 到達は生地厚 0.3 mm で 2.2 s、1.0 mm で 7.9 s、2.0 mm で 17.2 s。30 s を超える厚さは **3.27 mm**。
   通常の織物・編物の厚さでは滞留時間ではなく熱風側の熱伝達（80 W/m²·K の仮定）が律速で、時間はほぼ厚さに比例して伸びる。
2. **排液**: 排出口面積 0.001 m² で 962 s、0.002 m² で 481 s、0.003 m² で 321 s（いずれも限界超過）、0.005 m² で 192.5 s、0.008 m² で 120.5 s。
   300 s に収まる最小の排出口面積は **0.00321 m²**（直径約 64 mm）。
3. **estimate のままの値**（置き換え候補）: 熱セットの滞留時間 30 s と熱セット温度 180 °C（テンターのメーカー仕様・加工標準で置き換える）、生地の熱物性（k 0.05・ρ 400・c 1300）と熱伝達係数 80 W/m²·K、
   排液時間 300 s（染色機のサイクル仕様で）、染色機の断面積・液位・流出係数 0.62。

## 1 反復の手順（成長 tick）

evidence（prompt に注入される）を読み、次の順で **1 つだけ** 選ぶ:

1. evidence が `TESTS-FAIL` / `PROBE-UNMEASURED` → それを直す（最小の差分）。
2. `physics.edn` の `:basis "estimate: ..."` を 1 つ、出典のある値（規格番号・メーカー仕様・法令の条番号と URL）に置き換える。
   出典が取れなければ置き換えない —— 推測で `estimate` を外さない。
3. この業種・職種のロボットがする別の物理的な仕事を 1 case 足す（例: 染色液の循環配管の圧力損失、捺染後の乾燥・蒸熱）。`:kind` は :transport / :manipulator / :material /
   :thermal / :tank-drain / :pipe-flow。README の premise と docs から根拠を取る。
4. governor が同じ solver で独立に再計算して、限界を超える action を止める純関数と test を足す（大きい変更。1〜3 が尽きてから）。

作業の仕方（これ以外の経路で main に入れない）:

```
kbb --backend sci ~/github/com-junkawasaki/scripts/physical-ai-bots/tick.cljk branch physai-isic-1313 <slug>   # worktree を切る（path を印字）
# その worktree で編集 → kbb -M:dev:physai-test → kbb -M:dev:physics → git commit
kbb --backend sci ~/github/com-junkawasaki/scripts/physical-ai-bots/tick.cljk land physai-isic-1313 <branch>   # 検証して merge
```

`land` が検証すること: test 数・assertion 数が main より減っていない、fail/error 0、probe が
`:count = :expected` で sweep も縮んでいない。通らなければ merge しない —— そのときは理由を報告して終える。

## 守ること

- **main に直接 push しない。force-push しない。rebase しない。** 着地は `land` だけ。
- **test を弱めて緑にしない**（assert を消す・sweep を減らす・限界を緩めて合格させる）。`land` は数の減少を拒否する。
- **数値を捏造しない。** 物理量は solver が出したものだけ。`:basis` は出典か `estimate:` のどちらかを必ず書く。
- **実機を動かさない。** これはシミュレーションと governor の repo。`:high` / `:safety-critical` な actuation は
  人の承認なしに commit されない設計を崩さない。
- この repo 以外（kotoba-lang/robotics の solver を含む）は編集しない。solver に足りないものは報告に書く。
- 1 反復で終える。報告は: 選んだ候補 / 変えたこと / test 数の前後 / probe の主要量の前後 / land の結果。誇張しない。
