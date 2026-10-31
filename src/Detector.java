import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Recognizes Minecraft server folders (including modpack server packs from CurseForge, FTB, ATLauncher...) and searches for them. */
final class Detector {
    private Detector() {}

    private static final List<String> SCRIPT_RANK = List.of("start.bat", "startserver.bat", "start-server.bat", "serverstart.bat", "launch.bat", "run.bat");
    private static final Set<String> SKIP = Set.of("windows", "$recycle.bin", "system volume information", "program files", "program files (x86)", "programdata",
            "node_modules", ".git", ".gradle", ".m2", "temp", "tmp", "cache", "caches", "libraries", "mods", "world", "world_nether", "world_the_end", "logs", "crash-reports",
            "versions", "assets", "saves", "resourcepacks", "shaderpacks", "screenshots", "config", "plugins", "datapacks", ".fabric", "defaultconfigs", "kubejs", "region",
            "playerdata", "backups", "updater-backups", "journeymap", "runtime", "recovery", "msocache", "perflogs", "$windows.~bt", "windowsapps", "wsl", "docker",
            "bin", "lib", "jmods", "legal", "include", "dist", "build", "obj", "target", "venv", ".venv", "__pycache__", "site-packages", "stats", "advancements", "dimensions", "generated");
    private static final Pattern MC_LOG = Pattern.compile("Starting minecraft server version ([0-9A-Za-z._+-]+)");
    private static final Pattern PAPER_LOG = Pattern.compile("\\(MC: ([0-9][0-9A-Za-z._-]*)\\)");

    private static boolean has(Path dir, String name) { return Files.exists(dir.resolve(name)); }

    private static Map<String, String> readProps(Path file) {
        Map<String, String> m = new LinkedHashMap<>();
        try {
            for (String l : Files.readAllLines(file, StandardCharsets.ISO_8859_1)) {
                String t = l.strip();
                if (t.isEmpty() || t.startsWith("#")) continue;
                int eq = t.indexOf('=');
                if (eq > 0) m.put(t.substring(0, eq).trim(), t.substring(eq + 1).trim());
            }
        } catch (IOException ignored) { }
        return m;
    }

    private static List<String> topFiles(Path dir, String suffix) {
        List<String> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(Files::isRegularFile).map(p -> p.getFileName().toString()).filter(n -> n.toLowerCase().endsWith(suffix)).sorted().forEach(out::add);
        } catch (IOException ignored) { }
        return out;
    }

    /** @return a description of the server in dir, or null if it does not look like a Minecraft server folder. */
    static Map<String, Object> inspect(Path dir) {
        if (!Files.isDirectory(dir)) return null;
        boolean props = has(dir, "server.properties");
        boolean client = has(dir, "options.txt") || has(dir, "launcher_profiles.json") || has(dir, "minecraftinstance.json") || has(dir, "instance.cfg")
                || (has(dir, "saves") && !props);
        if (client && !props) return null;
        List<String> jars = topFiles(dir, ".jar");
        List<String> bats = new ArrayList<>(topFiles(dir, ".bat"));
        bats.addAll(topFiles(dir, ".cmd"));
        boolean forgeLibs = Files.isDirectory(dir.resolve("libraries/net/minecraftforge/forge"));
        boolean neoLibs = Files.isDirectory(dir.resolve("libraries/net/neoforged/neoforge"));
        boolean serverJar = jars.stream().anyMatch(n -> n.equalsIgnoreCase("server.jar") || n.startsWith("minecraft_server") || n.startsWith("fabric-server") || n.startsWith("quilt-server")
                || n.startsWith("forge-") || n.startsWith("neoforge-") || n.startsWith("paper") || n.startsWith("spigot") || n.startsWith("purpur") || n.startsWith("craftbukkit") || n.startsWith("folia"));
        boolean packMarkers = has(dir, "server-setup-config.yaml") || has(dir, "user_jvm_args.txt") || has(dir, "variables.txt") || has(dir, "serverstarter.jar");
        boolean eulaFile = has(dir, "eula.txt");
        boolean looks = props || (eulaFile && (serverJar || forgeLibs || neoLibs)) || (!bats.isEmpty() && (serverJar || forgeLibs || neoLibs || packMarkers) && (has(dir, "mods") || serverJar));
        if (!looks) return null;

        String kind;
        if (neoLibs || jars.stream().anyMatch(n -> n.startsWith("neoforge-"))) kind = "neoforge";
        else if (forgeLibs || jars.stream().anyMatch(n -> n.startsWith("forge-"))) kind = "forge";
        else if (has(dir, "fabric-server-launch.jar") || has(dir, ".fabric-server-launcher") || has(dir, "fabric-server-launcher.properties")) kind = "fabric";
        else if (has(dir, "quilt-server-launch.jar")) kind = "quilt";
        else if (has(dir, "purpur.yml")) kind = "purpur";
        else if (jars.stream().anyMatch(n -> n.startsWith("paper")) || has(dir, "config/paper-global.yml") || has(dir, "paper.yml")) kind = "paper";
        else if (jars.stream().anyMatch(n -> n.startsWith("folia"))) kind = "folia";
        else if (has(dir, "spigot.yml") || jars.stream().anyMatch(n -> n.startsWith("spigot") || n.startsWith("craftbukkit")) || has(dir, "bukkit.yml")) kind = "spigot";
        else if (serverJar) kind = "vanilla";
        else kind = "unknown";

        String mc = mcVersion(dir, kind, jars);
        Map<String, String> p = props ? readProps(dir.resolve("server.properties")) : Map.of();
        int port = 25565;
        try { port = Integer.parseInt(p.getOrDefault("server-port", "25565")); } catch (NumberFormatException ignored) { }
        int rconPort = 25575;
        try { rconPort = Integer.parseInt(p.getOrDefault("rcon.port", "25575")); } catch (NumberFormatException ignored) { }
        String eula = !eulaFile ? "missing" : readEula(dir) ? "accepted" : "declined";

        bats.sort(Comparator.comparingInt((String n) -> {
            int i = SCRIPT_RANK.indexOf(n.toLowerCase());
            if (i >= 0) return i;
            String l = n.toLowerCase();
            return l.contains("install") || l.contains("update") || l.contains("uninstall") ? 50 : 20;
        }).thenComparing(String::compareToIgnoreCase));
        Integer sp = bats.isEmpty() ? null : Ports.scriptPort(dir, bats.get(0));
        if (sp != null) port = sp;
        int mods = 0;
        try (Stream<Path> s = Files.list(dir.resolve("mods"))) {
            mods = (int) s.filter(f -> f.getFileName().toString().endsWith(".jar")).count();
        } catch (IOException ignored) { }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dir", dir.toString());
        m.put("name", dir.getFileName() == null ? dir.toString() : dir.getFileName().toString());
        m.put("kind", kind);
        m.put("mc", mc);
        m.put("port", port);
        m.put("rconPort", rconPort);
        m.put("bedrockPort", Ports.bedrockPort(dir));
        m.put("rcon", "true".equalsIgnoreCase(p.get("enable-rcon")) && !p.getOrDefault("rcon.password", "").isEmpty());
        m.put("world", p.getOrDefault("level-name", "world"));
        m.put("scripts", bats);
        m.put("script", bats.isEmpty() ? null : bats.get(0));
        m.put("jars", jars);
        m.put("mods", mods);
        m.put("eula", eula);
        m.put("firstRun", !props);
        m.put("hint", sourceHint(dir));
        m.put("managed", isManaged(dir));
        return m;
    }

    private static boolean readEula(Path dir) {
        try { return Files.readString(dir.resolve("eula.txt"), StandardCharsets.ISO_8859_1).toLowerCase().contains("eula=true"); } catch (IOException e) { return false; }
    }

    private static boolean isManaged(Path dir) {
        for (Srv s : Main.servers) if (s.dir.toAbsolutePath().normalize().toString().equalsIgnoreCase(dir.toAbsolutePath().normalize().toString())) return true;
        return false;
    }

    private static String mcVersion(Path dir, String kind, List<String> jars) {
        if (jars.contains("server.jar")) {
            try (ZipFile z = new ZipFile(dir.resolve("server.jar").toFile())) {
                ZipEntry e = z.getEntry("version.json");
                if (e != null) {
                    String id = Json.str(Json.parse(new String(z.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8)), "id");
                    if (id != null) return id;
                }
            } catch (IOException | RuntimeException ignored) { }
        }
        try (Stream<Path> s = Files.list(dir.resolve("libraries/net/minecraftforge/forge"))) {
            Optional<String> v = s.map(p -> p.getFileName().toString()).filter(n -> n.contains("-")).findFirst();
            if (v.isPresent()) return v.get().substring(0, v.get().indexOf('-'));
        } catch (IOException ignored) { }
        try (Stream<Path> s = Files.list(dir.resolve("libraries/net/neoforged/neoforge"))) {
            Optional<String> v = s.map(p -> p.getFileName().toString()).findFirst();
            if (v.isPresent()) return Installer.neoMc(v.get());
        } catch (IOException ignored) { }
        try {
            Path log = dir.resolve("logs/latest.log");
            if (Files.exists(log)) {
                String head = new String(Files.readAllBytes(log), 0, (int) Math.min(Files.size(log), 200_000), StandardCharsets.UTF_8);
                Matcher m = MC_LOG.matcher(head);
                if (m.find()) return m.group(1);
                m = PAPER_LOG.matcher(head);
                if (m.find()) return m.group(1);
            }
        } catch (IOException ignored) { }
        for (String j : jars) {
            Matcher m = Pattern.compile("(?:minecraft_server|paper|purpur|spigot|craftbukkit|folia|forge|neoforge)[-.]?(\\d+\\.\\d+(?:\\.\\d+)?)").matcher(j);
            if (m.find()) return m.group(1);
        }
        try {
            if (has(dir, "manifest.json")) {
                Object mc = Json.obj(Json.parse(Files.readString(dir.resolve("manifest.json"), StandardCharsets.UTF_8))).get("minecraft");
                String v = Json.str(mc, "version");
                if (v != null) return v;
            }
            if (has(dir, "variables.txt")) {
                Matcher m = Pattern.compile("(?m)^MINECRAFT_VERSION=(\\S+)").matcher(Files.readString(dir.resolve("variables.txt"), StandardCharsets.UTF_8));
                if (m.find()) return m.group(1).replace("\"", "");
            }
        } catch (IOException | RuntimeException ignored) { }
        return null;
    }

    private static String sourceHint(Path dir) {
        String p = dir.toString().toLowerCase().replace('\\', '/');
        if (p.contains("/curseforge/")) return "CurseForge app";
        if (p.contains("/atlauncher/")) return "ATLauncher";
        if (p.contains("/ftb") || p.contains("feed-the-beast")) return "FTB";
        if (p.contains("/.technic/") || p.contains("/technic")) return "Technic";
        if (p.contains("/prismlauncher/") || p.contains("/multimc/")) return "Prism / MultiMC";
        if (p.contains("/modrinthapp/") || p.contains("/modrinth")) return "Modrinth app";
        if (p.contains("/mcsmanager")) return "MCSManager";
        if (p.contains("/crafty")) return "Crafty Controller";
        if (p.contains("/amp") && has(dir, "Minecraft")) return "AMP";
        if (has(dir, "manifest.json")) {
            try {
                String t = Files.readString(dir.resolve("manifest.json"), StandardCharsets.UTF_8);
                if (t.contains("minecraftModpack")) return "CurseForge server pack";
            } catch (IOException ignored) { }
        }
        if (has(dir, "modrinth.index.json")) return "Modrinth pack";
        if (has(dir, "server-setup-config.yaml")) return "ServerStarter modpack";
        if (has(dir, "variables.txt")) return "modpack server pack";
        return null;
    }

    // ---------- searching ----------

    static List<Path> quickRoots() {
        List<Path> r = new ArrayList<>();
        Path home = Path.of(System.getProperty("user.home"));
        String appData = System.getenv("APPDATA"), local = System.getenv("LOCALAPPDATA");
        r.add(Main.serversRoot());
        for (String s : new String[] {"Desktop", "Documents", "Downloads", "Minecraft", "Servers", "MinecraftServers", "Games", "curseforge/minecraft", "curseforge/minecraft/Instances", "Documents/Curse/Minecraft/Instances"})
            r.add(home.resolve(s));
        if (appData != null) for (String s : new String[] {"ATLauncher/servers", "ATLauncher/instances", ".technic", "ftb", "PrismLauncher/instances", "MCSManager", "ModrinthApp/profiles"}) r.add(Path.of(appData, s));
        if (local != null) for (String s : new String[] {"ftb-app/instances", "ftb-app"}) r.add(Path.of(local, s));
        for (String s : new String[] {"C:/Crafty", "C:/crafty-4", "C:/crafty-4/servers", "C:/MCSManager", "C:/Minecraft", "C:/Servers", "C:/MinecraftServers"}) r.add(Path.of(s));
        List<Path> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Path p : r) if (Files.isDirectory(p) && seen.add(p.toAbsolutePath().normalize().toString().toLowerCase())) out.add(p);
        return out;
    }

    /** All drive letters. They are probed (with a timeout) when searched, so an unplugged or disconnected drive cannot hang a search. */
    static List<Path> driveRoots() {
        List<Path> out = new ArrayList<>();
        for (Path p : FileSystems.getDefault().getRootDirectories()) out.add(p);
        return out;
    }

    /** True if the folder answers within the time limit (a disconnected network drive can block for a long time). */
    static boolean probe(Path p, long ms) {
        boolean[] ok = {false};
        Thread t = Thread.startVirtualThread(() -> {
            try { ok[0] = Files.isDirectory(p); } catch (RuntimeException ignored) { }
        });
        try { t.join(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return !t.isAlive() && ok[0];
    }

    /** Folders named like these are searched much deeper than usual. */
    private static final Pattern HINT = Pattern.compile("(?i)(^|[\\W_])mc($|[\\W_])|minecraft|servers?");

    /** Walks the roots looking for server folders; found servers are appended to results as they are discovered. */
    static void scan(Job j, List<Path> roots, int maxDepth, boolean driveLevel, long limitMs, List<Object> results, Set<String> seenDirs) throws Exception {
        long deadline = System.currentTimeMillis() + limitMs;
        long[] visited = {0};
        for (Path root : roots) {
            j.checkCancel();
            if (!probe(root, 2500)) {
                j.log("Skipping " + root + " (not responding or disconnected)");
                continue;
            }
            j.log("Searching " + root + " ...");
            walk(j, root, 0, maxDepth, driveLevel, deadline, visited, results, seenDirs);
            if (System.currentTimeMillis() > deadline) {
                j.log("Time limit reached; results so far are shown.");
                return;
            }
        }
        j.log("Looked through " + visited[0] + " folders.");
    }

    private static void walk(Job j, Path dir, int depth, int maxDepth, boolean driveLevel, long deadline, long[] visited, List<Object> results, Set<String> seen) throws Exception {
        if (System.currentTimeMillis() > deadline || results.size() >= 300) return;
        j.checkCancel();
        visited[0]++;
        if (visited[0] % 3000 == 0) j.log("  ...checked " + visited[0] + " folders so far");
        String key = dir.toAbsolutePath().normalize().toString().toLowerCase();
        if (!seen.add(key)) return;
        if (depth > 0 || Files.exists(dir.resolve("server.properties"))) {
            Map<String, Object> found = inspect(dir);
            if (found != null) {
                results.add(found);
                j.log("Found: " + dir + (found.get("mc") != null ? "  (" + found.get("kind") + " " + found.get("mc") + ")" : "  (" + found.get("kind") + ")"));
                return;
            }
        }
        String leaf = dir.getFileName() == null ? "" : dir.getFileName().toString();
        if (depth > 0 && HINT.matcher(leaf).find()) maxDepth = Math.max(maxDepth, depth + 6);
        if (depth >= maxDepth) return;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            List<Path> subs = new ArrayList<>();
            boolean programFolder = false;
            for (Path p : ds) {
                try {
                    var at = Files.readAttributes(p, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (at.isRegularFile()) {
                        String fn = p.getFileName().toString().toLowerCase();
                        if (fn.endsWith(".exe") || fn.endsWith(".dll")) programFolder = true;
                        continue;
                    }
                    if (!at.isDirectory() || at.isSymbolicLink() || at.isOther()) continue;
                } catch (IOException e) { continue; }
                String n = p.getFileName().toString().toLowerCase();
                if (SKIP.contains(n) || n.startsWith("$")) continue;
                if (n.equals("appdata") || (driveLevel && depth == 0 && n.equals("users"))) continue;
                subs.add(p);
            }
            if (programFolder && depth > 0) return;
            subs.sort(Comparator.comparing(p -> p.getFileName().toString().toLowerCase()));
            for (Path s : subs) walk(j, s, depth + 1, maxDepth, driveLevel, deadline, visited, results, seen);
        } catch (IOException | SecurityException ignored) { }
    }

    // ---------- adding ----------

    private static String uniqueName(String base) {
        String name = base.length() > 56 ? base.substring(0, 56) : base;
        String cand = name;
        int i = 2;
        while (true) {
            final String c = cand;
            if (Main.servers.stream().noneMatch(s -> s.name.equalsIgnoreCase(c))) return cand;
            cand = name + " " + i++;
        }
    }

    /** Adds several found servers with sensible defaults. Returns what was added and what was skipped (with reasons). */
    static Map<String, Object> bulkAdd(List<String> dirs, boolean enableRcon) {
        List<Object> added = new ArrayList<>(), skipped = new ArrayList<>();
        for (String d : dirs) {
            try {
                Path dir = Path.of(d).toAbsolutePath().normalize();
                Map<String, Object> info = inspect(dir);
                if (info == null) { skipped.add(Json.map("dir", d, "reason", "Not a Minecraft server folder")); continue; }
                if (Boolean.TRUE.equals(info.get("managed"))) { skipped.add(Json.map("dir", d, "reason", "Already in the manager")); continue; }
                Map<String, Object> req = new LinkedHashMap<>();
                req.put("dir", dir.toString());
                req.put("name", uniqueName(String.valueOf(info.get("name"))));
                req.put("enableRcon", enableRcon && !Boolean.TRUE.equals(info.get("rcon")));
                req.put("acceptEula", false);
                req.put("autostart", false);
                req.put("autoUpdate", false);
                List<?> scripts = (List<?>) info.get("scripts");
                if (scripts != null && !scripts.isEmpty()) req.put("script", info.get("script"));
                else {
                    List<?> jars = (List<?>) info.get("jars");
                    String kind = String.valueOf(info.get("kind"));
                    if (!kind.equals("forge") && !kind.equals("neoforge")) {
                        if (jars == null || jars.isEmpty()) { skipped.add(Json.map("dir", d, "reason", "No start script and no server jar found")); continue; }
                        req.put("jar", jars.contains("server.jar") ? "server.jar" : jars.get(0));
                    }
                    req.put("generate", true);
                }
                Srv s = add(req);
                List<Object> notes = new ArrayList<>();
                if (!s.rconEnabled()) notes.add("Remote console (RCON) is off, so console commands and a clean stop are unavailable until it is enabled in Settings.");
                if (Boolean.TRUE.equals(info.get("firstRun"))) notes.add("Not started yet; its start script may run an installer on first start.");
                if (!"accepted".equals(info.get("eula"))) notes.add("Minecraft EULA not accepted yet.");
                if (Boolean.TRUE.equals(req.get("generate"))) notes.add("A start script was generated for it.");
                added.add(Json.map("id", s.id, "name", s.name, "dir", s.dir.toString(), "kind", info.get("kind"), "notes", notes));
            } catch (Api a) {
                skipped.add(Json.map("dir", d, "reason", a.getMessage()));
            } catch (Exception e) {
                skipped.add(Json.map("dir", d, "reason", String.valueOf(e.getMessage())));
            }
        }
        return Json.map("added", added, "skipped", skipped);
    }

    static Srv add(Map<String, Object> req) throws Exception {
        Path dir = Path.of(String.valueOf(req.getOrDefault("dir", ""))).toAbsolutePath().normalize();
        Map<String, Object> info = inspect(dir);
        if (info == null) throw new Api(400, "No Minecraft server was found in that folder");
        if (Boolean.TRUE.equals(info.get("managed"))) throw new Api(409, "That folder is already managed");
        String name = String.valueOf(req.getOrDefault("name", info.get("name"))).trim();
        if (name.isEmpty() || name.length() > 60 || name.matches(".*[\\r\\n<>].*")) throw new Api(400, "Please give the server a name (up to 60 characters)");
        for (Srv s : Main.servers) if (s.name.equalsIgnoreCase(name)) throw new Api(409, "A server named \"" + name + "\" is already managed");
        String script = Json.str(req, "script");
        boolean generate = Boolean.TRUE.equals(req.get("generate"));
        if (generate) {
            String jar = Json.str(req, "jar");
            String kind = String.valueOf(info.get("kind"));
            int minMb = Math.max(256, Json.num(req, "minMb", 1024)), maxMb = Math.max(minMb, Json.num(req, "maxMb", 4096));
            String jvm = "-Xms" + minMb + "M -Xmx" + maxMb + "M";
            String line;
            if (kind.equals("forge") || kind.equals("neoforge")) {
                String args = Installer.findWinArgs(dir);
                if (args == null) throw new Api(400, "This Forge/NeoForge folder has no launch arguments yet. Run its installer or start script once, then add it.");
                Files.writeString(dir.resolve("user_jvm_args.txt"), jvm + "\r\n", StandardCharsets.ISO_8859_1);
                line = "\"%MCM_JAVA%\" @user_jvm_args.txt @" + args + " nogui";
            } else {
                if (jar == null || !jar.matches("[^\\\\/:*?\"<>|]+\\.jar") || !Files.exists(dir.resolve(jar))) throw new Api(400, "Pick the server .jar to launch");
                line = "\"%MCM_JAVA%\" " + jvm + " -Djava.awt.headless=true -XX:+UseG1GC -jar \"" + jar + "\" nogui";
            }
            script = "start.bat";
            if (Files.exists(dir.resolve(script))) script = "manager-start.bat";
            Files.writeString(dir.resolve(script), "@ECHO OFF\r\ncd /d \"%~dp0\"\r\nif not defined MCM_JAVA set \"MCM_JAVA=java\"\r\n" + line + "\r\n", StandardCharsets.ISO_8859_1);
        } else {
            if (script == null || !script.matches("[^\\\\/:*?\"<>|]+\\.(bat|cmd)") || !Files.exists(dir.resolve(script))) throw new Api(400, "Pick the start script for this server (or let the manager create one)");
        }
        if (Boolean.TRUE.equals(req.get("acceptEula"))) {
            Files.writeString(dir.resolve("eula.txt"), "#Accepted via MC Server Manager (https://aka.ms/MinecraftEULA)\r\neula=true\r\n", StandardCharsets.ISO_8859_1);
        }
        Map<String, Object> entry = Json.map("id", Installer.slug(name), "name", name, "dir", dir.toString(), "task", "", "script", script,
                "kind", info.get("kind"), "mc", info.get("mc"), "autostart", Boolean.TRUE.equals(req.get("autostart")),
                "autoUpdate", "fabric".equals(info.get("kind")) && Boolean.TRUE.equals(req.get("autoUpdate")));
        String java = Json.str(req, "java");
        if (java != null && !java.isBlank() && Files.exists(Path.of(java))) entry.put("java", java);
        Srv s = Main.addServer(entry);
        if (Boolean.TRUE.equals(req.get("enableRcon")) && !s.rconEnabled()) s.enableRcon();
        return s;
    }
}
