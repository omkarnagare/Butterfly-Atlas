package com.butterflyatlas.app.testing;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Sends one HTTP/1.1 request over a plain socket. The JDK HttpClient refuses to set the Host
 * header, which the DNS-rebinding tests need to control. Bodies are assumed ASCII (JSON here).
 */
public final class RawHttp {

    public record Response(int status, String body, Map<String, String> headers) {
    }

    private RawHttp() {
    }

    public static Response send(int port, String method, String path, Map<String, String> headers, String body)
            throws IOException {
        byte[] content = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        Map<String, String> all = new LinkedHashMap<>();
        all.put("Host", "127.0.0.1:" + port);
        headers.forEach((name, value) -> {
            all.keySet().removeIf(existing -> existing.equalsIgnoreCase(name));
            all.put(name, value);
        });
        all.put("Connection", "close");
        if (body != null) {
            all.put("Content-Length", String.valueOf(content.length));
        }
        StringBuilder head = new StringBuilder(method).append(' ').append(path).append(" HTTP/1.1\r\n");
        all.forEach((name, value) -> head.append(name).append(": ").append(value).append("\r\n"));
        head.append("\r\n");
        try (Socket socket = new Socket(InetAddress.getByName("127.0.0.1"), port)) {
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();
            out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
            out.write(content);
            out.flush();
            return parse(socket.getInputStream().readAllBytes());
        }
    }

    private static Response parse(byte[] raw) throws IOException {
        int split = -1;
        for (int i = 0; i + 3 < raw.length; i++) {
            if (raw[i] == '\r' && raw[i + 1] == '\n' && raw[i + 2] == '\r' && raw[i + 3] == '\n') {
                split = i;
                break;
            }
        }
        if (split < 0) {
            throw new IOException("Incomplete HTTP response");
        }
        String[] lines = new String(raw, 0, split, StandardCharsets.ISO_8859_1).split("\r\n");
        int status = Integer.parseInt(lines[0].split(" ")[1]);
        boolean chunked = Arrays.stream(lines).map(l -> l.toLowerCase(Locale.ROOT))
                .anyMatch(l -> l.startsWith("transfer-encoding:") && l.contains("chunked"));
        int start = split + 4;
        byte[] body = chunked ? dechunk(raw, start) : Arrays.copyOfRange(raw, start, raw.length);
        Map<String, String> headers = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0) {
                headers.put(lines[i].substring(0, colon).toLowerCase(Locale.ROOT), lines[i].substring(colon + 1).strip());
            }
        }
        return new Response(status, new String(body, StandardCharsets.UTF_8), headers);
    }

    private static byte[] dechunk(byte[] raw, int start) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int at = start;
        while (true) {
            int lineEnd = at;
            while (lineEnd + 1 < raw.length && !(raw[lineEnd] == '\r' && raw[lineEnd + 1] == '\n')) {
                lineEnd++;
            }
            if (lineEnd + 1 >= raw.length) {
                throw new IOException("Incomplete HTTP response");
            }
            int size = Integer.parseInt(new String(raw, at, lineEnd - at, StandardCharsets.ISO_8859_1).split(";")[0].trim(), 16);
            if (size == 0) {
                return out.toByteArray();
            }
            if (lineEnd + 2 + size > raw.length) {
                throw new IOException("Incomplete HTTP response");
            }
            out.write(raw, lineEnd + 2, size);
            at = lineEnd + 2 + size + 2;
        }
    }
}
