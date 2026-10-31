import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * JourneyMap waypoints that come with a singleplayer world (the .json files in journeymap/data/sp/WORLD/waypoints, which only live on the player's PC)
 * become JourneyMap "global" waypoints on the new server, so every player who has JourneyMap sees them. JourneyMap keeps global waypoints in the world's
 * data/journeymap/global_waypoints.dat; the layout written here is the one JourneyMap 6 itself writes, and the server only reads it when it starts.
 */
final class JourneyMapImport {
    private JourneyMapImport() { }

    static final String GROUP = "journeymap_global_default";
    private static final String ICON = "journeymap:textures/waypoint/icon/waypoint-icon.png";
    private static final int MAX_FILES = 5000;
    private static final long MAX_JSON = 64 * 1024;
    private static final int FALLBACK_DATA_VERSION = 4903;
    private static final Pattern ZIP_WAYPOINT = Pattern.compile("(?i)^((?:.*/)?journeymap/data/sp/([^/]+)/waypoints/)([^/]+\\.json)$");

    record Wp(String name, int x, int y, int z, int r, int g, int b, List<String> dims, boolean enabled) { }

    private static String norm(String s) { return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_"); }

    private static Map<String, Object> set(String id, String label, int count) { return Json.map("id", id, "label", label, "count", count); }

    private static int countJson(Path dir) {
        try (Stream<Path> s = Files.list(dir)) { return (int) s.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".json") && Files.isRegularFile(p)).count(); }
        catch (IOException | UncheckedIOException e) { return 0; }
    }

    // ---------- finding waypoint files ----------

    /** The waypoint sets that belong to a world: JourneyMap folders next to a world folder, or inside a zip. Each is {id, label, count}. */
    static List<Map<String, Object>> find(Worlds.Source src) {
        List<Map<String, Object>> out = new ArrayList<>();
        String want = norm(src.folderName());
        try {
            if (src.kind.equals("dir")) {
                Path anc = src.path.getParent();
                for (int up = 0; up < 3 && anc != null; up++, anc = anc.getParent()) {
                    Path sp = anc.resolve("journeymap").resolve("data").resolve("sp");
                    if (!Files.isDirectory(sp)) continue;
                    List<Path> sets = new ArrayList<>();
                    try (Stream<Path> s = Files.list(sp)) { for (Path d : s.sorted().toList()) if (Files.isDirectory(d.resolve("waypoints")) && countJson(d.resolve("waypoints")) > 0) sets.add(d); }
                    Path pick = null;
                    for (Path d : sets) if (norm(d.getFileName().toString()).equals(want)) pick = d;
                    if (pick == null && sets.size() == 1) pick = sets.get(0);
                    if (pick != null) {
                        out.add(set("dir:" + pick.resolve("waypoints"), "JourneyMap waypoints saved with this world (" + pick.getFileName() + ")", usable(pick.resolve("waypoints"))));
                        break;
                    }
                }
            } else {
                try (ZipFile z = new ZipFile(src.path.toFile())) {
                    Map<String, int[]> counts = new LinkedHashMap<>();
                    Map<String, String> names = new LinkedHashMap<>();
                    for (Map.Entry<String, ZipEntry> en : Worlds.Source.index(z).entrySet()) {
                        Matcher m = ZIP_WAYPOINT.matcher(en.getKey());
                        if (!m.matches()) continue;
                        names.put(m.group(1), m.group(2));
                        counts.computeIfAbsent(m.group(1), k -> new int[1]);
                        ZipEntry ze = en.getValue();
                        try (InputStream in = ze == null || ze.getSize() > MAX_JSON ? null : z.getInputStream(ze)) {
                            if (in != null && parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)) != null) counts.get(m.group(1))[0]++;
                        } catch (IOException | RuntimeException ignored) { }
                    }
                    String match = null;
                    for (String p : counts.keySet()) if (norm(names.get(p)).equals(want)) match = p;
                    for (String p : counts.keySet()) {
                        if (match != null && !p.equals(match)) continue;
                        out.add(set("zip:" + p, "JourneyMap waypoints in the zip (" + names.get(p) + ")", counts.get(p)[0]));
                    }
                }
            }
        } catch (IOException | RuntimeException ignored) { }
        return out;
    }

    /** Waypoint sets inside a folder the person picked: the waypoints folder itself, or a folder that holds one or more (a JourneyMap or .minecraft folder). */
    static List<Map<String, Object>> inspect(String path) {
        if (path == null || path.isBlank() || !Path.of(path).isAbsolute()) throw new Api(400, "Pick a folder.");
        Path root = Path.of(path).normalize();
        if (!Files.isDirectory(root)) throw new Api(404, "That folder does not exist.");
        List<Map<String, Object>> out = new ArrayList<>();
        int here = usable(root);
        if (here > 0) { out.add(set("dir:" + root, "Waypoint files in " + root.getFileName(), here)); return out; }
        try (Stream<Path> w = Files.walk(root, 7)) {
            for (Iterator<Path> it = w.iterator(); it.hasNext() && out.size() < 20; ) {
                Path p = it.next();
                if (!Files.isDirectory(p) || !p.getFileName().toString().equalsIgnoreCase("waypoints")) continue;
                int n = usable(p);
                if (n > 0) out.add(set("dir:" + p, "Waypoints of " + (p.getParent() == null ? "?" : p.getParent().getFileName()), n));
            }
        } catch (IOException | UncheckedIOException ignored) { }
        return out;
    }

    /** Checks that a request's chosen set exists (one of the world's own sets, or a folder that really holds waypoint files). */
    static void check(Worlds.Source src, String id) {
        if (id == null || id.isBlank()) throw new Api(400, "Choose which JourneyMap waypoints to import.");
        for (Map<String, Object> m : find(src)) if (id.equals(m.get("id"))) return;
        if (id.startsWith("dir:")) {
            Path p;
            try { p = Path.of(id.substring(4)).normalize(); } catch (InvalidPathException e) { throw new Api(400, "Bad waypoint folder."); }
            if (p.isAbsolute() && Files.isDirectory(p) && countJson(p) > 0) return;
        }
        throw new Api(400, "Those JourneyMap waypoints could not be found any more.");
    }

    // ---------- reading ----------

    private static Wp parse(String text) {
        Map<String, Object> m = Json.obj(Json.parse(text));
        if (m == null || "Death".equalsIgnoreCase(Json.str(m, "type"))) return null;
        String name = Json.str(m, "name");
        if (name == null || name.isBlank() || !(m.get("x") instanceof Number x) || !(m.get("y") instanceof Number y) || !(m.get("z") instanceof Number z)) return null;
        List<String> dims = new ArrayList<>();
        for (Object d : Json.arr(m.get("dimensions"))) if (d instanceof String s && s.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) dims.add(s);
        if (dims.isEmpty()) dims.add("minecraft:overworld");
        int r = m.get("r") instanceof Number n ? n.intValue() & 255 : 255, g = m.get("g") instanceof Number n ? n.intValue() & 255 : 255, b = m.get("b") instanceof Number n ? n.intValue() & 255 : 255;
        return new Wp(name.length() > 100 ? name.substring(0, 100) : name, x.intValue(), y.intValue(), z.intValue(), r, g, b, dims, !Boolean.FALSE.equals(m.get("enable")));
    }

    private static List<Wp> readDir(Path dir, int[] skipped) throws IOException {
        List<Wp> out = new ArrayList<>();
        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) { files = s.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".json") && Files.isRegularFile(p)).sorted().limit(MAX_FILES).toList(); }
        for (Path f : files) {
            try {
                if (Files.size(f) > MAX_JSON) { skipped[0]++; continue; }
                Wp w = parse(Files.readString(f, StandardCharsets.UTF_8));
                if (w == null) skipped[0]++; else out.add(w);
            } catch (IOException | RuntimeException ex) { skipped[0]++; }
        }
        return out;
    }

    /** How many files in a folder are real waypoints (death markers and other files do not count). */
    private static int usable(Path dir) {
        try { return readDir(dir, new int[1]).size(); } catch (IOException | RuntimeException e) { return 0; }
    }
    /** Reads every waypoint of a set. Files that are not waypoints (or are Death waypoints) are skipped. */
    static List<Wp> read(Worlds.Source src, String id, int[] skipped) throws IOException {
        List<Wp> out = new ArrayList<>();
        if (id.startsWith("zip:")) {
            if (!src.kind.equals("zip")) throw new Api(400, "Bad waypoint source.");
            String prefix = id.substring(4);
            try (ZipFile z = new ZipFile(src.path.toFile())) {
                for (Map.Entry<String, ZipEntry> e : Worlds.Source.index(z).entrySet()) {
                    Matcher m = ZIP_WAYPOINT.matcher(e.getKey());
                    if (!m.matches() || !m.group(1).equals(prefix)) continue;
                    if (out.size() + skipped[0] >= MAX_FILES) break;
                    if (e.getValue().getSize() > MAX_JSON) { skipped[0]++; continue; }
                    try (InputStream in = z.getInputStream(e.getValue())) {
                        Wp w = parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                        if (w == null) skipped[0]++; else out.add(w);
                    } catch (RuntimeException ex) { skipped[0]++; }
                }
            }
        } else {
            out.addAll(readDir(Path.of(id.substring(4)), skipped));
        }
        return out;
    }

    // ---------- writing JourneyMap's global waypoint file ----------

    private static Map<String, Object> icon() {
        Map<String, Object> i = new LinkedHashMap<>();
        i.put("textureWidth", 16);
        i.put("rotation", 0);
        i.put("resourceLocation", ICON);
        i.put("opacity", 1.0f);
        i.put("textureHeight", 16);
        return i;
    }

    private static Map<String, Object> group() {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("showDeviation", (byte) 0);
        settings.put("enable", (byte) 1);
        settings.put("display", new LinkedHashMap<String, Object>());
        settings.put("locked", (byte) 0);
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("settings", settings);
        g.put("name", "jm.waypoint.groups.global.name");
        g.put("icon", icon());
        g.put("guid", GROUP);
        g.put("description", "jm.waypoint.groups.global.description");
        g.put("modId", "journeymap");
        return g;
    }

    static String guidOf(Wp w) {
        return UUID.nameUUIDFromBytes((w.name() + "|" + w.x() + "|" + w.y() + "|" + w.z() + "|" + w.dims().get(0)).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static Map<String, Object> waypoint(Wp w, String guid) {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("showDeviation", (byte) 0);
        settings.put("enable", (byte) (w.enabled() ? 1 : 0));
        settings.put("persistent", (byte) 1);
        Map<String, Object> pos = new LinkedHashMap<>();
        pos.put("x", w.x());
        pos.put("y", w.y());
        pos.put("z", w.z());
        pos.put("dimension", w.dims().get(0));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("settings", settings);
        m.put("color", 0xFF000000 | (w.r() << 16) | (w.g() << 8) | w.b());
        m.put("pos", pos);
        m.put("origin", "journeymap");
        m.put("groupId", GROUP);
        m.put("name", w.name());
        m.put("icon", icon());
        m.put("guid", guid);
        m.put("modId", "journeymap");
        m.put("dimensions", new Nbt.Lst((byte) 8, new ArrayList<Object>(w.dims())));
        return m;
    }

    /** Adds the waypoints to world/data/journeymap/global_waypoints.dat (created if missing; existing global waypoints are kept). Returns how many were new. */
    static int write(Path world, List<Wp> wps, int dataVersion) throws IOException {
        Path file = world.resolve("data").resolve("journeymap").resolve("global_waypoints.dat");
        Map<String, Object> root = Files.isRegularFile(file) ? Nbt.readGzip(Files.newInputStream(file)) : new LinkedHashMap<>();
        Map<String, Object> data = Nbt.child(root, "data");
        if (data == null) { data = new LinkedHashMap<>(); root.put("data", data); }
        Map<String, Object> groups = Nbt.child(data, "groups");
        if (groups == null) { groups = new LinkedHashMap<>(); data.put("groups", groups); }
        Map<String, Object> waypoints = Nbt.child(data, "waypoints");
        if (waypoints == null) { waypoints = new LinkedHashMap<>(); data.put("waypoints", waypoints); }
        if (!groups.containsKey(GROUP)) groups.put(GROUP, group());
        int added = 0;
        for (Wp w : wps) {
            String guid = guidOf(w);
            if (waypoints.containsKey(guid)) continue;
            waypoints.put(guid, waypoint(w, guid));
            added++;
        }
        if (!root.containsKey("DataVersion")) root.put("DataVersion", dataVersion > 0 ? dataVersion : FALLBACK_DATA_VERSION);
        Files.createDirectories(file.getParent());
        if (Files.exists(file)) Files.copy(file, file.resolveSibling("global_waypoints.dat.mcm-old"), StandardCopyOption.REPLACE_EXISTING);
        Files.write(file, Nbt.writeGzip(root));
        return added;
    }
}
