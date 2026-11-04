import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * The browser-based installer:  runtime\bin\java.exe -jar app\mcmanager.jar --setup   (Install.bat does this).
 * It serves the setup wizard (web/setup) on this PC only, behind a one-time token, and runs the same steps the Inno installer does:
 * Setup.ps1 copies the files and sets up shortcuts, firewall and startup, then --provision stores the choices, then the manager starts.
 */
final class SetupWizard {
    private SetupWizard() { }

    private static final String COOKIE = "mcsesh_setup";
    private static Path src;
    private static String token;
    private static int listenPort;
    private static CompletableFuture<Map<String, String>> net;

    private static final List<String> lines = Collections.synchronizedList(new ArrayList<>());
    private static volatile boolean running, done, ok;
    private static volatile String error = "", openUrl = "";
    private static volatile int percent;
    private static final CountDownLatch quit = new CountDownLatch(1);

    static void run() throws Exception {
        src = Main.HOME;
        if (!Files.isRegularFile(src.resolve("Setup.ps1")) || !Files.isRegularFile(src.resolve("app").resolve("mcmanager.jar"))) {
            System.out.println("Setup must be started from the unzipped MC.SESH folder (the one that contains Setup.ps1 and the app folder).");
            System.exit(3);
        }
        byte[] rnd = new byte[18];
        new SecureRandom().nextBytes(rnd);
        token = Base64.getUrlEncoder().withoutPadding().encodeToString(rnd);
        HttpServer http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 16);
        listenPort = http.getAddress().getPort();
        http.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        http.createContext("/", SetupWizard::handle);
        http.start();
        net = CompletableFuture.supplyAsync(SetupWizard::netInfo);
        String url = "http://127.0.0.1:" + listenPort + "/?t=" + token;
        System.out.println("MC.SESH setup is open in your browser.");
        System.out.println("If it did not open, paste this address into your browser:  " + url);
        System.out.println("Keep this window open until setup finishes. Close it to cancel.");
        if (System.getenv("MCSESH_NO_BROWSER") == null) {   // set by tests that drive the wizard in their own browser
            try { new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start(); } catch (IOException e) { System.out.println("(Could not open the browser automatically: " + e.getMessage() + ")"); }
        }
        quit.await(3, TimeUnit.HOURS);
        Thread.sleep(300);
        http.stop(0);
        System.exit(0);
    }

    // ---------- requests ----------

    private static void handle(HttpExchange ex) throws IOException {
        try {
            String host = ex.getRequestHeaders().getFirst("Host");
            if (host == null || !(host.equals("127.0.0.1:" + listenPort) || host.equals("localhost:" + listenPort))) { text(ex, 403, "Forbidden"); return; }
            String path = ex.getRequestURI().getPath(), method = ex.getRequestMethod(), q = ex.getRequestURI().getRawQuery();
            boolean authed = hasCookie(ex);
            if (path.equals("/") && q != null && q.contains("t=" + token)) {
                ex.getResponseHeaders().add("Set-Cookie", COOKIE + "=" + token + "; Path=/; HttpOnly; SameSite=Strict");
                authed = true;
            }
            if (path.startsWith("/api/")) {
                if (!authed) { json(ex, 403, Json.map("error", "Open the setup page with Install.bat.")); return; }
                api(ex, method, path);
                return;
            }
            if (!authed && path.equals("/")) { text(ex, 403, "Open MC.SESH setup by double-clicking Install.bat."); return; }
            staticFile(ex, path);
        } catch (Api a) {
            json(ex, a.code, Json.map("error", a.getMessage()));
        } catch (Throwable t) {
            try { json(ex, 500, Json.map("error", String.valueOf(t.getMessage() != null ? t.getMessage() : t))); } catch (Throwable ignored) { }
        } finally {
            ex.close();
        }
    }

    private static boolean hasCookie(HttpExchange ex) {
        List<String> cs = ex.getRequestHeaders().get("Cookie");
        if (cs != null) for (String c : cs) for (String p : c.split(";")) if (p.trim().equals(COOKIE + "=" + token)) return true;
        return false;
    }

    private static void staticFile(HttpExchange ex, String path) throws IOException {
        String rel = path.equals("/") ? "index.html" : path.substring(1);
        if (rel.contains("..") || rel.contains("\\") || rel.contains("//")) throw new Api(404, "Not found");
        if (rel.equals("music/custom")) {
            for (String ext : new String[]{"mp3", "ogg", "wav", "m4a"}) {
                Path f = src.resolve("music").resolve("theme." + ext);
                if (Files.isRegularFile(f)) { raw(ex, "audio/" + (ext.equals("mp3") ? "mpeg" : ext.equals("m4a") ? "mp4" : ext), Files.readAllBytes(f)); return; }
            }
            throw new Api(404, "Not found");
        }
        String res = rel.equals("logo.png") || rel.equals("favicon-32.png") ? rel : "setup/" + rel;
        byte[] data;
        try (InputStream in = SetupWizard.class.getResourceAsStream("/web/" + res)) {
            if (in == null) throw new Api(404, "Not found");
            data = in.readAllBytes();
        }
        String ext = rel.substring(rel.lastIndexOf('.') + 1);
        String type = switch (ext) {
            case "html" -> "text/html; charset=utf-8";
            case "css" -> "text/css; charset=utf-8";
            case "js" -> "text/javascript; charset=utf-8";
            case "png" -> "image/png";
            case "jpg" -> "image/jpeg";
            case "svg" -> "image/svg+xml";
            default -> throw new Api(404, "Not found");
        };
        ex.getResponseHeaders().add("Cache-Control", "no-store");
        raw(ex, type, data);
    }

    private static void api(HttpExchange ex, String method, String path) throws Exception {
        switch (path) {
            case "/api/setup/info" -> json(ex, 200, info());
            case "/api/setup/path" -> json(ex, 200, checkPath(Json.str(body(ex), "path")));
            case "/api/setup/install" -> {
                if (!method.equals("POST")) throw new Api(405, "Method not allowed");
                start(body(ex));
                json(ex, 200, Json.map("started", true));
            }
            case "/api/setup/progress" -> {
                int from = 0;
                String q = ex.getRequestURI().getQuery();
                if (q != null && q.startsWith("from=")) try { from = Math.max(0, Integer.parseInt(q.substring(5))); } catch (NumberFormatException ignored) { }
                List<String> out;
                synchronized (lines) { out = new ArrayList<>(lines.subList(Math.min(from, lines.size()), lines.size())); }
                json(ex, 200, Json.map("lines", out, "next", from + out.size(), "running", running, "done", done, "ok", ok, "error", error, "percent", percent, "url", openUrl));
            }
            case "/api/setup/finish" -> { json(ex, 200, Json.map("ok", true)); Thread.startVirtualThread(() -> { try { Thread.sleep(1500); } catch (InterruptedException ignored) { } quit.countDown(); }); }
            default -> throw new Api(404, "Not found");
        }
    }

    private static Map<String, Object> body(HttpExchange ex) throws IOException {
        byte[] b = ex.getRequestBody().readNBytes(1 << 20);
        return b.length == 0 ? new LinkedHashMap<>() : Json.obj(Json.parse(new String(b, StandardCharsets.UTF_8)));
    }

    private static void json(HttpExchange ex, int code, Object o) throws IOException { raw(ex, code, "application/json; charset=utf-8", Json.stringify(o).getBytes(StandardCharsets.UTF_8)); }
    private static void text(HttpExchange ex, int code, String s) throws IOException { raw(ex, code, "text/plain; charset=utf-8", s.getBytes(StandardCharsets.UTF_8)); }
    private static void raw(HttpExchange ex, String type, byte[] b) throws IOException { raw(ex, 200, type, b); }
    private static void raw(HttpExchange ex, int code, String type, byte[] b) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(code, b.length == 0 ? -1 : b.length);
        if (b.length > 0) ex.getResponseBody().write(b);
    }

    // ---------- facts about this PC ----------

    private static Map<String, String> netInfo() {
        Map<String, String> m = new LinkedHashMap<>();
        try {
            Process p = new ProcessBuilder("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command",
                    "$c = Get-NetIPConfiguration | Where-Object { $_.IPv4DefaultGateway -and $_.NetAdapter.Status -eq 'Up' } | Select-Object -First 1; if ($c) { $c.IPv4Address.IPAddress + '|' + $c.IPv4DefaultGateway.NextHop + '|' + $c.NetAdapter.MacAddress }")
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), Charset.defaultCharset()).trim();
            p.waitFor(20, TimeUnit.SECONDS);
            String[] f = out.lines().filter(l -> l.contains("|")).findFirst().orElse("").split("\\|");
            if (f.length >= 3) { m.put("ip", f[0].trim()); m.put("gateway", f[1].trim()); m.put("mac", f[2].trim()); }
        } catch (Exception ignored) { }
        return m;
    }

    private static boolean portFree(int p) {
        try (ServerSocket s = new ServerSocket()) { s.setReuseAddress(false); s.bind(new InetSocketAddress(p)); return true; } catch (IOException e) { return false; }
    }

    private static double gb(long bytes) { return Math.round(bytes / 1073741824.0 * 10) / 10.0; }

    private static Map<String, Object> info() throws Exception {
        List<Object> drives = new ArrayList<>();
        String best = "C:\\";
        long bestFree = -1;
        for (Path root : FileSystemsRoots.list()) {
            try {
                var st = Files.getFileStore(root);
                if (st.isReadOnly() || st.getTotalSpace() <= 0) continue;
                long free = st.getUsableSpace();
                drives.add(Json.map("root", root.toString(), "freeGb", gb(free), "totalGb", gb(st.getTotalSpace())));
                if (free > bestFree) { bestFree = free; best = root.toString(); }
            } catch (IOException ignored) { }
        }
        int port = 8585;
        while (port < 8700 && !portFree(port)) port++;
        long ram = 0;
        try { ram = ((com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean()).getTotalMemorySize(); } catch (Throwable ignored) { }
        Map<String, String> n;
        try { n = net.get(15, TimeUnit.SECONDS); } catch (Exception e) { n = Map.of(); }
        boolean custom = false;
        for (String ext : new String[]{"mp3", "ogg", "wav", "m4a"}) if (Files.isRegularFile(src.resolve("music").resolve("theme." + ext))) custom = true;
        return Json.map("version", Main.version(), "installDir", "C:\\MC.SESH", "serversDir", best + "MC.Servers", "port", port, "ramGb", Math.max(2, Math.round(ram / 1073741824.0)),
                "drives", drives, "ip", n.getOrDefault("ip", ""), "gateway", n.getOrDefault("gateway", ""), "mac", n.getOrDefault("mac", ""), "customMusic", custom,
                "inPlace", src.toString());
    }

    /** Roots of the fixed drives. */
    private static final class FileSystemsRoots {
        static List<Path> list() {
            List<Path> l = new ArrayList<>();
            for (Path p : java.nio.file.FileSystems.getDefault().getRootDirectories()) l.add(p);
            return l;
        }
    }

    private static Map<String, Object> checkPath(String raw) {
        String s = raw == null ? "" : raw.trim().replace("\"", "");
        if (s.isEmpty()) return Json.map("ok", false, "message", "Please enter a folder.");
        try {
            Path p = Path.of(s);
            if (!p.isAbsolute() || p.getRoot() == null) return Json.map("ok", false, "message", "Please give a full path, such as D:\\MC.Servers");
            if (!Files.exists(p.getRoot())) return Json.map("ok", false, "message", "The drive " + p.getRoot() + " does not exist.");
            Path near = p;
            while (near != null && !Files.exists(near)) near = near.getParent();
            if (near == null || !Files.isWritable(near)) return Json.map("ok", false, "message", "That place is not writable. Pick another folder (a folder on a drive you own, not Windows or Program Files).");
            var st = Files.getFileStore(near);
            boolean existing = Files.isRegularFile(p.resolve("data").resolve("config.json"));
            Map<String, Object> out = Json.map("ok", true, "root", p.getRoot().toString(), "freeGb", gb(st.getUsableSpace()), "existing", existing, "message", "");
            if (existing) out.put("prefill", prefill(p));
            return out;
        } catch (Exception e) {
            return Json.map("ok", false, "message", "That is not a valid folder path.");
        }
    }

    /** What an existing install already uses, so an update offers those choices again instead of fresh guesses. The CurseForge key itself is never sent, only whether one is saved. */
    private static Map<String, Object> prefill(Path inst) {
        Map<String, Object> out = Json.map();
        try {
            Map<String, Object> c = Json.obj(Json.parse(Files.readString(inst.resolve("data").resolve("config.json"), StandardCharsets.UTF_8)));
            Map<String, Object> paths = c.get("paths") instanceof Map ? Json.obj(c.get("paths")) : Json.map();
            out.put("port", Json.num(c, "port", 0));
            out.put("serversRoot", java.util.Objects.toString(Json.str(paths, "serversRoot"), ""));
            out.put("backupsRoot", java.util.Objects.toString(Json.str(paths, "backupsRoot"), ""));
            out.put("createDefaults", c.get("createDefaults") instanceof Map ? c.get("createDefaults") : Json.map());
            out.put("hasCfKey", c.get("curseforge") instanceof Map<?, ?> cf && cf.get("apiKey") != null && !String.valueOf(cf.get("apiKey")).isBlank());
        } catch (Exception ignored) { }
        try {   // how this install starts today, so the startup page offers it again instead of "manual"
            Process q = new ProcessBuilder("schtasks", "/query", "/tn", "MC.SESH", "/xml").redirectErrorStream(true).start();
            String xml = new String(q.getInputStream().readAllBytes(), Charset.defaultCharset());
            if (q.waitFor() == 0 && xml.toLowerCase().contains(inst.toString().toLowerCase())) out.put("startup", xml.contains("<BootTrigger") ? "boot" : xml.contains("<LogonTrigger") ? "logon" : "off");
        } catch (Exception ignored) { }
        return out;
    }

    // ---------- the install ----------

    private static synchronized void start(Map<String, Object> o) {
        if (running) throw new Api(409, "Setup is already running.");
        String installDir = str(o, "installDir"), serversDir = str(o, "serversDir"), backupsDir = str(o, "backupsDir");
        for (String[] f : new String[][]{{"install", installDir}, {"servers", serversDir}}) {
            if (!Boolean.TRUE.equals(checkPath(f[1]).get("ok"))) throw new Api(400, "The " + f[0] + " folder is not usable: " + checkPath(f[1]).get("message"));
        }
        if (backupsDir.isEmpty()) backupsDir = Path.of(serversDir).resolve("_backups").toString();
        else if (!Boolean.TRUE.equals(checkPath(backupsDir).get("ok"))) throw new Api(400, "The backups folder is not usable: " + checkPath(backupsDir).get("message"));
        int port = Json.num(o, "port", 0);
        if (port < 1024 || port > 65535) throw new Api(400, "The port must be between 1024 and 65535.");
        String startup = str(o, "startup");
        if (!List.of("off", "logon", "boot").contains(startup)) startup = "off";
        String user = str(o, "ownerUser"), pass = o.get("ownerPass") instanceof String s ? s : "", cf = str(o, "cfKey");
        if (!user.isEmpty() && pass.length() < 8) throw new Api(400, "The owner password must be at least 8 characters.");
        Map<String, Object> defaults = Main.cleanCreateDefaults(o.get("defaults"));
        boolean firewall = Boolean.TRUE.equals(o.get("firewall")), shortcuts = Boolean.TRUE.equals(o.get("shortcuts"));
        lines.clear();
        running = true; done = false; ok = false; error = ""; openUrl = ""; percent = 2;
        final String fInstall = installDir, fServers = serversDir, fBackups = backupsDir, fStartup = startup;
        Thread.startVirtualThread(() -> install(fInstall, fServers, fBackups, port, fStartup, firewall, shortcuts, user, pass, cf, defaults));
    }

    private static String str(Map<String, Object> o, String k) { Object v = o.get(k); return v == null ? "" : String.valueOf(v).trim().replace("\"", ""); }

    private static void say(String s, int pct) {
        if (s != null && !s.isBlank()) lines.add(s);
        if (pct > percent) percent = pct;
    }

    private static int run(List<String> cmd, Path dir, Map<String, String> env, int fromPct, int toPct) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        if (dir != null) pb.directory(dir.toFile());
        if (env != null) pb.environment().putAll(env);
        Process p = pb.start();
        Charset cs = Charset.forName(System.getProperty("native.encoding", "UTF-8"));
        int pct = fromPct;
        try (var r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream(), cs))) {
            String l;
            while ((l = r.readLine()) != null) { pct = Math.min(toPct, pct + 3); say(l.stripTrailing(), pct); }
        }
        return p.waitFor();
    }

    private static boolean up(int port) {
        try {
            HttpURLConnection c = (HttpURLConnection) URI.create("http://127.0.0.1:" + port + "/").toURL().openConnection();
            c.setConnectTimeout(800); c.setReadTimeout(1500);
            int code = c.getResponseCode();
            return code < 500;
        } catch (Exception e) { return false; }
    }

    /** Starts the manager the way this install normally runs: through its Windows task when it has one (so it keeps its rights), otherwise with Start.bat. */
    private static void startManager(Path inst) throws IOException {
        try {
            Process q = new ProcessBuilder("schtasks", "/query", "/tn", "MC.SESH", "/xml").redirectErrorStream(true).start();
            String xml = new String(q.getInputStream().readAllBytes(), Charset.defaultCharset());
            if (q.waitFor() == 0 && xml.toLowerCase().contains(inst.toString().toLowerCase())) {
                Process r = new ProcessBuilder("schtasks", "/run", "/tn", "MC.SESH").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
                if (r.waitFor() == 0) return;
            }
        } catch (Exception ignored) { }
        new ProcessBuilder("cmd.exe", "/c", inst.resolve("Start.bat").toString(), "quiet").directory(inst.toFile()).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
    }

    private static void install(String installDir, String serversDir, String backupsDir, int port, String startup, boolean firewall, boolean shortcuts, String user, String pass, String cf, Map<String, Object> defaults) {
        Path inst = Path.of(installDir);
        try {
            boolean upgrade = Files.isRegularFile(inst.resolve("data").resolve("config.json"));
            say(upgrade ? "Found an existing MC.SESH here: your settings and servers are kept." : "Setting up MC.SESH in " + installDir, 5);
            if (upgrade) say("Your Minecraft servers keep running. If Windows asks you to approve stopping MC.SESH, approve it (look for the shield on the taskbar).", 6);
            List<String> cmd = new ArrayList<>(List.of("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", src.resolve("Setup.ps1").toString(), "-Silent", "-NoLaunch",
                    "-InstallDir", installDir, "-ServersDir", serversDir, "-Port", String.valueOf(port), "-Startup", startup));
            if (shortcuts) cmd.add("-Shortcuts");
            if (firewall) cmd.add("-Firewall");
            if (firewall || !startup.equals("off")) say("Windows may ask you to approve a step (look for a shield or a prompt on your taskbar).", 8);
            int rc = run(cmd, src, null, 10, 60);
            if (rc != 0) throw new IOException("Setup could not finish (code " + rc + "). Read the messages above, then press Try again.");
            say("Files copied.", 62);

            {   // an update stores the choices too: the pages come prefilled from the old install, so unchanged choices are written back as they were
                Path opt = inst.resolve("data").resolve("install-options.json");
                Files.writeString(opt, Json.stringify(Json.map("serversRoot", serversDir, "backupsRoot", backupsDir, "createDefaults", defaults, "port", port)), StandardCharsets.UTF_8);
                Map<String, String> env = new LinkedHashMap<>();
                env.put("MCSESH_CF_KEY", cf);
                if (!user.isEmpty()) { env.put("MCSESH_OWNER_USER", user); env.put("MCSESH_OWNER_PASS", pass); }
                say("Saving your choices...", 66);
                int prc = run(List.of(inst.resolve("runtime").resolve("bin").resolve("java.exe").toString(), "-Xmx96m", "-jar", inst.resolve("app").resolve("mcmanager.jar").toString(), "--provision", opt.toString()), inst, env, 66, 78);
                Files.deleteIfExists(opt);
                if (prc != 0) say("The owner account could not be created here. You can create it the first time you open MC.SESH.", 78);
            }

            if (!up(port)) {
                say("Starting MC.SESH...", 82);
                startManager(inst);
            }
            long end = System.currentTimeMillis() + 90_000;
            while (!up(port)) {
                if (System.currentTimeMillis() > end) throw new IOException("MC.SESH was installed but did not answer on port " + port + ". Open it from the Start Menu or run Start.bat in " + installDir + ".");
                Thread.sleep(700);
            }
            openUrl = "http://localhost:" + port + "/";
            say("MC.SESH is running.", 100);
            ok = true;
        } catch (Throwable t) {
            error = String.valueOf(t.getMessage() != null ? t.getMessage() : t);
            say(error, percent);
            // an existing MC.SESH was stopped to replace its files: bring it back so a failed install never leaves the manager down
            try {
                if (Files.isRegularFile(inst.resolve("data").resolve("config.json")) && !up(port) && Files.isRegularFile(inst.resolve("Start.bat"))) {
                    startManager(inst);
                    say("Your existing MC.SESH was started again.", percent);
                }
            } catch (Throwable ignored) { }
        } finally {
            done = true;
            running = false;
        }
    }
}
