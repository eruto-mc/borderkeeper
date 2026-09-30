package net.erutobusiness.borderkeeper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * region ファイルの見出しから「原点まわりで<b>全部そろっている</b>半径」を測る。
 *
 * <p>⚠⚠ <b>なぜ要るか（2026-08-21）</b>: BorderKeeper はもともと
 * Chunky の申告（焼けたチャンク数）だけでボーダーを決めていた。⚠ <b>その数は
 * この世界のものとは限らない</b>——Chunky の進捗はワールドの外
 * （{@code config/chunky/tasks/}）に在るので、ワールドを差し替えても残る。
 * 2026-08-20 に前の世界の 45,727 チャンクを根拠にボーダーが 2866 まで開き、
 * 実在は 27,026 チャンク・全部そろう半径は ±1,184 だった。
 *
 * <p>⚠ <b>チャンク数では代わりにならない.</b> 同じ数でも、輪の形に焼けているのか
 * 穴だらけなのかで「安全に歩ける半径」は変わる。<b>形を見るしかない。</b>
 *
 * <p>⚠ <b>ここには Minecraft が出てこない</b>（{@link BorderPlan} と同じ方針）。
 * 引数はフォルダのパスだけなので、サーバを起動せずに確かめられる。
 */
public final class GeneratedExtent {

    /**
     * 測り直す間隔。⚠ <b>Chunky の進捗は何度も飛んでくる.</b>
     * そのたびに全リージョンを読むと、焼いている間ずっとディスクを舐めることになる。
     */
    private static final long CACHE_MS = 60_000L;

    /** パス -> {測った時刻, 半径ブロック}。⚠ 本体スレッドが {@link #cachedBlocks} で錠なしに読むので並行版。 */
    private static final Map<String, double[]> CACHE = new ConcurrentHashMap<>();

    /** 見に行くリージョンの上限。⚠ 事故で巨大な木を舐め続けないための止め。 */
    private static final int MAX_RADIUS_REGIONS = 2000;

    /**
     * 全部そろっている半径（ブロック）。⚠ <b>測れなければ 0</b>（＝呼ぶ側は抑えない）。
     *
     * <p>⚠ 「測れなかった」と「焼けていない」を同じ 0 で返しているのは、
     * どちらの場合も<b>抑える根拠が無い</b>から。縮める判断は
     * {@link BorderPlan#nextDiameter} が「根拠ゼロで縮めない」で受け止める。
     */
    public static synchronized double radiusBlocks(Path regionDir) {
        String key = regionDir.toString();
        double[] hit = CACHE.get(key);
        long now = System.currentTimeMillis();
        if (hit != null && now - (long) hit[0] < CACHE_MS) {
            return hit[1];
        }
        double r = measure(regionDir);
        CACHE.put(key, new double[]{now, r});
        return r;
    }

    /**
     * 控えに在る半径（ブロック）。⚠ <b>測らない・錠を取らない</b>（2026-10-01 追加）。控えが無ければ 0（＝抑えない）。
     *
     * <p>⚠ 本体スレッドの {@code apply()} はこちらを使う。{@link #radiusBlocks} は控えが切れていると
     * region を全部読むうえ {@code synchronized} なので、別スレッドが数えている間は本体スレッドが錠で待たされる。
     */
    public static double cachedBlocks(Path regionDir) {
        double[] hit = CACHE.get(regionDir.toString());
        return hit == null ? 0.0 : hit[1];
    }

    /**
     * ⚠ 試験から直接呼ぶ用（記録を挟まない）。
     *
     * <p>⚠⚠ <b>2026-10-01 に数え方を変えた</b>: それまではチャンクごとに {@code HashSet<Long>} へ入れていたが、
     * 鍵 {@code ((long) x << 32) ^ z} の {@code hashCode()} は x ^ z になり、±850 チャンクの四角では
     * 値が 2 千通りほどしか無い。⚠ <b>255 万件がほぼ同じ箱に落ち</b>、レンタルで 1 回 2 秒かかっていた
     * （spark の記録で本体スレッドを 1.8〜2.1 秒止めていた。時間の半分以上が {@code HashMap$TreeNode}）。
     * いまは region ごとの 1024 ビット（{@code long[16]}）を、region の座標の格子に並べて持つ。
     */
    static double measure(Path regionDir) {
        List<int[]> coords = new ArrayList<>();
        List<long[]> bits = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(regionDir, "r.*.mca")) {
            for (Path p : ds) {
                String[] parts = p.getFileName().toString().split("\\.");
                if (parts.length < 4) {
                    continue;
                }
                int rx;
                int rz;
                try {
                    rx = Integer.parseInt(parts[1]);
                    rz = Integer.parseInt(parts[2]);
                } catch (NumberFormatException e) {
                    continue;
                }
                byte[] head = new byte[4096];
                try (InputStream in = Files.newInputStream(p)) {
                    if (in.readNBytes(head, 0, 4096) < 4096) {
                        continue;               // ⚠ 見出しが揃っていない＝数えない
                    }
                } catch (IOException e) {
                    continue;
                }
                long[] b = new long[16];
                for (int j = 0; j < 1024; j++) {
                    int off = ((head[j * 4] & 0xFF) << 16)
                            | ((head[j * 4 + 1] & 0xFF) << 8)
                            | (head[j * 4 + 2] & 0xFF);
                    int len = head[j * 4 + 3] & 0xFF;
                    // ⚠ 長さ 0 の見出しは「場所は取ったが中身が無い」。数えない
                    if (off != 0 && len != 0) {
                        b[j >> 6] |= 1L << (j & 63);
                    }
                }
                coords.add(new int[]{rx, rz});
                bits.add(b);
            }
        } catch (IOException | RuntimeException e) {
            return 0.0;                          // ⚠ 測れない＝抑えない
        }
        Grid g = new Grid(coords, bits);
        if (!g.has(0, 0)) {
            return 0.0;
        }
        // ⚠ **輪ごとに見る.** 毎回四角を全部見ると半径の3乗の手間になり、
        //    本番の広さ（半径 750 チャンク見込み）で現実的でなくなる。
        int r = 0;
        while (r < MAX_RADIUS_REGIONS && ringComplete(g, r + 1)) {
            r++;
        }
        return r * 16.0;
    }

    /** region の座標の格子に、region ごとの 1024 ビットを並べたもの。 */
    private static final class Grid {
        private final int minX;
        private final int minZ;
        private final int w;
        private final int h;
        private final long[][] cells;

        Grid(List<int[]> coords, List<long[]> bits) {
            int x0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
            for (int[] c : coords) {
                x0 = Math.min(x0, c[0]);
                z0 = Math.min(z0, c[1]);
                x1 = Math.max(x1, c[0]);
                z1 = Math.max(z1, c[1]);
            }
            if (coords.isEmpty()) {
                x0 = z0 = 0;
                x1 = z1 = -1;
            }
            minX = x0;
            minZ = z0;
            w = x1 - x0 + 1;
            h = z1 - z0 + 1;
            cells = new long[Math.max(0, w) * Math.max(0, h)][];
            for (int i = 0; i < coords.size(); i++) {
                int[] c = coords.get(i);
                cells[(c[0] - minX) * h + (c[1] - minZ)] = bits.get(i);
            }
        }

        /** チャンク (cx, cz) が在るか。⚠ 負の座標も算術シフトと下位 5 ビットで region と中の位置に分かれる。 */
        boolean has(int cx, int cz) {
            int ix = (cx >> 5) - minX;
            int iz = (cz >> 5) - minZ;
            if (ix < 0 || iz < 0 || ix >= w || iz >= h) {
                return false;
            }
            long[] b = cells[ix * h + iz];
            if (b == null) {
                return false;
            }
            int j = (cx & 31) + ((cz & 31) << 5);
            return (b[j >> 6] & (1L << (j & 63))) != 0;
        }
    }

    private static boolean ringComplete(Grid g, int rr) {
        for (int x = -rr; x <= rr; x++) {
            if (!g.has(x, -rr) || !g.has(x, rr)) {
                return false;
            }
        }
        for (int z = -rr + 1; z <= rr - 1; z++) {
            if (!g.has(-rr, z) || !g.has(rr, z)) {
                return false;
            }
        }
        return true;
    }

    private GeneratedExtent() {
    }
}
