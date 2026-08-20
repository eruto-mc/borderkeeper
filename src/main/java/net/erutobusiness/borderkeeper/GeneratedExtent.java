package net.erutobusiness.borderkeeper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

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

    /** パス -> {測った時刻, 半径ブロック}。 */
    private static final Map<String, double[]> CACHE = new HashMap<>();

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

    /** ⚠ 試験から直接呼ぶ用（記録を挟まない）。 */
    static double measure(Path regionDir) {
        Set<Long> present = new HashSet<>();
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
                for (int j = 0; j < 1024; j++) {
                    int off = ((head[j * 4] & 0xFF) << 16)
                            | ((head[j * 4 + 1] & 0xFF) << 8)
                            | (head[j * 4 + 2] & 0xFF);
                    int len = head[j * 4 + 3] & 0xFF;
                    // ⚠ 長さ 0 の見出しは「場所は取ったが中身が無い」。数えない
                    if (off != 0 && len != 0) {
                        present.add(key(rx * 32 + (j % 32), rz * 32 + (j / 32)));
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            return 0.0;                          // ⚠ 測れない＝抑えない
        }
        if (!present.contains(key(0, 0))) {
            return 0.0;
        }
        // ⚠ **輪ごとに見る.** 毎回四角を全部見ると半径の3乗の手間になり、
        //    本番の広さ（半径 750 チャンク見込み）で現実的でなくなる。
        int r = 0;
        while (r < MAX_RADIUS_REGIONS && ringComplete(present, r + 1)) {
            r++;
        }
        return r * 16.0;
    }

    private static boolean ringComplete(Set<Long> present, int rr) {
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

    private GeneratedExtent() {
    }
}
