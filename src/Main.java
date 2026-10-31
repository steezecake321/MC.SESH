import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import java.util.zip.GZIPOutputStream;

public final class Main {
    static Path HOME, DATA, WEB, TOOLS, RUNTIME;
    private static final long STARTED = System.currentTimeMillis();

    static long uptimeMs() { return System.currentTimeMillis() - STARTED; }
    static Config cfg;
    static final Sys.Sampler sampler = new Sys.Sampler();
    static final List<Srv> servers = new CopyOnWriteArrayList<>();
    static final Map<String, Job> jobs = Collections.synchronizedMap(new LinkedHashMap<>());
    record Client(BlockingQueue<String> q, String uid) { }

    static final List<Client> clients = new CopyOnWriteArrayList<>();

    private static final Pattern SERVER_ROUTE = Pattern.compile("^/api/servers/([a-z0-9-]+)(?:/(.*))?$");
    private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");
    private static final Pattern PROP_KEY = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    public static void main(String[] args) throws Exception {
        HOME = Path.of(System.getProperty("mgr.home", appHome())).toAbsolutePath();
        DATA = Path.of(System.getProperty("mgr.data", HOME.resolve("data").toString())).toAbsolutePath();
        WEB = HOME.resolve("web");
        TOOLS = HOME.resolve("tools");
        RUNTIME = HOME.resolve("runtime");
        if (args.length > 0 && args[0].equals("--setup")) {   // the browser-based installer; it must not create a data folder beside the package
            SetupWizard.run();
            return;
        }
        Files.createDirectories(DATA);
        cfg = Config.load(DATA.resolve("config.json"));
        if (args.length > 0 && (args[0].equals("--owner") || args[0].equals("--reset-password"))) {
            Owner.run(args);
            return;
        }
        if (args.length > 1 && args[0].equals("--provision")) {
            Provision.run(Path.of(args[1]));
            return;
        }
        Users.migrateLegacy(cfg);
        writeShutdownToken();
        for (Map<String, Object> s : cfg.servers()) servers.add(new Srv(s));
        for (Srv s : servers) s.log.poll(null, s::onLine);
        Thread.startVirtualThread(() -> { for (Srv s : List.copyOf(servers)) if (s.hasGeyser()) Geyser.reconcile(s); });

        ScheduledExecutorService sch = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "scheduler");
            t.setDaemon(true);
            return t;
        });
        sch.scheduleWithFixedDelay(() -> {
            try {
                for (Srv s : servers) {
                    s.log.poll(new LogTail.Sink() {
                        public void lines(long first, List<String> l) {
                            if (!clients.isEmpty()) broadcast("log", Json.map("server", s.id, "first", first, "lines", l), s.id);
                        }

                        public void reset() { broadcast("reset", Json.map("server", s.id), s.id); }
                    }, s::onLine);
                }
            } catch (Throwable t) { log("log poll: " + t); }
        }, 1, 1, TimeUnit.SECONDS);
        sch.scheduleWithFixedDelay(() -> {
            try {
                if (!clients.isEmpty()) broadcastStatus();
            } catch (Throwable t) { log("status push: " + t); }
        }, 3, 3, TimeUnit.SECONDS);

        sch.scheduleWithFixedDelay(() -> {
            try { UpdateWatch.tick(); } catch (Throwable t) { log("update watch: " + t); }
        }, 60, 60, TimeUnit.SECONDS);
        sch.scheduleWithFixedDelay(() -> {
            try { LanBroadcast.tick(); } catch (Throwable t) { log("lan broadcast: " + t); }
        }, 6000, 1500, TimeUnit.MILLISECONDS);
        sch.scheduleWithFixedDelay(() -> {
            try { AutoBackup.tick(); } catch (Throwable t) { log("auto-backup: " + t); }
        }, 45, 60, TimeUnit.SECONDS);
        sch.scheduleWithFixedDelay(() -> {
            try { AutoRestart.tick(); } catch (Throwable t) { log("auto-restart: " + t); }
        }, 50, 30, TimeUnit.SECONDS);
        sch.scheduleWithFixedDelay(() -> {
            try { CrashWatch.tick(); } catch (Throwable t) { log("crash watch: " + t); }
        }, 12, 5, TimeUnit.SECONDS);

        HttpServer http;
        try {
            http = HttpServer.create(new InetSocketAddress(InetAddress.getByName(cfg.bind()), cfg.port()), 64);
        } catch (java.net.BindException e) {
            // Another copy already owns the port (for example a second start from Windows after one was already running): leave it alone.
            log("port " + cfg.port() + " is already in use, so another manager is probably running. This copy is exiting without doing anything.");
            Runtime.getRuntime().halt(0);
            return;
        }
        http.createContext("/", Main::handle);
        http.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        http.start();
        log("Manager started on " + cfg.bind() + ":" + cfg.port() + " with " + servers.size() + " server(s)");
        Thread.startVirtualThread(() -> {
            try {
                Thread.sleep(8000);
                for (Srv s : List.copyOf(servers)) {
                    if (!s.autostart() || s.running(true)) continue;
                    log("auto-starting " + s.id);
                    Job j = submit(s, "Auto-start", s::start);
                    while (j.active()) Thread.sleep(2000);
                    if ("error".equals(j.state)) Notify.post("health", "bad", s.id, s.name + " did not start", String.join(" ", j.lines.subList(Math.max(0, j.lines.size() - 2), j.lines.size())), Json.map("type", "open", "server", s.id, "tab", "console", "label", "Open " + s.name));
                }
            } catch (Throwable t) { log("auto-start: " + t); }
        });
    }

    static synchronized Srv addServer(Map<String, Object> entry) throws IOException {
        cfg.addServer(entry);
        Srv s = new Srv(entry);
        s.log.poll(null, s::onLine);
        servers.add(s);
        return s;
    }

    static synchronized void removeServer(Srv s) throws IOException {
        cfg.removeServer(s.id);
        servers.remove(s);
        Users.dropServer(s.id);
    }

    private static String shutdownToken;

    /**
     * A random token in data\shutdown.token lets the installer (running as the same Windows user, even without administrator rights) ask the
     * manager to close itself, which a normal window cannot do to a manager started by the elevated boot task.
     */
    private static void writeShutdownToken() {
        try {
            byte[] b = new byte[24];
            new java.security.SecureRandom().nextBytes(b);
            shutdownToken = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
            Path tf = DATA.resolve("shutdown.token");
            Files.writeString(tf, shutdownToken, StandardCharsets.UTF_8);
            try {
                java.nio.file.attribute.AclFileAttributeView v = Files.getFileAttributeView(tf, java.nio.file.attribute.AclFileAttributeView.class);
                if (v != null) {
                    java.nio.file.attribute.AclEntry e = java.nio.file.attribute.AclEntry.newBuilder().setType(java.nio.file.attribute.AclEntryType.ALLOW).setPrincipal(v.getOwner())
                            .setPermissions(EnumSet.allOf(java.nio.file.attribute.AclEntryPermission.class)).build();
                    List<java.nio.file.attribute.AclEntry> acl = new ArrayList<>(List.of(e));
                    // the manager runs with administrator rights, which makes the Administrators group the file's owner; the same account without those rights (Stop.bat, updates) must still be able to read it
                    try {
                        String dom = System.getenv("USERDOMAIN"), who = (dom == null || dom.isBlank() ? "" : dom + "\\") + System.getProperty("user.name");
                        java.nio.file.attribute.UserPrincipal me = tf.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(who);
                        if (!me.equals(v.getOwner()))
                            acl.add(java.nio.file.attribute.AclEntry.newBuilder().setType(java.nio.file.attribute.AclEntryType.ALLOW).setPrincipal(me).setPermissions(EnumSet.allOf(java.nio.file.attribute.AclEntryPermission.class)).build());
                    } catch (IOException | RuntimeException ignored) { }
                    v.setAcl(acl);
                }
            } catch (IOException | RuntimeException ignored) { }
        } catch (IOException e) {
            log("could not write the shutdown token: " + e);
        }
    }

    private static void shutdownApi(HttpExchange ex, String method) throws IOException {
        requirePost(method);
        String given = ex.getRequestHeaders().getFirst("X-Shutdown-Token");
        boolean ok = shutdownToken != null && given != null && isLocal(ex)
                && java.security.MessageDigest.isEqual(shutdownToken.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8));
        if (!ok) {
            log("refused a shutdown request from " + ex.getRemoteAddress().getAddress().getHostAddress());
            throw new Api(403, "Not allowed");
        }
        log("shutting down: asked by a local program (installer or Stop.bat). Minecraft servers keep running.");
        sendJson(ex, 200, Json.map("ok", true));
        Thread.startVirtualThread(() -> {
            try { Thread.sleep(500); } catch (InterruptedException ignored) { }
            Runtime.getRuntime().halt(0);
        });
    }

    /**
     * Restarts the manager itself; Minecraft servers keep running and are picked up again. The new copy is started by a helper
     * created through WMI so it lives outside the boot task's process group and survives this process ending.
     */
    private static void restartManager(HttpExchange ex) throws Exception {
        Path bat = HOME.resolve("Start.bat");
        if (!Files.isRegularFile(bat)) throw new Api(409, "This copy of the manager was not started from an installed folder, so it cannot restart itself. Use Stop.bat and Start.bat instead.");
        String cmd = "cmd.exe /c ping -n 6 127.0.0.1 >nul & \"" + bat + "\" quiet";
        String ps = "$r = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{ CommandLine = '" + cmd.replace("'", "''")
                + "'; CurrentDirectory = '" + HOME.toString().replace("'", "''") + "' }; if ($r.ReturnValue -ne 0) { exit 1 }";
        Process p = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command", ps)
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        if (!p.waitFor(20, TimeUnit.SECONDS) || p.exitValue() != 0) {
            p.destroyForcibly();
            throw new Api(500, "Could not set up the restart, so nothing was changed.");
        }
        log("restarting the manager: asked from the web page. Minecraft servers keep running.");
        sendJson(ex, 200, Json.map("ok", true));
        Thread.startVirtualThread(() -> {
            try { Thread.sleep(700); } catch (InterruptedException ignored) { }
            Runtime.getRuntime().halt(0);
        });
    }

    static String version() {
        try (InputStream in = Main.class.getResourceAsStream("/version.txt")) {
            if (in != null) return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException ignored) { }
        return "dev";
    }

    /** The folder that contains app\mcmanager.jar when packaged, otherwise the working directory (development). */
    private static String appHome() {
        try {
            Path loc = Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (Files.isRegularFile(loc) && loc.getParent() != null && loc.getParent().getParent() != null) return loc.getParent().getParent().toString();
        } catch (Exception ignored) { }
        return System.getProperty("user.dir");
    }

    /** Where new servers are created by default: the user's choice, else the common parent of already-managed servers, else HOME\servers. */
    static Path serversRoot() {
        String c = Json.str(cfg.paths(), "serversRoot");
        if (c != null && !c.isBlank()) return Path.of(c);
        if (!servers.isEmpty()) {
            Path p = servers.get(0).dir.getParent();
            if (p != null && servers.stream().allMatch(s -> p.equals(s.dir.getParent()))) return p;
        }
        return HOME.resolve("servers");
    }

    /** Where backups go when a server has no folder of its own: the installer's choice, else HOME\backups (what older installs use). */
    static Path backupsRoot() {
        String c = Json.str(cfg.paths(), "backupsRoot");
        return c != null && !c.isBlank() ? Path.of(c) : HOME.resolve("backups");
    }

    // ---------- logging / events ----------

    static synchronized void log(String s) {
        try {
            Path f = DATA.resolve("manager.log");
            if (Files.exists(f) && Files.size(f) > 1_000_000) Files.move(f, DATA.resolve("manager.log.old"), StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(f, java.time.LocalDateTime.now().withNano(0) + " " + s + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) { }
    }

    /** Sends an event to every connected browser; with a serverId, only to people who may see that server. */
    static void broadcast(String event, Object data, String serverId) {
        String msg = "event: " + event + "\ndata: " + Json.stringify(data) + "\n\n";
        for (Client c : clients) {
            if (serverId != null) {
                Users.Acct a = Users.byId(c.uid());
                if (a == null || a.level(serverId) < 0) continue;
            }
            if (!c.q().offer(msg)) clients.remove(c);
        }
    }

    /** Status of the servers this account may see, each tagged with that account's level. */
    static List<Object> allStatus(Users.Acct me) {
        sampler.refresh(false);
        return statusFor(me, null);
    }

    private static List<Object> statusFor(Users.Acct me, Map<String, Map<String, Object>> cache) {
        List<Object> l = new ArrayList<>();
        for (Srv s : servers) {
            int lvl = me.level(s.id);
            if (lvl < 0) continue;
            Map<String, Object> st = cache != null && cache.containsKey(s.id) ? cache.get(s.id) : s.status();
            Map<String, Object> copy = new LinkedHashMap<>(st);
            copy.put("level", lvl);
            l.add(copy);
        }
        return l;
    }

    static void broadcastStatus() {
        sampler.refresh(false);
        Map<String, Map<String, Object>> cache = new HashMap<>();
        for (Srv s : servers) cache.put(s.id, s.status());
        for (Client c : clients) {
            Users.Acct a = Users.byId(c.uid());
            if (a == null || a.disabled) { clients.remove(c); continue; }
            String msg = "event: status\ndata: " + Json.stringify(statusFor(a, cache)) + "\n\n";
            if (!c.q().offer(msg)) clients.remove(c);
        }
    }
    static Srv find(String id) {
        for (Srv s : servers) if (s.id.equals(id)) return s;
        throw new Api(404, "Unknown server");
    }

    static Job submit(Srv s, String title, Job.Body body) {
        Job j;
        synchronized (s != null ? s : jobs) {
            if (s != null && s.job != null && s.job.active()) throw new Api(409, "Another action is in progress on " + s.name + ": " + s.job.title);
            j = new Job(s != null ? s.id : "", title);
            if (s != null) s.job = j;
            synchronized (jobs) {
                jobs.put(j.id, j);
                while (jobs.size() > 40) jobs.remove(jobs.keySet().iterator().next());
            }
        }
        final Job job = j;
        Thread.startVirtualThread(() -> {
            try {
                body.run(job);
                job.state = "done";
            } catch (Job.Cancelled c) {
                job.log("Cancelled.");
                job.state = "cancelled";
            } catch (Api a) {
                job.log("Error: " + a.getMessage());
                job.state = "error";
            } catch (Throwable t) {
                job.log("Error: " + t);
                job.state = "error";
                log("job " + title + ": " + t);
            } finally {
                job.finished = System.currentTimeMillis();
                try { broadcastStatus(); } catch (Throwable ignored) { }
            }
        });
        return j;
    }

    // ---------- HTTP plumbing ----------

    private static void handle(HttpExchange ex) throws IOException {
        try {
            ex.getResponseHeaders().add("X-Content-Type-Options", "nosniff");
            ex.getResponseHeaders().add("X-Frame-Options", "DENY");
            ex.getResponseHeaders().add("Referrer-Policy", "no-referrer");
            ex.getResponseHeaders().add("Content-Security-Policy", "default-src 'self'; img-src 'self' data: https://cdn.modrinth.com; style-src 'self' 'unsafe-inline'; frame-ancestors 'none'");
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            if (path.startsWith("/api/")) api(ex, method, path);
            else staticFile(ex, method, path);
        } catch (Api a) {
            sendJson(ex, a.code, Json.map("error", a.getMessage()));
        } catch (IllegalArgumentException e) {
            sendJson(ex, 400, Json.map("error", "Bad request: " + e.getMessage()));
        } catch (Throwable t) {
            log("error " + ex.getRequestURI() + ": " + t);
            try { sendJson(ex, 500, Json.map("error", String.valueOf(t.getMessage() != null ? t.getMessage() : t))); } catch (Throwable ignored) { }
        } finally {
            BODIES.remove(ex);
            ex.close();
        }
    }

    private static void staticFile(HttpExchange ex, String method, String path) throws IOException {
        if (!method.equals("GET") && !method.equals("HEAD")) throw new Api(405, "Method not allowed");
        String rel = path.equals("/") ? "index.html" : path.substring(1);
        if (rel.contains("..") || rel.contains("\\") || rel.contains("//")) throw new Api(404, "Not found");
        byte[] data = null;
        Path f = WEB.resolve(rel).normalize();
        if (f.startsWith(WEB) && Files.isRegularFile(f)) data = Files.readAllBytes(f);
        else {
            try (InputStream in = Main.class.getResourceAsStream("/web/" + rel)) {
                if (in != null) data = in.readAllBytes();
            }
        }
        if (data == null) throw new Api(404, "Not found");
        String name = rel.substring(rel.lastIndexOf('/') + 1);
        String type = switch (name.substring(name.lastIndexOf('.') + 1)) {
            case "html" -> "text/html; charset=utf-8";
            case "css" -> "text/css; charset=utf-8";
            case "js" -> "text/javascript; charset=utf-8";
            case "svg" -> "image/svg+xml";
            case "png" -> "image/png";
            case "jpg" -> "image/jpeg";
            case "ico" -> "image/x-icon";
            case "json" -> "application/json; charset=utf-8";
            case "webmanifest" -> "application/manifest+json";
            default -> throw new Api(404, "Not found");
        };
        ex.getResponseHeaders().add("Cache-Control", "no-cache");
        send(ex, 200, type, data, method.equals("HEAD"));
    }

    static void send(HttpExchange ex, int code, String type, byte[] body, boolean headOnly) throws IOException {
        boolean gz = body.length > 1024 && acceptsGzip(ex) && (type.startsWith("text/") || type.contains("json") || type.contains("svg"));
        if (gz) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            try (GZIPOutputStream g = new GZIPOutputStream(bo)) { g.write(body); }
            body = bo.toByteArray();
            ex.getResponseHeaders().add("Content-Encoding", "gzip");
        }
        ex.getResponseHeaders().add("Content-Type", type);
        if (headOnly) {
            ex.sendResponseHeaders(code, -1);
            return;
        }
        ex.sendResponseHeaders(code, body.length == 0 ? -1 : body.length);
        if (body.length > 0) try (OutputStream o = ex.getResponseBody()) { o.write(body); }
    }

    static void sendJson(HttpExchange ex, int code, Object o) throws IOException {
        ex.getResponseHeaders().add("Cache-Control", "no-store");
        send(ex, code, "application/json; charset=utf-8", Json.stringify(o).getBytes(StandardCharsets.UTF_8), false);
    }

    private static boolean acceptsGzip(HttpExchange ex) {
        String h = ex.getRequestHeaders().getFirst("Accept-Encoding");
        return h != null && h.contains("gzip");
    }

    /** Parsed request bodies, per request. (HttpExchange attributes are shared by every request on a context, so they cannot be used for this.) */
    private static final Map<HttpExchange, Map<String, Object>> BODIES = new ConcurrentHashMap<>();

    static Map<String, Object> body(HttpExchange ex) throws IOException {
        Map<String, Object> cached = BODIES.get(ex);
        if (cached != null) return cached;
        byte[] b = ex.getRequestBody().readNBytes(1 << 20);
        Map<String, Object> m = b.length == 0 ? new LinkedHashMap<>() : Json.obj(Json.parse(new String(b, StandardCharsets.UTF_8)));
        BODIES.put(ex, m);
        return m;
    }

    static Map<String, String> query(HttpExchange ex) {
        Map<String, String> m = new HashMap<>();
        String q = ex.getRequestURI().getRawQuery();
        if (q == null) return m;
        for (String p : q.split("&")) {
            int eq = p.indexOf('=');
            String k = URLDecoder.decode(eq < 0 ? p : p.substring(0, eq), StandardCharsets.UTF_8);
            m.put(k, eq < 0 ? "" : URLDecoder.decode(p.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    private static String cookie(HttpExchange ex, String name) {
        List<String> hs = ex.getRequestHeaders().get("Cookie");
        if (hs == null) return null;
        for (String h : hs) for (String part : h.split(";")) {
            String p = part.trim();
            if (p.startsWith(name + "=")) return p.substring(name.length() + 1);
        }
        return null;
    }

    private static void setSession(HttpExchange ex, String token) {
        ex.getResponseHeaders().add("Set-Cookie", Users.COOKIE + "=" + token + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=" + (token.isEmpty() ? 0 : Users.SESSION_MS / 1000));
    }

    private static boolean isLocal(HttpExchange ex) {
        InetAddress a = ex.getRemoteAddress().getAddress();
        if (a.isLoopbackAddress()) return true;
        try { return NetworkInterface.getByInetAddress(a) != null; } catch (SocketException e) { return false; }
    }

    // ---------- API ----------

    private static void api(HttpExchange ex, String method, String path) throws Exception {
        boolean write = !method.equals("GET") && !method.equals("HEAD");
        if (write) {
            if (!"mgr".equals(ex.getRequestHeaders().getFirst("X-Requested-With"))) throw new Api(403, "Missing request header");
            String origin = ex.getRequestHeaders().getFirst("Origin");
            String host = ex.getRequestHeaders().getFirst("Host");
            if (origin != null && host != null && !origin.endsWith("//" + host)) throw new Api(403, "Cross-origin request blocked");
        }
        if (path.equals("/api/shutdown")) {
            shutdownApi(ex, method);
            return;
        }
        Users.Acct me = Users.fromToken(cookie(ex, Users.COOKIE));

        if (path.startsWith("/api/auth/")) {
            authApi(ex, method, path, me);
            return;
        }
        if (me == null) throw new Api(401, "Not logged in");
        String ip = ex.getRemoteAddress().getAddress().getHostAddress();
        try {
            route(ex, method, path, me);
        } catch (Api a) {
            if (a.code == 403) Users.audit(me.username, ip, "denied: " + method + " " + path, serverOfPath(path), a.getMessage());
            throw a;
        }
        if (write) Users.audit(me.username, ip, actionName(method, path), serverOfPath(path), describeBody(ex));
    }

    private static String serverOfPath(String path) {
        Matcher m = SERVER_ROUTE.matcher(path);
        return m.matches() ? m.group(1) : "";
    }

    private static String actionName(String method, String path) {
        Matcher m = SERVER_ROUTE.matcher(path);
        if (m.matches()) return m.group(2) == null ? method : m.group(2);
        return path.replaceFirst("^/api/", "");
    }

    /** A short, secret-free summary of a request body for the audit log. */
    private static String describeBody(HttpExchange ex) {
        Map<String, Object> b = BODIES.get(ex);
        if (b == null) return "";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<?, ?> e : b.entrySet()) {
            String k = String.valueOf(e.getKey());
            if (k.toLowerCase().matches(".*(pass|token|secret|current).*")) continue;
            Object v = e.getValue();
            String s = v instanceof Map<?, ?> m ? "{" + String.join(",", m.keySet().stream().map(String::valueOf).toList()) + "}" : String.valueOf(v);
            if (sb.length() > 0) sb.append("; ");
            sb.append(k).append('=').append(s.length() > 120 ? s.substring(0, 120) + "..." : s);
        }
        return sb.toString();
    }

    static void admin(Users.Acct me) {
        if (!me.admin) throw new Api(403, "Only the manager administrator (OP5) can do this");
    }

    /** Requires at least this level on the server (5 = administrator only). A server the account cannot see is reported as unknown. */
    static void need(Users.Acct me, Srv s, int level) {
        int have = me.level(s.id);
        if (have < 0) throw new Api(404, "Unknown server");
        if (have < level) throw new Api(403, level >= 5 ? "Only the manager administrator (OP5) can do this" : "This needs level " + level + " on " + s.name + " (you have level " + have + ")");
    }

    static Map<String, Object> meInfo(Users.Acct me) {
        return Json.map("id", me.id, "username", me.username, "admin", me.admin, "mcName", me.mcName, "grants", new LinkedHashMap<>(me.grants), "manage", me.opAnywhere(), "twoStep", !me.totp.isEmpty());
    }

    private static void route(HttpExchange ex, String method, String path, Users.Acct me) throws Exception {
        if (path.equals("/api/me") && method.equals("GET")) {
            sendJson(ex, 200, meInfo(me));
        } else if (path.equals("/api/servers") && method.equals("GET")) {
            sendJson(ex, 200, allStatus(me));
        } else if (path.equals("/api/stream") && method.equals("GET")) {
            stream(ex, me);
        } else if (path.equals("/api/curseforge")) {
            admin(me);
            if (method.equals("POST")) {
                CurseForge.setKey(Json.str(body(ex), "apiKey"));
                log("CurseForge API key " + (CurseForge.configured() ? "saved" : "removed"));
            }
            sendJson(ex, 200, Json.map("configured", CurseForge.configured(), "masked", CurseForge.masked()));
        } else if (path.startsWith("/api/packs/")) {
            admin(me);
            packsApi(ex, method, path.substring("/api/packs/".length()));
        } else if (path.equals("/api/notifications") && method.equals("GET")) {
            sendJson(ex, 200, Json.map("unread", Notify.unread(me), "notes", Notify.listFor(me, 100)));
        } else if (path.equals("/api/notifications/read") && method.equals("POST")) {
            Notify.markSeen(me);
            sendJson(ex, 200, Json.map("ok", true));
        } else if (path.equals("/api/notifications/clear") && method.equals("POST")) {
            Notify.clear(me);
            sendJson(ex, 200, Json.map("ok", true));
        } else if (path.equals("/api/notifications/settings")) {
            admin(me);
            if (method.equals("POST")) Notify.setWebhook(body(ex));
            sendJson(ex, 200, Json.map("webhook", Notify.webhook(), "categories", Notify.CATEGORIES));
        } else if (path.equals("/api/notifications/test") && method.equals("POST")) {
            admin(me);
            Map<String, Object> w = Notify.webhook();
            if (String.valueOf(w.get("url")).isEmpty()) throw new Api(400, "Enter a webhook address first.");
            Map<String, Object> tb = body(ex);
            if (tb.get("category") != null) { sendJson(ex, 200, Json.map("wouldSend", Notify.External.wants(w, String.valueOf(tb.get("category")), String.valueOf(tb.getOrDefault("severity", "info"))))); return; }
            String err = Notify.sendWebhook(w, new Notify.Note("test", System.currentTimeMillis(), "system", "good", "", "Test from MC.SESH", "If you can read this, the webhook works.", null));
            sendJson(ex, 200, Json.map("ok", err.isEmpty(), "error", err));
        } else if (path.equals("/api/notifications/check") && method.equals("POST")) {
            admin(me);
            sendJson(ex, 200, Json.map("started", UpdateWatch.checkNow()));
        } else if (path.equals("/api/refresh") && method.equals("POST")) {
            // Re-read every server from the machine (running or not, who is online) instead of trusting what was remembered.
            sampler.refresh(true);
            List<Thread> work = new ArrayList<>();
            for (Srv s : List.copyOf(servers)) work.add(Thread.startVirtualThread(() -> {
                try { s.resync(); } catch (Throwable t) { log("refresh " + s.id + ": " + t); }
            }));
            for (Thread t : work) t.join(15_000);
            broadcastStatus();
            Map<String, Map<String, Object>> cache = new HashMap<>();
            for (Srv s : servers) cache.put(s.id, s.status());
            sendJson(ex, 200, Json.map("servers", statusFor(me, cache)));
        } else if (path.equals("/api/backups/all") && method.equals("GET")) {
            sendJson(ex, 200, BackupManager.all(me));
        } else if (path.equals("/api/backups/lock") && method.equals("POST")) {
            Map<String, Object> lb = body(ex);
            BackupManager.resolve(me, Json.str(lb, "server"), Json.str(lb, "file"));
            BackupManager.setLocked(Json.str(lb, "server"), Json.str(lb, "file"), Boolean.TRUE.equals(lb.get("locked")));
            sendJson(ex, 200, Json.map("ok", true));
        } else if (path.equals("/api/backups/delete-many") && method.equals("POST")) {
            Map<String, Object> db = body(ex);
            List<Object> items = Json.arr(db.get("items"));
            if (items.isEmpty() || items.size() > 2000) throw new Api(400, "Pick between 1 and 2000 backups.");
            sendJson(ex, 200, BackupManager.deleteMany(me, items, Boolean.TRUE.equals(db.get("includePinned"))));
        } else if (path.equals("/api/backups/verify") && method.equals("POST")) {
            Map<String, Object> vb = body(ex);
            final Path vz = BackupManager.resolve(me, Json.str(vb, "server"), Json.str(vb, "file"));
            sendJson(ex, 200, Json.map("job", submit(null, "Check backup " + vz.getFileName(), j -> BackupManager.verify(j, vz)).id));
        } else if (path.equals("/api/backups/servers") && method.equals("GET")) {
            sendJson(ex, 200, Json.map("servers", BackupManager.servers(me), "admin", me.admin));
        } else if (path.equals("/api/backups/create-many") && method.equals("POST")) {
            Map<String, Object> cb = body(ex);
            List<Object> ids = Json.arr(cb.get("servers"));
            if (ids.isEmpty() || ids.size() > 200) throw new Api(400, "Pick at least one server.");
            String cscope = "world".equals(cb.get("scope")) ? "world" : "full";
            String cnote = String.valueOf(cb.getOrDefault("note", "")).replaceAll("\\p{Cntrl}", " ").strip();
            if (cnote.length() > 200) cnote = cnote.substring(0, 200);
            final String fcn = cnote;
            sendJson(ex, 200, Json.map("job", submit(null, "Back up " + ids.size() + " server" + (ids.size() == 1 ? "" : "s"), j -> BackupManager.createMany(j, me, ids, cscope, fcn)).id));
        } else if (path.equals("/api/backups/schedule") && method.equals("POST")) {
            Map<String, Object> sb = body(ex);
            Map<String, Object> extra = new LinkedHashMap<>();
            for (String k : new String[]{"keep", "mirror", "verify"}) if (sb.containsKey(k)) extra.put(k, sb.get(k));
            int n = BackupManager.setSchedule(me, Json.arr(sb.get("servers")), sb.get("schedule") instanceof Map ? Json.obj(sb.get("schedule")) : null, extra);
            sendJson(ex, 200, Json.map("updated", n));
        } else if (path.equals("/api/backups/note") && method.equals("POST")) {
            Map<String, Object> nb = body(ex);
            BackupManager.resolve(me, Json.str(nb, "server"), Json.str(nb, "file"));
            BackupManager.setNote(Json.str(nb, "server"), Json.str(nb, "file"), Json.str(nb, "note"));
            sendJson(ex, 200, Json.map("ok", true));
        } else if (path.equals("/api/backups/copy") && method.equals("POST")) {
            admin(me);
            Map<String, Object> kb = body(ex);
            List<Object> items = Json.arr(kb.get("items"));
            if (items.isEmpty() || items.size() > 2000) throw new Api(400, "Pick between 1 and 2000 backups.");
            final Path cdir = Path.of(String.valueOf(kb.getOrDefault("dir", "")).strip());
            sendJson(ex, 200, Json.map("job", submit(null, "Copy backups", j -> BackupManager.copyMany(j, me, items, cdir)).id));
        } else if (path.equals("/api/backups/clone") && method.equals("POST")) {
            admin(me);
            Map<String, Object> qb = body(ex);
            final String qs = Json.str(qb, "server"), qf = Json.str(qb, "file"), qn = Json.str(qb, "name");
            sendJson(ex, 200, Json.map("job", submit(null, "New server from backup", j -> BackupManager.cloneToNew(j, me, qs, qf, qn)).id));
        } else if (path.equals("/api/backups/download") && method.equals("GET")) {
            admin(me);
            Map<String, String> dq = query(ex);
            BackupManager.download(ex, BackupManager.resolve(me, dq.get("server"), dq.get("file")));
        } else if (path.equals("/api/updates/all") && method.equals("POST")) {
            admin(me);
            Map<String, Object> ub = body(ex);
            sendJson(ex, 200, Json.map("job", submit(null, "Update all servers", j -> updateAll(j, ub, me.username)).id));
        } else if (path.equals("/api/manager/restart") && method.equals("POST")) {
            admin(me);
            restartManager(ex);
        } else if (path.equals("/api/manager") && method.equals("GET")) {
            sendJson(ex, 200, Json.map("port", cfg.port(), "bind", cfg.bind(), "urls", lanUrls()));
        } else if (path.startsWith("/api/users") || path.equals("/api/audit")) {
            UsersApi.handle(ex, method, path, me);
        } else if (path.startsWith("/api/install/")) {
            admin(me);
            installApi(ex, method, path.substring("/api/install/".length()));
        } else if (path.startsWith("/api/worlds/")) {
            admin(me);
            worldsApi(ex, method, path.substring("/api/worlds/".length()));
        } else if (path.equals("/api/fs/list") && method.equals("GET")) {
            admin(me);
            sendJson(ex, 200, Fs.list(query(ex).get("path"), query(ex).get("files")));
        } else if (path.equals("/api/fs/mkdir") && method.equals("POST")) {
            admin(me);
            Map<String, Object> b = body(ex);
            sendJson(ex, 200, Fs.mkdir(Json.str(b, "parent"), Json.str(b, "name")));
        } else if (path.startsWith("/api/import/")) {
            admin(me);
            importApi(ex, method, path.substring("/api/import/".length()));
        } else if (path.equals("/api/java") && method.equals("GET")) {
            admin(me);
            sendJson(ex, 200, Json.map("runtimes", JavaRuntimes.detect("1".equals(query(ex).get("refresh")))));
        } else if (path.equals("/api/settings")) {
            admin(me);
            settingsApi(ex, method);
        } else if (path.equals("/api/ports") && method.equals("GET")) {
            admin(me);
            sendJson(ex, 200, Ports.overview());
        } else if (path.equals("/api/ports/fix") && method.equals("POST")) {
            admin(me);
            List<Object> changes = Ports.fixRcon();
            log("RCON ports reassigned: " + changes.size());
            sendJson(ex, 200, Json.map("changes", changes));
        } else if (path.equals("/api/startup") && method.equals("GET")) {
            admin(me);
            Map<String, Object> st = new LinkedHashMap<>(Startup.status());
            st.put("legacy", Startup.legacy());
            sendJson(ex, 200, st);
        } else if (path.equals("/api/startup/apply") && method.equals("POST")) {
            admin(me);
            Map<String, Object> sb = body(ex);
            String mode = String.valueOf(sb.get("mode"));
            boolean asSystem = Boolean.TRUE.equals(sb.get("asSystem"));
            sendJson(ex, 200, Json.map("job", submit(null, "Startup settings", j -> Startup.apply(j, mode, asSystem)).id));
        } else if (path.equals("/api/startup/takeover") && method.equals("POST")) {
            admin(me);
            Map<String, Object> b = body(ex);
            Srv s = find(String.valueOf(b.get("server")));
            String task = String.valueOf(b.get("task"));
            if (s.running(true)) throw new Api(409, "Stop " + s.name + " first (use Stop), then move it. Removing the old task does not touch a stopped server.");
            sendJson(ex, 200, Json.map("job", submit(s, "Move " + s.name + " to the manager", j -> Startup.takeover(j, task, s)).id));
        } else if (path.startsWith("/api/jobs/") && path.endsWith("/cancel") && method.equals("POST")) {
            Job j = jobs.get(path.substring("/api/jobs/".length(), path.length() - "/cancel".length()));
            if (j == null) throw new Api(404, "Unknown job");
            jobAccess(me, j, 4);
            j.cancel = true;
            sendJson(ex, 200, Json.map("ok", true));
        } else if (path.startsWith("/api/jobs/") && method.equals("GET")) {
            Job j = jobs.get(path.substring("/api/jobs/".length()));
            if (j == null) throw new Api(404, "Unknown job");
            jobAccess(me, j, 0);
            int from = 0;
            try { from = Integer.parseInt(query(ex).getOrDefault("from", "0")); } catch (NumberFormatException ignored) { }
            sendJson(ex, 200, j.toMap(Math.max(0, from)));
        } else {
            Matcher m = SERVER_ROUTE.matcher(path);
            if (!m.matches()) throw new Api(404, "Unknown endpoint");
            Srv s = find(m.group(1));
            String rest = m.group(2) == null ? "" : m.group(2);
            need(me, s, levelFor(rest, method));
            serverApi(ex, method, s, rest, me);
        }
    }

    private static void jobAccess(Users.Acct me, Job j, int level) {
        if (j.serverId == null || j.serverId.isEmpty()) {
            admin(me);
            return;
        }
        Srv s = null;
        for (Srv x : servers) if (x.id.equals(j.serverId)) s = x;
        if (s == null) admin(me);
        else need(me, s, level);
    }

    /** The level an account needs on a server for each action. */
    private static int levelFor(String rest, String method) {
        boolean get = method.equals("GET");
        return switch (rest) {
            case "", "info", "log", "logs", "logs/read", "autocomplete", "mods", "clientmods", "clientpack" -> 0;
            case "command", "players" -> 1;
            case "geyser", "geyser/whitelist", "geyser/whitelist/remove", "geyser/approve", "geyser/dismiss" -> 3;
            case "geyser/settings", "geyser/mode" -> 4;
            case "autorestart" -> get ? 3 : 4;
            case "journeymap", "journeymap/player", "journeymap/teleport", "journeymap/tpwp", "journeymap/send" -> 3;
            case "journeymap/delete", "journeymap/promote", "journeymap/settings", "journeymap/admins" -> 4;
            case "players/remove" -> 3;
            case "start", "stop", "restart", "job/cancel", "rcon/enable", "backups", "backups/create", "backups/restore", "backups/auto" -> 4;
            case "properties" -> get ? 3 : 4;
            default -> 5;
        };
    }
    private static List<String> lanUrls() {
        List<String> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLinkLocalAddress()) out.add("http://" + a.getHostAddress() + ":" + cfg.port());
                }
            }
        } catch (SocketException ignored) { }
        return out;
    }

    private static void authApi(HttpExchange ex, String method, String path, Users.Acct me) throws IOException {
        String ip = ex.getRemoteAddress().getAddress().getHostAddress();
        switch (path) {
            case "/api/auth/state" -> sendJson(ex, 200, Json.map("configured", Users.any(), "loggedIn", me != null, "canSetup", !Users.any() && isLocal(ex), "user", me == null ? null : meInfo(me), "hint", me == null ? Users.migratedHint() : null));
            case "/api/auth/setup" -> {
                requirePost(method);
                if (Users.any()) throw new Api(403, "An account already exists");
                if (!isLocal(ex)) throw new Api(403, "First-time setup must be done in a browser on the server PC (http://localhost:" + cfg.port() + ")");
                Map<String, Object> b = body(ex);
                Users.Acct a = Users.createFirstAdmin(Json.str(b, "username"), Json.str(b, "password"));
                setSession(ex, Users.token(a));
                Users.audit(a.username, ip, "administrator account created", "", "first-run setup");
                sendJson(ex, 200, Json.map("ok", true));
            }
            case "/api/auth/login" -> {
                requirePost(method);
                if (!Users.any()) throw new Api(409, "No account has been created yet");
                Map<String, Object> b = body(ex);
                Users.Acct a;
                try {
                    a = Users.login(ip, Json.str(b, "username"), Json.str(b, "password"));
                } catch (Api e) {
                    Users.audit(String.valueOf(Json.str(b, "username")), ip, "login failed", "", e.getMessage());
                    throw e;
                }
                if (!a.totp.isEmpty()) {
                    String code = Json.str(b, "code");
                    if (code == null || code.isBlank()) {
                        sendJson(ex, 200, Json.map("need2fa", true));
                        return;
                    }
                    if (!Users.check2fa(a, code)) {
                        Users.failedSecondStep(ip, a.username);
                        Users.audit(a.username, ip, "login failed", "", "wrong two-step code");
                        throw new Api(401, "That code is not right");
                    }
                    Users.loginOk(ip, a.username);
                }
                setSession(ex, Users.token(a));
                Users.markSeen(a);
                Users.audit(a.username, ip, "login", "", "");
                sendJson(ex, 200, Json.map("ok", true));
            }
            case "/api/auth/logout" -> {
                if (me != null) Users.audit(me.username, ip, "logout", "", "");
                setSession(ex, "");
                sendJson(ex, 200, Json.map("ok", true));
            }
            case "/api/auth/password" -> {
                requirePost(method);
                if (me == null) throw new Api(401, "Not logged in");
                Map<String, Object> b = body(ex);
                Users.login(ip, me.username, Json.str(b, "current"));
                Users.changePassword(me, Json.str(b, "password"));
                setSession(ex, Users.token(me));
                Users.audit(me.username, ip, "password changed", "", "");
                sendJson(ex, 200, Json.map("ok", true));
            }
            case "/api/auth/2fa/begin" -> {
                requirePost(method);
                if (me == null) throw new Api(401, "Not logged in");
                if (!me.totp.isEmpty()) throw new Api(409, "Two-step sign-in is already on. Turn it off first to set it up again.");
                sendJson(ex, 200, Users.begin2fa(me));
            }
            case "/api/auth/2fa/enable" -> {
                requirePost(method);
                if (me == null) throw new Api(401, "Not logged in");
                List<String> codes = Users.enable2fa(me, Json.str(body(ex), "code"));
                setSession(ex, Users.token(me));
                Users.audit(me.username, ip, "two-step sign-in turned on", "", "");
                sendJson(ex, 200, Json.map("codes", codes));
            }
            case "/api/auth/2fa/disable" -> {
                requirePost(method);
                if (me == null) throw new Api(401, "Not logged in");
                Users.login(ip, me.username, Json.str(body(ex), "password"));
                Users.disable2fa(me);
                setSession(ex, Users.token(me));
                Users.audit(me.username, ip, "two-step sign-in turned off", "", "");
                sendJson(ex, 200, Json.map("ok", true));
            }
            case "/api/auth/invite" -> {
                requirePost(method);
                Users.guess(ip, false, true);
                Map<String, Object> inv = Users.findInvite(Json.str(body(ex), "token"));
                if (inv == null) {
                    Users.guess(ip, false, false);
                    throw new Api(400, "This invite is not valid. It may have expired or already been used.");
                }
                List<Object> names = new ArrayList<>();
                for (Object s : Json.arr(inv.get("servers"))) for (Srv x : servers) if (x.id.equals(String.valueOf(s))) names.add(x.name);
                sendJson(ex, 200, Json.map("admin", inv.get("admin"), "level", inv.get("level"), "servers", names, "expires", inv.get("expires"), "createdBy", inv.get("createdByName"), "mcName", inv.get("mcName")));
            }
            case "/api/auth/register" -> {
                requirePost(method);
                Users.guess(ip, false, true);
                Map<String, Object> b = body(ex);
                Users.Acct a;
                try {
                    a = Users.redeem(Json.str(b, "token"), Json.str(b, "username"), Json.str(b, "password"), ip);
                } catch (Api e) {
                    if (e.code == 400 && e.getMessage().startsWith("This invite")) Users.guess(ip, false, false);
                    throw e;
                }
                setSession(ex, Users.token(a));
                Users.audit(a.username, ip, "account created from an invite", "", a.createdBy);
                sendJson(ex, 200, Json.map("ok", true));
            }
            default -> throw new Api(404, "Unknown endpoint");
        }
    }

    private static void stream(HttpExchange ex, Users.Acct me) throws IOException {
        BlockingQueue<String> q = new LinkedBlockingQueue<>(2000);
        ex.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().add("Cache-Control", "no-store");
        ex.sendResponseHeaders(200, 0);
        OutputStream o = ex.getResponseBody();
        Client me2 = new Client(q, me.id);
        clients.add(me2);
        try {
            o.write(("retry: 3000\n\nevent: status\ndata: " + Json.stringify(allStatus(me)) + "\n\n").getBytes(StandardCharsets.UTF_8));
            o.flush();
            while (true) {
                String m = q.poll(15, TimeUnit.SECONDS);
                o.write((m == null ? ": ping\n\n" : m).getBytes(StandardCharsets.UTF_8));
                o.flush();
            }
        } catch (IOException | InterruptedException ignored) {
        } finally {
            clients.remove(me2);
        }
    }
    private static void settingsApi(HttpExchange ex, String method) throws Exception {
        if (method.equals("POST")) {
            Map<String, Object> b = body(ex);
            String root = Json.str(b, "serversRoot");
            if (root != null && !root.isBlank()) {
                Path p = Path.of(root.trim());
                if (!p.isAbsolute()) throw new Api(400, "Use a full path such as D:\\Minecraft");
                Files.createDirectories(p);
                if (!Files.isWritable(p)) throw new Api(400, "Cannot write to " + p);
                cfg.paths().put("serversRoot", p.normalize().toString());
            }
            if (b.containsKey("backupsRoot")) {
                String br = Json.str(b, "backupsRoot");
                if (br == null || br.isBlank()) cfg.paths().remove("backupsRoot");
                else {
                    Path p = Path.of(br.trim());
                    if (!p.isAbsolute()) throw new Api(400, "Use a full path such as D:\\Minecraft\\_backups");
                    Files.createDirectories(p);
                    if (!Files.isWritable(p)) throw new Api(400, "Cannot write to " + p);
                    cfg.paths().put("backupsRoot", p.normalize().toString());
                }
            }
            if (b.containsKey("createDefaults")) cfg.root.put("createDefaults", cleanCreateDefaults(b.get("createDefaults")));
            if (Boolean.TRUE.equals(b.get("configured"))) cfg.paths().put("configured", true);
            cfg.save();
            sendJson(ex, 200, Json.map("ok", true));
            return;
        }
        String c = Json.str(cfg.paths(), "serversRoot");
        sendJson(ex, 200, Json.map("version", version(), "serversRoot", serversRoot().toString(), "serversRootIsDefault", c == null || c.isBlank(), "home", HOME.toString(), "data", DATA.toString(),
                "port", cfg.port(), "bind", cfg.bind(), "configured", Boolean.TRUE.equals(cfg.paths().get("configured")) || !servers.isEmpty(),
                "bundledJava", Files.exists(RUNTIME.resolve("bin").resolve("java.exe")), "javaMajor", Runtime.version().feature(),
                "runtimes", JavaRuntimes.detect(false), "urls", lanUrls(),
                "createDefaults", cfg.root.get("createDefaults") instanceof Map ? Json.obj(cfg.root.get("createDefaults")) : Json.map(),
                "backupsRoot", backupsRoot().toString(), "backupsRootIsDefault", Json.str(cfg.paths(), "backupsRoot") == null || Json.str(cfg.paths(), "backupsRoot").isBlank()));
    }

    /** Only known keys with sane values are kept; an empty result means "use the built-in defaults". */
    static Map<String, Object> cleanCreateDefaults(Object in) {
        Map<String, Object> src = in instanceof Map ? Json.obj(in) : Json.map(), out = new LinkedHashMap<>();
        String type = Json.str(src, "type");
        if (type != null && List.of("vanilla", "fabric", "forge", "neoforge").contains(type)) out.put("type", type);
        String ml = Json.str(src, "modLoader");
        if (ml != null && List.of("fabric", "forge", "neoforge").contains(ml)) out.put("modLoader", ml);
        String diff = Json.str(src, "difficulty");
        if (diff != null && List.of("peaceful", "easy", "normal", "hard").contains(diff)) out.put("difficulty", diff);
        String gm = Json.str(src, "gamemode");
        if (gm != null && List.of("survival", "creative", "adventure", "spectator").contains(gm)) out.put("gamemode", gm);
        if (src.get("maxPlayers") instanceof Number n && n.intValue() >= 1 && n.intValue() <= 500) out.put("maxPlayers", n.intValue());
        if (src.get("maxMb") instanceof Number n && n.intValue() >= 512 && n.intValue() <= 65536) out.put("maxMb", n.intValue());
        for (String k : List.of("bedrock", "journeymap", "planMods", "whitelist", "autostart", "lanBroadcast")) if (src.get(k) instanceof Boolean v) out.put(k, v);
        if (src.get("autoRestart") instanceof Map<?, ?> ar) {
            Map<String, Object> cleaned = AutoRestart.clean(Json.obj(ar));
            if (Boolean.TRUE.equals(cleaned.get("timeOn")) || Boolean.TRUE.equals(cleaned.get("idleOn"))) out.put("autoRestart", cleaned);
        }
        if ((Boolean.TRUE.equals(out.get("bedrock")) || Boolean.TRUE.equals(out.get("journeymap"))) && "vanilla".equals(out.get("type"))) out.put("type", "fabric");
        return out;
    }

    private static void importApi(HttpExchange ex, String method, String rest) throws Exception {
        requirePost(method);
        Map<String, Object> b = body(ex);
        switch (rest) {
            case "inspect" -> {
                String dir = Json.str(b, "dir");
                if (dir == null || !Path.of(dir.trim()).isAbsolute()) throw new Api(400, "Pick a folder (a full path such as D:\\Servers\\MyServer)");
                Map<String, Object> info = Detector.inspect(Path.of(dir.trim()).normalize());
                if (info == null) throw new Api(404, "No Minecraft server found in that folder. Pick the folder that contains server.properties or the server's start script.");
                sendJson(ex, 200, info);
            }
            case "add" -> {
                Srv s = Detector.add(b);
                log("imported server " + s.id + " from " + s.dir);
                sendJson(ex, 200, Json.map("id", s.id));
            }
            case "bulk" -> {
                List<String> dirs = new ArrayList<>();
                for (Object o : Json.arr(b.get("dirs"))) dirs.add(String.valueOf(o));
                if (dirs.isEmpty() || dirs.size() > 200) throw new Api(400, "Pick between 1 and 200 servers");
                Map<String, Object> r = Detector.bulkAdd(dirs, Boolean.TRUE.equals(b.get("enableRcon")));
                log("bulk import: " + Json.arr(r.get("added")).size() + " added, " + Json.arr(r.get("skipped")).size() + " skipped");
                sendJson(ex, 200, r);
            }
            case "scan" -> {
                boolean deep = "deep".equals(Json.str(b, "mode"));
                List<Path> roots = new ArrayList<>();
                for (Object o : Json.arr(b.get("roots"))) {
                    Path p = Path.of(String.valueOf(o).trim());
                    if (p.isAbsolute() && Files.isDirectory(p)) roots.add(p.normalize());
                }
                List<Object> results = Collections.synchronizedList(new ArrayList<>());
                final List<Path> rootsF = roots;
                Job j = submit(null, deep ? "Deep search for servers" : "Search for servers", job -> {
                    job.result = results;
                    Set<String> seen = new HashSet<>();
                    if (deep || !rootsF.isEmpty()) Detector.scan(job, rootsF.isEmpty() ? Detector.driveRoots() : rootsF, deep ? 8 : 6, false, deep ? 20 * 60_000L : 4 * 60_000L, results, seen);
                    else {
                        Detector.scan(job, Detector.quickRoots(), 6, false, 3 * 60_000L, results, seen);
                        Detector.scan(job, Detector.driveRoots(), 3, true, 3 * 60_000L, results, seen);
                    }
                    job.log(results.isEmpty() ? "No servers found." : "Search finished: " + results.size() + " server(s) found.");
                });
                sendJson(ex, 200, Json.map("job", j.id));
            }
            default -> throw new Api(404, "Unknown endpoint");
        }
    }

    private static String actorName(HttpExchange ex) {
        Users.Acct a = Users.fromToken(cookie(ex, Users.COOKIE));
        return a == null ? "manager" : a.username;
    }

    private static List<Long> idList(Map<String, Object> b) {
        List<Long> out = new ArrayList<>();
        for (Object o : Json.arr(b.get("mods"))) if (o instanceof Number n && n.longValue() > 0) out.add(n.longValue());
        if (out.isEmpty() || out.size() > 40) throw new Api(400, "Choose between 1 and 40 mods.");
        return out;
    }

    private static void worldsApi(HttpExchange ex, String method, String rest) throws Exception {
        switch (rest) {
            case "scan" -> sendJson(ex, 200, Json.map("worlds", Worlds.scan()));
            case "inspect" -> {
                requirePost(method);
                sendJson(ex, 200, Json.map("worlds", Worlds.inspect(Json.str(body(ex), "path"))));
            }
            case "details" -> {
                requirePost(method);
                sendJson(ex, 200, Worlds.details(body(ex)));
            }
            case "waypoints" -> {
                requirePost(method);
                sendJson(ex, 200, Json.map("sets", JourneyMapImport.inspect(Json.str(body(ex), "path"))));
            }
            default -> throw new Api(404, "Unknown endpoint");
        }
    }

    private static void packsApi(HttpExchange ex, String method, String rest) throws Exception {
        Map<String, String> qs = query(ex);
        switch (rest) {
            case "search" -> {
                int page = 0;
                try { page = Math.max(0, Math.min(50, Integer.parseInt(qs.getOrDefault("page", "0")))); } catch (NumberFormatException ignored) { }
                sendJson(ex, 200, Packs.search(qs.getOrDefault("source", ""), qs.get("q"), qs.get("sort"), page));
            }
            case "versions" -> sendJson(ex, 200, Json.map("versions", Packs.versions(qs.getOrDefault("source", ""), qs.getOrDefault("id", ""))));
            case "inspect" -> {
                requirePost(method);
                sendJson(ex, 200, Packs.inspect(Packs.obtain(body(ex))));
            }
            default -> throw new Api(404, "Unknown endpoint");
        }
    }

    private static void installApi(HttpExchange ex, String method, String rest) throws Exception {
        Map<String, String> qs = query(ex);
        switch (rest) {
            case "versions" -> sendJson(ex, 200, Installer.versions(qs.getOrDefault("type", ""), "1".equals(qs.get("snapshots"))));
            case "loaders" -> sendJson(ex, 200, Json.map("loaders", Installer.loaders(qs.getOrDefault("type", ""), qs.get("mc"))));
            case "java" -> sendJson(ex, 200, Installer.javaInfo(qs.get("mc")));
            case "biomes" -> sendJson(ex, 200, Json.map("supported", Installer.singleBiomeSupported(qs.get("mc")), "from", Installer.SINGLE_BIOME_FROM, "biomes", Installer.biomes(qs.get("mc"))));
            case "defaults" -> sendJson(ex, 200, Installer.defaults());
            case "geyser" -> sendJson(ex, 200, Geyser.availability(qs.getOrDefault("type", ""), qs.get("mc")));
            case "journeymap" -> sendJson(ex, 200, JourneyMap.availability(qs.getOrDefault("type", ""), qs.get("mc")));
            case "create-from-world" -> {
                requirePost(method);
                Map<String, Object> b = body(ex);
                String name = String.valueOf(b.getOrDefault("name", "")).trim();
                Worlds.validate(b);
                Job j = submit(null, "Create server " + name + " from a world", job -> Worlds.run(job, b, actorName(ex)));
                sendJson(ex, 200, Json.map("job", j.id, "id", Installer.slug(name)));
            }
            case "create-from-pack" -> {
                requirePost(method);
                Map<String, Object> b = body(ex);
                String name = String.valueOf(b.getOrDefault("name", "")).trim();
                Map<String, Object> pi = Packs.inspect(Packs.obtain(Json.obj(b.get("pack"))));
                if (pi.get("problem") != null) throw new Api(400, String.valueOf(pi.get("problem")));
                Map<String, Object> chk = new LinkedHashMap<>(b);
                chk.put("type", pi.get("loader"));
                chk.put("mc", pi.get("mc"));
                String loaderId = String.valueOf(pi.get("loaderVersion"));
                if (!Boolean.TRUE.equals(pi.get("loaderAvailable"))) {
                    for (Object l : Installer.loaders(String.valueOf(pi.get("loader")), String.valueOf(pi.get("mc")))) if (Boolean.TRUE.equals(Json.obj(l).get("recommended"))) loaderId = Json.str(l, "id");
                }
                chk.put("loader", loaderId);
                chk.put("geyser", false);
                Installer.validate(chk);
                Job j = submit(null, "Create server " + name + " from a modpack", job -> Packs.install(job, b, actorName(ex)));
                sendJson(ex, 200, Json.map("job", j.id, "id", Installer.slug(name)));
            }
            case "create" -> {
                requirePost(method);
                Map<String, Object> b = body(ex);
                String name = String.valueOf(b.getOrDefault("name", "")).trim();
                Installer.validate(b);
                Job j = submit(null, "Create server " + name, job -> Installer.install(job, b));
                sendJson(ex, 200, Json.map("job", j.id, "id", Installer.slug(name)));
            }
            default -> throw new Api(404, "Unknown endpoint");
        }
    }

    private static void serverApi(HttpExchange ex, String method, Srv s, String rest, Users.Acct me) throws Exception {
        boolean get = method.equals("GET");
        Map<String, String> qs = query(ex);
        switch (rest) {
            case "remove" -> {
                requirePost(method);
                if (s.running(true) || (s.job != null && s.job.active())) throw new Api(409, "Stop the server (and wait for running actions to finish) before removing it");
                removeServer(s);
                log("removed server " + s.id + " from the manager (files kept in " + s.dir + ")");
                sendJson(ex, 200, Json.map("ok", true));
            }
            case "settings" -> {
                requirePost(method);
                Map<String, Object> b = body(ex);
                if (b.containsKey("autostart")) s.cfg.put("autostart", Boolean.TRUE.equals(b.get("autostart")));
                if (b.containsKey("pinned") && s.isFabric()) s.setPinned(Boolean.TRUE.equals(b.get("pinned")));
                if (b.containsKey("autoUpdate")) s.cfg.put("autoUpdate", Boolean.TRUE.equals(b.get("autoUpdate")) && s.isFabric());
                if (b.containsKey("crashRestart")) s.cfg.put("crashRestart", Boolean.TRUE.equals(b.get("crashRestart")));
                if (b.containsKey("lanBroadcast")) s.cfg.put("lanBroadcast", Boolean.TRUE.equals(b.get("lanBroadcast")));
                boolean opsOn = b.containsKey("opsSync") && Boolean.TRUE.equals(b.get("opsSync"));
                if (b.containsKey("opsSync")) s.cfg.put("opsSync", opsOn);
                if (b.containsKey("java")) {
                    String jv = Json.str(b, "java");
                    if (jv == null || jv.isBlank()) s.cfg.remove("java");
                    else {
                        if (!jv.toLowerCase().endsWith("java.exe") || !Files.isRegularFile(Path.of(jv))) throw new Api(400, "That is not a java.exe");
                        s.cfg.put("java", jv);
                    }
                }
                if (b.containsKey("script")) {
                    String sc = Json.str(b, "script");
                    if (sc == null || !sc.matches("[^\\\\/:*?\"<>|]+\\.(bat|cmd)") || !Files.exists(s.dir.resolve(sc))) throw new Api(400, "That start script does not exist in the server folder");
                    s.cfg.put("script", sc);
                }
                cfg.save();
                if (opsOn) Thread.startVirtualThread(() -> OpsSync.apply(s));
                sendJson(ex, 200, Json.map("ok", true));
            }
            case "priority" -> {
                requirePost(method);
                String pr = Json.str(body(ex), "priority");
                if (!List.of("Normal", "AboveNormal", "High").contains(pr)) throw new Api(400, "Priority must be Normal, AboveNormal or High");
                s.cfg.put("priority", pr);
                cfg.save();
                boolean applied = s.running(false) && s.applyPriority();
                sendJson(ex, 200, Json.map("ok", true, "applied", applied, "running", s.running(false)));
            }
            case "launch" -> {
                if (get) sendJson(ex, 200, Launch.read(s));
                else {
                    Map<String, Object> b = body(ex);
                    Launch.write(s, num(b.get("minMb")), num(b.get("maxMb")), Json.str(b, "gc"), Json.str(b, "extra"), b.get("toggles") instanceof Map ? Json.obj(b.get("toggles")) : null);
                    log("launch settings for " + s.id + " changed: " + Json.str(b, "maxMb") + " MB max");
                    sendJson(ex, 200, Launch.read(s));
                }
            }
            case "" -> sendJson(ex, 200, s.status());
            case "info" -> sendJson(ex, 200, s.info());
            case "log" -> {
                int n = 500;
                try { n = Math.min(3000, Integer.parseInt(qs.getOrDefault("lines", "500"))); } catch (NumberFormatException ignored) { }
                sendJson(ex, 200, s.log.snapshot(n));
            }
            case "command" -> {
                requirePost(method);
                String cmd = Json.str(body(ex), "command");
                if (cmd != null) {
                    String norm = cmd.strip();
                    while (norm.startsWith("/")) norm = norm.substring(1);
                    int req = Perm.required(s, norm);
                    if (me.level(s.id) < req) throw new Api(403, "You are level " + me.level(s.id) + " on " + s.name + ", and this command needs level " + req + " (" + Perm.describe(req) + ").");
                }
                String out = s.command(cmd);
                sendJson(ex, 200, Json.map("output", out));
            }
            case "start" -> {
                requirePost(method);
                sendJson(ex, 200, Json.map("job", submit(s, "Start", s::start).id));
            }
            case "stop" -> {
                requirePost(method);
                int d = delay(body(ex));
                sendJson(ex, 200, Json.map("job", submit(s, d > 0 ? "Stop in " + d + "s" : "Stop", j -> s.stop(j, d)).id));
            }
            case "restart" -> {
                requirePost(method);
                int d = delay(body(ex));
                sendJson(ex, 200, Json.map("job", submit(s, d > 0 ? "Restart in " + d + "s" : "Restart", j -> {
                    s.stop(j, d, "restart");
                    s.start(j);
                }).id));
            }
            case "kill" -> {
                requirePost(method);
                sendJson(ex, 200, Json.map("job", submit(s, "Force kill", s::kill).id));
            }
            case "job/cancel" -> {
                requirePost(method);
                if (s.job != null) s.job.cancel = true;
                sendJson(ex, 200, Json.map("ok", true));
            }
            case "players" -> sendJson(ex, 200, s.playersData());
            case "players/remove" -> {
                requirePost(method);
                Map<String, Object> b = body(ex);
                String list = Json.str(b, "list"), value = Json.str(b, "value");
                boolean ok = "banIps".equals(list) ? value != null && IPV4.matcher(value).matches() : Srv.validPlayerName(value);
                if (!ok) throw new Api(400, "Invalid value");
                s.removeOffline(list, value);
                sendJson(ex, 200, Json.map("ok", true));
            }
            case "properties" -> {
                if (get) {
                    List<Object> l = new ArrayList<>();
                    for (Map.Entry<String, String> e : s.props().entrySet()) {
                        boolean secret = e.getKey().equals("rcon.password");
                        l.add(Json.map("key", e.getKey(), "value", secret ? "" : e.getValue(), "secret", secret, "set", !e.getValue().isEmpty()));
                    }
                    sendJson(ex, 200, Json.map("properties", l, "running", s.running(false)));
                } else {
                    Map<String, String> ch = new LinkedHashMap<>();
                    for (Map.Entry<String, Object> e : Json.obj(body(ex).get("changes")).entrySet()) {
                        String v = e.getValue() == null ? "" : String.valueOf(e.getValue());
                        if (!PROP_KEY.matcher(e.getKey()).matches()) throw new Api(400, "Bad property name: " + e.getKey());
                        if (e.getKey().equals("motd")) v = v.replace("\\n", "\n").replace("\r", "");
                        if (v.length() > 2000 || (v.indexOf('\n') >= 0 && !e.getKey().equals("motd")) || v.indexOf('\r') >= 0) throw new Api(400, "Bad value for " + e.getKey());
                        if (e.getKey().equals("rcon.password") && v.isEmpty()) continue;
                        ch.put(e.getKey(), v);
                    }
                    if (!ch.isEmpty()) s.writeProps(ch);
                    log("properties changed on " + s.id + ": " + ch.keySet());
                    if (ch.containsKey("motd")) { try { Geyser.syncNames(s); } catch (IOException | RuntimeException ge) { log("geyser names: " + ge); } }
                    sendJson(ex, 200, Json.map("ok", true, "restartNeeded", s.running(false)));
                }
            }
            case "rcon/enable" -> {
                requirePost(method);
                sendJson(ex, 200, Json.map("message", s.enableRcon()));
            }
            case "mods" -> sendJson(ex, 200, Json.map("mods", s.mods(), "fabric", s.isFabric()));
            case "backups" -> {
                Map<String, Object> sz = Backups.sizes(s);
                sendJson(ex, 200, Json.map("backups", Backups.list(s), "dir", Backups.dirFor(s).toString(), "keep", Backups.keep(s), "worldMb", sz.get("worldMb"), "fullMb", sz.get("fullMb"),
                        "auto", AutoBackup.get(s), "freeMb", Backups.freeMb(Backups.dirFor(s)), "running", s.running(false), "rcon", s.rconEnabled(), "mc", s.mcVersion(), "kind", s.kind(), "admin", me.admin));
            }
            case "backups/create" -> {
                requirePost(method);
                Map<String, Object> b = body(ex);
                String scope = Json.str(b, "scope") == null ? "full" : Json.str(b, "scope");
                if (!List.of("world", "full").contains(scope)) throw new Api(400, "Unknown backup type");
                String note = String.valueOf(b.getOrDefault("note", "")).replaceAll("\\p{Cntrl}", " ").strip();
                if (note.length() > 200) note = note.substring(0, 200);
                final String fnote = note;
                if (s.running(true) && !s.rconEnabled()) throw new Api(409, "The server is running and RCON is off, so saving cannot be paused for a safe copy. Stop the server first (or turn RCON on in Properties and restart it).");
                sendJson(ex, 200, Json.map("job", submit(s, "Backup " + s.name, j -> Backups.create(j, s, scope, Backups.dirFor(s), fnote, "manual", me.username)).id));
            }
            case "backups/restore" -> {
                requirePost(method);
                Map<String, Object> b = body(ex);
                String path = Json.str(b, "path");
                final Path zip;
                if (path != null && !path.isBlank()) {
                    admin(me);
                    Path p = Path.of(path.trim());
                    if (!p.isAbsolute() || !p.getFileName().toString().toLowerCase().endsWith(".zip") || !Files.isRegularFile(p)) throw new Api(400, "Give the full path of an existing .zip file.");
                    zip = p;
                } else {
                    zip = Backups.resolve(s, Json.str(b, "file"));
                }
                if (s.running(true)) throw new Api(409, "Stop " + s.name + " first.");
                Backups.check(s, zip);
                sendJson(ex, 200, Json.map("job", submit(s, "Restore " + s.name, j -> Backups.restore(j, s, zip, me.username)).id));
            }
            case "backups/delete" -> {
                requirePost(method);
                String delFile = Json.str(body(ex), "file");
                if (BackupManager.isLocked(s.id, delFile)) throw new Api(409, "That backup is pinned. Unpin it first in the Backup manager.");
                Backups.delete(s, delFile);
                sendJson(ex, 200, Json.map("ok", true));
            }
            case "backups/auto" -> {
                requirePost(method);
                AutoBackup.set(s, body(ex));
                sendJson(ex, 200, Json.map("auto", AutoBackup.get(s)));
            }
            case "autorestart" -> {
                if (method.equals("POST")) {
                    AutoRestart.set(s, body(ex));
                    Users.audit(actorName(ex), ex.getRemoteAddress().getAddress().getHostAddress(), "changed the automatic restart settings", s.id, "");
                }
                sendJson(ex, 200, Json.map("auto", AutoRestart.get(s), "rcon", s.rconEnabled()));
            }
            case "backups/settings" -> {
                requirePost(method);
                Backups.setSettings(s, body(ex));
                sendJson(ex, 200, Json.map("dir", Backups.dirFor(s).toString(), "keep", Backups.keep(s)));
            }
            case "convert" -> {
                if (get) sendJson(ex, 200, ServerConvert.info(s));
                else {
                    Map<String, Object> req = body(ex);
                    ServerConvert.validate(s, req);
                    sendJson(ex, 200, Json.map("job", submit(s, "Add mod loader to " + s.name, j -> ServerConvert.convert(j, s, req, me.username)).id));
                }
            }
            case "clientmods" -> {
                ClientPack.Result r = ClientPack.build(s, ClientPack.ids(qs.get("opt")), ClientPack.ids(qs.get("extra")));
                sendJson(ex, 200, r.model());
            }
            case "clientpack" -> {
                ClientPack.Result r = ClientPack.build(s, ClientPack.ids(qs.get("opt")), ClientPack.ids(qs.get("extra")));
                boolean cf = "curseforge".equals(qs.get("format"));
                List<String> missing = new ArrayList<>();
                byte[] data = cf ? ClientPack.curseforgeZip(s, r, missing) : ClientPack.mrpack(s, r);
                String fn = s.name.replaceAll("[^A-Za-z0-9 _.-]", "_").strip() + (cf ? "-client-curseforge.zip" : "-client.mrpack");
                if (cf && "1".equals(qs.get("dry"))) { sendJson(ex, 200, Json.map("missing", missing, "total", r.files().size())); return; }
                ex.getResponseHeaders().add("Content-Disposition", "attachment; filename=\"" + fn + "\"");
                ex.getResponseHeaders().add("Cache-Control", "no-store");
                send(ex, 200, "application/octet-stream", data, false);
            }
            case "delete-preview" -> sendJson(ex, 200, ServerDelete.preview(s));
            case "delete" -> {
                requirePost(method);
                Map<String, Object> req = body(ex);
                String blocked = ServerDelete.blockedReason(s);
                if (blocked != null) throw new Api(409, blocked);
                if (s.running(true)) throw new Api(409, "Stop " + s.name + " first.");
                if (!s.name.equals(Json.str(req, "confirmName"))) throw new Api(400, "The name you typed does not match \"" + s.name + "\".");
                sendJson(ex, 200, Json.map("job", submit(s, "Delete " + s.name, j -> ServerDelete.delete(j, s, req)).id));
            }
            case "geyser" -> sendJson(ex, 200, Geyser.status(s));
            case "geyser/whitelist" -> {
                requirePost(method);
                sendJson(ex, 200, Geyser.whitelistAdd(s, Json.str(body(ex), "gamertag")));
            }
            case "geyser/whitelist/remove" -> {
                requirePost(method);
                Geyser.whitelistRemove(s, Json.str(body(ex), "uuid"));
                sendJson(ex, 200, Json.map("ok", true));
            }
            case "geyser/approve" -> {
                requirePost(method);
                String n = Json.str(body(ex), "name");
                if (n == null || n.isBlank()) throw new Api(400, "No player given");
                Map<String, Object> r = Geyser.whitelistAdd(s, n);
                Geyser.dismiss(s, n);
                sendJson(ex, 200, r);
            }
            case "geyser/dismiss" -> {
                requirePost(method);
                Geyser.dismiss(s, String.valueOf(Json.str(body(ex), "name")));
                sendJson(ex, 200, Json.map("ok", true));
            }
            case "geyser/mode" -> {
                requirePost(method);
                Map<String, Object> bm = body(ex);
                Geyser.setMode(s, String.valueOf(Json.str(bm, "mode")), Json.num(bm, "minutes", 60));
                sendJson(ex, 200, Geyser.status(s));
            }
            case "geyser/settings" -> {
                requirePost(method);
                Geyser.applySettings(s, body(ex));
                sendJson(ex, 200, Geyser.status(s));
            }
            case "journeymap" -> {
                if (!JourneyMap.installed(s)) throw new Api(404, "JourneyMap is not installed on this server.");
                sendJson(ex, 200, JourneyMap.status(s));
            }
            case "journeymap/player" -> {
                requirePost(method);
                sendJson(ex, 200, Json.map("waypoints", JourneyMap.playerWaypoints(s, Json.str(body(ex), "uuid"))));
            }
            case "journeymap/teleport" -> {
                requirePost(method);
                Map<String, Object> jb = body(ex);
                Map<String, Object> r = JourneyMap.teleport(s, Json.str(jb, "player"), Json.str(jb, "dim"), jb.get("x"), jb.get("y"), jb.get("z"));
                Users.audit(actorName(ex), ex.getRemoteAddress().getAddress().getHostAddress(), "teleported a player to a waypoint", s.id, Json.str(jb, "player"));
                sendJson(ex, 200, r);
            }
            case "journeymap/tpwp" -> {
                requirePost(method);
                Map<String, Object> jb = body(ex);
                Map<String, Object> r = JourneyMap.tpwp(s, Json.str(jb, "player"), Json.str(jb, "waypoint"));
                Users.audit(actorName(ex), ex.getRemoteAddress().getAddress().getHostAddress(), "teleported a player to a waypoint", s.id, Json.str(jb, "player"));
                sendJson(ex, 200, r);
            }
            case "journeymap/send" -> {
                requirePost(method);
                Map<String, Object> jb = body(ex);
                List<String> targets = new ArrayList<>();
                for (Object o : Json.arr(jb.get("players"))) targets.add(String.valueOf(o));
                sendJson(ex, 200, JourneyMap.send(s, Json.str(jb, "name"), Json.str(jb, "dim"), jb.get("x"), jb.get("y"), jb.get("z"), Json.str(jb, "color"), targets));
            }
            case "journeymap/delete" -> {
                requirePost(method);
                Map<String, Object> r = JourneyMap.deleteGlobal(s, Json.str(body(ex), "guid"));
                Users.audit(actorName(ex), ex.getRemoteAddress().getAddress().getHostAddress(), "deleted a JourneyMap global waypoint", s.id, "");
                sendJson(ex, 200, r);
            }
            case "journeymap/promote" -> {
                requirePost(method);
                Map<String, Object> jb = body(ex);
                Map<String, Object> r = JourneyMap.promote(s, Json.str(jb, "uuid"), Json.str(jb, "guid"));
                Users.audit(actorName(ex), ex.getRemoteAddress().getAddress().getHostAddress(), "copied a player waypoint to the global waypoints", s.id, String.valueOf(r.get("name")));
                sendJson(ex, 200, r);
            }
            case "journeymap/settings" -> {
                requirePost(method);
                sendJson(ex, 200, JourneyMap.saveSettings(s, Json.obj(body(ex).get("values"))));
            }
            case "journeymap/admins" -> {
                requirePost(method);
                Map<String, Object> jb = body(ex);
                sendJson(ex, 200, JourneyMap.setAdmin(s, Json.str(jb, "uuid"), !Boolean.FALSE.equals(jb.get("add")), jb.get("opAccess") instanceof Boolean ob ? ob : null));
            }
            case "geyser/firewall" -> {
                requirePost(method);
                Integer port = (Integer) Geyser.status(s).get("bedrockPort");
                if (port == null) throw new Api(409, "Geyser has not created its settings yet; start the server once first.");
                final int fp = port;
                final int fw = Geyser.status(s).get("webrtcPort") instanceof Integer w ? w : 0;
                sendJson(ex, 200, Json.map("job", submit(null, "Allow Bedrock through the firewall", j -> Startup.bedrockFirewall(j, fp, fw)).id));
            }
            case "modrinth/search" -> {
                int page = 0;
                try { page = Math.max(0, Math.min(50, Integer.parseInt(qs.getOrDefault("page", "0")))); } catch (NumberFormatException ignored) { }
                sendJson(ex, 200, Modrinth.search(s, qs.get("q"), qs.get("sort"), page));
            }
            case "modrinth/plan" -> {
                requirePost(method);
                sendJson(ex, 200, Modrinth.plan(s, projectList(body(ex))));
            }
            case "modrinth/install" -> {
                requirePost(method);
                List<String> projects = projectList(body(ex));
                Modrinth.loaderOf(s);
                sendJson(ex, 200, Json.map("job", submit(s, projects.size() == 1 ? "Install mod" : "Install " + projects.size() + " mods", j -> Modrinth.install(s, projects, j)).id));
            }
            case "curseforge/search" -> {
                int page = 0;
                try { page = Math.max(0, Math.min(50, Integer.parseInt(qs.getOrDefault("page", "0")))); } catch (NumberFormatException ignored) { }
                Map<String, Object> r = CurseForge.search("mods", qs.get("q"), s.mcVersion(), Modrinth.loaderOf(s), qs.get("sort"), page);
                Set<Long> have = CurseForge.installedIds(s);
                for (Object h : Json.arr(r.get("hits"))) Json.obj(h).put("installed", Json.obj(h).get("id") instanceof Number n && have.contains(n.longValue()));
                r.put("mc", s.mcVersion());
                r.put("loader", Modrinth.loaderOf(s));
                sendJson(ex, 200, r);
            }
            case "curseforge/plan" -> {
                requirePost(method);
                sendJson(ex, 200, CurseForge.plan(s, idList(body(ex))));
            }
            case "curseforge/install" -> {
                requirePost(method);
                List<Long> ids = idList(body(ex));
                Modrinth.loaderOf(s);
                sendJson(ex, 200, Json.map("job", submit(s, ids.size() == 1 ? "Install mod from CurseForge" : "Install " + ids.size() + " mods from CurseForge", j -> CurseForge.install(s, ids, j)).id));
            }
            case "mods/remove" -> {
                requirePost(method);
                Modrinth.remove(s, Json.str(body(ex), "file"));
                sendJson(ex, 200, Json.map("ok", true));
            }
            case "mods/toggle" -> {
                requirePost(method);
                Map<String, Object> b = body(ex);
                s.setModEnabled(String.valueOf(b.get("file")), Boolean.TRUE.equals(b.get("enabled")));
                sendJson(ex, 200, Json.map("ok", true));
            }
            case "updates/check" -> {
                requirePost(method);
                sendJson(ex, 200, Json.map("job", submit(s, "Check for updates", j -> s.runUpdater(j, true)).id));
            }
            case "updates/apply" -> {
                requirePost(method);
                Map<String, Object> ub = body(ex);
                int d = delay(ub);
                boolean backupFirst = !Boolean.FALSE.equals(ub.get("backup"));
                sendJson(ex, 200, Json.map("job", submit(s, "Update", j -> {
                    boolean was = s.running(true);
                    if (backupFirst) {
                        j.log("Taking a backup of the whole server first ...");
                        Backups.create(j, s, "full", Backups.dirFor(s), "Before an update", "before-update", me.username);
                    }
                    if (was) s.stop(j, d);
                    List<String> out = s.runUpdater(j, false);
                    if (was) s.start(j);
                    else j.log("Server was stopped; leaving it stopped.");
                    if (out.stream().anyMatch(l -> l.contains("Could not") || l.contains("failed")))
                        throw new Api(500, "The updater reported problems (see above); some updates were not installed.");
                }).id));
            }
            case "logs" -> sendJson(ex, 200, Json.map("files", s.logFiles()));
            case "logs/read" -> {
                int tail = 1000;
                try { tail = Math.max(1, Math.min(5000, Integer.parseInt(qs.getOrDefault("tail", "1000")))); } catch (NumberFormatException ignored) { }
                sendJson(ex, 200, Json.map("lines", s.readLogFile(qs.getOrDefault("kind", "log"), qs.getOrDefault("name", ""), tail, qs.get("q"))));
            }
            case "autocomplete" -> {
                byte[] gz = Autocomplete.get(s);
                ex.getResponseHeaders().add("Content-Encoding", "gzip");
                ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
                ex.getResponseHeaders().add("Cache-Control", "private, max-age=3600");
                ex.sendResponseHeaders(200, gz.length);
                try (OutputStream o = ex.getResponseBody()) { o.write(gz); }
            }
            default -> throw new Api(404, "Unknown endpoint");
        }
    }

    private static long num(Object o) {
        if (o == null) return 0;
        try { return (long) Double.parseDouble(String.valueOf(o).trim()); } catch (NumberFormatException e) { throw new Api(400, "Memory values must be numbers"); }
    }

    /** One project ("project") or several ("projects") from a request body. */
    private static List<String> projectList(Map<String, Object> b) {
        List<String> out = new ArrayList<>();
        for (Object o : Json.arr(b.get("projects"))) out.add(String.valueOf(o));
        if (out.isEmpty() && b.get("project") != null) out.add(String.valueOf(b.get("project")));
        for (String p : out) if (!Modrinth.SAFE_ID.matcher(p).matches()) throw new Api(400, "Bad project id");
        return out;
    }

    static void requirePost(String method) {
        if (!method.equals("POST")) throw new Api(405, "POST only");
    }

    /** Updates every Fabric server that has updates, one after another: stop (if running), back up, update, start again. A failure on one server does not stop the rest. */
    private static void updateAll(Job j, Map<String, Object> ub, String who) throws Exception {
        int d = delay(ub);
        boolean backupFirst = !Boolean.FALSE.equals(ub.get("backup"));
        boolean skipBusy = Boolean.TRUE.equals(ub.get("skipOccupied"));
        List<String> done = new ArrayList<>(), failed = new ArrayList<>(), skipped = new ArrayList<>(), current = new ArrayList<>();
        List<Srv> todo = new ArrayList<>();
        for (Srv s : List.copyOf(servers)) if (s.isFabric()) todo.add(s);
        j.log("Checking " + todo.size() + " Fabric server" + (todo.size() == 1 ? "" : "s") + " for updates ...");
        int n = 0;
        for (Srv s : todo) {
            j.checkCancel();
            n++;
            j.log("");
            j.log("=== " + n + " of " + todo.size() + ": " + s.name + " ===");
            try {
                if (s.job != null && s.job.active()) { j.log("Busy with another action (" + s.job.title + "); skipped."); skipped.add(s.name + " (busy)"); continue; }
                boolean was = s.running(true);
                if (skipBusy && was && !Json.arr(s.status().get("players")).isEmpty()) { j.log("Players are online; skipped."); skipped.add(s.name + " (players online)"); continue; }
                List<String> check = s.runUpdater(j, true);
                boolean pending = check.stream().anyMatch(l -> l.contains(" -> "));
                if (!pending) { j.log(s.name + " is already up to date."); current.add(s.name); continue; }
                if (backupFirst) {
                    j.log("Taking a backup first ...");
                    Backups.create(j, s, "full", Backups.dirFor(s), "Before an update", "before-update", who);
                }
                if (was) s.stop(j, d);
                List<String> out = s.runUpdater(j, false);
                if (was) s.start(j);
                else j.log("Server was stopped; leaving it stopped.");
                if (out.stream().anyMatch(l -> l.contains("Could not") || l.contains("failed"))) throw new Api(500, "The updater reported problems; some updates were not installed.");
                done.add(s.name);
            } catch (Job.Cancelled c) {
                throw c;
            } catch (Exception e) {
                j.log("FAILED: " + e.getMessage());
                failed.add(s.name + ": " + e.getMessage());
            }
        }
        j.log("");
        String summary = "Updated: " + (done.isEmpty() ? "none" : String.join(", ", done)) + ". Already current: " + (current.isEmpty() ? "none" : String.join(", ", current))
                + (skipped.isEmpty() ? "" : ". Skipped: " + String.join(", ", skipped)) + (failed.isEmpty() ? "" : ". Failed: " + String.join("; ", failed)) + ".";
        j.log(summary);
        Notify.post("update", failed.isEmpty() ? "good" : "bad", "", "Update all servers: " + done.size() + " updated" + (failed.isEmpty() ? "" : ", " + failed.size() + " failed"), summary, null);
        if (!failed.isEmpty()) throw new Api(500, "Some servers could not be updated (see above).");
    }

    private static int delay(Map<String, Object> b) {
        return Math.max(0, Math.min(600, Json.num(b, "delay", 0)));
    }
}

