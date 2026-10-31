import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.SecureRandom;
import java.util.*;
import java.util.regex.*;
import java.util.stream.Stream;
import java.util.zip.*;

/** One managed Minecraft server folder: status, files, players, mods and lifecycle. */
final class Srv {
    private static final Pattern JOIN = Pattern.compile("\\[Server thread/INFO\\]: ([^\\s<]\\S*) joined the game$");
    private static final Pattern LEFT = Pattern.compile("\\[Server thread/INFO\\]: ([^\\s<]\\S*) left the game$");
    private static final Pattern STARTING = Pattern.compile("Starting minecraft server version");
    private static final Pattern NAME_OK = Pattern.compile("^[.A-Za-z0-9_\\- ]{1,48}$");

    final Map<String, Object> cfg;
    final String id;
    final String name;
    volatile String task;
    final Path dir;
    final Rcon rcon = new Rcon();
    final LogTail log;
    final Set<String> online = Collections.synchronizedSet(new LinkedHashSet<>());
    volatile long launchedAt;
    volatile boolean stopping;
    /** When someone (the manager, or the game itself via /stop) last asked this server to stop, so a later exit is not mistaken for a crash. */
    volatile long stopRequestedAt;
    volatile boolean sawStopLine;
    /** Set when a player joins; the automatic backups use it to tell whether anything changed since the last one. */
    volatile boolean sawPlayer;
    volatile boolean ready;
    volatile Job job;

    private Map<String, String> propsCache;
    private String propsStamp = "";
    private String mcCache, mcStamp = "", loaderCache, loaderStamp = "";
    private Set<String> opsCache = Set.of();
    private long opsStamp = -1;
    private long worldSizeAt;
    private long worldSizeMb;
    private final Map<String, Map<String, Object>> modMeta = new HashMap<>();

    Srv(Map<String, Object> cfg) {
        this.cfg = cfg;
        this.id = String.valueOf(cfg.get("id"));
        this.name = String.valueOf(cfg.get("name"));
        this.dir = Path.of(String.valueOf(cfg.get("dir")));
        String t = Json.str(cfg, "task");
        this.task = t == null ? "" : t;
        this.log = new LogTail(dir.resolve("logs").resolve("latest.log"));
    }

    boolean autostart() { return Boolean.TRUE.equals(cfg.get("autostart")); }

    /** When this marker file exists the updater keeps the current Minecraft version and only updates loader and mods. */
    boolean pinned() { return Files.exists(dir.resolve("pin-minecraft.txt")); }

    void setPinned(boolean pin) throws IOException {
        Path f = dir.resolve("pin-minecraft.txt");
        if (pin) Files.writeString(f, "Delete this file to let the updater upgrade Minecraft automatically.\r\n", StandardCharsets.ISO_8859_1);
        else Files.deleteIfExists(f);
    }

    String kind() {
        String k = Json.str(cfg, "kind");
        if (k != null && !k.isBlank()) return k;
        if (isFabric()) return "fabric";
        if (Files.isDirectory(dir.resolve("libraries").resolve("net").resolve("neoforged"))) return "neoforge";
        if (Files.isDirectory(dir.resolve("libraries").resolve("net").resolve("minecraftforge"))) return "forge";
        return "vanilla";
    }

    // ---------- properties ----------

    synchronized Map<String, String> props() {
        Path p = dir.resolve("server.properties");
        try {
            String stamp = Files.getLastModifiedTime(p).toMillis() + ":" + Files.size(p);
            if (propsCache != null && stamp.equals(propsStamp)) return propsCache;
            Map<String, String> m = new LinkedHashMap<>();
            for (String line : Files.readAllLines(p, StandardCharsets.ISO_8859_1)) {
                String t = line.stripLeading();
                if (t.isEmpty() || t.startsWith("#") || t.startsWith("!")) continue;
                int eq = t.indexOf('=');
                if (eq < 1) continue;
                m.put(t.substring(0, eq).trim(), unescape(t.substring(eq + 1)));
            }
            propsCache = m;
            propsStamp = stamp;
            return m;
        } catch (IOException e) {
            return propsCache != null ? propsCache : new LinkedHashMap<>();
        }
    }

    private static String unescape(String raw) {
        try {
            Properties p = new Properties();
            p.load(new StringReader("k=" + raw));
            return p.getProperty("k", raw);
        } catch (IOException | IllegalArgumentException e) {
            return raw;
        }
    }

    static String escape(String v) {
        try {
            Properties p = new Properties();
            p.setProperty("k", v);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            p.store(bo, null);
            for (String l : bo.toString(StandardCharsets.ISO_8859_1).split("\\R")) if (l.startsWith("k=")) return l.substring(2);
        } catch (IOException ignored) { }
        return v;
    }

    synchronized void writeProps(Map<String, String> changes) throws IOException {
        Path p = dir.resolve("server.properties");
        if (!Files.exists(p)) Files.writeString(p, "#Minecraft server properties\r\n", StandardCharsets.ISO_8859_1);
        String text = Files.readString(p, StandardCharsets.ISO_8859_1);
        String nl = text.contains("\r\n") ? "\r\n" : "\n";
        List<String> lines = new ArrayList<>(Arrays.asList(text.split("\\R", -1)));
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) lines.remove(lines.size() - 1);
        Set<String> done = new HashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            String t = lines.get(i).stripLeading();
            if (t.isEmpty() || t.startsWith("#") || t.startsWith("!")) continue;
            int eq = t.indexOf('=');
            if (eq < 1) continue;
            String key = t.substring(0, eq).trim();
            if (changes.containsKey(key)) {
                lines.set(i, key + "=" + escape(changes.get(key)));
                done.add(key);
            }
        }
        for (Map.Entry<String, String> e : changes.entrySet()) if (!done.contains(e.getKey())) lines.add(e.getKey() + "=" + escape(e.getValue()));
        Path tmp = p.resolveSibling("server.properties.mgr.tmp");
        Files.writeString(tmp, String.join(nl, lines) + nl, StandardCharsets.ISO_8859_1);
        Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
        propsStamp = "";
    }

    private long scriptPortStamp = -1;
    private Integer scriptPortCache;

    /** The port players use: a "--port N" in the start script wins over server.properties (as it does when the server runs). */
    int gamePort() {
        try {
            Path f = dir.resolve(script());
            long t = Files.exists(f) ? Files.getLastModifiedTime(f).toMillis() : 0;
            synchronized (this) {
                if (t != scriptPortStamp) {
                    scriptPortStamp = t;
                    scriptPortCache = Ports.scriptPort(dir, script());
                }
                if (scriptPortCache != null) return scriptPortCache;
            }
        } catch (IOException ignored) { }
        try { return Integer.parseInt(props().getOrDefault("server-port", "25565").trim()); } catch (NumberFormatException e) { return 25565; }
    }

    boolean rconEnabled() {
        Map<String, String> p = props();
        return "true".equalsIgnoreCase(p.get("enable-rcon")) && !p.getOrDefault("rcon.password", "").isEmpty();
    }

    int rconPort() {
        try { return Integer.parseInt(props().getOrDefault("rcon.port", "25575").trim()); } catch (NumberFormatException e) { return 25575; }
    }

    private String rconPass() { return props().getOrDefault("rcon.password", ""); }

    String enableRcon() throws IOException {
        Map<String, String> ch = new LinkedHashMap<>();
        ch.put("enable-rcon", "true");
        if (rconPass().isEmpty()) {
            String chars = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
            SecureRandom r = new SecureRandom();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 24; i++) sb.append(chars.charAt(r.nextInt(chars.length())));
            ch.put("rcon.password", sb.toString());
        }
        ch.put("broadcast-rcon-to-ops", "false");
        // Keep an existing RCON port only if it is valid and does not clash with another managed server; otherwise pick a free one.
        int cur = props().containsKey("rcon.port") ? rconPort() : -1;
        if (cur < 1024 || cur > 65535 || cur == gamePort() || Ports.allUsed(this).contains(cur)) ch.put("rcon.port", Integer.toString(Ports.freeRconPort(this, null)));
        writeProps(ch);
        return "RCON enabled on port " + (ch.containsKey("rcon.port") ? ch.get("rcon.port") : cur) + ". Restart the server for it to take effect.";
    }

    // ---------- identity / versions ----------

    boolean isFabric() { return Files.exists(dir.resolve("fabric-server-launch.jar")); }

    /** The launch script, relative to the server folder (start.bat unless the server was imported with another one). */
    String script() {
        String s = Json.str(cfg, "script");
        return s == null || s.isBlank() ? "start.bat" : s;
    }

    boolean hasStartBat() { return Files.exists(dir.resolve(script())); }

    boolean autoUpdate() { return Boolean.TRUE.equals(cfg.get("autoUpdate")); }
    private volatile long geyserChecked;
    private volatile boolean geyserSeen;

    /** Whether Geyser is installed (checked at most every 15 seconds, because the status is polled constantly). */
    boolean hasGeyser() {
        long now = System.currentTimeMillis();
        if (now - geyserChecked > 15_000) {
            geyserSeen = Geyser.installed(this);
            geyserChecked = now;
        }
        return geyserSeen;
    }

    String priority() {
        String p = Json.str(cfg, "priority");
        return "AboveNormal".equals(p) || "High".equals(p) ? p : "Normal";
    }

    /** Sets the Windows CPU priority of the running server process (not possible for a process with higher rights than the manager). */
    boolean applyPriority() {
        Integer pid = listenerPid();
        if (pid == null) return false;
        Sys.Result r = Sys.run(15000, "powershell", "-NoProfile", "-NonInteractive", "-Command", "try { (Get-Process -Id " + pid + " -ErrorAction Stop).PriorityClass = '" + priority() + "'; 'ok' } catch { 'fail' }");
        return r.out().trim().endsWith("ok");
    }

    synchronized String mcVersion() {
        Path jar = dir.resolve("server.jar");
        try {
            String stamp = Files.getLastModifiedTime(jar).toMillis() + ":" + Files.size(jar);
            if (stamp.equals(mcStamp)) return mcCache;
            mcStamp = stamp;
            mcCache = null;
            try (ZipFile z = new ZipFile(jar.toFile())) {
                ZipEntry e = z.getEntry("version.json");
                if (e != null) mcCache = Json.str(Json.parse(new String(z.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8)), "id");
            }
        } catch (IOException | RuntimeException e) {
            mcCache = null;
        }
        return mcCache != null ? mcCache : Json.str(cfg, "mc");
    }

    synchronized String loaderVersion() {
        Path jar = dir.resolve("fabric-server-launch.jar");
        try {
            String stamp = Files.getLastModifiedTime(jar).toMillis() + ":" + Files.size(jar);
            if (stamp.equals(loaderStamp)) return loaderCache;
            loaderStamp = stamp;
            loaderCache = null;
            try (ZipFile z = new ZipFile(jar.toFile())) {
                ZipEntry e = z.getEntry("META-INF/MANIFEST.MF");
                if (e != null) {
                    String mf = new String(z.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8).replaceAll("\\r?\\n ", "");
                    Matcher m = Pattern.compile("fabric-loader/([^/]+)/").matcher(mf);
                    if (m.find()) loaderCache = m.group(1);
                }
            }
        } catch (IOException | RuntimeException e) {
            loaderCache = null;
        }
        return loaderCache;
    }

    String worldName() {
        try {
            Matcher m = Pattern.compile("--world\\s+(?:\"([^\"]+)\"|(\\S+))").matcher(Files.readString(dir.resolve("start.bat")));
            if (m.find()) return m.group(1) != null ? m.group(1) : m.group(2);
        } catch (IOException ignored) { }
        return props().getOrDefault("level-name", "world");
    }

    // ---------- status ----------

    boolean running(boolean forceSample) {
        Main.sampler.refresh(forceSample);
        return listenerPid() != null;
    }

    /** Process id of the server on this game port, or null if it is not up. Servers that share a port (e.g. a copied BAK folder) cannot all own it: the one we launched wins, then the first configured. */
    volatile int ownedPid;

    Srv portOwner() {
        int port = gamePort();
        Integer pid = Main.sampler.pidForPort(port);
        if (pid == null) {
            ownedPid = 0;
            return null;
        }
        if (ownedPid != 0 && ownedPid != pid) ownedPid = 0;
        List<Srv> sharing = new ArrayList<>();
        for (Srv s : Main.servers) if (s == this || s.gamePort() == port) sharing.add(s);
        if (sharing.size() <= 1) return this;
        for (Srv s : sharing) if (s.ownedPid == pid) return s;
        for (Srv s : sharing) if (s.launchedAt > 0 && System.currentTimeMillis() - s.launchedAt < 300_000) return s;
        return sharing.get(0);
    }

    Integer listenerPid() {
        Srv owner = portOwner();
        if (owner != this) return null;
        Integer pid = Main.sampler.pidForPort(gamePort());
        if (pid != null && launchedAt > 0) ownedPid = pid;
        return pid;
    }

    /** Name of another server that currently holds this server's game port, or null. */
    String portHeldBy() {
        Srv o = portOwner();
        return o != null && o != this ? o.name : null;
    }

    private Set<String> ops() {
        Path p = dir.resolve("ops.json");
        try {
            long t = Files.getLastModifiedTime(p).toMillis();
            if (t != opsStamp) {
                Set<String> s = new HashSet<>();
                for (Object o : Json.arr(Json.parse(Files.readString(p)))) {
                    String n = Json.str(o, "name");
                    if (n != null) s.add(n.toLowerCase());
                }
                opsCache = s;
                opsStamp = t;
            }
        } catch (IOException | RuntimeException e) {
            opsCache = Set.of();
        }
        return opsCache;
    }

    Map<String, Object> status() {
        Main.sampler.refresh(false);
        Map<String, String> p = props();
        int port = gamePort();
        Integer pid = listenerPid();
        String heldBy = pid == null ? portHeldBy() : null;
        boolean up = pid != null;
        if (up) launchedAt = 0;
        else stopping = false;
        String state = up ? (stopping ? "stopping" : "running")
                : (launchedAt > 0 && System.currentTimeMillis() - launchedAt < 300_000 ? "starting" : "stopped");
        if (!up) {
            online.clear();
            ready = false;
        }
        List<Object> players = new ArrayList<>();
        Set<String> ops = ops();
        synchronized (online) {
            for (String n : online) players.add(Json.map("name", n, "bedrock", n.startsWith("."), "op", ops.contains(n.toLowerCase())));
        }
        long uptime = 0;
        if (up) {
            long st = ProcessHandle.of(pid).flatMap(h -> h.info().startInstant()).map(java.time.Instant::toEpochMilli).orElse(0L);
            if (st == 0) {
                try { st = Files.getLastModifiedTime(dir.resolve(worldName()).resolve("session.lock")).toMillis(); } catch (IOException ignored) { }
            }
            if (st > 0) uptime = Math.max(0, (System.currentTimeMillis() - st) / 1000);
        }
        int max = 0;
        try { max = Integer.parseInt(p.getOrDefault("max-players", "20").trim()); } catch (NumberFormatException ignored) { }
        Job j = job;
        return Json.map("id", id, "name", name, "state", state, "port", port, "pid", up ? pid : null,
                "memMb", up ? Main.sampler.memMb(pid) : 0, "uptime", uptime, "players", players, "maxPlayers", max,
                "motd", p.getOrDefault("motd", ""), "rcon", rconEnabled(), "rconPort", rconPort(), "fabric", isFabric(), "kind", kind(), "portHeldBy", heldBy, "priority", priority(), "opsSync", OpsSync.enabled(this), "crashRestart", CrashWatch.enabled(this), "lanBroadcast", LanBroadcast.enabled(this), "geyser", hasGeyser(), "journeymap", JourneyMap.installed(this), "autostart", autostart(), "autoUpdate", autoUpdate(), "pinned", pinned(), "mc", mcVersion(), "java", Json.str(cfg, "java"),
                "loader", loaderVersion(), "job", j != null && j.active() ? j.id : null,
                "jobTitle", j != null && j.active() ? j.title : null);
    }

    void onLine(String line) {
        Geyser.onLine(this, line);
        Matcher m;
        if ((m = JOIN.matcher(line)).find()) {
            online.add(m.group(1));
            sawPlayer = true;
        }
        else if ((m = LEFT.matcher(line)).find()) online.remove(m.group(1));
        else if (STARTING.matcher(line).find()) {
            online.clear();
            ready = false;
            sawStopLine = false;
        } else if (line.contains("For help, type \"help\"")) ready = true;
        else if (line.contains("[Server thread/INFO]: Stopping server")) sawStopLine = true;
    }

    Map<String, Object> info() {
        long now = System.currentTimeMillis();
        if (now - worldSizeAt > 600_000) {
            worldSizeAt = now;
            Path w = dir.resolve(worldName());
            long total = 0;
            if (Files.isDirectory(w)) {
                try (Stream<Path> s = Files.walk(w)) {
                    total = s.filter(Files::isRegularFile).mapToLong(f -> {
                        try { return Files.size(f); } catch (IOException e) { return 0; }
                    }).sum();
                } catch (IOException | UncheckedIOException ignored) { }
            }
            worldSizeMb = total / (1024 * 1024);
        }
        return Json.map("mc", mcVersion(), "loader", loaderVersion(), "fabric", isFabric(), "world", worldName(),
                "worldSizeMb", worldSizeMb, "dir", dir.toString(), "launcher", task.isEmpty() ? "start.bat" : "scheduled task: " + task,
                "modCount", mods().size());
    }

    /** Forgets what the manager remembered about this server and reads it from the machine again (the Refresh button). */
    void resync() {
        propsStamp = "";
        mcStamp = "";
        loaderStamp = "";
        opsStamp = -1;
        worldSizeAt = 0;
        scriptPortStamp = -1;
        geyserChecked = 0;
        boolean up = running(true);
        Job j = job;
        if (!up) {
            if (j == null || !j.active()) { launchedAt = 0; stopping = false; }
            online.clear();
            ready = false;
            return;
        }
        if (!rconEnabled()) return;
        try {
            String out = rcon.exec(rconPort(), rconPass(), "list").replaceAll("§.", "");
            int i = out.indexOf(':');
            if (out.startsWith("There are") && i > 0) {
                Set<String> now = new LinkedHashSet<>();
                for (String n : out.substring(i + 1).split(",")) if (!n.isBlank()) now.add(n.strip());
                synchronized (online) { online.clear(); online.addAll(now); }
                ready = true;
            }
        } catch (IOException ignored) { }
    }

    // ---------- commands ----------

    String command(String cmd) {
        cmd = cmd == null ? "" : cmd.strip();
        while (cmd.startsWith("/")) cmd = cmd.substring(1);
        if (cmd.isEmpty() || cmd.length() > 1500 || cmd.indexOf('\n') >= 0 || cmd.indexOf('\r') >= 0) throw new Api(400, "Invalid command");
        if (!running(false)) throw new Api(409, "Server is not running");
        if (!rconEnabled()) throw new Api(409, "RCON is not enabled for this server. Enable it on the Properties tab and restart the server.");
        try {
            if (cmd.equalsIgnoreCase("stop")) {
                stopping = true;
                stopRequestedAt = System.currentTimeMillis();
                rcon.execAndForget(rconPort(), rconPass(), "stop");
                return "Stopping server...";
            }
            return rcon.exec(rconPort(), rconPass(), cmd).replaceAll("§.", "");
        } catch (IOException e) {
            throw new Api(502, "RCON error: " + e.getMessage() + (ready ? "" : " (the server may still be loading - try again in a few seconds)"));
        }
    }

    // ---------- lifecycle ----------

    private void launch() throws IOException {
        if (!hasStartBat()) throw new Api(400, "No " + script() + " in " + dir);
        launchedAt = System.currentTimeMillis();
        stopping = false;
        ready = false;
        if (!task.isEmpty()) {
            Sys.run(15000, "schtasks", "/End", "/TN", task);
            Sys.Result r = Sys.run(20000, "schtasks", "/Run", "/TN", task);
            if (r.code() != 0) {
                launchedAt = 0;
                throw new Api(500, "Could not run scheduled task '" + task + "': " + r.out().trim());
            }
        } else {
            ProcessBuilder pb = new ProcessBuilder("cmd.exe", "/c", dir.resolve(script()).toString()).directory(dir.toFile()).redirectErrorStream(true)
                    .redirectOutput(Main.DATA.resolve("launch-" + id + ".log").toFile()).redirectInput(ProcessBuilder.Redirect.from(new File("NUL")));
            pb.environment().put("MCM_JAVA", JavaRuntimes.javaFor(this));
            pb.start();
        }
    }

    void start(Job j) throws Exception {
        if (running(true)) {
            j.log("Server is already running.");
            return;
        }
        if (launchedAt > 0 && System.currentTimeMillis() - launchedAt < 300_000) throw new Api(409, name + " is already starting.");
        String holder = portHeldBy();
        if (holder != null) throw new Api(409, "Port " + gamePort() + " is already in use by " + holder + ". " + name + " uses the same game port (is it a copy?). Change its port on the Properties tab first.");
        j.log("Starting " + name + (task.isEmpty() ? "" : " (scheduled task '" + task + "')") + "...");
        Path upd = dir.resolve("updater.log");
        long updPos = Files.exists(upd) ? Files.size(upd) : 0;
        if (autoUpdate() && isFabric()) {
            j.log("Checking for Fabric and mod updates...");
            try {
                runUpdater(j, false);
                updPos = Files.exists(upd) ? Files.size(upd) : 0;
            } catch (Job.Cancelled c) {
                throw c;
            } catch (Exception e) {
                j.log("Update check failed (starting anyway): " + e.getMessage());
            }
        }
        OpsSync.apply(this);
        Geyser.reconcile(this);
        String bedrockNote = Geyser.prepareBedrockPort(this);
        if (bedrockNote != null) j.log(bedrockNote);
        launch();
        long deadline = System.currentTimeMillis() + 300_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                j.sleep(1500);
            } catch (Job.Cancelled c) {
                j.log("Stopped waiting (the server is still launching in the background).");
                throw c;
            }
            if (Files.exists(upd) && Files.size(upd) > updPos) {
                try (RandomAccessFile f = new RandomAccessFile(upd.toFile(), "r")) {
                    f.seek(updPos);
                    byte[] b = new byte[(int) (f.length() - updPos)];
                    f.readFully(b);
                    updPos = f.length();
                    for (String l : new String(b, StandardCharsets.UTF_8).split("\\R")) if (!l.isBlank()) j.log("[updater] " + l.replaceFirst("^\\S+ \\S+ ", ""));
                }
            }
            if (running(true)) {
                CrashWatch.markUp(this);
                j.log("Port " + gamePort() + " is open; waiting for the server to finish loading...");
                for (int i = 0; i < 60 && !ready; i++) j.sleep(1500);
                if (!priority().equals("Normal")) applyPriority();
                if (hasGeyser()) Geyser.reconcile(this);
                j.log(ready ? "Server is up on port " + gamePort() + "." : "Server is listening on port " + gamePort() + " (still loading).");
                return;
            }
        }
        launchedAt = 0;
        throw new Api(504, "Server did not open its port within 5 minutes. Check the console log.");
    }

    void stop(Job j, int delay) throws Exception { stop(j, delay, "stop"); }

    private static String secondsText(int n) {
        if (n >= 60 && n % 60 == 0) return (n / 60) + " minute" + (n == 60 ? "" : "s");
        return n + " second" + (n == 1 ? "" : "s");
    }

    /** verb is what players are told ("stop" or "restart"); the countdown warns at the start, at 1 minute, 30, 10 and the last 5 seconds. */
    void stop(Job j, int delay, String verb) throws Exception {
        if (!running(true)) {
            j.log("Server is not running.");
            return;
        }
        if (!rconEnabled()) throw new Api(409, "RCON is not enabled, so the server cannot be stopped gracefully from here. Enable RCON (Properties tab) or use Force kill.");
        try (java.net.Socket probe = new java.net.Socket()) {
            if (ready) probe.connect(new java.net.InetSocketAddress("127.0.0.1", rconPort()), 1500);
        } catch (IOException e) {
            throw new Api(409, name + " is running but nothing is listening on its RCON port " + rconPort() + ": it was started before RCON was turned on (or before the RCON port was changed), "
                    + "so it only reads RCON settings at its next start. The manager cannot stop it safely, only with Force kill (the world autosaves about every 5 minutes, so at most that much is lost). "
                    + "Force kill it once when nobody is playing and start it from the manager; from then on it stops normally.");
        }
        final Integer pidBefore = listenerPid();
        stopping = true;
        stopRequestedAt = System.currentTimeMillis();
        try {
            if (delay > 0) {
                j.log((verb.equals("restart") ? "Restarting" : "Stopping") + " in " + delay + " seconds; players are being warned.");
                for (int left = delay; left > 0; left--) {
                    if (j.cancel) {
                        stopping = false;
                        throw new Job.Cancelled();
                    }
                    if (left == delay || left == 60 || left == 30 || left == 10 || left <= 5)
                        rcon.exec(rconPort(), rconPass(), "say Server will " + verb + " in " + secondsText(left));
                    Thread.sleep(1000);
                }
            }
            j.log("Sending stop (world is saved first)...");
            for (int attempt = 0; ; attempt++) {
                try {
                    rcon.execAndForget(rconPort(), rconPass(), "stop");
                    break;
                } catch (java.net.ConnectException e) {
                    if (attempt >= 8) throw e;
                    j.sleep(2500);
                }
            }
        } catch (IOException e) {
            stopping = false;
            throw new Api(502, "RCON error: " + e.getMessage());
        }
        for (int i = 0; i < 80; i++) {
            Thread.sleep(1500);
            if (!running(true)) {
                rcon.close();
                if (pidBefore != null) waitForExit(j, pidBefore);
                stopping = false;
                j.log("Server stopped.");
                return;
            }
        }
        stopping = false;
        throw new Api(504, "Server did not stop within 2 minutes. Use Force kill if it is stuck.");
    }

    /** The port closing is not enough: the JVM can linger and keep mod jars locked, which breaks updates and restarts. */
    private void waitForExit(Job j, int pid) throws Exception {
        for (int i = 0; i < 30; i++) {
            Main.sampler.refresh(true);
            if (!Main.sampler.alive(pid)) return;
            if (i == 5) j.log("Waiting for the server process to exit...");
            Thread.sleep(1000);
        }
        j.log("The server saved and closed its port but the process is stuck; ending it.");
        if (!task.isEmpty()) Sys.run(15000, "schtasks", "/End", "/TN", task);
        Main.sampler.refresh(true);
        if (Main.sampler.alive(pid)) {
            Sys.Result r = Sys.run(15000, "taskkill", "/PID", Integer.toString(pid), "/F", "/T");
            if (r.out().toLowerCase().contains("access is denied")) endLegacyTasks(j);
        }
        for (int i = 0; i < 10; i++) {
            Thread.sleep(1000);
            Main.sampler.refresh(true);
            if (!Main.sampler.alive(pid)) return;
        }
        throw new Api(500, ELEVATED_MSG);
    }

    private static final String ELEVATED_MSG = "That server process was started with administrator rights (most likely by an old Windows scheduled task), "
            + "so the manager cannot end it. Stop it with Stop (graceful), or end it from Task Manager run as administrator.";

    /** Old scheduled tasks that launch this server run elevated; ending the task ends its process tree even though we are not elevated. */
    private void endLegacyTasks(Job j) {
        try {
            for (Object o : Startup.legacy()) {
                if (!id.equals(Json.str(o, "server")) || !"Running".equalsIgnoreCase(Json.str(o, "state"))) continue;
                String tn = Json.str(o, "name");
                j.log("Ending the Windows task \"" + tn + "\" that started this server...");
                Sys.run(15000, "schtasks", "/End", "/TN", tn);
            }
        } catch (RuntimeException e) {
            j.log("Could not look up the starting task: " + e.getMessage());
        }
    }

    void kill(Job j) throws Exception {
        j.log("Force stopping " + name + "...");
        stopRequestedAt = System.currentTimeMillis();
        Main.sampler.refresh(true);
        String holder = portHeldBy();
        if (holder != null) throw new Api(409, "Nothing was killed: the process on port " + gamePort() + " belongs to " + holder + ", not " + name + ". They share a game port.");
        if (!task.isEmpty()) Sys.run(15000, "schtasks", "/End", "/TN", task);
        Main.sampler.refresh(true);
        Integer pid = listenerPid();
        if (pid != null) {
            Sys.Result r = Sys.run(15000, "taskkill", "/PID", pid.toString(), "/F", "/T");
            j.log(r.out().trim());
            if (r.out().toLowerCase().contains("access is denied")) endLegacyTasks(j);
        }
        for (int i = 0; i < 20; i++) {
            Thread.sleep(750);
            if (!running(true)) {
                rcon.close();
                stopping = false;
                launchedAt = 0;
                j.log("Server process ended.");
                return;
            }
        }
        throw new Api(500, ELEVATED_MSG);
    }

    List<String> runUpdater(Job j, boolean dryRun) throws Exception {
        Path script = Main.cfg.updater();
        if (!Files.exists(script)) throw new Api(500, "Updater script not found: " + script);
        if (!isFabric()) throw new Api(400, "Updates are only available for Fabric servers.");
        List<String> cmd = new ArrayList<>(List.of("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", script.toString(), "-ServerDir", dir.toString(),
                "-CacheDir", Main.DATA.resolve("cache").resolve("updater").toString()));
        if (dryRun) cmd.add("-DryRun");
        ProcessBuilder upb = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true);
        upb.environment().put("MCM_JAVA", JavaRuntimes.javaFor(this));
        Process p = upb.start();
        List<String> out = new ArrayList<>();
        Thread killer = Thread.startVirtualThread(() -> {
            try {
                if (!p.waitFor(15, java.util.concurrent.TimeUnit.MINUTES)) p.destroyForcibly();
            } catch (InterruptedException ignored) { }
        });
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String l;
            while ((l = r.readLine()) != null) {
                l = l.replaceFirst("^\\[Updater\\] ", "").strip();
                if (!l.isEmpty()) {
                    out.add(l);
                    j.log(l);
                }
            }
        }
        p.waitFor();
        killer.interrupt();
        return out;
    }

    // ---------- players ----------

    private List<Object> readList(String file) {
        try { return Json.arr(Json.parse(Files.readString(dir.resolve(file), StandardCharsets.UTF_8))); } catch (IOException | RuntimeException e) { return new ArrayList<>(); }
    }

    Map<String, Object> playersData() {
        List<Object> pl = new ArrayList<>();
        Set<String> ops = ops();
        synchronized (online) {
            for (String n : online) pl.add(Json.map("name", n, "bedrock", n.startsWith("."), "op", ops.contains(n.toLowerCase())));
        }
        Map<String, String> p = props();
        List<Object> known = new ArrayList<>();
        for (Object o : readList("usercache.json")) known.add(Json.map("name", Json.str(o, "name"), "uuid", Json.str(o, "uuid")));
        return Json.map("online", pl, "whitelist", readList("whitelist.json"), "ops", readList("ops.json"),
                "bans", readList("banned-players.json"), "banIps", readList("banned-ips.json"), "known", known,
                "whitelistOn", "true".equalsIgnoreCase(p.get("white-list")), "enforceWhitelist", "true".equalsIgnoreCase(p.get("enforce-whitelist")));
    }

    void removeOffline(String list, String value) throws IOException {
        if (running(true)) throw new Api(409, "Server is running; use the in-game commands instead.");
        String file = switch (list) {
            case "whitelist" -> "whitelist.json";
            case "ops" -> "ops.json";
            case "bans" -> "banned-players.json";
            case "banIps" -> "banned-ips.json";
            default -> throw new Api(400, "Unknown list");
        };
        String field = list.equals("banIps") ? "ip" : "name";
        List<Object> out = new ArrayList<>();
        for (Object o : readList(file)) if (!value.equalsIgnoreCase(Json.str(o, field))) out.add(o);
        Files.writeString(dir.resolve(file), Json.stringify(out), StandardCharsets.UTF_8);
    }

    static boolean validPlayerName(String n) { return n != null && NAME_OK.matcher(n).matches(); }

    // ---------- mods ----------

    List<Map<String, Object>> mods() {
        List<Map<String, Object>> out = new ArrayList<>();
        Path md = dir.resolve("mods");
        if (!Files.isDirectory(md)) return out;
        try (Stream<Path> s = Files.list(md)) {
            for (Path f : s.sorted().toList()) {
                String fn = f.getFileName().toString();
                boolean disabled = fn.endsWith(".jar.disabled");
                if (!fn.endsWith(".jar") && !disabled) continue;
                try {
                    BasicFileAttributes at = Files.readAttributes(f, BasicFileAttributes.class);
                    String key = fn + ":" + at.lastModifiedTime().toMillis() + ":" + at.size();
                    Map<String, Object> meta;
                    synchronized (modMeta) { meta = modMeta.get(key); }
                    if (meta == null) {
                        meta = readModMeta(f);
                        synchronized (modMeta) { modMeta.put(key, meta); }
                    }
                    Map<String, Object> m = new LinkedHashMap<>(meta);
                    m.put("file", fn);
                    m.put("enabled", !disabled);
                    m.put("sizeKb", at.size() / 1024);
                    out.add(m);
                } catch (IOException ignored) { }
            }
        } catch (IOException ignored) { }
        return out;
    }

    private Map<String, Object> readModMeta(Path jar) {
        String fn = jar.getFileName().toString();
        try (ZipFile z = new ZipFile(jar.toFile())) {
            ZipEntry e = z.getEntry("fabric.mod.json");
            if (e != null) {
                Object o = Json.parse(new String(z.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8).replace("﻿", ""));
                String desc = Json.str(o, "description");
                return Json.map("id", Json.str(o, "id"), "name", Json.str(o, "name") != null ? Json.str(o, "name") : Json.str(o, "id"),
                        "version", Json.str(o, "version"), "description", desc == null ? "" : desc);
            }
        } catch (IOException | RuntimeException ignored) { }
        return Json.map("id", null, "name", fn.replaceAll("\\.jar(\\.disabled)?$", ""), "version", null, "description", "");
    }

    void setModEnabled(String file, boolean enabled) throws IOException {
        if (running(false)) throw new Api(409, "Stop the server before enabling or disabling mods.");
        if (!file.matches("[^\\\\/:*?\"<>|]+\\.jar(\\.disabled)?")) throw new Api(400, "Bad file name");
        Path src = dir.resolve("mods").resolve(file);
        if (!Files.isRegularFile(src)) throw new Api(404, "No such mod file");
        String base = file.replaceAll("\\.disabled$", "");
        Path dst = dir.resolve("mods").resolve(enabled ? base : base + ".disabled");
        if (!src.equals(dst)) Files.move(src, dst);
    }

    // ---------- log archive ----------

    List<Map<String, Object>> logFiles() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String[] d : new String[][] {{"logs", "log"}, {"crash-reports", "crash"}}) {
            Path p = dir.resolve(d[0]);
            if (!Files.isDirectory(p)) continue;
            try (Stream<Path> s = Files.list(p)) {
                for (Path f : s.toList()) {
                    String fn = f.getFileName().toString();
                    if (!Files.isRegularFile(f) || !(fn.endsWith(".log") || fn.endsWith(".log.gz") || fn.endsWith(".txt"))) continue;
                    out.add(Json.map("name", fn, "kind", d[1], "size", Files.size(f), "modified", Files.getLastModifiedTime(f).toMillis()));
                }
            } catch (IOException ignored) { }
        }
        out.sort((a, b) -> Long.compare((Long) b.get("modified"), (Long) a.get("modified")));
        return out;
    }

    List<String> readLogFile(String kind, String name, int tail, String q) throws IOException {
        Path f = null;
        for (Map<String, Object> m : logFiles()) {
            if (m.get("name").equals(name) && m.get("kind").equals(kind)) f = dir.resolve(kind.equals("crash") ? "crash-reports" : "logs").resolve(name);
        }
        if (f == null) throw new Api(404, "No such log file");
        List<String> lines = new ArrayList<>();
        try (InputStream raw = Files.newInputStream(f);
             BufferedReader r = new BufferedReader(new InputStreamReader(name.endsWith(".gz") ? new GZIPInputStream(raw) : raw, StandardCharsets.UTF_8))) {
            String ql = q == null ? "" : q.toLowerCase();
            String l;
            ArrayDeque<String> ring = new ArrayDeque<>();
            while ((l = r.readLine()) != null) {
                if (!ql.isEmpty() && !l.toLowerCase().contains(ql)) continue;
                ring.addLast(l);
                if (ring.size() > tail) ring.removeFirst();
            }
            lines.addAll(ring);
        }
        return lines;
    }
}
