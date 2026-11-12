package io.github.takicoyuki.downloader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.OptionalLong;

/**
 * 1ファイル分のダウンロードを行う。複数スレッドから同時に呼び出してよい（タスクごとに状態を持たない）。
 *
 * <ul>
 *   <li>書き込み中は "{保存名}.part" に保存し、最後まで受信できたら本来の名前に置き換える
 *       （途中で止まっても中途半端なファイルが完成品に見えない）</li>
 *   <li>.part が残っていれば Range ヘッダで続きから再開する（サーバーが対応していれば）</li>
 *   <li>通信エラー・5xx・429 は待ち時間を倍にしながらリトライする。4xx はリトライしない</li>
 * </ul>
 */
public class FileDownloader {

    private final HttpClient client;
    private final DownloaderOptions options;
    private final Sleeper sleeper;

    public FileDownloader(HttpClient client, DownloaderOptions options) {
        this(client, options, Thread::sleep);
    }

    FileDownloader(HttpClient client, DownloaderOptions options, Sleeper sleeper) {
        this.client = client;
        this.options = options;
        this.sleeper = sleeper;
    }

    public DownloadResult download(DownloadTask task) {
        long started = System.nanoTime();
        Path target = task.target();

        if (Files.exists(target)) {
            return result(task, DownloadResult.Status.SKIPPED, 0, 0, started, null);
        }

        Path part = target.resolveSibling(target.getFileName() + ".part");
        long received = 0;
        boolean resumed = false;
        String lastError = null;

        for (int attempt = 1; attempt <= options.maxAttempts(); attempt++) {
            try {
                Files.createDirectories(target.getParent());
                Transfer t = transfer(task, part);
                received += t.bytes();
                resumed |= t.resumed();
                moveIntoPlace(part, target);
                DownloadResult.Status status = resumed ? DownloadResult.Status.RESUMED : DownloadResult.Status.DOWNLOADED;
                return result(task, status, received, attempt, started, null);
            } catch (RetryableException | IOException e) {
                lastError = e.getMessage();
                if (attempt < options.maxAttempts()) {
                    backoff(attempt);
                }
            } catch (NonRetryableException e) {
                return result(task, DownloadResult.Status.FAILED, received, attempt, started, e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return result(task, DownloadResult.Status.FAILED, received, attempt, started, "中断されました");
            }
        }
        return result(task, DownloadResult.Status.FAILED, received, options.maxAttempts(), started, lastError);
    }

    private Transfer transfer(DownloadTask task, Path part) throws IOException, InterruptedException {
        long existing = Files.exists(part) ? Files.size(part) : 0;

        HttpRequest.Builder request = HttpRequest.newBuilder(task.url())
                .timeout(options.requestTimeout())
                .header("User-Agent", options.userAgent())
                .GET();
        if (existing > 0) {
            request.header("Range", "bytes=" + existing + "-");
        }

        HttpResponse<InputStream> response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        int code = response.statusCode();

        try (InputStream body = response.body()) {
            if (code == 416 && existing > 0) {
                // 範囲外 = .part がすでに最後まで落ちている
                return new Transfer(0, true);
            }
            if (code == 429 || code >= 500) {
                throw new RetryableException("HTTP " + code + ": " + task.url());
            }
            if (code != 200 && code != 206) {
                throw new NonRetryableException("HTTP " + code + ": " + task.url());
            }

            boolean append = code == 206 && existing > 0;
            OptionalLong expected = response.headers().firstValueAsLong("Content-Length");

            long written;
            try (OutputStream out = Files.newOutputStream(part,
                    StandardOpenOption.CREATE,
                    append ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING)) {
                written = body.transferTo(out);
            }
            if (expected.isPresent() && written != expected.getAsLong()) {
                throw new RetryableException("受信サイズが一致しません（期待 " + expected.getAsLong() + " / 実際 " + written + "）: " + task.url());
            }
            return new Transfer(written, append);
        }
    }

    private static void moveIntoPlace(Path part, Path target) throws IOException {
        try {
            Files.move(part, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void backoff(int attempt) {
        long millis = options.initialBackoff().toMillis() << (attempt - 1);
        try {
            sleeper.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static DownloadResult result(DownloadTask task, DownloadResult.Status status, long bytes,
                                         int attempts, long startedNanos, String error) {
        return new DownloadResult(task, status, bytes, attempts, Duration.ofNanos(System.nanoTime() - startedNanos), error);
    }

    private record Transfer(long bytes, boolean resumed) {
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    static class RetryableException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        RetryableException(String message) {
            super(message);
        }
    }

    static class NonRetryableException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        NonRetryableException(String message) {
            super(message);
        }
    }
}
