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
