package net.erutobusiness.borderkeeper;

/**
 * 「焼けたチャンク数」から「ボーダーをどこへ置くか」を決める計算だけを持つ。
 *
 * <p>⚠ <b>ここには Minecraft も Chunky も出てこない.</b> 判断だけを切り離してあるので、
 * サーバを起動せずに机上で確かめられる。<b>運用で実際に踏んだ穴</b>の対策を
 * そのまま移してあるので、<b>1つずつ理由が書いてある</b>。
 */
public final class BorderPlan {

    /**
     * 正方形の半径 r ブロック内のチャンク数は (r/8)^2。よって r = 8*sqrt(チャンク数)。
     *
     * <p>これが成り立つのは Chunky を {@code pattern=concentric}（中心から輪を広げる順）で
     * 走らせているときだけ。⚠ {@code region} 順だと「ここまでは焼けた」と言える瞬間が
     * 完走時しか無く、進捗から半径を出せない。
     */
    public static double radiusFromChunks(long chunks) {
        return 8.0 * Math.sqrt(Math.max(0L, chunks));
    }

    /**
     * ボーダーと「焼けた範囲」の差（余白）。⚠ <b>当てずっぽうで置かない.</b>
     *
     * <p>ボーダーに立ったプレイヤーの周りには {@code view-distance} チャンクぶんが
     * サーバから配られる＝<b>その範囲は生成されてしまう</b>。だから
     * 「焼けた範囲 − ボーダー」が view-distance より大きくないと、
     * ボーダーがどれだけ硬くても「プレイヤーにチャンクを生成させない」は破れる。
     *
     * <p>+2チャンクは端数と移動ぶんの安全側。
     */
    public static int margin(int viewDistanceChunks) {
        return (viewDistanceChunks + 2) * 16;
    }

    /**
     * 完走していない（＝輪の途中かもしれない）タスクに掛ける割引。
     *
     * <p>⚠ <b>完走したタスクには掛けない.</b> 掛けたままだと、焼き終わっているのに
     * ボーダーが 5% 手前で止まる（2026-08-05 に実際に起きた）。
     */
    public static final double SAFETY = 0.95;

    /**
     * 下限。⚠ <b>「ワールド生成のときに必ず焼かれるスポーン周辺」の内側に置く.</b>
     * ここを大きくすると「生成済みの場所にしか行けない」が破れるので、遊びやすさで盛らない。
     * 事前生成は歩く速さより桁違いに速いので、数分で追い越す。
     */
    public static final int MIN_RADIUS = 128;

    /** バニラ既定のボーダー直径＝実質無制限。作りたてのワールドはこれ。 */
    public static final double VANILLA_DIAMETER = 59999968.0;

    /**
     * その次元で許せるボーダー半径。
     *
     * @param burnedChunks その次元で焼けたチャンク数
     * @param finished     そのタスクが完走したか（⚠ 完走なら SAFETY を掛けない）
     * @param marginBlocks {@link #margin}
     */
    public static double allowedRadius(long burnedChunks, boolean finished, int marginBlocks) {
        double r = radiusFromChunks(burnedChunks) * (finished ? 1.0 : SAFETY);
        return Math.max(0.0, r - marginBlocks);
    }

    /**
     * オーバーワールドのボーダー直径を決める。
     *
     * <p>⚠ <b>「行ける全次元のうち、いちばん余裕の無い次元」に合わせる.</b>
     * 各次元で許せる半径をオーバーワールド換算（×座標比）して、その最小を採る。
     * こうしておけば <b>ネザーのボーダー = オーバーワールド ÷ 8</b> が常に成り立ち、
     * ポータルの行き先が丸められることが<b>どちら向きにも</b>起きない。
     *
     * @param overworldAllowed オーバーワールドで許せる半径
     * @param netherAllowed    ネザーで許せる半径（⚠ ×8 して比べる）
     */
    public static int overworldDiameter(double overworldAllowed, double netherAllowed) {
        return overworldDiameter(overworldAllowed, netherAllowed, false);
    }

    /**
     * 同上。⚠ <b>人が居る間はネザーに縛らせない</b>（2026-08-26 追加）。
     *
     * <p>⚠⚠ <b>なぜ要るか</b>: 無人時に両次元を並行で焼くと、<b>ネザーが追いつくまでの数分間</b>、
     * 上の「全次元の最小」の規則でボーダーが小さくなる。⚠ <b>無人ならそれでよい</b>——
     * 誰も締め出さないし、追いつけば戻る。
     *
     * <p>⚠ ところが<b>その最中に部員が入ってくると Chunky Autopause が事前生成を止める</b>ので、
     * 進捗イベントが来なくなり、⚠⚠ <b>ボーダーが小さいまま固定される</b>。
     * 2026-08-26 に実際に <b>512 で固定</b>され、遊んでいた場所までボーダーの外になった。
     *
     * <p>⚠ <b>人が居る間はオーバーワールドだけで決める。</b> オーバーワールドの土地は
     * {@link GeneratedExtent} が「全部そろっている」と実測しているので、そこまで開くのは安全。
     * ネザーは自分のボーダー（÷8）が狭いままになるだけで、<b>誰も締め出さない</b>。
     *
     * <p>⚠ <b>「縮めない」ではない.</b> オーバーワールドの実測そのものが小さければ、
     * 人が居ても縮む（2026-08-21 の「開きすぎ」を直す道を塞がないため）。
     *
     * @param playersOnline 1人でも居るか
     */
    public static int overworldDiameter(double overworldAllowed, double netherAllowed,
                                        boolean playersOnline) {
        double safe = playersOnline
                ? overworldAllowed
                : Math.min(overworldAllowed, netherAllowed * 8.0);
        return (int) (Math.max(MIN_RADIUS, safe) * 2.0);
    }

    /**
     * 申告された半径を、<b>実際に生成されている土地</b>で上から抑える（2026-08-21 追加）。
     *
     * <p>⚠⚠ <b>Chunky の進捗は、この世界のものとは限らない.</b> 進捗はワールドの外
     * （{@code config/chunky/tasks/}）に在るので、ワールドを差し替えても残る。
     * 2026-08-20 に、遊び用サーバのワールドを別のものへ差し替えたあと、
     * <b>前の世界の進捗（45,727 チャンク）</b>を根拠にボーダーが 2866 まで開いた。
     * その世界に実在したのは 27,026 チャンクで、<b>全部そろっていたのは ±1,184</b> だった。
     *
     * <p>踏み込んだ人は、チャンク生成に伴う山岳河川の計算で
     * <b>サーバスレッドが 217 秒止まる</b>のを見る（症状は「入れるのに地形が来ない」で、
     * ⚠ <b>ログには何も出ない</b>）。
     *
     * <p>⚠ <b>測れなかったときは抑えない.</b>「測れなかった」と「焼けていない」は別物で、
     * {@link #nextDiameter} の「根拠ゼロで縮めない」と同じ考え方。
     *
     * <p>⚠⚠ <b>2026-08-26: 天井だけでなく<u>床</u>にもした。</b>
     * それまでは {@code Math.min} で<b>上から抑えるだけ</b>だったので、
     * <b>焼けたチャンク数が小さいと、実際に土地が在ってもボーダーが縮んだ</b>。
     *
     * <p>実際に踏んだ形（本番のレンタルサーバ）: Chunky のタスクを入れ直すと
     * <b>進捗が 0 から数え直しになる</b>。ディスクには 4,176 ブロックぶんの土地が在るのに
     * 申告は 8,548 チャンク（=510 ブロック）で、⚠ <b>ボーダーが 7,632 → 256 に縮んだ</b>。
     *
     * <p>⚠⚠ <b>直し方の考え方は1本:「ディスクで実測できたなら、それが正」。</b>
     * 進捗カウンタは<b>そのタスクがどこまで進んだか</b>であって、
     * <b>この世界にどれだけ土地が在るか</b>ではない。後者は {@link GeneratedExtent} が
     * region ファイルの見出しから直に測っており、<b>そちらが唯一の一次情報</b>。
     * これで過去2件が同じ規則で受け止まる:
     * <ul>
     *   <li>2026-08-21（前の世界の進捗で<b>開きすぎ</b>）… 実測が<b>天井</b>として効く → 縮む</li>
     *   <li>2026-08-26（進捗が 0 に戻って<b>縮みすぎ</b>）… 実測が<b>床</b>として効く → 縮まない</li>
     * </ul>
     *
     * @param allowedRadius   焼けたチャンク数から出した半径（⚠ <b>実測できたら使わない</b>）
     * @param generatedRadius 実測した「全部そろう半径」（ブロック）。0 以下なら測れなかった
     * @param marginBlocks    {@link #margin}
     */
    public static double clampToGenerated(double allowedRadius, double generatedRadius,
                                          int marginBlocks) {
        if (generatedRadius <= 0.0) {
            return allowedRadius;              // ⚠ 測れない＝抑えも支えもしない
        }
        return Math.max(0.0, generatedRadius - marginBlocks);
    }

    /** ネザーのボーダー直径。⚠ <b>常にオーバーワールド ÷ 8 ちょうど.</b> */
    public static int netherDiameter(int overworldDiameter) {
        return Math.max(2, overworldDiameter / 8);
    }

    /**
     * ボーダーを動かすべきか。
     *
     * <p>⚠ <b>64ブロック以内の差では動かさない.</b> 目標は実測 KB/チャンク から毎回引き直すので
     * 走らせるたびに数十ブロックずれる。厳密に比べると、定期処理が毎回ボーダーを動かし続ける。
     *
     * <p>⚠⚠ <b>根拠ゼロで縮めない.</b> 「測れなかった」と「焼けていない」は別物。
     * 焼けた範囲を1つも測れていないのに縮めると、遊べていた範囲が突然閉じる
     * （2026-08-05 に 1719 → 256 に縮める計画が実際に出た）。
     *
     * @return 動かすなら新しい直径、動かさないなら {@code null}
     */
    public static Integer nextDiameter(double currentDiameter, int wantDiameter, boolean haveEvidence) {
        if (Math.abs(wantDiameter - currentDiameter) <= 64) {
            return null;
        }
        if (wantDiameter < currentDiameter && !haveEvidence) {
            return null;                      // 縮める向き＋根拠なし＝動かさない
        }
        return wantDiameter;
    }

    private BorderPlan() {
    }
}
