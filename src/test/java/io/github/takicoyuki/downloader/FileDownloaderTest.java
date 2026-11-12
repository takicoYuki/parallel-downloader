package io.github.takicoyuki.downloader;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileDownloaderTest {

    @TempDir
    Path dir;

    TestServer server;
    List<Long> sleeps;
    FileDownloader downloader;

    @BeforeEach
    void setUp() throws IOException {
        server = new TestServer();
        sleeps = new ArrayList<>();
        DownloaderOptions options = DownloaderOptions.defaults()
                .withMaxAttempts(3)
                .withInitialBackoff(Duration.ofMillis(100));
        downloader = new FileDownloader(HttpClient.newHttpClient(), options, sleeps::add);
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void ダウンロードして本来の名前で保存し_partファイルは残さない() throws IOException {
        byte[] content = "hello".getBytes(StandardCharsets.UTF_8);
        server.addFile("/a.txt", content);
        Path target = dir.resolve("a.txt");

        DownloadResult r = downloader.download(new DownloadTask(server.url("/a.txt"), target));

        assertEquals(DownloadResult.Status.DOWNLOADED, r.status());
        assertEquals(content.length, r.bytes());
        assertArrayEquals(content, Files.readAllBytes(target));
        assertFalse(Files.exists(dir.resolve("a.txt.part")));
    }

    @Test
    void 保存先にファイルがあればリクエストせずにスキップする() throws IOException {
        server.addFile("/a.txt", "new".getBytes(StandardCharsets.UTF_8));
        Path target = dir.resolve("a.txt");
        Files.writeString(target, "old");

        DownloadResult r = downloader.download(new DownloadTask(server.url("/a.txt"), target));

        assertEquals(DownloadResult.Status.SKIPPED, r.status());
        assertEquals("old", Files.readString(target));
        assertEquals(0, server.requestCount("/a.txt"));
    }

    @Test
    void サーバーエラーは待ち時間を倍にしながらリトライする() {
        server.addFile("/flaky.bin", new byte[]{1, 2, 3});
        server.failFirst("/flaky.bin", 2);

        DownloadResult r = downloader.download(new DownloadTask(server.url("/flaky.bin"), dir.resolve("flaky.bin")));

        assertEquals(DownloadResult.Status.DOWNLOADED, r.status());
        assertEquals(3, r.attempts());
        assertEquals(List.of(100L, 200L), sleeps);
    }

    @Test
    void リトライ回数を超えたら失敗として返し_例外は投げない() {
        server.addFile("/down.bin", new byte[]{1});
        server.failFirst("/down.bin", 10);

        DownloadResult r = downloader.download(new DownloadTask(server.url("/down.bin"), dir.resolve("down.bin")));

        assertEquals(DownloadResult.Status.FAILED, r.status());
        assertEquals(3, r.attempts());
        assertTrue(r.error().contains("HTTP 500"));
    }

    @Test
    void _404はリトライしない() {
        DownloadResult r = downloader.download(new DownloadTask(server.url("/missing.bin"), dir.resolve("missing.bin")));

        assertEquals(DownloadResult.Status.FAILED, r.status());
        assertEquals(1, r.attempts());
        assertEquals(1, server.requestCount("/missing.bin"));
        assertTrue(sleeps.isEmpty());
    }

    @Test
    void partファイルがあれば続きから再開する() throws IOException {
        byte[] content = new byte[10_000];
        Arrays.fill(content, (byte) 7);
        content[9_999] = 9;
        server.addFile("/big.bin", content);
        Path target = dir.resolve("big.bin");
        Files.write(dir.resolve("big.bin.part"), Arrays.copyOf(content, 4_000));

        DownloadResult r = downloader.download(new DownloadTask(server.url("/big.bin"), target));

        assertEquals(DownloadResult.Status.RESUMED, r.status());
        assertEquals(6_000, r.bytes());
        assertArrayEquals(content, Files.readAllBytes(target));
    }

    @Test
    void サーバーがRangeに対応していなければ最初から取り直す() throws IOException {
        byte[] content = "0123456789".getBytes(StandardCharsets.UTF_8);
        server.addFile("/norange.txt", content);
        server.setRangeSupported(false);
        Path target = dir.resolve("norange.txt");
        Files.writeString(dir.resolve("norange.txt.part"), "XXXX");

        DownloadResult r = downloader.download(new DownloadTask(server.url("/norange.txt"), target));

        assertEquals(DownloadResult.Status.DOWNLOADED, r.status());
        assertArrayEquals(content, Files.readAllBytes(target));
    }
}
