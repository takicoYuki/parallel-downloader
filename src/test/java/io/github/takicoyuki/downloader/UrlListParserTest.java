package io.github.takicoyuki.downloader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UrlListParserTest {

    private final Path out = Path.of("out").toAbsolutePath();
    private final UrlListParser parser = new UrlListParser();

    private List<String> names(List<DownloadTask> tasks) {
        return tasks.stream().map(t -> t.target().getFileName().toString()).toList();
    }

    @Test
    void 空行とコメント行は無視し_ファイル名はURLから決める() {
        List<DownloadTask> tasks = parser.parse(List.of(
                "# 資料",
                "",
                "https://example.com/files/report.pdf",
                "  https://example.com/a/%E8%B3%87%E6%96%99.zip  "
        ), out);

        assertEquals(List.of("report.pdf", "資料.zip"), names(tasks));
        assertEquals(URI.create("https://example.com/files/report.pdf"), tasks.get(0).url());
        assertTrue(tasks.stream().allMatch(t -> t.target().getParent().equals(out)));
    }

    @Test
    void URLのあとに保存名を指定できる() {
        List<DownloadTask> tasks = parser.parse(List.of("https://example.com/download?id=1   月次 レポート.xlsx"), out);

        assertEquals(List.of("月次 レポート.xlsx"), names(tasks));
    }

    @Test
    void 同じ名前が重なったら連番を付ける() {
        List<DownloadTask> tasks = parser.parse(List.of(
                "https://a.example.com/x/data.csv",
                "https://b.example.com/y/data.csv",
                "https://c.example.com/z/DATA.csv"
        ), out);

        assertEquals(List.of("data.csv", "data (2).csv", "DATA (3).csv"), names(tasks));
    }

    @Test
    void 名前が決められないURLは連番の名前にする() {
        List<DownloadTask> tasks = parser.parse(List.of("https://example.com/", "https://example.com/dir/"), out);

        assertEquals(List.of("download-1", "download-2"), names(tasks));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "../../etc/passwd   | .._.._etc_passwd",
            "..\\..\\secret.txt | .._.._secret.txt",
            "a:b*c?.txt         | a_b_c_.txt",
            "..                 | download-1",
    })
    void 保存名は出力フォルダの外に出ないよう無害化する(String requested, String expected) {
        List<DownloadTask> tasks = parser.parse(List.of("https://example.com/f " + requested), out);

        assertEquals(expected, tasks.get(0).target().getFileName().toString());
        assertEquals(out, tasks.get(0).target().getParent());
    }

    @Test
    void httpとhttps以外のURLはエラーにする() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> parser.parse(List.of("https://example.com/ok", "file:///etc/passwd"), out));

        assertTrue(e.getMessage().contains("2行目"));
    }
}
