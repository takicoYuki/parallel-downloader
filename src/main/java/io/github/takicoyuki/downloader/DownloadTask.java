package io.github.takicoyuki.downloader;

import java.net.URI;
import java.nio.file.Path;
import java.util.Objects;

/**
 * ダウンロード1件分の指示。
 *
 * @param url    ダウンロード元
 * @param target 保存先（出力フォルダ内のファイルパス）
 */
public record DownloadTask(URI url, Path target) {

    public DownloadTask {
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(target, "target");
    }
}
