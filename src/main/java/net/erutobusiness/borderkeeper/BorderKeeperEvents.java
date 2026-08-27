package net.erutobusiness.borderkeeper;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.popcraft.chunky.api.ChunkyAPI;
import org.popcraft.chunky.api.event.task.GenerationProgressEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Chunky の進捗を受け取り、ワールドボーダーを追随させる。
 *
 * <p>⚠ <b>進捗イベントはワーカースレッドから来る。</b> ボーダーの操作は
 * {@code server.execute(...)} でサーバスレッドへ渡す。
 */
@Mod.EventBusSubscriber(modid = BorderKeeper.MODID)
public final class BorderKeeperEvents {

    /** ワールド全体（全次元の合計）に使ってよい容量。⚠ 上限は半径ではなく容量で決める。 */
    private static final double BUDGET_GB = 45.0;

    /**
     * ⚠ <b>容量の再測定は重い</b>（ワールドのフォルダを歩く）ので、この間隔でしかやらない。
     * その間は最後に測った値を使う。
     */
    private static final long SIZE_RECHECK_MS = 10 * 60 * 1000L;

    private static final ResourceLocation OVERWORLD = new ResourceLocation("minecraft", "overworld");
    private static final ResourceLocation NETHER = new ResourceLocation("minecraft", "the_nether");

    /** 次元ごとの「焼けたチャンク数」。Chunky のイベントが運んでくる生の値。 */
    private static final Map<String, Long> BURNED = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> DONE = new ConcurrentHashMap<>();

    private static final AtomicLong lastSizeCheck = new AtomicLong(0);
    private static volatile double worldGb = -1.0;
    private static volatile boolean paused = false;
    private static volatile MinecraftServer server;

    /**
     * ⚠⚠ <b>事前生成専用のワールドでは何もしない</b>（2026-08-19 に踏んだ）。
     *
     * <p>この mod は「焼けた範囲より内側にボーダーを置く」ことで、部員が未生成の地形へ
     * 出られないようにする。<b>遊ぶ世界では正しい</b>。
     *
     * <p>⚠ 事前生成だけを目的としたサーバでは、それが<b>生成の邪魔になる</b>。実測:
     * 半径1000の焼きで、Chunky が 3617 チャンク（＝半径 481 ブロック相当）まで進んだ
     * 時点で最初の更新が走り、ボーダーが <b>59999968 → 594</b>（半径 297 ブロック
     * ＝18.6 チャンク）へ縮んだ。すでに焼けている範囲より内側である。
     * その瞬間に生成中だった輪が失われ、<b>原点から 14〜19 チャンクの帯 715 個</b>が
     * 世界から欠けた（22,201 のはずが 21,486）。
     * 欠けた場所に村が1つあり、<b>「川の水が消えた」ように見えて実際は地形が無かった</b>。
     *
     * <p>それまでの焼きで出なかったのは競合だったから。起動が遅く機械を分け合った回だけ、
     * Chunky が先に進みすぎて露見した。⚠ <b>時々しか出ない不具合は、出ないほうが危ない。</b>
     *
     * <p>判定は世界の名前で行う。{@code burn_seed_worlds.py} は必ず {@code burn-} で
     * 始まる level-name を作るので、これで事前生成専用だと分かる。
     */
    private static boolean isPregenOnlyWorld(MinecraftServer s) {
        String name = s.getWorldData().getLevelName();
        return name != null && name.startsWith("burn-");
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        server = event.getServer();
        if (isPregenOnlyWorld(event.getServer())) {
            BorderKeeper.LOGGER.info("Border Keeper: 事前生成専用のワールド（{}）なので"
                            + "ボーダーには触らない",
                    event.getServer().getWorldData().getLevelName());
            server = null;
            return;
        }
        ChunkyAPI a = api();
        if (a == null) {
            // ⚠ 黙って何もしないのが一番危ない。理由を1行出す
            BorderKeeper.LOGGER.warn("Border Keeper: Chunky の API を掴めなかった。"
                    + "ボーダーは追随しない");
            return;
        }
        a.onGenerationProgress(BorderKeeperEvents::onProgress);
        BorderKeeper.LOGGER.info("Border Keeper: Chunky の進捗を購読した（予算 {} GB）", BUDGET_GB);
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        server = null;
        BURNED.clear();
        DONE.clear();
    }

    /**
     * ⚠⚠ <b>部員が入ってきたら、その場でボーダーを計算し直す</b>（2026-08-26 追加）。
     *
     * <p>⚠ <b>なぜ要るか</b>: この mod は <b>Chunky の進捗イベントでしか動かない</b>。
     * ところが Chunky Autopause は<b>人が入ると事前生成を止める</b>ので、
     * ⚠⚠ <b>そこから進捗イベントが1つも来なくなる</b>。
     *
     * <p>つまり「無人のあいだにネザーが追いつくまで一時的に狭くなっていた」状態のまま
     * 部員が入ってくると、⚠ <b>狭いボーダーがそのまま固定される</b>。
     * 2026-08-26 に実際に <b>512 で固定</b>され、部員1人が自分の居場所ごとボーダーの外になった。
     *
     * <p>⚠ 入った瞬間に計算し直せば、{@link BorderPlan#overworldDiameter} の
     * 「人が居る間はオーバーワールドだけで決める」が効いて<b>広い側へ戻る</b>。
     *
     * <p>⚠ <b>広げるだけの向きではない.</b> オーバーワールドの実測そのものが小さければ縮む
     * （2026-08-21 の「開きすぎ」を直す道を塞がないため）。
     *
     * <p>⚠ <b>サーバスレッドへ渡す。</b> このイベント自体はサーバスレッドで来るが、
     * <b>この時点で参加者が一覧に入っているとは限らない</b>ので、
     * {@code execute} で次の tick へ回して<b>確実に数えられる状態</b>にしてから計算する。
     */
    @SubscribeEvent
    public static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        MinecraftServer s = server;
        if (s == null) {
            return;
        }
        // ⚠⚠ **本体スレッドでディスクを読まない**（2026-08-28 に本番で固めた）。
        //    `apply()` は `GeneratedExtent.radiusBlocks()` を呼び、**region ファイルを全部読む**。
        //    レンタルでは 574 本・7.9GB あり、⚠ **部員が入るたびに本体スレッドが数分止まった**
        //    （`Timed out waiting for world statistics` が並び、⚠ **その部員自身が入れなくなる**）。
        //    ⚠ 起きた並び:
        //      01:55:47.151 joined the game
        //      01:55:47.336 Border Keeper: 部員が入ったのでボーダーを計算し直す
        //      01:56:19     Timed out waiting for world statistics（以後ずっと）
        //
        // ⚠ **測るのは別スレッド、ボーダーを触るのは本体スレッド**に分ける。
        //    `GeneratedExtent` は 60 秒の控えを持つので、先に温めておけば
        //    ⚠ **`apply()` の中の測定は控えに当たって一瞬で返る**。
        net.minecraft.Util.backgroundExecutor().execute(() -> {
            long t0 = System.nanoTime();
            warmGeneratedExtent(s);
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            s.execute(() -> {
                BorderKeeper.LOGGER.info(
                        "Border Keeper: 部員が入ったのでボーダーを計算し直す"
                                + "（生成済みの測定は別スレッドで {} ms）", ms);
                apply(s);
            });
        });
    }

    /**
     * ⚠ <b>ディスクを読む所だけを別スレッドで先に済ませる</b>（2026-08-28 追加）。
     *
     * <p>⚠ ここでは**ボーダーを1つも触らない**。触るのは本体スレッドの {@link #apply}。
     * ⚠ 失敗しても黙って戻る——測れなければ `apply()` 側が 0 として扱い、抑えないだけ。
     */
    private static void warmGeneratedExtent(MinecraftServer s) {
        try {
            generatedRadius(s.getLevel(Level.OVERWORLD));
            generatedRadius(s.getLevel(Level.NETHER));
        } catch (RuntimeException e) {
            BorderKeeper.LOGGER.warn("Border Keeper: 生成済みの先読みに失敗した: {}", e.toString());
        }
    }

    /**
     * ⚠ ワーカースレッドから呼ばれる。ボーダー操作はここでやらない。
     *
     * <p>⚠⚠ <b>ディスクを読む所は、ここ（ワーカー）で済ませる</b>（2026-08-28 追加）。
     * ⚠ 以前は `apply()` の中で本体スレッドが region を読んでおり、
     * ⚠ **控えの 60 秒が切れた回だけ、本体スレッドが数百本の region を読んで止まっていた**。
     * ⚠ ここで温めておけば、`apply()` の測定は控えに当たる。
     */
    private static void onProgress(GenerationProgressEvent e) {
        BURNED.put(e.world(), e.chunks());
        DONE.put(e.world(), e.complete());
        MinecraftServer s = server;
        if (s != null) {
            warmGeneratedExtent(s);
            s.execute(() -> apply(s));
        }
    }

    private static void apply(MinecraftServer s) {
        ServerLevel overworld = s.getLevel(Level.OVERWORLD);
        if (overworld == null) {
            return;
        }
        int margin = BorderPlan.margin(s.getPlayerList().getViewDistance());

        // ⚠⚠ **Chunky の申告を、実際に生成されている土地で上から抑える**（2026-08-21 追加）。
        //    ⚠ 進捗はワールドの外（config/chunky/tasks/）に在るので、**ワールドを差し替えても残る**。
        //    2026-08-20 に前の世界の 45,727 チャンクを根拠にボーダーが 2866 まで開き、
        //    実在は 27,026 チャンク・全部そろう半径は ±1,184 だった。
        //    踏み込んだ人は山岳河川の計算で **217 秒**固まる（ログには何も出ない）。
        ServerLevel netherLevel = s.getLevel(Level.NETHER);
        double owGenerated = generatedRadius(overworld);
        double owAllowed = BorderPlan.clampToGenerated(allowed(OVERWORLD, margin),
                owGenerated, margin);
        double netherAllowed = BorderPlan.clampToGenerated(allowed(NETHER, margin),
                generatedRadius(netherLevel), margin);
        // ⚠ ネザーをまだ1チャンクも焼いていない段階では、そちらに引きずられて 0 になる。
        //    その場合はオーバーワールドだけで決める（ネザーは後から追いつく）。
        //
        // ⚠⚠ **「焼き始めた瞬間」も同じ穴だった**（2026-08-26）。
        //    上の `burned(NETHER) > 0` は **1チャンクでも焼けば外れる**ので、
        //    両次元を並行で焼き始めた瞬間にネザーが縛り側へ回り、
        //    ⚠ **ボーダーが 7,968 → 512 へ落ちた**。追いつけば戻るが、
        //    ⚠⚠ **その最中に部員が入ると Autopause が事前生成を止めるので、小さいまま固定される**。
        //    実際に 512 で固定され、部員1人が自分の居場所ごとボーダーの外になった。
        //    → **人が居る間はオーバーワールドだけで決める**（{@link BorderPlan#overworldDiameter} の3引数版）。
        boolean playersOnline = !s.getPlayerList().getPlayers().isEmpty();
        int want = (burned(NETHER) > 0)
                ? BorderPlan.overworldDiameter(owAllowed, netherAllowed, playersOnline)
                : (int) (Math.max(BorderPlan.MIN_RADIUS, owAllowed) * 2.0);

        WorldBorder border = overworld.getWorldBorder();
        boolean evidence = burned(OVERWORLD) > 0;
        // ⚠ **書き換える前に控える。** 2026-08-11 に setSize したあとで getSize を読んでいて、
        //    ログが「256 → 256」と出た。何から何へ動いたのか分からず、記録として役に立たない。
        long before = (long) border.getSize();
        Integer next = BorderPlan.nextDiameter(before, want, evidence);
        if (next != null) {
            border.setSize(next);
            if (netherLevel != null) {
                // ⚠ **ネザーは常に 1/8 ちょうど**。これでポータルの行き先がどちら向きにも丸められない。
                //    次元ごとに独立したボーダーを持てるのは World Border Fixer が入っているため。
                netherLevel.getWorldBorder().setSize(BorderPlan.netherDiameter(next));
            }
            // ⚠ **実測した半径も一緒に出す。** どちらが効いたのか（申告か実測か）が
            //    後から読めないと、「なぜこの値になったのか」を調べ直す羽目になる。
            BorderKeeper.LOGGER.info(
                    "Border Keeper: ボーダー {} → {}（オーバーワールド {} チャンク{}・余白 {}・"
                            + "実測の全部そろう半径 {}）",
                    before, next, burned(OVERWORLD),
                    Boolean.TRUE.equals(DONE.get(OVERWORLD.toString())) ? "・完走" : "", margin,
                    owGenerated > 0 ? (long) owGenerated : "測れず");
        }
        checkBudget(s);
    }

    /**
     * その次元で「原点まわりに全部そろっている半径」（ブロック）。⚠ 測れなければ 0。
     *
     * <p>⚠ <b>ここは場所を教えるだけ</b>で、数えるのは {@link GeneratedExtent}
     * （Minecraft を知らないので、サーバを起動せずに確かめられる）。
     */
    private static double generatedRadius(ServerLevel level) {
        if (level == null) {
            return 0.0;
        }
        try {
            Path dim = net.minecraft.world.level.dimension.DimensionType.getStorageFolder(
                    level.dimension(),
                    level.getServer().getWorldPath(
                            net.minecraft.world.level.storage.LevelResource.ROOT));
            return GeneratedExtent.radiusBlocks(dim.resolve("region"));
        } catch (RuntimeException e) {
            // ⚠ **測れないことで落とさない。** 抑えないだけで、ボーダーの追随自体は続ける
            BorderKeeper.LOGGER.warn("Border Keeper: 生成済みの範囲を測れなかった: {}", e.toString());
            return 0.0;
        }
    }

    /**
     * Chunky の API を掴む。⚠ <b>無ければ null を返す</b>（例外で落とさない）。
     *
     * <p>{@code ChunkyProvider.get()} は Chunky がサーバ起動時に登録する本体。
     * ⚠ これを掴む経路は当部で実績がある（dynamicwaters_asyncrivers の 1.4.0 で使った）。
     */
    private static ChunkyAPI api() {
        try {
            org.popcraft.chunky.Chunky chunky = org.popcraft.chunky.ChunkyProvider.get();
            return chunky == null ? null : new org.popcraft.chunky.api.ChunkyAPIImpl(chunky);
        } catch (Throwable t) {
            return null;
        }
    }

    private static long burned(ResourceLocation dim) {
        Long v = BURNED.get(dim.toString());
        return v == null ? 0L : v;
    }

    private static double allowed(ResourceLocation dim, int margin) {
        return BorderPlan.allowedRadius(burned(dim),
                Boolean.TRUE.equals(DONE.get(dim.toString())), margin);
    }

    /** 予算を超えたら事前生成を止める。⚠ 測るのは重いので {@link #SIZE_RECHECK_MS} ごと。 */
    private static void checkBudget(MinecraftServer s) {
        long now = System.currentTimeMillis();
        long prev = lastSizeCheck.get();
        if (now - prev < SIZE_RECHECK_MS || !lastSizeCheck.compareAndSet(prev, now)) {
            return;
        }
        Path root = s.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
        // ⚠⚠ **数えるのは別スレッド**（2026-08-28）。`folderGb` は**ワールドを丸ごと歩く**ので、
        //    本体スレッドでやると tick が止まる。⚠ レンタル（7.9GB）で実際に止まった——
        //    ⚠ **部員が入った直後に数分固まり、その部員が入れなくなった**
        //    （`Timed out waiting for world statistics` が並ぶ）。
        //    ⚠ 10 分に1回とはいえ、⚠ **当たった回に必ず止まる**ので回数の問題ではない。
        //
        // ⚠ **止める操作だけ本体スレッドへ戻す**（Chunky の API を別スレッドから叩かない）。
        net.minecraft.Util.backgroundExecutor().execute(() -> {
            long t0 = System.nanoTime();
            double gb = folderGb(root);
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            if (gb < 0) {
                return;                   // 測れなかった。⚠ 測れないことを理由に止めない
            }
            worldGb = gb;
            if (gb < BUDGET_GB || paused) {
                if (ms > 1000L) {
                    BorderKeeper.LOGGER.info(
                            "Border Keeper: 容量を数えた {} GB（別スレッドで {} ms）",
                            String.format("%.1f", gb), ms);
                }
                return;
            }
            s.execute(() -> {
                if (paused) {
                    return;               // ⚠ 待っている間に誰かが止めていたら二重にやらない
                }
                paused = true;
                BorderKeeper.LOGGER.warn("Border Keeper: ワールドが {} GB に達した（予算 {} GB）"
                        + " → 事前生成を止める", String.format("%.1f", gb), BUDGET_GB);
                // ⚠ 止めるのは pause。cancel すると進捗が消えて、次に再開できない
                ChunkyAPI a = api();
                if (a != null) {
                    a.pauseTask(OVERWORLD.toString());
                    a.pauseTask(NETHER.toString());
                } else {
                    BorderKeeper.LOGGER.warn("Border Keeper: 事前生成を止められなかった（API が無い）");
                }
            });
        });
    }

    private static double folderGb(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            long bytes = walk.filter(Files::isRegularFile).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (IOException e) {
                    return 0L;
                }
            }).sum();
            return bytes / (1024.0 * 1024.0 * 1024.0);
        } catch (IOException e) {
            return -1.0;
        }
    }

    private BorderKeeperEvents() {
    }
}
