package io.github.bandbridge;

import io.github.bandbridge.core.Probe;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A tiny MCP server (Streamable HTTP transport, JSON responses) on the phone.
 *
 *   POST /mcp   JSON-RPC 2.0: initialize, notifications/*, ping, tools/list, tools/call
 *   GET  /      plain-text status (no data)
 *
 * Every /mcp request must carry the token: "Authorization: Bearer <token>" (or ?token=… for clients
 * that cannot set headers). Wrong / missing token → 401. No third-party libraries.
 */
final class McpServer {

    static final String PROTOCOL = "2025-06-18";
    private static final String[] KNOWN = { "2025-06-18", "2025-03-26", "2024-11-05" };

    private final int port;
    private final String token;
    private final BandTools tools;
    private volatile ServerSocket ss;
    private volatile boolean running;
    private final ExecutorService pool = Executors.newFixedThreadPool(4);

    McpServer(int port, String token, BandTools tools) { this.port = port; this.token = token; this.tools = tools; }

    boolean isRunning() { return running; }

    void start() throws IOException {
        if (running) return;
        ServerSocket s = new ServerSocket();
        s.setReuseAddress(true);
        s.bind(new InetSocketAddress(port));
        ss = s;
        running = true;
        Thread t = new Thread(() -> {
            while (running) {
                try {
                    Socket c = s.accept();
                    pool.execute(() -> handle(c));
                } catch (IOException e) { if (running) Probe.post("mcp", "accept fail | " + e.getMessage()); }
            }
        }, "bandbridge-mcp");
        t.setDaemon(true);
        t.start();
        Probe.post("mcp", "listening | port " + port);
    }

    void stop() {
        running = false;
        try { if (ss != null) ss.close(); } catch (IOException ignored) { }
        ss = null;
    }

    /* ── HTTP ──────────────────────────────────────────────────────────────── */

    private void handle(Socket c) {
        try (Socket sock = c) {
            sock.setSoTimeout(15000);
            InputStream in = new BufferedInputStream(sock.getInputStream());
            String reqLine = readLine(in);
            if (reqLine == null || reqLine.isEmpty()) return;
            String[] rl = reqLine.split(" ");
            if (rl.length < 2) return;
            String method = rl[0], target = rl[1];
            Map<String, String> hd = new HashMap<>();
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int k = line.indexOf(':');
                if (k > 0) hd.put(line.substring(0, k).trim().toLowerCase(Locale.ROOT), line.substring(k + 1).trim());
            }
            String path = target, query = "";
            int q = target.indexOf('?');
            if (q >= 0) { path = target.substring(0, q); query = target.substring(q + 1); }
            OutputStream out = sock.getOutputStream();

            if ("OPTIONS".equals(method)) { reply(out, 204, "text/plain", ""); return; }
            if ("GET".equals(method) && "/".equals(path)) {
                reply(out, 200, "text/plain; charset=utf-8", "Huawei Band Bridge MCP server is running.\nEndpoint: POST /mcp (token required)\n");
                return;
            }
            if (!"/mcp".equals(path)) { reply(out, 404, "text/plain", "not found"); return; }
            if (!authorized(hd.get("authorization"), query)) { reply(out, 401, "application/json", "{\"error\":\"missing or wrong token\"}"); return; }
            if ("GET".equals(method)) { reply(out, 405, "text/plain", "this server does not open an SSE stream; use POST"); return; }
            if ("DELETE".equals(method)) { reply(out, 200, "text/plain", ""); return; }
            if (!"POST".equals(method)) { reply(out, 405, "text/plain", "method not allowed"); return; }

            int len = 0;
            try { len = Integer.parseInt(hd.getOrDefault("content-length", "0")); } catch (NumberFormatException ignored) { }
            if (len <= 0 || len > 1_000_000) { reply(out, 400, "text/plain", "bad body"); return; }
            byte[] body = new byte[len];
            int got = 0;
            while (got < len) { int r = in.read(body, got, len - got); if (r < 0) break; got += r; }
            String text = new String(body, 0, got, StandardCharsets.UTF_8).trim();

            if (text.startsWith("[")) {                                   // batch
                JSONArray reqs = new JSONArray(text), res = new JSONArray();
                for (int i = 0; i < reqs.length(); i++) { JSONObject r = rpc(reqs.getJSONObject(i)); if (r != null) res.put(r); }
                if (res.length() == 0) reply(out, 202, "text/plain", "");
                else reply(out, 200, "application/json", res.toString());
            } else {
                JSONObject r = rpc(new JSONObject(text));
                if (r == null) reply(out, 202, "text/plain", "");
                else reply(out, 200, "application/json", r.toString());
            }
        } catch (Throwable t) {
            Probe.post("mcp", "request fail | " + t.getClass().getSimpleName() + ":" + t.getMessage());
        }
    }

    private boolean authorized(String authHeader, String query) {
        String given = null;
        if (authHeader != null && authHeader.regionMatches(true, 0, "Bearer ", 0, 7)) given = authHeader.substring(7).trim();
        if (given == null) for (String kv : query.split("&")) if (kv.startsWith("token=")) given = java.net.URLDecoder.decode(kv.substring(6));
        if (given == null || token == null || token.isEmpty()) return false;
        return MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int ch;
        while ((ch = in.read()) >= 0) {
            if (ch == '\n') break;
            if (ch != '\r') b.write(ch);
            if (b.size() > 8192) break;
        }
        if (ch < 0 && b.size() == 0) return null;
        return b.toString("UTF-8");
    }

    private static void reply(OutputStream out, int code, String type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        String status = code == 200 ? "OK" : code == 202 ? "Accepted" : code == 204 ? "No Content" : code == 401 ? "Unauthorized"
                : code == 404 ? "Not Found" : code == 405 ? "Method Not Allowed" : code == 400 ? "Bad Request" : "Error";
        String head = "HTTP/1.1 " + code + " " + status + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + b.length + "\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Access-Control-Allow-Headers: Authorization, Content-Type, Mcp-Session-Id, Mcp-Protocol-Version\r\n"
                + "Access-Control-Allow-Methods: GET, POST, DELETE, OPTIONS\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(b);
        out.flush();
    }

    /* ── JSON-RPC ──────────────────────────────────────────────────────────── */

    /** null = notification (no response) */
    private JSONObject rpc(JSONObject req) {
        Object id = req.opt("id");
        String m = req.optString("method", "");
        try {
            if (id == null || id == JSONObject.NULL) return null;               // notifications/initialized etc.
            JSONObject p = req.optJSONObject("params");
            switch (m) {
                case "initialize": {
                    String want = p == null ? PROTOCOL : p.optString("protocolVersion", PROTOCOL);
                    String use = PROTOCOL;
                    for (String k : KNOWN) if (k.equals(want)) use = want;
                    JSONObject res = new JSONObject()
                            .put("protocolVersion", use)
                            .put("capabilities", new JSONObject().put("tools", new JSONObject().put("listChanged", false)))
                            .put("serverInfo", new JSONObject().put("name", "huawei-band-bridge").put("version", BuildInfo.VERSION))
                            .put("instructions", "Health data from the user's Huawei band, read directly over Bluetooth by their phone. "
                                    + "Use band_now for current values, band_day for a day or time window, band_sleep for a night, "
                                    + "band_stress / band_emotion for stress and mood, and band_hr_live for a beat-by-beat "
                                    + "heart-rate measurement (service 0x19 RRI stream; pass seconds to start one, omit to read). "
                                    + "Never invent values: null or empty means no record, and hr_live_not_running means nothing is being measured.");
                    return ok(id, res);
                }
                case "ping": return ok(id, new JSONObject());
                case "tools/list": return ok(id, new JSONObject().put("tools", BandTools.list()));
                case "tools/call": {
                    String name = p == null ? "" : p.optString("name", "");
                    JSONObject args = p == null ? null : p.optJSONObject("arguments");
                    try {
                        JSONObject r = tools.call(name, args);
                        return ok(id, new JSONObject().put("content", new JSONArray().put(new JSONObject().put("type", "text").put("text", r.toString())))
                                .put("isError", false));
                    } catch (IllegalArgumentException e) {
                        return err(id, -32602, e.getMessage());
                    } catch (Throwable t) {
                        return ok(id, new JSONObject().put("content", new JSONArray().put(new JSONObject().put("type", "text").put("text", "error: " + t.getMessage())))
                                .put("isError", true));
                    }
                }
                default: return err(id, -32601, "method not found: " + m);
            }
        } catch (Throwable t) {
            try { return err(id, -32603, String.valueOf(t.getMessage())); } catch (Throwable t2) { return null; }
        }
    }

    private static JSONObject ok(Object id, JSONObject result) throws Exception { return new JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result); }
    private static JSONObject err(Object id, int code, String msg) throws Exception {
        return new JSONObject().put("jsonrpc", "2.0").put("id", id).put("error", new JSONObject().put("code", code).put("message", msg));
    }
}
