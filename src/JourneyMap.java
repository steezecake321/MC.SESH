import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JourneyMap on a server: whether it is installed, the global and per-player waypoints (read from the world's NBT files), teleporting to a waypoint,
 * sending a waypoint to online players, and the server-side settings and admin list. Waypoints are only ever edited through JourneyMap's own commands
 * (live, for online players) or, for global waypoints, in the file while the server is stopped (a backup is kept).
 */
final class JourneyMap {
    private JourneyMap() { }

    private static final Pattern NAME_OK = Pattern.compile("[A-Za-z0-9_.\\-]{1,32}");
    private static final Pattern DIM_OK = Pattern.compile("[a-z0-9_.\\-]+:[a-z0-9_./\\-]+");
    private static final Pattern WP_NAME_OK = Pattern.compile("[A-Za-z0-9 _.,'()\\-]{1,40}");
    private static final Pattern VALUE_OK = Pattern.compile("[A-Za-z0-9_.\\-]{1,40}");
    private static final Pattern JAR_VER = Pattern.compile("journeymap-[a-z]+-[^-]+-([0-9][0-9.]*)\\.jar", Pattern.CASE_INSENSITIVE);

    // ---------- is it there ----------

    private static final Map<String, long[]> INSTALLED_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    /** True when the server's mods folder has a JourneyMap jar (the mod itself, not only its API). Cached for a few seconds because status is polled. */
    static boolean installed(Srv s) {
        String key = s.dir.toString();
        long[] c = INSTALLED_CACHE.get(key);
        long now = System.currentTimeMillis();
        if (c != null && now - c[0] < 10_000) return c[1] == 1;
        boolean has = jar(s) != null;
        INSTALLED_CACHE.put(key, new long[]{now, has ? 1 : 0});
        return has;
    }

    private static Path jar(Srv s) {
        Path mods = s.dir.resolve("mods");
        if (!Files.isDirectory(mods)) return null;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(mods, "*.jar")) {
            for (Path p : ds) {
                String n = p.getFileName().toString().toLowerCase();
                if (n.startsWith("journeymap") && !n.startsWith("journeymap-api")) return p;
            }
        } catch (IOException ignored) { }
        return null;
    }

    static String version(Srv s) {
        Path j = jar(s);
        if (j == null) return "";
        Matcher m = JAR_VER.matcher(j.getFileName().toString());
        return m.find() ? m.group(1) : "";
    }

    /** Whether Modrinth has JourneyMap for this loader and Minecraft version, with a reason when it does not. */
    static Map<String, Object> availability(String type, String mc) {
        if (!List.of("fabric", "forge", "neoforge").contains(type))
            return Json.map("available", false, "reason", "JourneyMap is a mod, so it needs a Fabric, Forge or NeoForge server.");
        if (mc == null || mc.isBlank()) return Json.map("available", false, "reason", "");
        if (Modrinth.available("journeymap", type, mc)) return Json.map("available", true, "reason", "");
        return Json.map("available", false, "reason", "JourneyMap is not available for " + type + " on Minecraft " + mc + " yet.");
    }

    // ---------- locations ----------

    private static Path wpDir(Srv s) { return s.dir.resolve(s.worldName()).resolve("data").resolve("journeymap"); }

    /** journeymap/server/<version>/ with the newest version folder. */
    private static Path cfgDir(Srv s) {
        Path base = s.dir.resolve("journeymap").resolve("server");
        if (!Files.isDirectory(base)) return null;
        Path best = null;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(base)) {
            for (Path p : ds) if (Files.isDirectory(p) && (best == null || p.getFileName().toString().compareTo(best.getFileName().toString()) > 0)) best = p;
        } catch (IOException ignored) { }
        return best;
    }

    private static Path adminsFile(Srv s) { return s.dir.resolve("config").resolve("journeymap-server.json"); }

    // ---------- names ----------

    private static Map<String, String> uuidNames(Srv s) {
        Map<String, String> m = new HashMap<>();
        try {
            for (Object o : Json.arr(Json.parse(Files.readString(s.dir.resolve("usercache.json"), StandardCharsets.UTF_8)))) {
                String u = Json.str(o, "uuid"), n = Json.str(o, "name");
                if (u != null && n != null) m.put(u.toLowerCase(), n);
            }
        } catch (IOException | RuntimeException ignored) { }
        return m;
    }

    private static String uuidOf(Srv s, String name) {
        for (Map.Entry<String, String> e : uuidNames(s).entrySet()) if (e.getValue().equalsIgnoreCase(name)) return e.getKey();
        return null;
    }

    // ---------- waypoints (read) ----------

    private static Map<String, Object> waypoint(String guid, NbtTree.Tag w) {
        NbtTree.Tag pos = w.get("pos");
        String dim = pos != null && pos.get("dimension") != null ? pos.get("dimension").str() : "";
        int color = w.get("color") != null ? (int) w.get("color").num() : 0xFFFFFF;
        NbtTree.Tag icon = w.get("icon");
        return Json.map("guid", guid, "name", w.get("name") == null ? "" : w.get("name").str(), "dim", dim,
                "x", pos == null || pos.get("x") == null ? 0 : Math.round(pos.get("x").num()),
                "y", pos == null || pos.get("y") == null ? 0 : Math.round(pos.get("y").num()),
                "z", pos == null || pos.get("z") == null ? 0 : Math.round(pos.get("z").num()),
                "color", String.format("#%06x", color & 0xFFFFFF), "icon", icon != null && icon.get("resourceLocation") != null ? icon.get("resourceLocation").str() : "");
    }

    private static List<Object> waypointList(NbtTree.Tag container) {
        List<Object> out = new ArrayList<>();
        if (container != null && container.type == NbtTree.COMPOUND)
            for (Map.Entry<String, NbtTree.Tag> e : container.map().entrySet()) if (e.getValue().type == NbtTree.COMPOUND) out.add(waypoint(e.getKey(), e.getValue()));
        out.sort(Comparator.comparing(o -> String.valueOf(Json.str(o, "name")).toLowerCase()));
        return out;
    }

    private static NbtTree.Tag readData(Path file) {
        if (!Files.isRegularFile(file)) return null;
        try {
            NbtTree.Tag root = NbtTree.read(file, null);
            return root.get("data");
        } catch (IOException e) { return null; }
    }

    static List<Object> globalWaypoints(Srv s) {
        NbtTree.Tag d = readData(wpDir(s).resolve("global_waypoints.dat"));
        return d == null ? new ArrayList<>() : waypointList(d.get("waypoints"));
    }

    static List<Object> playerWaypoints(Srv s, String uuid) {
        NbtTree.Tag d = readData(wpDir(s).resolve("player_waypoints.dat"));
        NbtTree.Tag by = d == null ? null : d.get("waypoints_by_player");
        NbtTree.Tag mine = by == null ? null : by.get(uuid == null ? "" : uuid.toLowerCase());
        if (mine == null && by != null && uuid != null) for (Map.Entry<String, NbtTree.Tag> e : by.map().entrySet()) if (e.getKey().equalsIgnoreCase(uuid)) mine = e.getValue();
        return waypointList(mine);
    }

    private static List<Object> players(Srv s) {
        NbtTree.Tag d = readData(wpDir(s).resolve("player_waypoints.dat"));
        NbtTree.Tag by = d == null ? null : d.get("waypoints_by_player");
        Map<String, String> names = uuidNames(s);
        List<Object> out = new ArrayList<>();
        if (by != null) for (Map.Entry<String, NbtTree.Tag> e : by.map().entrySet()) {
            int n = e.getValue().type == NbtTree.COMPOUND ? e.getValue().map().size() : 0;
            String nm = names.get(e.getKey().toLowerCase());
            out.add(Json.map("uuid", e.getKey(), "name", nm == null ? "" : nm, "count", n));
        }
        out.sort((a, b) -> {
            String na = Json.str(a, "name"), nb = Json.str(b, "name");
            if (na.isEmpty() != nb.isEmpty()) return na.isEmpty() ? 1 : -1;
            return na.compareToIgnoreCase(nb);
        });
        return out;
    }

    // ---------- the whole tab ----------

    static Map<String, Object> status(Srv s) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("installed", installed(s));
        out.put("version", version(s));
        out.put("world", s.worldName());
        out.put("running", s.running(false));
        out.put("global", globalWaypoints(s));
        out.put("players", players(s));
        out.put("settings", settings(s));
        out.put("admins", admins(s));
        return out;
    }

    // ---------- teleport and live waypoint commands ----------

    private static void needRunning(Srv s) {
        if (!s.running(false)) throw new Api(409, "The server is not running. Start it first.");
    }

    private static String num(double v) { return String.format(Locale.ROOT, "%.1f", v); }

    private static double coord(Object o, String what) {
        if (!(o instanceof Number n)) throw new Api(400, "Give a number for " + what + ".");
        double v = n.doubleValue();
        if (Double.isNaN(v) || Math.abs(v) > 30_000_000) throw new Api(400, what + " is out of range.");
        return v;
    }

    private static String dim(String d) {
        if (d == null || !DIM_OK.matcher(d).matches()) throw new Api(400, "That does not look like a dimension (for example minecraft:overworld).");
        return d;
    }

    private static String player(String p) {
        if (p == null || !NAME_OK.matcher(p).matches()) throw new Api(400, "That is not a valid player name.");
        return p;
    }

    /** Moves an online player to a place, in the right dimension. */
    static Map<String, Object> teleport(Srv s, String player, String dim, Object x, Object y, Object z) {
        needRunning(s);
        String cmd = "execute in " + dim(dim) + " run tp " + player(player) + " " + num(coord(x, "x") + 0.5) + " " + num(coord(y, "y")) + " " + num(coord(z, "z") + 0.5);
        String out = s.command(cmd);
        return Json.map("ok", true, "command", cmd, "output", out == null ? "" : out.trim());
    }

    /** /tpwp <player> <waypoint>: finds the waypoint by name (the player's own first, then the global ones) and teleports the player there. */
    static Map<String, Object> tpwp(Srv s, String player, String waypoint) {
        needRunning(s);
        player(player);
        if (waypoint == null || waypoint.isBlank()) throw new Api(400, "Give the waypoint's name.");
        String want = waypoint.trim().toLowerCase();
        List<Object> own = playerWaypoints(s, uuidOf(s, player)), glob = globalWaypoints(s);
        Map<String, Object> hit = null;
        for (List<Object> src : List.of(own, glob)) {
            for (Object o : src) if (String.valueOf(Json.str(o, "name")).toLowerCase().equals(want)) { hit = Json.obj(o); break; }
            if (hit != null) break;
        }
        if (hit == null) for (List<Object> src : List.of(own, glob)) {
            for (Object o : src) if (String.valueOf(Json.str(o, "name")).toLowerCase().contains(want)) { hit = Json.obj(o); break; }
            if (hit != null) break;
        }
        if (hit == null) throw new Api(404, "No waypoint named \"" + waypoint.trim() + "\" for " + player + " or in the global list.");
        Map<String, Object> r = teleport(s, player, Json.str(hit, "dim"), hit.get("x"), hit.get("y"), hit.get("z"));
        r.put("waypoint", Json.str(hit, "name"));
        return r;
    }

    /** Gives a waypoint to online players through JourneyMap's own command (it lands in their own waypoint list). */
    static Map<String, Object> send(Srv s, String name, String dim, Object x, Object y, Object z, String color, List<String> players) {
        needRunning(s);
        if (name == null || !WP_NAME_OK.matcher(name).matches()) throw new Api(400, "The waypoint name can use letters, numbers, spaces and . , ' ( ) - _ (up to 40 characters).");
        if (color == null || !color.replace("#", "").matches("[0-9a-fA-F]{6}")) throw new Api(400, "Pick a colour.");
        if (players == null || players.isEmpty()) throw new Api(400, "Pick at least one player.");
        List<String> outs = new ArrayList<>();
        for (String p : players) {
            String cmd = "jm waypoint create \"" + name + "\" " + dim(dim) + " " + Math.round(coord(x, "x")) + " " + Math.round(coord(y, "y")) + " " + Math.round(coord(z, "z")) + " " + color.replace("#", "").toLowerCase() + " " + player(p);
            String o = s.command(cmd);
            outs.add(p + ": " + (o == null || o.isBlank() ? "sent" : o.trim()));
        }
        return Json.map("ok", true, "results", outs);
    }

    // ---------- global waypoints (file, server stopped) ----------

    static Map<String, Object> deleteGlobal(Srv s, String guid) throws IOException {
        if (s.running(true)) throw new Api(409, "Stop the server first: JourneyMap keeps global waypoints in memory while it runs and would write the old list back. (Players can still delete their own waypoints in game.)");
        Path f = wpDir(s).resolve("global_waypoints.dat");
        if (!Files.isRegularFile(f)) throw new Api(404, "This world has no global waypoints.");
        String[] rn = new String[1];
        NbtTree.Tag root = NbtTree.read(f, rn);
        NbtTree.Tag w = root.get("data") == null ? null : root.get("data").get("waypoints");
        if (w == null || guid == null || w.map().remove(guid) == null) throw new Api(404, "That waypoint is not in the list any more.");
        backup(f);
        NbtTree.write(f, rn[0], root);
        return Json.map("ok", true);
    }

    /** Copies one of a player's own waypoints into the global list (everyone with JourneyMap then sees it). The server must be stopped. */
    static Map<String, Object> promote(Srv s, String playerUuid, String guid) throws IOException {
        if (s.running(true)) throw new Api(409, "Stop the server first: JourneyMap keeps global waypoints in memory while it runs and would write the old list back.");
        Path pf = wpDir(s).resolve("player_waypoints.dat"), gf = wpDir(s).resolve("global_waypoints.dat");
        if (!Files.isRegularFile(pf)) throw new Api(404, "This world has no saved player waypoints.");
        NbtTree.Tag pr = NbtTree.read(pf, null);
        NbtTree.Tag by = pr.get("data") == null ? null : pr.get("data").get("waypoints_by_player");
        NbtTree.Tag mine = null;
        if (by != null && playerUuid != null) for (Map.Entry<String, NbtTree.Tag> e : by.map().entrySet()) if (e.getKey().equalsIgnoreCase(playerUuid)) mine = e.getValue();
        NbtTree.Tag src = mine == null || guid == null ? null : mine.get(guid);
        if (src == null) throw new Api(404, "That waypoint is not in the player's list any more.");
        if (!Files.isRegularFile(gf)) throw new Api(409, "This world has no global waypoint file yet. Add one global waypoint in game first (JourneyMap then creates the file), then try again.");
        String[] rn = new String[1];
        NbtTree.Tag gr = NbtTree.read(gf, rn);
        NbtTree.Tag data = gr.get("data");
        if (data == null) throw new Api(409, "The global waypoint file is not in the format this version understands.");
        NbtTree.Tag gw = data.get("waypoints");
        if (gw == null) { gw = NbtTree.compound(); data.map().put("waypoints", gw); }
        Map<String, Object> want = waypoint(guid, src);
        for (Object o : waypointList(gw)) {
            if (Json.str(o, "name").equals(Json.str(want, "name")) && Json.str(o, "dim").equals(Json.str(want, "dim"))
                    && String.valueOf(((Map<?, ?>) o).get("x")).equals(String.valueOf(want.get("x"))) && String.valueOf(((Map<?, ?>) o).get("y")).equals(String.valueOf(want.get("y")))
                    && String.valueOf(((Map<?, ?>) o).get("z")).equals(String.valueOf(want.get("z"))))
                return Json.map("ok", true, "already", true, "name", Json.str(want, "name"));
        }
        NbtTree.Tag c = NbtTree.copy(src);
        String newGuid = gw.map().containsKey(guid) ? UUID.randomUUID().toString() : guid;
        c.map().put("guid", NbtTree.string(newGuid));
        c.map().put("groupId", NbtTree.string("journeymap_global_default"));
        gw.map().put(newGuid, c);
        backup(gf);
        NbtTree.write(gf, rn[0], gr);
        return Json.map("ok", true, "already", false, "name", Json.str(want, "name"));
    }

    private static void backup(Path f) throws IOException {
        Files.copy(f, f.resolveSibling(f.getFileName() + ".mcm-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".bak"), StandardCopyOption.REPLACE_EXISTING);
    }

    // ---------- settings (journeymap/server/<version>/journeymap.server.global.config) ----------

    private static Path globalCfg(Srv s) {
        Path d = cfgDir(s);
        return d == null ? null : d.resolve("journeymap.server.global.config");
    }

    /** Config files are comment lines (//) followed by one JSON object of string values. */
    private static Map<String, String> readCfg(Path f) throws IOException {
        StringBuilder json = new StringBuilder();
        for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
            line = line.replace("﻿", "");
            if (!line.stripLeading().startsWith("//")) json.append(line).append('\n');
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : Json.obj(Json.parse(json.toString())).entrySet()) out.put(e.getKey(), String.valueOf(e.getValue()));
        return out;
    }

    static Map<String, Object> settings(Srv s) {
        Path f = globalCfg(s);
        if (f == null || !Files.isRegularFile(f)) return Json.map("found", false, "values", Json.map());
        try {
            return Json.map("found", true, "values", new LinkedHashMap<String, Object>(readCfg(f)));
        } catch (IOException | RuntimeException e) {
            return Json.map("found", false, "values", Json.map(), "error", String.valueOf(e.getMessage()));
        }
    }

    /** Changes values of keys that already exist in the global config; the comment header and every other value stay as they are. */
    static Map<String, Object> saveSettings(Srv s, Map<String, Object> changes) throws IOException {
        Path f = globalCfg(s);
        if (f == null || !Files.isRegularFile(f)) throw new Api(404, "JourneyMap has not created its settings yet. Start the server once first.");
        Map<String, String> cur = readCfg(f);
        int n = 0;
        for (Map.Entry<String, Object> e : changes.entrySet()) {
            if (!cur.containsKey(e.getKey())) throw new Api(400, "Unknown JourneyMap setting: " + e.getKey());
            String v = String.valueOf(e.getValue());
            if (!VALUE_OK.matcher(v).matches()) throw new Api(400, "That value is not allowed for " + e.getKey() + ".");
            if (!v.equals(cur.get(e.getKey()))) { cur.put(e.getKey(), v); n++; }
        }
        if (n == 0) return Json.map("ok", true, "changed", 0, "running", s.running(false));
        List<String> header = new ArrayList<>();
        for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) if (line.replace("﻿", "").stripLeading().startsWith("//")) header.add(line.replace("﻿", ""));
        StringBuilder sb = new StringBuilder();
        for (String h : header) sb.append(h).append('\n');
        sb.append("{\n");
        int i = 0;
        for (Map.Entry<String, String> e : cur.entrySet()) sb.append("  \"").append(e.getKey()).append("\": \"").append(e.getValue()).append('"').append(++i < cur.size() ? "," : "").append('\n');
        sb.append("}\n");
        backup(f);
        Files.writeString(f, sb.toString(), StandardCharsets.UTF_8);
        return Json.map("ok", true, "changed", n, "running", s.running(false));
    }

    // ---------- JourneyMap admins (config/journeymap-server.json) ----------

    static Map<String, Object> admins(Srv s) {
        Path f = adminsFile(s);
        Map<String, String> names = uuidNames(s);
        List<Object> list = new ArrayList<>();
        boolean op = true;
        if (Files.isRegularFile(f)) {
            try {
                Map<String, Object> j = Json.obj(Json.parse(Files.readString(f, StandardCharsets.UTF_8)));
                op = !Boolean.FALSE.equals(j.get("opAccess"));
                for (Object o : Json.arr(j.get("admins"))) {
                    String u = String.valueOf(o);
                    String n = names.get(u.toLowerCase());
                    list.add(Json.map("uuid", u, "name", n == null ? "" : n));
                }
            } catch (IOException | RuntimeException ignored) { }
        }
        return Json.map("opAccess", op, "list", list);
    }

    static Map<String, Object> setAdmin(Srv s, String uuid, boolean add, Boolean opAccess) throws IOException {
        Path f = adminsFile(s);
        Map<String, Object> j = new LinkedHashMap<>();
        if (Files.isRegularFile(f)) {
            try { j.putAll(Json.obj(Json.parse(Files.readString(f, StandardCharsets.UTF_8)))); } catch (RuntimeException ignored) { }
        }
        List<Object> list = new ArrayList<>(Json.arr(j.get("admins")));
        if (uuid != null && !uuid.isBlank()) {
            if (!uuid.matches("[0-9a-fA-F-]{32,36}")) throw new Api(400, "That is not a player id.");
            list.removeIf(o -> String.valueOf(o).equalsIgnoreCase(uuid));
            if (add) list.add(uuid.toLowerCase());
        }
        j.put("admins", list);
        if (opAccess != null) j.put("opAccess", opAccess);
        else j.putIfAbsent("opAccess", true);
        Files.createDirectories(f.getParent());
        Files.writeString(f, Json.stringify(j), StandardCharsets.UTF_8);
        return Json.map("ok", true, "running", s.running(false), "admins", admins(s));
    }
}
