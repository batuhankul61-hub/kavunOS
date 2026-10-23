package com.kavunos.tv;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A one-page web server on the local network for "Add from phone". It runs only while that screen is open
 * and answers only requests that carry its random token. The page adds pictures (one per POST; every one is
 * re-encoded here, scaled to cover 1920x1080, so nothing the phone sends is stored as is) and shows the
 * collection as thumbnails to leave out of the rotation or delete.
 */
final class PhoneUploadServer {

    /** What the page may read and change in the collection; names are checked against it. */
    interface Library {
        /** JSON array of {"n": name, "h": hidden} in grid order. */
        String list();

        /** A small JPEG of the picture, or null for a name that is not in the collection. */
        byte[] thumb(String name);

        boolean setHidden(String name, boolean hidden);

        boolean delete(String name);

        /** After every change, on the server thread. */
        void changed();
    }

    private static final String TAG = "TvHome.PhoneUpload";
    static final int MAX_UPLOAD_BYTES = 15 * 1024 * 1024;
    private static final int MAX_HEADER_BYTES = 8 * 1024;
    private static final int TARGET_W = 1920, TARGET_H = 1080;

    private final File folder;
    private final String page;
    private final Library library;
    private final String token;
    private ServerSocket socket;
    private volatile boolean running;
    private int count;

    PhoneUploadServer(File folder, String page, Library library) {
        this.folder = folder;
        this.page = page;
        this.library = library;
        byte[] random = new byte[6];
        new SecureRandom().nextBytes(random);
        StringBuilder t = new StringBuilder();
        for (byte b : random) t.append(String.format(Locale.ROOT, "%02x", b));
        token = t.toString();
    }

    /** Starts listening on {@code address}; returns the URL to open on the phone. */
    String start(InetAddress address) throws IOException {
        socket = new ServerSocket(0, 8, address);
        running = true;
        Thread thread = new Thread(this::serve, "phone-upload");
        thread.setDaemon(true);
        thread.start();
        return "http://" + address.getHostAddress() + ":" + socket.getLocalPort() + "/?t=" + token;
    }

    void stop() {
        running = false;
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
    }

    private void serve() {
        while (running) {
            try (Socket client = socket.accept()) {
                client.setSoTimeout(30_000);
                handle(client.getInputStream(), client.getOutputStream());
            } catch (IOException e) {
                if (running) Log.w(TAG, "request failed", e);
            }
        }
    }

    private void handle(InputStream in, OutputStream out) throws IOException {
        String head = readHead(in);
        String[] request = head == null ? new String[0] : head.split("\r\n")[0].split(" ");
        if (request.length < 2) {
            respond(out, 400, "text/plain", "bad request".getBytes(StandardCharsets.UTF_8));
            return;
        }
        String method = request[0];
        String target = request[1];
        int q = target.indexOf('?');
        String path = q < 0 ? target : target.substring(0, q);
        Map<String, String> query = query(q < 0 ? "" : target.substring(q + 1));
        String given = query.get("t");
        if (given == null || !MessageDigest.isEqual(given.getBytes(StandardCharsets.US_ASCII),
                token.getBytes(StandardCharsets.US_ASCII))) {
            text(out, 403, "forbidden");
            return;
        }
        String name = query.get("n");
        switch (method + " " + path) {
            case "GET /":
                respond(out, 200, "text/html; charset=utf-8", page.getBytes(StandardCharsets.UTF_8));
                return;
            case "GET /list":
                respond(out, 200, "application/json", library.list().getBytes(StandardCharsets.UTF_8));
                return;
            case "GET /thumb": {
                byte[] jpeg = name == null ? null : library.thumb(name);
                if (jpeg == null) text(out, 404, "not found");
                else respond(out, 200, "image/jpeg", jpeg);
                return;
            }
            case "POST /hide":
                answer(out, name != null && library.setHidden(name, "1".equals(query.get("v"))));
                return;
            case "POST /delete":
                answer(out, name != null && library.delete(name));
                return;
            case "POST /upload":
                upload(head, in, out);
                return;
            default:
                text(out, 404, "not found");
        }
    }

    private void answer(OutputStream out, boolean ok) throws IOException {
        if (ok) library.changed();
        text(out, ok ? 200 : 404, ok ? "ok" : "not found");
    }

    private void upload(String head, InputStream in, OutputStream out) throws IOException {
        long length = -1;
        for (String line : head.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("content-length")) {
                try {
                    length = Long.parseLong(line.substring(colon + 1).trim());
                } catch (NumberFormatException ignored) {
                }
            }
        }
        if (length <= 0 || length > MAX_UPLOAD_BYTES) {
            text(out, 413, "too large");
            return;
        }
        byte[] body = new byte[(int) length];
        int read = 0;
        while (read < body.length) {
            int n = in.read(body, read, body.length - read);
            if (n < 0) break;
            read += n;
        }
        if (read != body.length) {
            text(out, 400, "short body");
            return;
        }
        if (WallpaperRotation.images(folder).size() >= 100) {
            text(out, 507, "full");
            return;
        }
        if (!save(body)) {
            text(out, 415, "not a picture");
            return;
        }
        count++;
        library.changed();
        text(out, 200, "ok");
    }

    /** Decodes, scales to cover 1920x1080 and writes a fresh JPEG; false when it is not a picture. */
    private boolean save(byte[] body) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(body, 0, body.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = 1;
        while (bounds.outWidth / (opts.inSampleSize * 2) >= TARGET_W
                && bounds.outHeight / (opts.inSampleSize * 2) >= TARGET_H) opts.inSampleSize *= 2;
        Bitmap decoded = BitmapFactory.decodeByteArray(body, 0, body.length, opts);
        if (decoded == null) return false;
        float scale = Math.max((float) TARGET_W / decoded.getWidth(), (float) TARGET_H / decoded.getHeight());
        Bitmap scaled = scale < 1f
                ? Bitmap.createScaledBitmap(decoded, Math.round(decoded.getWidth() * scale),
                Math.round(decoded.getHeight() * scale), true)
                : decoded;
        if (!folder.isDirectory() && !folder.mkdirs()) return false;
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
        File target = new File(folder, "phone-" + stamp + "-" + (count + 1) + ".jpg");
        File partial = new File(folder, "." + target.getName() + ".part");
        try (FileOutputStream file = new FileOutputStream(partial)) {
            scaled.compress(Bitmap.CompressFormat.JPEG, 90, file);
        } finally {
            if (scaled != decoded) scaled.recycle();
            decoded.recycle();
        }
        return partial.renameTo(target);
    }

    private static Map<String, String> query(String query) {
        Map<String, String> result = new HashMap<>();
        for (String part : query.split("&")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            try {
                result.put(part.substring(0, eq), URLDecoder.decode(part.substring(eq + 1), "UTF-8"));
            } catch (UnsupportedEncodingException | IllegalArgumentException ignored) {
            }
        }
        return result;
    }

    /** Reads up to the blank line after the headers; null when it never comes. */
    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int state = 0;
        while (head.size() < MAX_HEADER_BYTES) {
            int b = in.read();
            if (b < 0) return null;
            head.write(b);
            state = (b == '\r' && (state == 0 || state == 2)) ? state + 1
                    : (b == '\n' && (state == 1 || state == 3)) ? state + 1 : 0;
            if (state == 4) return head.toString(StandardCharsets.ISO_8859_1.name());
        }
        return null;
    }

    private static void text(OutputStream out, int status, String body) throws IOException {
        respond(out, status, "text/plain", body.getBytes(StandardCharsets.UTF_8));
    }

    private static void respond(OutputStream out, int status, String type, byte[] body) throws IOException {
        String head = "HTTP/1.1 " + status + " X\r\nContent-Type: " + type + "\r\nContent-Length: " + body.length
                + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }
}
