package io.github.takicoyuki.downloader;

import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * 使い方:
 * <pre>
 * java -jar parallel-downloader.jar &lt;URLリスト&gt; &lt;出力フォルダ&gt; [--threads N] [--retries N]
 * </pre>
 * 終了コード: 0 = 全件成功（スキップを含む） / 1 = 失敗あり / 2 = 引数の誤り
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        System.exit(run(args));
    }

    static int run(String[] args) throws Exception {
        if (args.length < 2) {
            printUsage();
            return 2;
        }
        Path listFile = Path.of(args[0]);
        Path outputDir = Path.of(args[1]);
        DownloaderOptions options = DownloaderOptions.defaults();

        try {
            for (int i = 2; i < args.length; i++) {
                switch (args[i]) {
                    case "--threads" -> options = options.withThreads(Integer.parseInt(args[++i]));
                    case "--retries" -> options = options.withMaxAttempts(Integer.parseInt(args[++i]) + 1);
                    default -> throw new IllegalArgumentException("不明なオプション: " + args[i]);
                }
            }
        } catch (IllegalArgumentException | ArrayIndexOutOfBoundsException e) {
            System.err.println(e.getMessage());
            printUsage();
            return 2;
        }

        if (!Files.isRegularFile(listFile)) {
            System.err.println("URLリストが見つかりません: " + listFile);
            return 2;
        }

        List<DownloadTask> tasks = new UrlListParser().parse(listFile, outputDir);
        System.out.printf("%d 件を %d スレッドでダウンロードします → %s%n", tasks.size(), options.threads(), outputDir.toAbsolutePath());

        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        BatchDownloader batch = new BatchDownloader(new FileDownloader(client, options), options.threads());

        List<DownloadResult> results = batch.downloadAll(tasks, (r, done, total) ->
                System.out.printf("[%d/%d] %-10s %s%s%n", done, total, r.status(),
                        r.task().target().getFileName(),
                        r.isSuccess() ? "  (" + formatBytes(r.bytes()) + ", " + r.elapsed().toMillis() + " ms)" : "  ← " + r.error()));

        long failed = results.stream().filter(r -> !r.isSuccess()).count();
        long bytes = results.stream().mapToLong(DownloadResult::bytes).sum();
        System.out.printf("完了: 成功 %d / 失敗 %d / 合計 %s%n", results.size() - failed, failed, formatBytes(bytes));
        return failed == 0 ? 0 : 1;
    }

    static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB"};
        double value = bytes;
        int unit = -1;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }

    private static void printUsage() {
        System.err.println("使い方: java -jar parallel-downloader.jar <URLリスト> <出力フォルダ> [--threads N] [--retries N]");
    }
}
