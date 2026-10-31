import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Notifications for the people who manage servers: administrators see everything, operators (level 4) see the events of their own servers.
 * Each one is kept (the newest 300), pushed to open browsers right away, and counted as unread per person until they open the bell.
 */
final class Notify {
    private Notify() { }

    static final int KEEP = 300;

    record Note(String id, long at, String category, String severity, String serverId, String title, String text, Map<String, Object> action) {
        Map<String, Object> json() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("at", at);
            m.put("category", category);
            m.put("severity", severity);
            m.put("server", serverId == null ? "" : serverId);
            m.put("title", title);
            m.put("text", text);
            if (action != null) m.put("action", action);
            return m;
        }
    }

    private static final List<Note> notes = new ArrayList<>();
    private static final Map<String, Long> seen = new HashMap<>();
    private static final Map<String, Long> lastByKey = new HashMap<>();
    private static boolean loaded;

    private static Path file() { return Main.DATA.resolve("notifications.json"); }

    private static synchronized void load() {
        if (loaded) return;
        loaded = true;
        try {
            if (Files.isRegularFile(file())) {
                Map<String, Object> root = Json.obj(Json.parse(Files.readString(file(), StandardCharsets.UTF_8)));
                for (Object o : Json.arr(root.get("notes"))) {
                    Map<String, Object> m = Json.obj(o);
                    Object act = m.get("action");
                    notes.add(new Note(Json.str(m, "id"), m.get("at") instanceof Number n ? n.longValue() : 0, Json.str(m, "category"), Json.str(m, "severity"), Json.str(m, "server"),
                            Json.str(m, "title"), Json.str(m, "text"), act instanceof Map ? Json.obj(act) : null));
                }
                Map<String, Object> s = Json.obj(root.get("seen"));
                for (Map.Entry<String, Object> e : s.entrySet()) if (e.getValue() instanceof Number n) seen.put(e.getKey(), n.longValue());
            }
        } catch (IOException | RuntimeException e) {
            Main.log("could not read notifications.json: " + e);
        }
    }

    private static synchronized void save() {
        try {
            List<Object> l = new ArrayList<>();
            for (Note n : notes) l.add(n.json());
            Files.writeString(file(), Json.stringify(Json.map("notes", l, "seen", seen)), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Main.log("could not save notifications.json: " + e);
        }
    }

    /** Administrators, and operators (level 4) of the server the event is about. */
    static boolean visible(Users.Acct a, Note n) {
        if (a == null || a.disabled) return false;
        if (a.admin) return true;
        return n.serverId() != null && !n.serverId().isEmpty() && a.level(n.serverId()) >= 4;
    }

    /** severity: info, good, warn or bad. category: update, health, backup, system, security, player. action (optional): {type, label, ...} for a button in the list. */
    static Note post(String category, String severity, String serverId, String title, String text, Map<String, Object> action) {
        load();
        Note n = new Note(UUID.randomUUID().toString().substring(0, 8), System.currentTimeMillis(), category, severity, serverId == null ? "" : serverId, title, text == null ? "" : text, action);
        synchronized (Notify.class) {
            notes.add(n);
            while (notes.size() > KEEP) notes.remove(0);
            save();
        }
        Main.log("notification [" + category + "/" + severity + "] " + (serverId == null || serverId.isEmpty() ? "" : serverId + ": ") + title);
        push(n);
        try { External.deliver(n); } catch (Throwable t) { Main.log("notification delivery: " + t); }
        return n;
    }

    /** Same as post, but at most once per cooldown for the same key (so a repeating condition does not flood the list). */
    static Note postOnce(String key, long cooldownMs, String category, String severity, String serverId, String title, String text, Map<String, Object> action) {
        long now = System.currentTimeMillis();
        synchronized (Notify.class) {
            Long last = lastByKey.get(key);
            if (last != null && now - last < cooldownMs) return null;
            lastByKey.put(key, now);
        }
        return post(category, severity, serverId, title, text, action);
    }

    private static void push(Note n) {
        String msg = "event: notify\ndata: " + Json.stringify(n.json()) + "\n\n";
        for (Main.Client c : Main.clients) {
            Users.Acct a = Users.byId(c.uid());
            if (!visible(a, n)) continue;
            if (!c.q().offer(msg)) Main.clients.remove(c);
        }
    }

    static synchronized List<Map<String, Object>> listFor(Users.Acct me, int limit) {
        load();
        List<Map<String, Object>> out = new ArrayList<>();
        long since = seen.getOrDefault(me.id, 0L);
        for (int i = notes.size() - 1; i >= 0 && out.size() < limit; i--) {
            Note n = notes.get(i);
            if (!visible(me, n)) continue;
            Map<String, Object> m = n.json();
            m.put("unread", n.at() > since);
            out.add(m);
        }
        return out;
    }

    static synchronized int unread(Users.Acct me) {
        load();
        long since = seen.getOrDefault(me.id, 0L);
        int c = 0;
        for (Note n : notes) if (n.at() > since && visible(me, n)) c++;
        return c;
    }

    static synchronized void markSeen(Users.Acct me) {
        load();
        seen.put(me.id, System.currentTimeMillis());
        save();
    }

    static synchronized void clear(Users.Acct me) {
        load();
        notes.removeIf(n -> visible(me, n));
        seen.put(me.id, System.currentTimeMillis());
        save();
    }

    static final List<String> CATEGORIES = List.of("update", "health", "backup", "system", "security", "player");
    private static int rank(String severity) { return "bad".equals(severity) ? 2 : "warn".equals(severity) ? 1 : 0; }

    /** The webhook settings (administrators): url, format discord | json, categories, minSeverity info | warn | bad. */
    static synchronized Map<String, Object> webhook() {
        Object o = Main.cfg.root.get("notify");
        Map<String, Object> n = o instanceof Map ? Json.obj(o) : new LinkedHashMap<>();
        Map<String, Object> w = n.get("webhook") instanceof Map ? Json.obj(n.get("webhook")) : new LinkedHashMap<>();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("url", w.get("url") instanceof String s ? s : "");
        out.put("format", "json".equals(w.get("format")) ? "json" : "discord");
        List<Object> cats = new ArrayList<>();
        if (w.get("categories") instanceof List<?> l) { for (Object c : l) if (c instanceof String s && CATEGORIES.contains(s)) cats.add(s); } else cats.addAll(CATEGORIES);
        out.put("categories", cats);
        out.put("minSeverity", "bad".equals(w.get("minSeverity")) ? "bad" : "warn".equals(w.get("minSeverity")) ? "warn" : "info");
        out.put("lastError", w.get("lastError") instanceof String s ? s : "");
        return out;
    }

    static synchronized void setWebhook(Map<String, Object> b) throws IOException {
        Map<String, Object> cur = webhook();
        if (b.containsKey("url")) {
            String u = String.valueOf(b.get("url")).strip();
            if (!u.isEmpty()) checkUrl(u);
            cur.put("url", u);
        }
        if (b.containsKey("format")) cur.put("format", "json".equals(b.get("format")) ? "json" : "discord");
        if (b.containsKey("categories")) {
            List<Object> cats = new ArrayList<>();
            for (Object c : Json.arr(b.get("categories"))) if (c instanceof String s && CATEGORIES.contains(s) && !cats.contains(s)) cats.add(s);
            cur.put("categories", cats);
        }
        if (b.containsKey("minSeverity")) cur.put("minSeverity", "bad".equals(b.get("minSeverity")) ? "bad" : "warn".equals(b.get("minSeverity")) ? "warn" : "info");
        Map<String, Object> n = Main.cfg.root.get("notify") instanceof Map ? Json.obj(Main.cfg.root.get("notify")) : new LinkedHashMap<>();
        n.put("webhook", cur);
        Main.cfg.root.put("notify", n);
        Main.cfg.save();
    }

    static void checkUrl(String u) {
        java.net.URI uri;
        try { uri = java.net.URI.create(u); } catch (IllegalArgumentException e) { throw new Api(400, "That is not a valid web address."); }
        String sch = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!(sch.equals("https") || sch.equals("http")) || uri.getHost() == null) throw new Api(400, "The webhook address must start with https:// (or http://).");
        if (u.length() > 600) throw new Api(400, "That web address is too long.");
    }

    private static final java.net.http.HttpClient HTTP = java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(8)).build();

    private static String clean(String s) { return s == null ? "" : s.replace("@", "@\u200b"); }

    static String payload(Map<String, Object> w, Note n) {
        String serverName = "";
        for (Srv s : Main.servers) if (s.id.equals(n.serverId())) serverName = s.name;
        if ("json".equals(w.get("format")))
            return Json.stringify(Json.map("title", n.title(), "text", n.text(), "severity", n.severity(), "category", n.category(), "server", n.serverId(), "serverName", serverName, "time", n.at()));
        int color = switch (n.severity() == null ? "info" : n.severity()) { case "bad" -> 0xEF6A6A; case "warn" -> 0xE0B341; case "good" -> 0x4CAF6A; default -> 0x6FB3FF; };
        Map<String, Object> embed = Json.map("title", clean(n.title()), "description", clean(n.text()), "color", color);
        if (!serverName.isEmpty()) embed.put("footer", Json.map("text", clean(serverName)));
        return Json.stringify(Json.map("username", "MC Server Manager", "allowed_mentions", Json.map("parse", new ArrayList<Object>()), "embeds", List.of(embed)));
    }

    /** Sends one message to the webhook now and returns an error text, or "" when it was accepted. */
    static String sendWebhook(Map<String, Object> w, Note n) {
        String url = String.valueOf(w.get("url"));
        try {
            java.net.http.HttpResponse<String> r = HTTP.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(url)).timeout(java.time.Duration.ofSeconds(10))
                    .header("Content-Type", "application/json").header("User-Agent", "MC-Server-Manager").POST(java.net.http.HttpRequest.BodyPublishers.ofString(payload(w, n), StandardCharsets.UTF_8)).build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            return r.statusCode() / 100 == 2 ? "" : "The webhook answered " + r.statusCode();
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return String.valueOf(e.getMessage());
        }
    }

    private static synchronized void rememberWebhookError(String err) {
        Map<String, Object> n = Main.cfg.root.get("notify") instanceof Map ? Json.obj(Main.cfg.root.get("notify")) : null;
        if (n == null || !(n.get("webhook") instanceof Map)) return;
        Json.obj(n.get("webhook")).put("lastError", err);
        try { Main.cfg.save(); } catch (IOException ignored) { }
    }

    /** Delivery outside the browser: the webhook (desktop notifications are the browser's job and need no server side). */
    static final class External {
        private External() { }

        /** True when the webhook settings w want an event of this category and severity. */
        static boolean wants(Map<String, Object> w, String category, String severity) {
            return !String.valueOf(w.get("url")).isEmpty() && ((List<?>) w.get("categories")).contains(category) && rank(severity) >= rank(String.valueOf(w.get("minSeverity")));
        }

        static void deliver(Note n) {
            Map<String, Object> w = webhook();
            if (!wants(w, n.category(), n.severity())) return;
            Thread.startVirtualThread(() -> {
                String err = sendWebhook(w, n);
                if (!err.equals(String.valueOf(w.get("lastError")))) rememberWebhookError(err);
                if (!err.isEmpty()) Main.log("webhook: " + err);
            });
        }
    }
}