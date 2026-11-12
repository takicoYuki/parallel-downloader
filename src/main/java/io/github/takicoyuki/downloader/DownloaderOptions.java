package io.github.takicoyuki.downloader;

import java.time.Duration;

/**
 * ダウンロードの設定。
 *
 * @param threads        同時にダウンロードする数
 * @param maxAttempts    1ファイルあたりの最大試行回数（初回を含む）
 * @param initialBackoff 1回目のリトライまでの待ち時間。以降は2倍ずつ伸ばす
 * @param requestTimeout 1リクエストのタイムアウト
 * @param userAgent      リクエストに付ける User-Agent
 */
public record DownloaderOptions(
        int threads,
        int maxAttempts,
        Duration initialBackoff,
        Duration requestTimeout,
        String userAgent
) {

    public DownloaderOptions {
        if (threads < 1) {
            throw new IllegalArgumentException("threads は1以上にしてください: " + threads);
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts は1以上にしてください: " + maxAttempts);
        }
    }

    public static DownloaderOptions defaults() {
        return new DownloaderOptions(4, 3, Duration.ofSeconds(1), Duration.ofMinutes(5), "parallel-downloader/1.0");
    }

    public DownloaderOptions withThreads(int threads) {
        return new DownloaderOptions(threads, maxAttempts, initialBackoff, requestTimeout, userAgent);
    }

    public DownloaderOptions withMaxAttempts(int maxAttempts) {
        return new DownloaderOptions(threads, maxAttempts, initialBackoff, requestTimeout, userAgent);
    }

    public DownloaderOptions withInitialBackoff(Duration initialBackoff) {
        return new DownloaderOptions(threads, maxAttempts, initialBackoff, requestTimeout, userAgent);
    }
}
