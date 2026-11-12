package io.github.takicoyuki.downloader;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * URLリストのテキストを読み込んで {@link DownloadTask} の一覧にする。
 *
 * <pre>
 * # コメント行と空行は無視する
 * https://example.com/files/report.pdf
 * https://example.com/download?id=123   好きな名前.zip   ← URLのあとに保存名を書ける
 * </pre>
 *
 * 保存名は出力フォルダの外に出ないよう無害化し、同じ名前が重なったら "name (2).ext" のように連番を付ける。
 */
public class UrlListParser {

    private static final String WINDOWS_INVALID_CHARS = "[<>:\"/\\\\|?*\\x00-\\x1F]";

    public List<DownloadTask> parse(Path listFile, Path outputDir) throws IOException {
        return parse(Files.readAllLines(listFile, StandardCharsets.UTF_8), outputDir);
    }

    public List<DownloadTask> parse(List<String> lines, Path outputDir) {
        Path base = outputDir.toAbsolutePath().normalize();
        List<DownloadTask> tasks = new ArrayList<>();
        Set<String> usedNames = new HashSet<>();

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] parts = line.split("\\s+", 2);
            URI url = toUri(parts[0], i + 1);
            String requested = parts.length > 1 ? parts[1].strip() : fileNameFromUrl(url);
            String name = uniqueName(sanitize(requested, tasks.size() + 1), usedNames);

            Path target = base.resolve(name).normalize();
            if (!target.getParent().equals(base)) {
                throw new IllegalArgumentException("保存先が出力フォルダの外になります（" + (i + 1) + "行目）: " + requested);
            }
            tasks.add(new DownloadTask(url, target));
        }
        return tasks;
    }

    private static URI toUri(String text, int lineNo) {
        try {
            URI uri = new URI(text);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                throw new IllegalArgumentException("http/https 以外のURLです（" + lineNo + "行目）: " + text);
            }
            return uri;
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("URLの形式が正しくありません（" + lineNo + "行目）: " + text, e);
        }
    }

    static String fileNameFromUrl(URI url) {
        String path = url.getRawPath();
        if (path == null || path.isEmpty() || path.endsWith("/")) {
            return "";
        }
        String last = path.substring(path.lastIndexOf('/') + 1);
        return URLDecoder.decode(last, StandardCharsets.UTF_8);
    }

    static String sanitize(String name, int seq) {
        String cleaned = name.replaceAll(WINDOWS_INVALID_CHARS, "_").strip();
        // "." や ".." だけの名前、末尾のドットは Windows で扱えないので除く
        cleaned = cleaned.replaceAll("\\.+$", "");
        if (cleaned.isEmpty() || cleaned.chars().allMatch(c -> c == '_')) {
            return "download-" + seq;
        }
        return cleaned;
    }

    private static String uniqueName(String name, Set<String> used) {
        String candidate = name;
        int n = 2;
        while (!used.add(candidate.toLowerCase(Locale.ROOT))) {
            int dot = name.lastIndexOf('.');
            candidate = dot > 0
                    ? name.substring(0, dot) + " (" + n + ")" + name.substring(dot)
                    : name + " (" + n + ")";
            n++;
        }
        return candidate;
    }
}
