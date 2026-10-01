package net.erutobusiness.borderkeeper;

/**
 * {@link BorderPlan} の机上試験。⚠ <b>サーバもマイクラも要らない。</b>
 *
 * <p>⚠⚠ <b>なぜ 2026-08-26 に書いたか</b>: BorderPlan の冒頭は
 * 「判断だけを切り離してあるので、サーバを起動せずに机上で確かめられる」と宣言しているのに、
 * <b>試験が1本も無かった</b>。その状態で本番のレンタルサーバに Chunky のタスクを入れたところ、
 * ⚠ <b>ボーダーが 7,632 → 256 に縮んだ</b>（在室0人だったので実害は無し）。
 *
 * <p>⚠ <b>この試験は「その事故を再現する陽性対照」を先頭に置く。</b>
 * 直したつもりの実装が事故を再現しなくなったことを、機械で見分けられるようにする。
 *
 * <p>走らせ方（Gradle も Forge も要らない）:
 * <pre>
 *   javac -d /tmp/bp src/main/java/net/erutobusiness/borderkeeper/BorderPlan.java \
 *                    src/test/java/net/erutobusiness/borderkeeper/BorderPlanSelfTest.java
 *   java -cp /tmp/bp net.erutobusiness.borderkeeper.BorderPlanSelfTest
 * </pre>
 */
public final class BorderPlanSelfTest {

    private static int failed = 0;
    private static int passed = 0;

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("  OK   " + name);
        } else {
            failed++;
            System.out.println("  !! NG " + name);
            System.out.println("        " + detail);
        }
    }

    /** 実際に走っている値（2026-08-26 のレンタルサーバから採った）。 */
    private static final int VIEW_DISTANCE = 10;
    private static final int MARGIN = BorderPlan.margin(VIEW_DISTANCE);      // 192

    public static void main(String[] args) {
        System.out.println("BorderPlan 机上試験（余白 " + MARGIN + " ブロック）");
        System.out.println();

        // ── 前提の確認（ここがずれていたら以下の数字が全部無意味）──────────────
        System.out.println("== 前提 ==");
        check("余白は (view-distance + 2) * 16", MARGIN == 192, "得た値 " + MARGIN);
        // 8 * sqrt(2567) * 0.95 - 192 = 193.0... → 直径 386。実ログの「321 → 386」と一致する
        double r2567 = BorderPlan.allowedRadius(2567L, false, MARGIN);
        check("2567 チャンク → 半径 193 前後（実ログの 386 直径と合う）",
              Math.abs(r2567 - 193.0) < 2.0, "得た値 " + r2567);
        System.out.println();

        // ── 陽性対照: 2026-08-26 の事故そのもの ─────────────────────────────
        //
        // 本番の状態:
        //   ボーダー直径          7632（手元から持ち込んだまま。BorderKeeper は一度も検算していない）
        //   オーバーワールド      ディスク実測 4176 ブロック / 入れ直したタスクの進捗 8548 チャンク
        //   ネザー                ディスク実測 0（＝測れない）/ 進捗 2 チャンク
        System.out.println("== 陽性対照: 2026-08-26 の事故を再現する ==");
        double owAllowed = BorderPlan.clampToGenerated(
                BorderPlan.allowedRadius(8548L, false, MARGIN), 4176.0, MARGIN);
        double netherAllowed = BorderPlan.clampToGenerated(
                BorderPlan.allowedRadius(2L, false, MARGIN), 0.0, MARGIN);
        int want = BorderPlan.overworldDiameter(owAllowed, netherAllowed);
        Integer next = BorderPlan.nextDiameter(7632.0, want, true);

        check("いまの実装は 直径 256 を出す（＝事故が再現する）",
              want == 256, "得た値 " + want);
        check("しかも nextDiameter が**通してしまう**（縮める向きなのに）",
              next != null && next == 256,
              "得た値 " + next + "（null なら止まっている）");
        System.out.println("     ⚠ 縮む理由はオーバーワールドではなく **ネザー**——");
        System.out.println("        オーバーワールドで許せる半径 " + (int) owAllowed
                + " ／ ネザー " + (int) netherAllowed + "（×8 して比べるので 0）");
        System.out.println("        → 全次元の最小が 0 になり MIN_RADIUS "
                + BorderPlan.MIN_RADIUS + " まで落ちる");
        System.out.println();

        // ── ここから「直したあとに満たすべきこと」──────────────────────────
        //
        // ⚠⚠ **規則を1本にする**（2026-08-26）:
        //     「ディスクで実測できたなら、それが正。天井にも床にもする。」
        //   これで過去2件の事故が**両方**受け止められる:
        //     2026-08-21（開きすぎ）… 実測が**天井**として効く → 縮む（正しい）
        //     2026-08-26（進捗が0に）… 実測が**床**として効く → 縮まない（正しい）
        System.out.println("== 直したあとに満たすこと: 実測できたら実測が正 ==");
        // 進捗は小さい（8548 チャンク → 510）が、ディスクには 4176 ブロックぶん在る
        double twoSided = BorderPlan.clampToGenerated(510.0, 4176.0, MARGIN);
        check("進捗が小さくても、実測（4176）まで下げない＝床として効く",
              Math.abs(twoSided - (4176.0 - MARGIN)) < 1.0,
              "得た値 " + twoSided + "（期待 " + (4176.0 - MARGIN) + "）");
        // 進捗が大きすぎても、実測で抑える（2026-08-21 と同じ形）
        double stillCapped = BorderPlan.clampToGenerated(9999.0, 1184.0, MARGIN);
        check("進捗が大きすぎても、実測（1184）で抑える＝天井として効く",
              Math.abs(stillCapped - (1184.0 - MARGIN)) < 1.0,
              "得た値 " + stillCapped);
        System.out.println();

        // ⚠⚠ **ネザーが縛る**（2026-08-26 に実物で確かめた）。
        //   ネザーは region が4個しか無く、実測は約 496 ブロック。
        //   規則「全次元の最小」により、オーバーワールドのボーダーもそこに縛られる。
        //   ⚠ **これは不具合ではなく設計どおり**——ポータルの行き先が丸められないための規則。
        //   ⚠ だから **ネザーを事前生成しない限り 7,632 は戻らない**。ここを試験で固定しておく。
        System.out.println("== ネザーが縛ることを固定する（設計どおり・不具合ではない） ==");
        double owMeasured = BorderPlan.clampToGenerated(510.0, 4176.0, MARGIN);
        double netherMeasured = BorderPlan.clampToGenerated(0.0, 496.0, MARGIN);
        int withNether = BorderPlan.overworldDiameter(owMeasured, netherMeasured);
        check("ネザー 496 ブロックだと、オーバーワールドは直径 4,864 前後までしか許されない",
              Math.abs(withNether - 4864) <= 32, "得た値 " + withNether);
        // ネザーを 669 ブロック（＝ 3816/8 + 余白）まで焼けば、7,632 が許される
        double netherEnough = BorderPlan.clampToGenerated(0.0, 669.0, MARGIN);
        int withEnoughNether = BorderPlan.overworldDiameter(owMeasured, netherEnough);
        check("ネザーを 669 ブロックまで焼けば 7,632 以上が許される",
              withEnoughNether >= 7632, "得た値 " + withEnoughNether);
        System.out.println("     ⚠ ネザー 669 ブロック ＝ 42 チャンク半径 ＝ 約 7,200 チャンク（短時間で焼ける）");
        System.out.println();

        // ⚠⚠ **人が居る間はネザーに縛らせない**（2026-08-26 追加）。
        //
        // ⚠ **なぜ要るか**: 無人時に両次元を並行で焼くと、ネザーが追いつくまでの数分間、
        //   全次元の最小の規則でボーダーが小さくなる。⚠ **無人ならそれでよい。**
        //   ところが**その最中に部員が入ってくると Chunky Autopause が事前生成を止める**ので、
        //   ⚠⚠ **ボーダーが小さいまま固定される**。2026-08-26 に実際に 512 で固定され、
        //   遊んでいた場所までボーダーの外になった。
        //
        // ⚠ **人が居る間はオーバーワールドだけで決める**——オーバーワールドの土地は
        //   実測で「全部そろっている」ことが分かっているので、そこまで開くのは安全。
        //   ネザーは自分のボーダー（÷8）が狭いままになるだけで、誰も締め出さない。
        System.out.println("== 人が居る間はネザーに縛らせない ==");
        double owOk = 3984.0;        // オーバーワールドの実測から（4176 - 192）
        double netherBehind = 32.0;  // ネザーは追いついていない（実測 224 - 192）
        check("無人なら、ネザーに縛られて小さくなる（設計どおり）",
              BorderPlan.overworldDiameter(owOk, netherBehind, false) == 512,
              "得た値 " + BorderPlan.overworldDiameter(owOk, netherBehind, false));
        check("⚠ 人が居るなら、オーバーワールドだけで決める（縮まない）",
              BorderPlan.overworldDiameter(owOk, netherBehind, true) == 7968,
              "得た値 " + BorderPlan.overworldDiameter(owOk, netherBehind, true));
        check("人が居ても、オーバーワールドの実測が小さければ小さくなる（＝止めすぎない）",
              BorderPlan.overworldDiameter(500.0, 999.0, true) == 1000,
              "得た値 " + BorderPlan.overworldDiameter(500.0, 999.0, true));
        check("引数2つの古い形は「無人」と同じ（既存の呼び出しを壊さない）",
              BorderPlan.overworldDiameter(owOk, netherBehind)
                      == BorderPlan.overworldDiameter(owOk, netherBehind, false),
              "食い違った");
        System.out.println();

        // ── 陰性対照1: 2026-08-21 の事故（開きすぎ）が再発しないこと ──────────
        //
        // 前の世界の進捗 45,727 チャンクが残ったまま、実在は「全部そろう半径 ±1,184」だった。
        System.out.println("== 陰性対照1: 前の世界の進捗で**開きすぎない** ==");
        double staleAllowed = BorderPlan.clampToGenerated(
                BorderPlan.allowedRadius(45727L, false, MARGIN), 1184.0, MARGIN);
        check("ディスク実測（1184）で上から抑えられる",
              Math.abs(staleAllowed - (1184.0 - MARGIN)) < 1.0,
              "得た値 " + staleAllowed);
        System.out.println();

        // ── 陰性対照2: 測れなかったときは抑えない ───────────────────────────
        System.out.println("== 陰性対照2: ディスクを測れなかったら抑えない ==");
        double unmeasured = BorderPlan.clampToGenerated(2000.0, 0.0, MARGIN);
        check("測れない（0）ときは申告のまま", Math.abs(unmeasured - 2000.0) < 0.001,
              "得た値 " + unmeasured);
        System.out.println();

        // ── 陰性対照3: 根拠が無ければ縮めない ──────────────────────────────
        System.out.println("== 陰性対照3: 根拠ゼロなら縮めない ==");
        check("根拠なし＋縮める向き → 動かさない",
              BorderPlan.nextDiameter(7632.0, 256, false) == null, "動いてしまった");
        check("根拠なしでも**広げる**向きは通す",
              BorderPlan.nextDiameter(256.0, 7632, false) != null, "止まってしまった");
        System.out.println();

        // ── 陰性対照4: 64 ブロック以内は動かさない ─────────────────────────
        System.out.println("== 陰性対照4: 誤差では動かさない ==");
        check("差 64 以内は動かさない",
              BorderPlan.nextDiameter(7632.0, 7600, true) == null, "動いてしまった");
        System.out.println();

        // ── 陰性対照5: ネザーは常にオーバーワールド ÷ 8 ────────────────────
        System.out.println("== 陰性対照5: ネザー = オーバーワールド ÷ 8 ==");
        check("7632 → 954", BorderPlan.netherDiameter(7632) == 954,
              "得た値 " + BorderPlan.netherDiameter(7632));
        System.out.println();

        System.out.println("判定: OK " + passed + " 件 / NG " + failed + " 件");
        System.exit(failed == 0 ? 0 : 1);
    }

    private BorderPlanSelfTest() {
    }
}
