package com.solcesto.game;

import android.content.res.AssetManager;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Minimal embedded HTTP server that serves the game from assets/www.
 * WebView cannot load ES modules over file:// (CORS blocks them), so the
 * game must be served over http://127.0.0.1.
 */
public class LocalHttpServer {

    private static final String TAG = "C3Server";
    private static final String DOC_ROOT = "www";
    private static final int BUFFER_SIZE = 64 * 1024;

    private ServerSocket serverSocket;
    private final AssetManager assets;
    private final ExecutorService pool;
    private volatile boolean running;

    public LocalHttpServer(AssetManager assets) {
        this.assets = assets;
        this.pool = Executors.newCachedThreadPool();
    }

    /**
     * Starts the server on a FIXED loopback port.
     *
     * IMPORTANT: localStorage is scoped per origin (scheme + host + PORT).
     * If the port changes on every launch, the game save data / settings /
     * language preference are silently lost because the WebView sees a
     * different origin each run. We therefore try a fixed port first and
     * only fall back to nearby fixed ports (not an ephemeral port) when it
     * is taken, so the origin stays stable across launches in practice.
     */
    private static final int BASE_PORT = 18929;
    private static final int PORT_ATTEMPTS = 16;

    public int start() throws IOException {
        IOException last = null;
        for (int i = 0; i < PORT_ATTEMPTS; i++) {
            int candidate = BASE_PORT + i;
            try {
                serverSocket = new ServerSocket(candidate, 50, InetAddress.getByName("127.0.0.1"));
                running = true;
                new Thread(this::acceptLoop, "c3-http-accept").start();
                Log.i(TAG, "HTTP server started on 127.0.0.1:" + candidate);
                return candidate;
            } catch (IOException e) {
                last = e;
                Log.w(TAG, "port " + candidate + " busy, trying next");
            }
        }
        throw new IOException("no free fixed port available (all " + PORT_ATTEMPTS + " busy)", last);
    }

    private void acceptLoop() {
        while (running) {
            try {
                final Socket socket = serverSocket.accept();
                pool.execute(() -> handle(socket));
            } catch (IOException e) {
                if (running) {
                    Log.e(TAG, "accept failed: " + e.getMessage());
                }
                break;
            }
        }
    }

    private void handle(Socket socket) {
        try (Socket s = socket;
             InputStream rawIn = s.getInputStream();
             OutputStream out = s.getOutputStream()) {

            BufferedReader reader = new BufferedReader(new InputStreamReader(rawIn, StandardCharsets.ISO_8859_1), 8192);
            String requestLine = reader.readLine();
            if (requestLine == null || requestLine.isEmpty()) {
                return;
            }

            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                return;
            }
            String method = parts[0].toUpperCase();
            String path = parts[1];

            // parse headers (need Range for video)
            long rangeStart = -1;
            long rangeEnd = -1;
            boolean hasRange = false;
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                if (line.regionMatches(true, 0, "Range:", 0, 6)) {
                    hasRange = true;
                    String spec = line.substring(6).trim();
                    int eq = spec.indexOf('=');
                    if (eq >= 0) {
                        String range = spec.substring(eq + 1).trim();
                        String[] bounds = range.split("-");
                        try {
                            rangeStart = Long.parseLong(bounds[0].trim());
                            if (bounds.length > 1 && !bounds[1].trim().isEmpty()) {
                                rangeEnd = Long.parseLong(bounds[1].trim());
                            }
                        } catch (NumberFormatException ignored) {
                            // invalid range -> ignore
                        }
                    }
                }
            }

            if (!"GET".equals(method) && !"HEAD".equals(method)) {
                writeSimple(out, 405, "text/plain", "Method Not Allowed".getBytes(StandardCharsets.UTF_8), false);
                return;
            }

            String decoded = path;
            try {
                decoded = URLDecoder.decode(path, "UTF-8");
            } catch (IllegalArgumentException e) {
                // keep raw path
            }
            if (decoded.contains("..")) {
                writeSimple(out, 403, "text/plain", "Forbidden".getBytes(StandardCharsets.UTF_8), false);
                return;
            }

            // map URL path to asset
            String assetPath = DOC_ROOT + decoded;
            if (assetPath.endsWith("/")) {
                assetPath += "index.html";
            }
            if (assetPath.equals(DOC_ROOT)) {
                assetPath += "/index.html";
            }

            byte[] content;
            long contentLength;
            InputStream assetIn;
            try {
                assetIn = assets.open(assetPath, AssetManager.ACCESS_RANDOM);
                contentLength = assetIn.available();
                // for ranged requests, read the slice
                if (hasRange && rangeStart >= 0) {
                    long start = rangeStart;
                    long end = rangeEnd >= 0 ? rangeEnd : contentLength - 1;
                    if (start >= contentLength) {
                        assetIn.close();
                        writeSimple(out, 416, "text/plain", "Range Not Satisfiable".getBytes(StandardCharsets.UTF_8), false);
                        return;
                    }
                    if (end >= contentLength) {
                        end = contentLength - 1;
                    }
                    long skip = start;
                    while (skip > 0) {
                        long n = assetIn.skip(skip);
                        if (n <= 0) break;
                        skip -= n;
                    }
                    long len = end - start + 1;
                    if (method.equals("HEAD")) {
                        writeRangeHeader(out, 206, mimeFor(assetPath), len, start, end, contentLength);
                        assetIn.close();
                        return;
                    }
                    writeRangeHeader(out, 206, mimeFor(assetPath), len, start, end, contentLength);
                    copy(assetIn, out, len);
                    assetIn.close();
                    return;
                }
                if (method.equals("HEAD")) {
                    writeHeader(out, 200, mimeFor(assetPath), contentLength);
                    assetIn.close();
                    return;
                }
                writeHeader(out, 200, mimeFor(assetPath), contentLength);
                copy(assetIn, out, contentLength);
                assetIn.close();
            } catch (IOException e) {
                writeSimple(out, 404, "text/plain", "Not Found".getBytes(StandardCharsets.UTF_8), false);
            }
        } catch (Exception e) {
            Log.e(TAG, "handle failed: " + e.getMessage());
        }
    }

    private static void copy(InputStream in, OutputStream out, long total) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        long remaining = total;
        while (remaining > 0) {
            int toRead = (int) Math.min(buffer.length, remaining);
            int n = in.read(buffer, 0, toRead);
            if (n < 0) break;
            out.write(buffer, 0, n);
            remaining -= n;
        }
        out.flush();
    }

    private static void writeHeader(OutputStream out, int status, String mime, long length) throws IOException {
        String reason = status == 200 ? "OK" : "Not Found";
        String resp = "HTTP/1.1 " + status + " " + reason + "\r\n"
                + "Content-Type: " + mime + "\r\n"
                + "Content-Length: " + length + "\r\n"
                + "Accept-Ranges: bytes\r\n"
                + "Cache-Control: no-cache\r\n"
                + "Connection: close\r\n"
                + "\r\n";
        out.write(resp.getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    private static void writeRangeHeader(OutputStream out, int status, String mime, long length, long start, long end, long total) throws IOException {
        String resp = "HTTP/1.1 206 Partial Content\r\n"
                + "Content-Type: " + mime + "\r\n"
                + "Content-Length: " + length + "\r\n"
                + "Content-Range: bytes " + start + "-" + end + "/" + total + "\r\n"
                + "Accept-Ranges: bytes\r\n"
                + "Cache-Control: no-cache\r\n"
                + "Connection: close\r\n"
                + "\r\n";
        out.write(resp.getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    private static void writeSimple(OutputStream out, int status, String mime, byte[] body, boolean isHead) throws IOException {
        writeHeader(out, status, mime, body.length);
        if (!isHead) {
            out.write(body);
            out.flush();
        }
    }

    private static String mimeFor(String path) {
        String lower = path.toLowerCase();
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html; charset=utf-8";
        if (lower.endsWith(".js")) return "text/javascript; charset=utf-8";
        if (lower.endsWith(".mjs")) return "text/javascript; charset=utf-8";
        if (lower.endsWith(".css")) return "text/css; charset=utf-8";
        if (lower.endsWith(".json")) return "application/json; charset=utf-8";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".webm")) return "video/webm";
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".ogg")) return "audio/ogg";
        if (lower.endsWith(".wav")) return "audio/wav";
        if (lower.endsWith(".woff")) return "font/woff";
        if (lower.endsWith(".woff2")) return "font/woff2";
        if (lower.endsWith(".ttf")) return "font/ttf";
        if (lower.endsWith(".txt")) return "text/plain; charset=utf-8";
        return "application/octet-stream";
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {
            // closing
        }
        pool.shutdownNow();
    }
}
