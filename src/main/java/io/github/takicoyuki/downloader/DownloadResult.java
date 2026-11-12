package io.github.takicoyuki.downloader;

import java.time.Duration;

/**
 * ダウンロード1件分の結果。
 *
 * @param task     対象のタスク
 * @param status   結果
 * @param bytes    今回の実行で受信したバイト数（再開した場合は追加分のみ）
 * @param attempts 試行回数
 * @param elapsed  かかった時間
 * @param error    失敗した場合の理由（成功時は null）
 */
public record DownloadResult(
        DownloadTask task,
        Status status,
        long bytes,
        int attempts,
        Duration elapsed,
        String error
) {

    public enum Status {
        /** ダウンロードした */
        DOWNLOADED,
        /** 途中まで落ちていたファイルの続きからダウンロードした */
        RESUMED,
        /** 保存先に同名ファイルがあったので何もしなかった */
        SKIPPED,
        /** リトライしても失敗した */
        FAILED
    }

    public boolean isSuccess() {
        return status != Status.FAILED;
    }
}
