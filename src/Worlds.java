import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Singleplayer worlds: finding them on this PC (the standard launcher and the common third-party launchers), reading what they are, and turning one into a
 * server. The world is always copied, never moved. The host's character can be saved under their Minecraft account so they arrive with their things.
 */
final class Worlds {
    private Worlds() { }

    private static final String[] GAMEMODES = {"survival", "creative", "adventure", "spectator"};
    private static final String[] DIFFICULTIES = {"peaceful", "easy", "normal", "hard"};
    private static final Set<String> SKIP_DIRS = Set.of("mods", "libraries", "logs", "assets", "versions", "runtime", "config", "resourcepacks", "shaderpacks", "screenshots",
            "crash-reports", "cache", "backups", "jre", "java", "natives", "defaultconfigs", "$recycle.bin", "system volume information");
    private static final java.util.regex.Pattern WORLD_BAD = java.util.regex.Pattern.compile("[\\\\/:*?\"<>|\\p{Cntrl}]");
    /** The Minecraft data version from which worlds keep player files under players/data instead of playerdata (the first version known to do so). */
    private static final int NEW_LAYOUT_DATA_VERSION = 4903;

    // ---------- reading level.dat ----------

    private static Map<String, Object> readNbt(InputStream in) throws IOException {
        try (in) { return Nbt.readGzip(in); }
    }

    private static String uuidString(Object o) {
        if (o instanceof int[] a && a.length == 4) {
            long msb = ((long) a[0] << 32) | (a[1] & 0xFFFFFFFFL), lsb = ((long) a[2] << 32) | (a[3] & 0xFFFFFFFFL);
            return new UUID(msb, lsb).toString();
        }
        return null;
    }

    private static Long findSeed(Map<String, Object> data) {
        for (String k : new String[]{"WorldGenSettings", "world_gen_settings", "worldGenSettings"}) {
            Map<String, Object> g = Nbt.child(data, k);
            if (g != null && Nbt.lng(g.get("seed")) != null) return Nbt.lng(g.get("seed"));
        }
        return Nbt.lng(data.get("RandomSeed"));
    }

    private static String normName(String n) { return n.replace('\\', '/'); }

    /** What a level.dat says about its world, or null when it is not a usable level.dat. */
    static Map<String, Object> describeLevel(Map<String, Object> root, String folder) {
        Map<String, Object> data = Nbt.child(root, "Data");
        if (data == null) return null;
        Map<String, Object> ver = Nbt.child(data, "Version");
        Map<String, Object> player = Nbt.child(data, "Player");
        Map<String, Object> ds = Nbt.child(data, "difficulty_settings");
        int gt = data.get("GameType") instanceof Number n ? n.intValue() : 0;
        String diff = DIFFICULTIES[1];
        boolean hard = data.get("hardcore") instanceof Number n && n.intValue() != 0;
        if (ds != null && Nbt.str(ds.get("difficulty")) != null) {
            diff = Nbt.str(ds.get("difficulty"));
            hard = ds.get("hardcore") instanceof Number n && n.intValue() != 0;
        } else if (data.get("Difficulty") instanceof Number n) diff = DIFFICULTIES[Math.max(0, Math.min(3, n.intValue()))];
        if (!Arrays.asList(DIFFICULTIES).contains(diff)) diff = "easy";
        Long seed = findSeed(data);
        String name = Nbt.str(data.get("LevelName"));
        return Json.map("folder", folder, "name", name == null || name.isBlank() ? folder : name, "mc", ver == null ? null : Nbt.str(ver.get("Name")),
                "dataVersion", data.get("DataVersion") instanceof Number n ? n.intValue() : null, "lastPlayed", Nbt.lng(data.get("LastPlayed")) == null ? 0 : Nbt.lng(data.get("LastPlayed")),
                "gamemode", GAMEMODES[Math.max(0, Math.min(3, gt))], "difficulty", diff, "hardcore", hard, "seed", seed == null ? "" : Long.toString(seed),
                "hasPlayer", player != null, "playerUuid", player == null ? null : uuidString(player.get("UUID")));
    }

    // ---------- finding worlds ----------

    private static void find(Path dir, int depth, int maxDepth, long deadline, List<Path> out) {
        if (System.currentTimeMillis() > deadline || out.size() >= 80) return;
        if (Files.isRegularFile(dir.resolve("level.dat"))) { out.add(dir); return; }
        if (depth >= maxDepth) return;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            List<Path> subs = new ArrayList<>();
            for (Path p : ds) {
                try {
                    BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (!a.isDirectory() || a.isSymbolicLink()) continue;
                } catch (IOException e) { continue; }
                String n = p.getFileName().toString().toLowerCase();
                if (SKIP_DIRS.contains(n) || n.startsWith("$")) continue;
                subs.add(p);
            }
            subs.sort(Comparator.comparing(p -> p.getFileName().toString().toLowerCase()));
            for (Path s : subs) find(s, depth + 1, maxDepth, deadline, out);
        } catch (IOException | SecurityException | UncheckedIOException ignored) { }
    }

    private static List<Path> launcherRoots() {
        List<Path> r = new ArrayList<>();
        String app = System.getenv("APPDATA"), local = System.getenv("LOCALAPPDATA");
        Path home = Path.of(System.getProperty("user.home"));
        if (app != null) for (String s : new String[]{".minecraft", "PrismLauncher/instances", "PolyMC/instances", "MultiMC/instances", "ModrinthApp/profiles", "ATLauncher/instances", "gdlauncher_carbon/data/instances", "gdlauncher_next/instances", ".technic/modpacks", "ftb", "ftb-app/instances"})
            r.add(Path.of(app, s));
        if (local != null) for (String s : new String[]{"ftb-app/instances", "Programs/PrismLauncher/instances", "PrismLauncher/instances"}) r.add(Path.of(local, s));
        for (String s : new String[]{"curseforge/minecraft/Instances", "Documents/curseforge/minecraft/Instances", "Documents/Curse/Minecraft/Instances", ".minecraft", "AppData/Roaming/.minecraft"}) r.add(home.resolve(s));
        List<Path> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Path p : r) if (Files.isDirectory(p) && seen.add(p.toAbsolutePath().normalize().toString().toLowerCase())) out.add(p);
        return out;
    }

    private static String sourceOf(Path world) {
        String p = world.toString().toLowerCase().replace('\\', '/');
        if (p.contains("/curseforge/") || p.contains("/curse/")) return "CurseForge app";
        if (p.contains("/prismlauncher/") || p.contains("/polymc/")) return "Prism Launcher";
        if (p.contains("/multimc/")) return "MultiMC";
        if (p.contains("/modrinthapp/") || p.contains("/modrinth")) return "Modrinth App";
        if (p.contains("/atlauncher/")) return "ATLauncher";
        if (p.contains("/ftb")) return "FTB App";
        if (p.contains("/gdlauncher")) return "GDLauncher";
        if (p.contains("/.technic/")) return "Technic";
        if (p.contains("/.minecraft/")) return "Minecraft Launcher";
        return "";
    }

    /** The game folder of the instance a world lives in (the folder that holds saves and mods), or null. */
    private static Path gameDir(Path world) {
        Path saves = world.getParent();
        return saves != null && saves.getFileName() != null && saves.getFileName().toString().equalsIgnoreCase("saves") ? saves.getParent() : null;
    }

    private static String instanceName(Path game) {
        if (game == null) return "";
        String n = game.getFileName() == null ? "" : game.getFileName().toString();
        if ((n.equals(".minecraft") || n.equalsIgnoreCase("minecraft")) && game.getParent() != null && game.getParent().getFileName() != null) {
            String pn = game.getParent().getFileName().toString();
            return pn.equalsIgnoreCase("Roaming") || pn.equalsIgnoreCase("AppData") || pn.equals(System.getProperty("user.name")) ? "" : pn;
        }
        return n;
    }

    private static String loaderOfJar(Path jar) {
        try (ZipFile z = new ZipFile(jar.toFile())) {
            if (z.getEntry("fabric.mod.json") != null) return "fabric";
            if (z.getEntry("META-INF/neoforge.mods.toml") != null) return "neoforge";
            if (z.getEntry("META-INF/mods.toml") != null) return "forge";
            if (z.getEntry("quilt.mod.json") != null) return "quilt";
        } catch (IOException | RuntimeException ignored) { }
        return null;
    }

    static List<Path> jars(Path modsDir) {
        List<Path> out = new ArrayList<>();
        if (modsDir == null || !Files.isDirectory(modsDir)) return out;
        try (Stream<Path> s = Files.list(modsDir)) {
            for (Path p : s.sorted().toList()) if (Files.isRegularFile(p) && p.getFileName().toString().toLowerCase().endsWith(".jar")) out.add(p);
        } catch (IOException | UncheckedIOException ignored) { }
        return out;
    }

    /** How many mods the instance has and which loader they are for (a quick look at the jar files, no network). */
    static Map<String, Object> modSummary(Path game) {
        List<Path> jars = jars(game == null ? null : game.resolve("mods"));
        Map<String, Integer> votes = new HashMap<>();
        int looked = 0;
        for (Path j : jars) {
            if (++looked > 300) break;
            String l = loaderOfJar(j);
            if (l != null) votes.merge(l, 1, Integer::sum);
        }
        String loader = votes.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
        return Json.map("count", jars.size(), "loader", loader);
    }

    private static long dirSize(Path dir, long deadline) {
        long[] t = {0};
        try (Stream<Path> w = Files.walk(dir)) {
            for (Iterator<Path> it = w.iterator(); it.hasNext(); ) {
                if (System.currentTimeMillis() > deadline) return -1;
                Path p = it.next();
                try { BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); if (a.isRegularFile()) t[0] += a.size(); } catch (IOException ignored) { }
            }
        } catch (IOException | UncheckedIOException ignored) { }
        return t[0];
    }

    // ---------- a world source (folder or zip) ----------

    static final class Source {
        final String kind;
        final Path path;
        final String root;

        Source(String kind, Path path, String root) { this.kind = kind; this.path = path; this.root = root; }

        private String prefix() { return root.isEmpty() ? "" : root + "/"; }

        /** Entries of the zip (names normalised to forward slashes, because zips made by some Windows tools use backslashes). */
        static Map<String, ZipEntry> index(ZipFile z) {
            Map<String, ZipEntry> m = new LinkedHashMap<>();
            for (Enumeration<? extends ZipEntry> en = z.entries(); en.hasMoreElements(); ) {
                ZipEntry e = en.nextElement();
                String n = normName(e.getName());
                if (!n.endsWith("/")) m.put(n, e);
            }
            return m;
        }

        String folderName() { return kind.equals("dir") ? path.getFileName().toString() : root.isEmpty() ? stem(path) : root.substring(root.lastIndexOf('/') + 1); }

        Map<String, Object> levelRoot() throws IOException {
            if (kind.equals("dir")) return readNbt(Files.newInputStream(path.resolve("level.dat")));
            try (ZipFile z = new ZipFile(path.toFile())) {
                ZipEntry e = index(z).get(prefix() + "level.dat");
                if (e == null) throw new Api(404, "level.dat is missing from the zip");
                return readNbt(z.getInputStream(e));
            }
        }

        /** The seed from data/minecraft/world_gen_settings.dat (newer versions keep it there, not in level.dat), and how many player files the world holds. */
        private void side(Map<String, Object> m) {
            Long seed = null;
            int players = 0;
            try {
                if (kind.equals("dir")) {
                    Path wgs = path.resolve("data").resolve("minecraft").resolve("world_gen_settings.dat");
                    if (Files.isRegularFile(wgs)) seed = Nbt.lng(Nbt.child(readNbt(Files.newInputStream(wgs)), "data").get("seed"));
                    for (String d : new String[]{"players/data", "playerdata"}) {
                        Path pd = path.resolve(d);
                        if (Files.isDirectory(pd)) try (Stream<Path> s = Files.list(pd)) { players += (int) s.filter(p -> p.getFileName().toString().endsWith(".dat")).count(); }
                    }
                } else {
                    try (ZipFile z = new ZipFile(path.toFile())) {
                        Map<String, ZipEntry> idx = index(z);
                        ZipEntry w = idx.get(prefix() + "data/minecraft/world_gen_settings.dat");
                        if (w != null) seed = Nbt.lng(Nbt.child(readNbt(z.getInputStream(w)), "data").get("seed"));
                        for (String n : idx.keySet()) if (n.startsWith(prefix() + "players/data/") && n.endsWith(".dat") || n.startsWith(prefix() + "playerdata/") && n.endsWith(".dat")) players++;
                    }
                }
            } catch (IOException | RuntimeException ignored) { }
            if (seed != null && "".equals(m.get("seed"))) m.put("seed", Long.toString(seed));
            m.put("playerFiles", players);
        }

        Map<String, Object> describe(long sizeDeadline) throws IOException {
            Map<String, Object> m = describeLevel(levelRoot(), folderName());
            if (m == null) return null;
            side(m);
            if (Json.num(m, "playerFiles", 0) > 0) m.put("hasPlayer", true);
            m.put("kind", kind);
            m.put("path", path.toString());
            m.put("root", root);
            if (kind.equals("dir")) {
                Path game = gameDir(path);
                Map<String, Object> mods = modSummary(game);
                long sz = sizeDeadline > 0 && System.currentTimeMillis() < sizeDeadline ? dirSize(path, Math.min(sizeDeadline, System.currentTimeMillis() + 2500)) : -1;
                m.put("source", sourceOf(path));
                m.put("instance", instanceName(game));
                m.put("modCount", mods.get("count"));
                m.put("loader", mods.get("loader"));
                m.put("sizeMb", sz < 0 ? null : (Object) (sz >> 20));
            } else {
                m.put("source", "Zip file");
                m.put("instance", "");
                m.put("modCount", 0);
                m.put("loader", null);
                m.put("sizeMb", null);
            }
            return m;
        }
    }

    static Source source(Map<String, Object> w) {
        String kind = Json.str(w, "kind"), p = Json.str(w, "path"), root = Json.str(w, "root");
        if (!"dir".equals(kind) && !"zip".equals(kind)) throw new Api(400, "Unknown world source");
        if (p == null || !Path.of(p).isAbsolute()) throw new Api(400, "Bad world path");
        Path path = Path.of(p).normalize();
        root = root == null ? "" : root;
        if (root.startsWith("/") || root.contains("\\") || root.contains("..") || root.contains(":")) throw new Api(400, "Bad world path inside the zip");
        if (kind.equals("dir")) {
            if (!Files.isRegularFile(path.resolve("level.dat"))) throw new Api(404, "That folder is not a Minecraft world (no level.dat).");
        } else if (!Files.isRegularFile(path)) throw new Api(404, "That zip file no longer exists.");
        return new Source(kind, path, root);
    }

    private static String stem(Path zip) {
        String n = zip.getFileName().toString();
        return n.toLowerCase().endsWith(".zip") ? n.substring(0, n.length() - 4) : n;
    }

    /** All worlds in the standard places on this PC, newest first. */
    static List<Map<String, Object>> scan() {
        long deadline = System.currentTimeMillis() + 9000;
        List<Path> found = new ArrayList<>();
        for (Path root : launcherRoots()) {
            if (!Detector.probe(root, 2000)) continue;
            find(root, 0, 6, deadline, found);
        }
        Set<String> seen = new HashSet<>();
        List<Map<String, Object>> out = new ArrayList<>();
        long sizeBudget = System.currentTimeMillis() + 6000;
        for (Path w : found) {
            if (!seen.add(w.toAbsolutePath().normalize().toString().toLowerCase())) continue;
            try {
                Map<String, Object> d = new Source("dir", w, "").describe(sizeBudget);
                if (d != null) out.add(d);
            } catch (IOException | RuntimeException ignored) { }
        }
        out.sort((a, b) -> Long.compare(((Number) b.get("lastPlayed")).longValue(), ((Number) a.get("lastPlayed")).longValue()));
        return out;
    }

    /** Worlds inside a path the user picked: a world folder, a launcher or instance folder, or a zip. */
    static List<Map<String, Object>> inspect(String path) throws IOException {
        if (path == null || path.isBlank()) throw new Api(400, "Choose a folder or a zip file.");
        Path p = Path.of(path.trim());
        if (!p.isAbsolute()) throw new Api(400, "Use a full path such as D:\\Minecraft\\saves\\My World");
        p = p.normalize();
        List<Map<String, Object>> out = new ArrayList<>();
        if (Files.isRegularFile(p)) {
            if (!Archives.isArchive(p)) throw new Api(400, "Only folders and archives (" + Archives.listing() + ") can be imported.");
            if (!Archives.isZip(p)) {
                Path dir = Archives.extract(p);
                try {
                    List<Map<String, Object>> found = inspect(dir.toString());
                    for (Map<String, Object> m : found) {
                        m.put("source", "Archive " + p.getFileName());
                        m.put("archive", p.toString());
                    }
                    return found;
                } catch (Api a) {
                    Archives.remove(dir);
                    throw new Api(a.code, a.code == 404 ? "No Minecraft world (a folder with level.dat) was found inside that archive." : a.getMessage());
                }
            }
            List<String> roots = new ArrayList<>();
            try (ZipFile z = new ZipFile(p.toFile())) {
                for (String n : Source.index(z).keySet()) {
                    if (!(n.equals("level.dat") || n.endsWith("/level.dat")) || n.split("/").length > 4) continue;
                    roots.add(n.equals("level.dat") ? "" : n.substring(0, n.length() - "/level.dat".length()));
                    if (roots.size() >= 40) break;
                }
            } catch (java.util.zip.ZipException e) {
                throw new Api(400, "That file is not a readable zip.");
            }
            for (String r : roots) {
                try {
                    Map<String, Object> m = new Source("zip", p, r).describe(0);
                    if (m != null) out.add(m);
                } catch (IOException | RuntimeException ignored) { }
            }
            if (out.isEmpty()) throw new Api(404, "No Minecraft world (a folder with level.dat) was found inside that zip.");
            return out;
        }
        if (!Files.isDirectory(p)) throw new Api(404, "That path does not exist.");
        List<Path> found = new ArrayList<>();
        find(p, 0, 5, System.currentTimeMillis() + 8000, found);
        long sizeBudget = System.currentTimeMillis() + 6000;
        for (Path w : found) {
            try {
                Map<String, Object> d = new Source("dir", w, "").describe(sizeBudget);
                if (d != null) out.add(d);
            } catch (IOException | RuntimeException ignored) { }
        }
        if (out.isEmpty()) throw new Api(404, "No Minecraft world (a folder with level.dat) was found there. Pick the world's own folder, or a launcher or instance folder that contains a saves folder.");
        out.sort((a, b) -> Long.compare(((Number) b.get("lastPlayed")).longValue(), ((Number) a.get("lastPlayed")).longValue()));
        return out;
    }

    // ---------- details (network) ----------

    /** The instance's mods, looked up on Modrinth: what each is, and whether it runs on the client, the server or both. */
    static Map<String, Object> classifyMods(Path modsDir) throws IOException {
        List<Path> jars = jars(modsDir);
        Map<String, Path> byHash = new LinkedHashMap<>();
        for (Path j : jars) byHash.put(Modrinth.sha1(j), j);
        Map<String, Map<String, Object>> versions = Modrinth.versionsByHash(byHash.keySet());
        Set<String> pids = new LinkedHashSet<>();
        for (Map<String, Object> v : versions.values()) if (Json.str(v, "project_id") != null) pids.add(Json.str(v, "project_id"));
        Map<String, Map<String, Object>> projects = Modrinth.projectsById(pids);
        List<Object> items = new ArrayList<>(), unknown = new ArrayList<>();
        Set<String> seenProjects = new HashSet<>();
        for (Map.Entry<String, Path> e : byHash.entrySet()) {
            Map<String, Object> v = versions.get(e.getKey());
            String pid = v == null ? null : Json.str(v, "project_id");
            Map<String, Object> proj = pid == null ? null : projects.get(pid);
            if (proj == null) { unknown.add(e.getValue().getFileName().toString()); continue; }
            if (!seenProjects.add(pid)) continue;
            items.add(Json.map("file", e.getValue().getFileName().toString(), "projectId", pid, "title", Json.str(proj, "title"), "slug", Json.str(proj, "slug"), "icon", Json.str(proj, "icon_url"),
                    "client", Json.str(proj, "client_side"), "server", Json.str(proj, "server_side"), "version", Json.str(v, "version_number"), "sha1", e.getKey()));
        }
        return Json.map("items", items, "unknown", unknown);
    }

    /** Everything the wizard needs to prefill itself for one world. */
    static Map<String, Object> details(Map<String, Object> req) throws IOException {
        Source src = source(req);
        Map<String, Object> m = src.describe(System.currentTimeMillis() + 3000);
        if (m == null) throw new Api(404, "That world could not be read.");
        Map<String, Object> out = new LinkedHashMap<>(m);
        out.put("journeymap", JourneyMapImport.find(src));
        if (src.kind.equals("dir")) {
            Path game = gameDir(src.path);
            out.put("inUse", inUse(src.path));
            if (game != null && Json.num(m, "modCount", 0) > 0) {
                try { out.put("mods", classifyMods(game.resolve("mods"))); }
                catch (Api | IOException e) { out.put("modsError", e.getMessage()); }
            }
        }
        return out;
    }

    /** True when Minecraft appears to have the world open (its session.lock cannot be written). */
    private static boolean inUse(Path world) {
        Path lock = world.resolve("session.lock");
        if (!Files.isRegularFile(lock)) return false;
        try (FileChannel ch = FileChannel.open(lock, StandardOpenOption.WRITE)) {
            return ch.tryLock() == null;
        } catch (IOException | java.nio.channels.OverlappingFileLockException e) {
            return true;
        }
    }

    // ---------- validating a create-from-world request ----------

    static void validate(Map<String, Object> req) throws IOException {
        Installer.validate(req);
        Map<String, Object> w = Json.obj(req.get("world"));
        Source src = source(w);
        Map<String, Object> info = src.describe(0);
        if (info == null) throw new Api(400, "That world could not be read.");
        String wmc = Json.str(info, "mc"), smc = String.valueOf(req.get("mc"));
        if (wmc != null && wmc.matches("\\d+(\\.\\d+)*") && smc.matches("\\d+(\\.\\d+)*") && Installer.cmp(smc, wmc) < 0)
            throw new Api(400, "This world was last saved in Minecraft " + wmc + ". A server running the older " + smc + " cannot open it; choose " + wmc + " or newer.");
        if (src.kind.equals("dir") && inUse(src.path) && !Boolean.TRUE.equals(w.get("ignoreInUse")))
            throw new Api(409, "Minecraft seems to have this world open. Close the game first so the copy is complete (or tick the box to copy it anyway).");
        if (Boolean.TRUE.equals(w.get("bringCharacter"))) {
            String name = Json.str(w, "playerName");
            if (!Srv.validPlayerName(name)) throw new Api(400, "Enter your Minecraft username (3-16 letters, digits or _) to bring your character over.");
            if (!Boolean.TRUE.equals(info.get("hasPlayer"))) throw new Api(400, "This world has no saved singleplayer character to bring over.");
            if (OpsSync.lookupMojang(name) == null) throw new Api(400, "Mojang does not know the Minecraft name \"" + name + "\" (or this PC cannot reach Mojang). Check the spelling and the internet connection.");
        }
        if (w.get("journeymap") instanceof Map<?, ?> jm && jm.get("id") != null) {
            if ("vanilla".equals(String.valueOf(req.get("type")))) throw new Api(400, "JourneyMap waypoints need a Fabric, Forge or NeoForge server (JourneyMap runs as a mod). Choose one of those, or untick the waypoint import.");
            JourneyMapImport.check(src, String.valueOf(jm.get("id")));
        }
        for (Object id : Json.arr(w.get("mods"))) if (!Modrinth.SAFE_ID.matcher(String.valueOf(id)).matches()) throw new Api(400, "Bad mod id");
        if (Json.arr(w.get("mods")).size() > 200) throw new Api(400, "Too many mods selected");
    }

    // ---------- the import itself ----------

    private static void deleteTree(Path dir) {
        try (Stream<Path> w = Files.walk(dir)) {
            w.sorted(Comparator.reverseOrder()).forEach(f -> { try { Files.deleteIfExists(f); } catch (IOException ignored) { } });
        } catch (IOException | UncheckedIOException ignored) { }
    }

    private static void copyFromDir(Job j, Path from, Path to) throws IOException {
        List<Path> files;
        try (Stream<Path> w = Files.walk(from)) { files = w.toList(); }
        long total = 0;
        for (Path p : files) if (Files.isRegularFile(p)) total += Files.size(p);
        long done = 0;
        int last = 0;
        j.log("Copying the world (" + (total >> 20) + " MB) ...");
        for (Path p : files) {
            j.checkCancel();
            Path t = to.resolve(from.relativize(p).toString());
            if (Files.isDirectory(p)) { Files.createDirectories(t); continue; }
            if (!Files.isRegularFile(p) || p.getFileName().toString().equals("session.lock")) continue;
            Files.createDirectories(t.getParent());
            try { Files.copy(p, t, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES); }
            catch (IOException e) { throw new Api(500, "Could not copy " + from.relativize(p) + ": " + e.getMessage()); }
            done += Files.size(p);
            int pct = total == 0 ? 100 : (int) (done * 100 / total);
            if (pct >= last + 25 && pct < 100) { last = pct / 25 * 25; j.log("  " + last + "% ..."); }
        }
    }

    private static void copyFromZip(Job j, Path zip, String root, Path to) throws IOException {
        Path base = to.toAbsolutePath().normalize();
        String prefix = root.isEmpty() ? "" : root + "/";
        j.log("Extracting the world from the zip ...");
        int n = 0;
        try (ZipFile z = new ZipFile(zip.toFile())) {
            for (Map.Entry<String, ZipEntry> en : Source.index(z).entrySet()) {
                String name = en.getKey();
                if (!name.startsWith(prefix)) continue;
                String rel = name.substring(prefix.length());
                if (rel.isEmpty() || rel.startsWith("/") || rel.contains(":") || Arrays.asList(rel.split("/")).contains("..")) throw new Api(400, "The zip contains an unsafe path: " + name);
                if (rel.equals("session.lock")) continue;
                Path t = base.resolve(rel).normalize();
                if (!t.startsWith(base)) throw new Api(400, "The zip contains an unsafe path: " + name);
                j.checkCancel();
                Files.createDirectories(t.getParent());
                try (InputStream in = z.getInputStream(en.getValue())) { Files.copy(in, t, StandardCopyOption.REPLACE_EXISTING); }
                if (en.getValue().getTime() > 0) { try { Files.setLastModifiedTime(t, FileTime.fromMillis(en.getValue().getTime())); } catch (IOException ignored) { } }
                n++;
            }
        }
        j.log("Extracted " + n + " files.");
    }

    private static int[] uuidInts(UUID u) {
        long m = u.getMostSignificantBits(), l = u.getLeastSignificantBits();
        return new int[]{(int) (m >>> 32), (int) m, (int) (l >>> 32), (int) l};
    }

    /** Newer worlds keep player files in players/data, older ones in playerdata. */
    static Path playerDataDir(Path world, Integer dataVersion) {
        if (Files.isDirectory(world.resolve("players"))) return world.resolve("players").resolve("data");
        if (Files.isDirectory(world.resolve("playerdata")) || Files.isDirectory(world.resolve("advancements")) || Files.isDirectory(world.resolve("stats"))) return world.resolve("playerdata");
        return dataVersion != null && dataVersion >= NEW_LAYOUT_DATA_VERSION ? world.resolve("players").resolve("data") : world.resolve("playerdata");
    }

    private static void fixUuid(Map<String, Object> player, UUID uuid) {
        if (player.get("UUID") instanceof int[] || !player.containsKey("UUID")) player.put("UUID", uuidInts(uuid));
        if (player.containsKey("UUIDMost")) { player.put("UUIDMost", uuid.getMostSignificantBits()); player.put("UUIDLeast", uuid.getLeastSignificantBits()); }
    }

    /** Makes sure the entered Minecraft account has a player file (inventory, position, experience), and optionally makes that player an operator. */
    private static void bringCharacter(Job j, Srv s, Path world, Map<String, Object> level, String name, boolean makeOp) throws IOException {
        Map<String, Object> data = Nbt.child(level, "Data");
        Integer dv = data != null && data.get("DataVersion") instanceof Integer i ? i : null;
        String id = OpsSync.uuidFor(s, name);
        if (id == null) throw new Api(502, "Could not look up the Minecraft account \"" + name + "\" at Mojang.");
        UUID uuid = UUID.fromString(id);
        Path pd = playerDataDir(world, dv);
        Files.createDirectories(pd);
        Path target = pd.resolve(uuid + ".dat");
        Map<String, Object> player = Nbt.child(data, "Player");
        if (player != null) {
            String before = uuidString(player.get("UUID"));
            fixUuid(player, uuid);
            if (!player.containsKey("DataVersion") && dv != null) player.put("DataVersion", dv);
            if (Files.exists(target)) Files.move(target, pd.resolve(uuid + ".dat.mcm-old"), StandardCopyOption.REPLACE_EXISTING);
            Files.write(target, Nbt.writeGzip(player));
            j.log("Your character (inventory, position, experience) is saved for " + name + " (" + uuid + ").");
            if (before != null && !before.equals(uuid.toString())) j.log("Note: the singleplayer character belonged to a different account id (" + before + "). Pets you tamed may not recognise you as their owner.");
        } else if (Files.exists(target)) {
            j.log("Your character is already stored under " + name + "'s account in this world, so nothing needed to be moved.");
        } else {
            List<Path> files;
            try (Stream<Path> st = Files.list(pd)) { files = st.filter(p -> p.getFileName().toString().endsWith(".dat")).toList(); }
            if (files.size() == 1) {
                Map<String, Object> pl = Nbt.readGzip(Files.newInputStream(files.get(0)));
                String before = uuidString(pl.get("UUID")) == null ? files.get(0).getFileName().toString().replace(".dat", "") : uuidString(pl.get("UUID"));
                fixUuid(pl, uuid);
                Files.write(target, Nbt.writeGzip(pl));
                j.log("Your character was stored under another account id (" + before + "); it is now also saved for " + name + " (" + uuid + ").");
            } else {
                j.log("The world holds " + files.size() + " player files and none is for " + name + ", so the manager cannot tell which is yours. Players start fresh at spawn.");
            }
        }
        for (String folder : new String[]{"players/advancements", "advancements", "players/stats", "stats"}) {
            Path d = world.resolve(folder);
            if (!Files.isDirectory(d)) continue;
            Path mine = d.resolve(uuid + ".json");
            if (Files.exists(mine)) continue;
            try (Stream<Path> st = Files.list(d)) {
                List<Path> jsons = st.filter(p -> p.getFileName().toString().endsWith(".json")).toList();
                if (jsons.size() == 1) { Files.copy(jsons.get(0), mine); j.log("Carried your " + folder.substring(folder.lastIndexOf('/') + 1) + " over."); }
            }
        }
        if (makeOp) {
            Path f = s.dir.resolve("ops.json");
            List<Object> entries = Files.exists(f) ? new ArrayList<>(Json.arr(Json.parse(Files.readString(f, StandardCharsets.UTF_8)))) : new ArrayList<>();
            boolean has = false;
            for (Object o : entries) if (id.equalsIgnoreCase(Json.str(o, "uuid"))) has = true;
            if (!has) entries.add(Json.map("uuid", id, "name", name, "level", 4, "bypassesPlayerLimit", false));
            Files.writeString(f, Json.stringify(entries), StandardCharsets.UTF_8);
            j.log(name + " is an operator on the new server.");
        }
    }

    static String run(Job j, Map<String, Object> req0, String actor) throws Exception {
        Map<String, Object> req = new LinkedHashMap<>(req0);
        Map<String, Object> w = Json.obj(req.get("world"));
        Source src = source(w);
        boolean startAfter = Boolean.TRUE.equals(req.get("startAfter"));
        req.put("startAfter", false);
        Map<String, Object> info = src.describe(0);
        String levelName = Json.str(req, "worldName");
        String id = Installer.install(j, req);
        Srv s = Main.find(id);
        try {
            Path dest = s.dir.resolve(levelName).normalize();
            if (!dest.getParent().equals(s.dir.normalize())) throw new Api(400, "Bad world folder name");
            if (Files.exists(dest)) deleteTree(dest);
            Files.createDirectories(dest);
            if (src.kind.equals("dir")) copyFromDir(j, src.path, dest); else copyFromZip(j, src.path, src.root, dest);
            if (!Files.isRegularFile(dest.resolve("level.dat"))) throw new Api(500, "The copied world has no level.dat");
            j.log("World copied into " + dest + ". The original was not changed.");
            Map<String, String> props = new LinkedHashMap<>();
            if (info != null && Boolean.TRUE.equals(info.get("hardcore"))) props.put("hardcore", "true");
            if (!props.isEmpty()) s.writeProps(props);
            if (Boolean.TRUE.equals(w.get("bringCharacter"))) {
                try { bringCharacter(j, s, dest, Nbt.readGzip(Files.newInputStream(dest.resolve("level.dat"))), Json.str(w, "playerName"), !Boolean.FALSE.equals(w.get("makeOp"))); }
                catch (Api | IOException e) { j.log("Could not bring your character over: " + e.getMessage() + " (the world itself was imported)."); }
            }
            List<String> wanted = new ArrayList<>();
            for (Object o : Json.arr(w.get("mods"))) wanted.add(String.valueOf(o));
            if (src.kind.equals("dir") && gameDir(src.path) != null && Files.isDirectory(gameDir(src.path).resolve("mods"))) {
                if (Boolean.TRUE.equals(w.get("keepClientMods"))) {
                    try {
                        List<Object> extras = new ArrayList<>();
                        for (Object it : Json.arr(classifyMods(gameDir(src.path).resolve("mods")).get("items"))) {
                            String pid = Json.str(it, "projectId"), client = Json.str(it, "client");
                            if (!wanted.contains(pid) && !"unsupported".equals(client)) extras.add(Json.map("projectId", pid, "title", Json.str(it, "title"), "slug", Json.str(it, "slug"), "sha1", Json.str(it, "sha1"), "file", Json.str(it, "file")));
                        }
                        if (!extras.isEmpty()) {
                            s.cfg.put("clientExtras", extras);
                            Main.cfg.save();
                            j.log("Remembered " + extras.size() + " mod(s) from your game that only players need (for the client list and modpack).");
                        }
                    } catch (Api | IOException e) { j.log("Could not look up the client-side mods: " + e.getMessage()); }
                }
            }
            if (!wanted.isEmpty()) {
                j.log("Installing " + wanted.size() + " of your mods on the server (and the helper mods they need) ...");
                try { Modrinth.install(s, wanted, j); }
                catch (Exception e) { j.log("Could not install the mods: " + e.getMessage() + " (the server and world were created; add mods later from the Mods tab)."); }
            }
            if (w.get("journeymap") instanceof Map<?, ?> jm && jm.get("id") != null) importWaypoints(j, s, dest, src, String.valueOf(jm.get("id")));
            Users.audit(actor, "", "server created from a world", s.id, src.path.toString());
            j.log("Done. " + s.name + " is ready" + (startAfter ? "." : ". Start it when you are ready."));
            if (startAfter) s.start(j);
        } catch (Throwable t) {
            j.log("The import failed; removing the new server again.");
            try { Main.removeServer(s); } catch (Exception ignored) { }
            deleteTree(s.dir);
            throw t;
        } finally {
            if (Archives.isExtracted(src.path)) {
                Path unpacked = Archives.extractionOf(src.path);
                if (unpacked != null) Archives.remove(unpacked);
            }
        }
        return id;
    }

    /** Makes the JourneyMap waypoints that came with the world global waypoints of the new server, and makes sure the server has JourneyMap. */
    private static void importWaypoints(Job j, Srv s, Path world, Source src, String id) {
        try {
            int[] skipped = {0};
            List<JourneyMapImport.Wp> wps = JourneyMapImport.read(src, id, skipped);
            if (wps.isEmpty()) { j.log("No usable JourneyMap waypoints were found, so none were imported."); return; }
            Map<String, Object> level = Nbt.readGzip(Files.newInputStream(world.resolve("level.dat")));
            Map<String, Object> data = Nbt.child(level, "Data");
            int dv = data != null && data.get("DataVersion") instanceof Integer i ? i : 0;
            int added = JourneyMapImport.write(world, wps, dv);
            j.log("Imported " + added + " JourneyMap waypoint" + (added == 1 ? "" : "s") + " as global waypoints" + (skipped[0] > 0 ? " (" + skipped[0] + " file(s) skipped: death markers or unreadable)" : "") + ". Every player who has JourneyMap sees them.");
            boolean has = false;
            for (Path jar : jars(s.dir.resolve("mods"))) if (jar.getFileName().toString().toLowerCase().contains("journeymap")) has = true;
            if (!has) {
                j.log("Installing JourneyMap on the server (it holds the global waypoints) ...");
                try { Modrinth.install(s, List.of("journeymap"), j); }
                catch (Exception e) { j.log("Could not install JourneyMap: " + e.getMessage() + " (the waypoints are saved in the world; add JourneyMap from the Mods tab and they appear)."); }
            }
            j.log("Players need the JourneyMap mod on their own game to see them.");
        } catch (Exception e) {
            j.log("Could not import the JourneyMap waypoints: " + e.getMessage() + " (the world itself was imported).");
        }
    }

    static String safeFolderName(String raw) {
        String n = raw == null ? "" : WORLD_BAD.matcher(raw.strip()).replaceAll("_").strip();
        if (n.length() > 64) n = n.substring(0, 64).strip();
        if (n.isEmpty() || n.equals(".") || n.contains("..") || n.endsWith(".") || n.matches("(?i)^(con|prn|aux|nul|com[1-9]|lpt[1-9])(\\..*)?$")) return "world";
        return n;
    }
}
