package io.github.takicoyuki.downloader;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BatchDownloaderTest {

    @TempDir
    Path dir;

    TestServer server;

    @BeforeEach
    void setUp() throws IOException {
        server = new TestServer();
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private BatchDownloader batch(int threads) {
        DownloaderOptions options = DownloaderOptions.defaults()
                .withThreads(threads)
                .withMaxAttempts(1)
                .withInitialBackoff(Duration.ZERO);
        return new BatchDownloader(new FileDownloader(HttpClient.newHttpClient(), options), threads);
    }

    private List<DownloadTask> tasks(int count) {
        return IntStream.range(0, count).mapToObj(i -> {
            server.addFile("/f" + i, new byte[]{(byte) i});
            return new DownloadTask(server.url("/f" + i), dir.resolve("f" + i));
        }).toList();
    }

    @Test
    void 同時に実行する数はスレッド数を超えない() throws InterruptedException {
        server.setDelayMillis(150);

        List<DownloadResult> results = batch(3).downloadAll(tasks(9), BatchDownloader.ProgressListener.NONE);

        assertTrue(results.stream().allMatch(DownloadResult::isSuccess));
        assertTrue(server.maxConcurrentRequests() <= 3, "最大同時実行数: " + server.maxConcurrentRequests());
        assertTrue(server.maxConcurrentRequests() >= 2, "並列に動いていない: " + server.maxConcurrentRequests());
    }

    @Test
    void 一部が失敗しても残りは処理し_結果は入力と同じ順番で返す() throws InterruptedException {
        List<DownloadTask> tasks = new ArrayList<>(tasks(4));
        tasks.add(2, new DownloadTask(server.url("/missing"), dir.resolve("missing")));

        List<DownloadResult> results = batch(2).downloadAll(tasks, BatchDownloader.ProgressListener.NONE);

        assertEquals(5, results.size());
        for (int i = 0; i < tasks.size(); i++) {
            assertEquals(tasks.get(i), results.get(i).task());
        }
        assertEquals(DownloadResult.Status.FAILED, results.get(2).status());
        assertEquals(4, results.stream().filter(DownloadResult::isSuccess).count());
    }

    @Test
    void 進捗は1件ごとに全件分通知される() throws InterruptedException {
        List<Integer> finishedCounts = Collections.synchronizedList(new ArrayList<>());

        batch(4).downloadAll(tasks(6), (r, done, total) -> {
            assertEquals(6, total);
            finishedCounts.add(done);
        });

        assertEquals(List.of(1, 2, 3, 4, 5, 6), finishedCounts.stream().sorted().toList());
    }
}
