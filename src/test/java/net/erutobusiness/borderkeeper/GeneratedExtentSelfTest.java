package net.erutobusiness.borderkeeper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

/**
 * {@link GeneratedExtent} の机上試験（2026-10-01）。⚠ <b>サーバもマイクラも要らない。</b>
 *
 * <p>⚠ なぜ書いたか: 数え方を「チャンクごとの {@code HashSet<Long>}」から「region ごとの 1024 ビット」へ変えた。
 * ⚠ <b>前の数え方をこの中へそのまま写し</b>、乱数で作った世界で同じ半径を返すかを突き合わせる。
 *
 * <p>走らせ方:
 * <pre>
 *   javac -d /tmp/ge src/main/java/net/erutobusiness/borderkeeper/GeneratedExtent.java \
 *                    src/test/java/net/erutobusiness/borderkeeper/GeneratedExtentSelfTest.java
 *   java -cp /tmp/ge net.erutobusiness.borderkeeper.GeneratedExtentSelfTest
 * </pre>
 */
public final class GeneratedExtentSelfTest {

    private static int failed = 0;
    private static int passed = 0;

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("  OK   " + name + "（" + detail + "）");
        } else {
            failed++;
            System.out.println("  NG   " + name + "（" + detail + "）");
        }
    }

    /** region ファイルを見出しだけ書く。present[j] が真の所に「中身が在る」印を付ける。 */
    private static void writeRegion(Path dir, int rx, int rz, boolean[] present) throws IOException {
        byte[] head = new byte[4096];
        for (int j = 0; j < 1024; j++) {
            if (present[j]) {
                head[j * 4 + 2] = 2;         // 場所 2（0 でなければよい）
                head[j * 4 + 3] = 1;         // 長さ 1
            }
        }
        Files.write(dir.resolve("r." + rx + "." + rz + ".mca"), head);
    }

    /** ⚠ 2026-10-01 より前の数え方（1.5.2 の GeneratedExtent.measure をそのまま写した）。 */
    private static double oldMeasure(Path regionDir) throws IOException {
        Set<Long> present = new HashSet<>();
        try (var ds = Files.newDirectoryStream(regionDir, "r.*.mca")) {
            for (Path p : ds) {
                String[] parts = p.getFileName().toString().split("\\.");
                int rx = Integer.parseInt(parts[1]);
                int rz = Integer.parseInt(parts[2]);
                byte[] head = Files.readAllBytes(p);
                for (int j = 0; j < 1024; j++) {
                    int off = ((head[j * 4] & 0xFF) << 16) | ((head[j * 4 + 1] & 0xFF) << 8) | (head[j * 4 + 2] & 0xFF);
                    int len = head[j * 4 + 3] & 0xFF;
                    if (off != 0 && len != 0) {
                        present.add(key(rx * 32 + (j % 32), rz * 32 + (j / 32)));
                    }
                }
            }
        }
        if (!present.contains(key(0, 0))) {
            return 0.0;
        }
        int r = 0;
        while (r < 2000 && oldRing(present, r + 1)) {
            r++;
        }
        return r * 16.0;
    }

    private static boolean oldRing(Set<Long> present, int rr) {
        for (int x = -rr; x <= rr; x++) {
            if (!present.contains(key(x, -rr)) || !present.contains(key(x, rr))) {
                return false;
            }
        }
        for (int z = -rr + 1; z <= rr - 1; z++) {
            if (!present.contains(key(-rr, z)) || !present.contains(key(rr, z))) {
                return false;
            }
        }
        return true;
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    /** 原点を中心に半径 rc チャンクまで全部在る世界（region は ±rr 本）。hole が非 null なら1チャンク抜く。 */
    private static Path squareWorld(int rr, int rc, int[] hole) throws IOException {
        Path dir = Files.createTempDirectory("ge-test");
        for (int rx = -rr; rx < rr; rx++) {
            for (int rz = -rr; rz < rr; rz++) {
                boolean[] present = new boolean[1024];
                for (int j = 0; j < 1024; j++) {
                    int cx = rx * 32 + (j % 32);
                    int cz = rz * 32 + (j / 32);
                    present[j] = Math.max(Math.abs(cx), Math.abs(cz)) <= rc
                            && !(hole != null && cx == hole[0] && cz == hole[1]);
                }
                writeRegion(dir, rx, rz, present);
            }
        }
        return dir;
    }

    public static void main(String[] args) throws IOException {
        // ① 形が決まっている世界
        Path a = squareWorld(2, 40, null);
        check("半径 40 チャンクの四角", GeneratedExtent.measure(a) == 40 * 16.0, "得た " + GeneratedExtent.measure(a));
        Path b = squareWorld(2, 40, new int[]{-17, 25});
        check("⚠ 負の座標の穴（-17, 25）で止まる", GeneratedExtent.measure(b) == 24 * 16.0,
              "得た " + GeneratedExtent.measure(b) + "（25 の輪に穴＝24 まで）");
        Path c = squareWorld(2, 40, new int[]{0, 0});
        check("原点が無ければ 0", GeneratedExtent.measure(c) == 0.0, "得た " + GeneratedExtent.measure(c));
        Path empty = Files.createTempDirectory("ge-empty");
        check("region が 1 本も無ければ 0", GeneratedExtent.measure(empty) == 0.0, "得た " + GeneratedExtent.measure(empty));

        // ② 乱数で作った世界で、前の数え方と同じ答えか（負の座標・端・穴を混ぜる）
        Random rnd = new Random(20261001L);
        int same = 0;
        int trials = 40;
        for (int t = 0; t < trials; t++) {
            int rc = 5 + rnd.nextInt(90);
            int[] hole = rnd.nextBoolean() ? new int[]{rnd.nextInt(2 * rc + 1) - rc, rnd.nextInt(2 * rc + 1) - rc} : null;
            Path w = squareWorld(3, rc, hole);
            if (GeneratedExtent.measure(w) == oldMeasure(w)) {
                same++;
            }
        }
        check("乱数の世界 " + trials + " 個で前の数え方と同じ半径", same == trials, same + " / " + trials);

        // ③ 本番に近い広さ（region 50×50＝2,500 本・半径 780 チャンク）で時間を比べる
        Path big = squareWorld(25, 780, null);
        long t0 = System.nanoTime();
        double nw = GeneratedExtent.measure(big);
        long msNew = (System.nanoTime() - t0) / 1_000_000L;
        t0 = System.nanoTime();
        double od = oldMeasure(big);
        long msOld = (System.nanoTime() - t0) / 1_000_000L;
        check("本番に近い広さで同じ半径", nw == od && nw == 780 * 16.0, "新 " + nw + " ／ 前 " + od);
        System.out.println("  時間: 新しい数え方 " + msNew + " ms ／ 前の数え方 " + msOld + " ms（region 2,500 本）");

        System.out.println();
        System.out.println(failed == 0 ? "OK: " + passed + " 件すべて通った" : "NG: " + failed + " 件");
        System.exit(failed == 0 ? 0 : 1);
    }

    private GeneratedExtentSelfTest() {
    }
}
