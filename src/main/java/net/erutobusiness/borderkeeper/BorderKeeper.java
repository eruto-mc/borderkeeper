package net.erutobusiness.borderkeeper;

import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

/**
 * ワールドボーダーを、Chunky が実際に焼き終わった範囲に追随させる。
 *
 * <p><b>何のためか.</b> プレイヤーが未生成の土地へ出ると、その場でチャンク生成が走る。
 * 当パックは地形MODを重ねているので1チャンクが重く、参加直後に数十秒サーバが止まる。
 * ボーダーを「焼けた範囲」の内側に置いておけば、遊んでいる最中に生成が起きない。
 *
 * <p><b>なぜ MOD なのか（2026-08-11）.</b> これは元々<b>サーバの外で動く運用スクリプト</b>だった。
 * RCON で繋ぎ、<b>サーバのログを正規表現で読んで</b>進捗を得ていた——スクリプト自身のコメントが
 * 「{@code config/chunky/tasks/*.properties} は当てにならない。ログが唯一の生の情報源」と
 * 書いていたほどで、外から見える情報がそれしか無かった。
 *
 * <p>ところが Chunky は {@code ChunkyAPI} で進捗イベントを公開していた。
 * 外に居たせいで抱えていた厄介事は、中に入れば全部消える:
 *
 * <ul>
 *   <li>ログを正規表現で読む → {@code GenerationProgressEvent} を受け取るだけ</li>
 *   <li>ログは再起動で切り替わるので region ファイルのヘッダを自前で読んで突き合わせていた
 *       → 進捗はイベントで届く</li>
 *   <li>前のワールドの残骸タスクを弾く仕掛け → そもそも読まない</li>
 *   <li>RCON で {@code worldborder set} を撃つ → {@code level.getWorldBorder().setSize()}</li>
 *   <li>⚠ レンタルサーバに python3 があるか不明だった → <b>依存が消える</b></li>
 * </ul>
 *
 * <p><b>常駐の寿命もここで正しくなる.</b> 外部の常駐（{@code --watch}）は
 * 「落ちても黙って止まる」という不具合を抱えていた。だが<b>ボーダーを更新する必要があるのは
 * サーバが動いている間だけ</b>で、MOD の中の定期処理なら寿命がぴったり一致する。
 * 別々に落ちるという事象自体が起こらない。
 *
 * <p>⚠ <b>外部スクリプトのほうも捨てていない.</b> 焼く前の見積もり（状態を出すだけ）は外から
 * 打てるほうが便利だし、この MOD が入っていない環境でも使える。
 */
@Mod(BorderKeeper.MODID)
public class BorderKeeper {

    public static final String MODID = "borderkeeper";
    public static final Logger LOGGER = LogUtils.getLogger();

    public BorderKeeper() {
        // 実体は BorderKeeperEvents（Forge のイベントバスに載る）と BorderPlan（判断）。
        LOGGER.info("Border Keeper: ボーダーを Chunky の進捗に追随させる");
    }
}
