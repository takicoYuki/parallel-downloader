package io.github.takicoyuki.downloader;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** テスト用のローカルHTTPサーバー（JDK標準の com.sun.net.httpserver を使用） */
class TestServer implements AutoCloseable {

    private final HttpServer server;
    private final Map<String, byte[]> files = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> failuresLeft = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> requestCounts = new ConcurrentHashMap<>();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxInFlight = new AtomicInteger();
    private volatile long delayMillis;
    private volatile boolean rangeSupported = true;

    TestServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", this::handle);
        server.start();
    }

    URI url(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    void addFile(String path, byte[] content) {
        files.put(path, content);
    }

    /** 指定回数だけ 500 を返してから成功させる */
    void failFirst(String path, int times) {
        failuresLeft.put(path, new AtomicInteger(times));
    }

    void setDelayMillis(long delayMillis) {
        this.delayMillis = delayMillis;
    }

    void setRangeSupported(boolean rangeSupported) {
        this.rangeSupported = rangeSupported;
    }

    int requestCount(String path) {
        return requestCounts.getOrDefault(path, new AtomicInteger()).get();
    }

    int maxConcurrentRequests() {
        return maxInFlight.get();
    }

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        requestCounts.computeIfAbsent(path, k -> new AtomicInteger()).incrementAndGet();
        int now = inFlight.incrementAndGet();
        maxInFlight.accumulateAndGet(now, Math::max);
        try {
            if (delayMillis > 0) {
                Thread.sleep(delayMillis);
            }
            AtomicInteger failures = failuresLeft.get(path);
            if (failures != null && failures.getAndDecrement() > 0) {
                send(ex, 500, new byte[0]);
                return;
            }
            byte[] body = files.get(path);
            if (body == null) {
                send(ex, 404, new byte[0]);
                return;
            }
            String range = ex.getRequestHeaders().getFirst("Range");
            if (rangeSupported && range != null && range.startsWith("bytes=")) {
                int from = Integer.parseInt(range.substring(6, range.indexOf('-')));
                if (from >= body.length) {
                    send(ex, 416, new byte[0]);
                    return;
                }
                byte[] rest = java.util.Arrays.copyOfRange(body, from, body.length);
                ex.getResponseHeaders().add("Content-Range", "bytes " + from + "-" + (body.length - 1) + "/" + body.length);
                send(ex, 206, rest);
                return;
            }
            send(ex, 200, body);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            inFlight.decrementAndGet();
        }
    }

    private static void send(HttpExchange ex, int status, byte[] body) throws IOException {
        ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
