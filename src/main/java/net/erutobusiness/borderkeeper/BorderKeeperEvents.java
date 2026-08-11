package net.erutobusiness.borderkeeper;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.border.WorldBorder;
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

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        server = event.getServer();
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

    /** ⚠ ワーカースレッドから呼ばれる。重い処理とボーダー操作をここでやらない。 */
    private static void onProgress(GenerationProgressEvent e) {
        BURNED.put(e.world(), e.chunks());
        DONE.put(e.world(), e.complete());
        MinecraftServer s = server;
        if (s != null) {
            s.execute(() -> apply(s));
        }
    }

    private static void apply(MinecraftServer s) {
        ServerLevel overworld = s.getLevel(Level.OVERWORLD);
        if (overworld == null) {
            return;
        }
        int margin = BorderPlan.margin(s.getPlayerList().getViewDistance());

        double owAllowed = allowed(OVERWORLD, margin);
        double netherAllowed = allowed(NETHER, margin);
        // ⚠ ネザーをまだ1チャンクも焼いていない段階では、そちらに引きずられて 0 になる。
        //    その場合はオーバーワールドだけで決める（ネザーは後から追いつく）。
        int want = (burned(NETHER) > 0)
                ? BorderPlan.overworldDiameter(owAllowed, netherAllowed)
                : (int) (Math.max(BorderPlan.MIN_RADIUS, owAllowed) * 2.0);

        WorldBorder border = overworld.getWorldBorder();
        boolean evidence = burned(OVERWORLD) > 0;
        // ⚠ **書き換える前に控える。** 2026-08-11 に setSize したあとで getSize を読んでいて、
        //    ログが「256 → 256」と出た。何から何へ動いたのか分からず、記録として役に立たない。
        long before = (long) border.getSize();
        Integer next = BorderPlan.nextDiameter(before, want, evidence);
        if (next != null) {
            border.setSize(next);
            ServerLevel nether = s.getLevel(Level.NETHER);
            if (nether != null) {
                // ⚠ **ネザーは常に 1/8 ちょうど**。これでポータルの行き先がどちら向きにも丸められない。
                //    次元ごとに独立したボーダーを持てるのは World Border Fixer が入っているため。
                nether.getWorldBorder().setSize(BorderPlan.netherDiameter(next));
            }
            BorderKeeper.LOGGER.info(
                    "Border Keeper: ボーダー {} → {}（オーバーワールド {} チャンク{}・余白 {}）",
                    before, next, burned(OVERWORLD),
                    Boolean.TRUE.equals(DONE.get(OVERWORLD.toString())) ? "・完走" : "", margin);
        }
        checkBudget(s);
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
        double gb = folderGb(root);
        if (gb < 0) {
            return;                       // 測れなかった。⚠ 測れないことを理由に止めない
        }
        worldGb = gb;
        if (gb >= BUDGET_GB && !paused) {
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
        }
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
