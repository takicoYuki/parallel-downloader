# parallel-downloader

![CI](https://github.com/takicoYuki/parallel-downloader/actions/workflows/ci.yml/badge.svg)

URL を並べたテキストファイルを渡すと、**複数スレッドで並列に一括ダウンロード**する Java の CLI ツールです。
外部ライブラリは使わず、JDK 標準の `HttpClient` と `ExecutorService` だけで作っています。

## 使い方

```bash
mvn -B package
java -jar target/parallel-downloader-1.0.0.jar urls.txt ./downloads --threads 4 --retries 2
```

`urls.txt` の書き方：

```text
# コメント行と空行は無視されます
https://example.com/files/report.pdf
https://example.com/download?id=123   月次レポート.xlsx    ← URL のあとに保存名を書けます
```

実行結果の例：

```text
3 件を 2 スレッドでダウンロードします → /home/user/downloads
[1/3] DOWNLOADED メモ.txt  (3 B, 113 ms)
[2/3] FAILED     nothing.bin  ← HTTP 404: http://127.0.0.1:8765/nothing.bin
[3/3] DOWNLOADED big.bin  (2.9 MB, 142 ms)
完了: 成功 2 / 失敗 1 / 合計 2.9 MB
```

終了コードは、全件成功なら `0`、失敗が 1 件でもあれば `1`、引数が誤っていれば `2` です。バッチから呼んで結果を判定できます。

## 工夫した点

| 課題 | 対応 |
|---|---|
| 同時に投げすぎるとサーバーに負荷がかかる | 固定サイズのスレッドプールで、同時実行数を `--threads` 以下に抑える |
| 1 件の失敗で全体が止まる | 失敗は例外ではなく結果（`FAILED`）として返し、残りは処理を続ける |
| 一時的な通信エラー | 通信エラー・5xx・429 は待ち時間を倍にしながらリトライ。4xx はリトライしない |
| 途中で止まると中途半端なファイルが残る | `*.part` に書き込み、最後まで受信できたら本来の名前に置き換える |
| 大きなファイルの取り直し | `.part` が残っていれば `Range` ヘッダで続きから再開。サーバーが非対応なら最初から取り直す |
| 再実行で同じファイルを取り直す | 保存先に同名ファイルがあればスキップするので、失敗分だけ再実行できる |
| 保存名による上書き・ディレクトリトラバーサル | `../` や Windows で使えない文字を無害化し、名前が重なったら `name (2).ext` にする |
| 受信の途切れ | `Content-Length` と受信サイズを照合し、合わなければリトライ |

## 構成

```
src/main/java/io/github/takicoyuki/downloader
├── Main.java               CLI（引数の解析、進捗表示、終了コード）
├── UrlListParser.java      URL リストの読み込み、保存名の決定と無害化
├── BatchDownloader.java    スレッドプールで並列実行し、結果を入力順にまとめる
├── FileDownloader.java     1 ファイルのダウンロード（リトライ・再開・一時ファイル）
├── DownloadTask.java       ダウンロード 1 件の指示
├── DownloadResult.java     ダウンロード 1 件の結果
└── DownloaderOptions.java  スレッド数・リトライ回数などの設定
```

## テスト

```bash
mvn test
```

JDK 標準の `com.sun.net.httpserver` でテスト用のローカル HTTP サーバーを立て、実際の通信で確認しています。

| テスト | 内容 |
|---|---|
| `FileDownloaderTest` | 保存と一時ファイルの後始末、既存ファイルのスキップ、リトライと待ち時間、404 はリトライしない、続きからの再開、Range 非対応時の取り直し |
| `BatchDownloaderTest` | 同時実行数がスレッド数を超えないこと、一部失敗でも残りを処理すること、結果の順番、進捗の通知 |
| `UrlListParserTest` | コメント・空行、保存名の指定、名前の重複、パストラバーサルの無害化、http/https 以外の拒否 |

## 開発について

以前、ダウンロードリンクをまとめて落とすために作った自作ツールを、公開用に作り直したものです。
作り直しには AI コーディングアシスタントの Claude（Anthropic）を使っています。
