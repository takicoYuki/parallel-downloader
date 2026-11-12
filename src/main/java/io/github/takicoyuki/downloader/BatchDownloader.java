package io.github.takicoyuki.downloader;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 複数のタスクを決まった数のスレッドで並列にダウンロードする。
 * 1件が失敗しても他は止めず、全件の結果を入力と同じ順番で返す。
 */
public class BatchDownloader {

    /** 1件終わるごとに呼ばれる（複数スレッドから呼ばれるので、実装はスレッドセーフにすること） */
    @FunctionalInterface
    public interface ProgressListener {
        void onFinished(DownloadResult result, int finishedCount, int totalCount);

        ProgressListener NONE = (result, finished, total) -> {
        };
    }

    private final FileDownloader downloader;
    private final int threads;

    public BatchDownloader(FileDownloader downloader, int threads) {
        if (threads < 1) {
            throw new IllegalArgumentException("threads は1以上にしてください: " + threads);
        }
        this.downloader = downloader;
        this.threads = threads;
    }

    public List<DownloadResult> downloadAll(List<DownloadTask> tasks, ProgressListener listener) throws InterruptedException {
        AtomicInteger finished = new AtomicInteger();
        int total = tasks.size();

        try (ExecutorService pool = Executors.newFixedThreadPool(threads, namedThreads())) {
            List<Future<DownloadResult>> futures = new ArrayList<>(total);
            for (DownloadTask task : tasks) {
                futures.add(pool.submit(() -> {
                    DownloadResult result = downloader.download(task);
                    listener.onFinished(result, finished.incrementAndGet(), total);
                    return result;
                }));
            }

            List<DownloadResult> results = new ArrayList<>(total);
            for (Future<DownloadResult> f : futures) {
                try {
                    results.add(f.get());
                } catch (ExecutionException e) {
                    // FileDownloader は例外を結果に変換して返すので通常ここには来ない
                    throw new IllegalStateException("想定外のエラー", e.getCause());
                }
            }
            return results;
        }
    }

    private static java.util.concurrent.ThreadFactory namedThreads() {
        AtomicInteger seq = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, "downloader-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}
