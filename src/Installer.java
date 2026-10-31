import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import java.util.regex.*;
import java.util.stream.Stream;

/** Creates new servers: lists versions and downloads Vanilla, Fabric, Forge or NeoForge. */
final class Installer {
    private Installer() {}

    private static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(15)).build();
    private static final Map<String, Object[]> CACHE = new HashMap<>();
    private static final String MOJANG = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    private static final String FABRIC = "https://meta.fabricmc.net/v2";
    private static final String FORGE_META = "https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml";
    private static final String FORGE_PROMOS = "https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json";
    private static final String NEO_VERSIONS = "https://maven.neoforged.net/api/maven/versions/releases/net/neoforged/neoforge";
    static final Set<String> TYPES = Set.of("vanilla", "fabric", "forge", "neoforge");
    private static final Pattern NAME_OK = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9 _.-]{0,39}$");
    private static final Pattern TOKEN_OK = Pattern.compile("^[0-9A-Za-z._+-]{1,60}$");

    // ---------- http ----------

    static synchronized String get(String url) throws IOException {
        Object[] c = CACHE.get(url);
        if (c != null && System.currentTimeMillis() - (Long) c[0] < 600_000) return (String) c[1];
        try {
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(40))
                    .header("User-Agent", "MCServerManager/1.0").build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode() + " from " + url);
            CACHE.put(url, new Object[] {System.currentTimeMillis(), r.body()});
            return r.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted");
        }
    }

    static void download(String url, Path dest, String sha1, Job j) throws IOException {
        Files.createDirectories(dest.getParent());
        Path tmp = dest.resolveSibling(dest.getFileName() + ".part");
        if (j != null) j.log("Downloading " + dest.getFileName() + "...");
        try {
            HttpResponse<InputStream> r = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(10))
                    .header("User-Agent", "MCServerManager/1.0").build(), HttpResponse.BodyHandlers.ofInputStream());
            if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode() + " downloading " + url);
            long total = r.headers().firstValueAsLong("Content-Length").orElse(-1);
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            long got = 0, nextLog = 10L << 20;
            try (InputStream in = r.body(); OutputStream out = Files.newOutputStream(tmp)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    md.update(buf, 0, n);
                    got += n;
                    if (j != null && got >= nextLog) {
                        j.log("  " + (got >> 20) + " MB" + (total > 0 ? " of " + (total >> 20) + " MB" : ""));
                        nextLog += 10L << 20;
                    }
                }
            }
            if (sha1 != null && !sha1.isBlank()) {
                String hex = HexFormat.of().formatHex(md.digest());
                if (!hex.equalsIgnoreCase(sha1.trim())) throw new IOException("Checksum mismatch for " + dest.getFileName());
            }
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
        } catch (InterruptedException | java.security.NoSuchAlgorithmException e) {
            throw new IOException(String.valueOf(e.getMessage()));
        } finally {
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) { }
        }
    }

    private static String mavenSha1(String url) {
        try { return get(url + ".sha1").trim().split("\\s+")[0]; } catch (IOException e) { return null; }
    }

    // ---------- version lists ----------

    private static Map<String, Object> manifest() throws IOException { return Json.obj(Json.parse(get(MOJANG))); }

    static int cmp(String a, String b) {
        String[] x = a.split("[.\\-+]"), y = b.split("[.\\-+]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            String p = i < x.length ? x[i] : "0", q = i < y.length ? y[i] : "0";
            boolean pn = p.matches("\\d+"), qn = q.matches("\\d+");
            int c = pn && qn ? Long.compare(Long.parseLong(p), Long.parseLong(q)) : pn ? 1 : qn ? -1 : p.compareTo(q);
            if (c != 0) return c;
        }
        return 0;
    }

    static String neoMc(String v) {
        String[] p = v.replaceAll("-.*$", "").split("\\.");
        try {
            int a = Integer.parseInt(p[0]);
            if (a >= 26) return a + "." + p[1];
            return "1." + a + (p.length > 1 && !p[1].equals("0") ? "." + p[1] : "");
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Map<String, List<String>> forgeByMc() throws IOException {
        Map<String, List<String>> m = new HashMap<>();
        Matcher mt = Pattern.compile("<version>([^<]+)</version>").matcher(get(FORGE_META));
        while (mt.find()) {
            String v = mt.group(1);
            int d = v.indexOf('-');
            if (d > 0) m.computeIfAbsent(v.substring(0, d), k -> new ArrayList<>()).add(v.substring(d + 1));
        }
        return m;
    }

    private static Map<String, List<String>> neoByMc() throws IOException {
        Map<String, List<String>> m = new HashMap<>();
        for (Object o : Json.arr(Json.obj(Json.parse(get(NEO_VERSIONS))).get("versions"))) {
            String mc = neoMc(String.valueOf(o));
            if (mc != null) m.computeIfAbsent(mc, k -> new ArrayList<>()).add(String.valueOf(o));
        }
        return m;
    }

    static Map<String, Object> versions(String type, boolean snapshots) throws IOException {
        if (!TYPES.contains(type)) throw new Api(400, "Unknown server type");
        Map<String, Object> man = manifest();
        String latest = Json.str(man.get("latest"), "release");
        Set<String> fabricStable = new HashSet<>(), fabricAll = new HashSet<>();
        Map<String, List<String>> forge = null, neo = null;
        switch (type) {
            case "fabric" -> {
                for (Object o : Json.arr(Json.parse(get(FABRIC + "/versions/game")))) {
                    fabricAll.add(Json.str(o, "version"));
                    if (Boolean.TRUE.equals(Json.obj(o).get("stable"))) fabricStable.add(Json.str(o, "version"));
                }
            }
            case "forge" -> forge = forgeByMc();
            case "neoforge" -> neo = neoByMc();
            default -> { }
        }
        List<Object> out = new ArrayList<>();
        boolean leadingSnapshots = true;
        for (Object o : Json.arr(man.get("versions"))) {
            String id = Json.str(o, "id"), t = Json.str(o, "type");
            boolean release = "release".equals(t);
            if (release) leadingSnapshots = false;
            if (!release && !(snapshots && "snapshot".equals(t) && leadingSnapshots)) continue;
            boolean ok = switch (type) {
                case "vanilla" -> true;
                case "fabric" -> release ? fabricStable.contains(id) : fabricAll.contains(id);
                case "forge" -> forge.containsKey(id);
                default -> neo.containsKey(id);
            };
            if (ok && release && isOlderThan(id, "1.14")) continue;
            if (ok) out.add(Json.map("id", id, "type", release ? "release" : "snapshot"));
        }
        String lat = latest;
        if (!out.isEmpty() && !out.stream().anyMatch(x -> latest.equals(Json.str(x, "id")))) lat = Json.str(out.get(0), "id");
        return Json.map("latest", lat, "versions", out);
    }

    private static boolean isOlderThan(String id, String ref) {
        return id.matches("\\d+\\.\\d+(\\.\\d+)?") && cmp(id, ref) < 0;
    }

    static List<Object> loaders(String type, String mc) throws IOException {
        List<Object> out = new ArrayList<>();
        if (!TOKEN_OK.matcher(mc == null ? "" : mc).matches()) throw new Api(400, "Bad Minecraft version");
        switch (type) {
            case "fabric" -> {
                boolean first = true;
                for (Object o : Json.arr(Json.parse(get(FABRIC + "/versions/loader/" + mc)))) {
                    Object l = Json.obj(o).get("loader");
                    boolean stable = Boolean.TRUE.equals(Json.obj(l).get("stable"));
                    if (!stable && !out.isEmpty()) continue;
                    out.add(Json.map("id", Json.str(l, "version"), "recommended", first && stable, "label", Json.str(l, "version") + (stable ? "" : " (unstable)")));
                    if (stable) first = false;
                }
            }
            case "forge" -> {
                List<String> vs = new ArrayList<>(forgeByMc().getOrDefault(mc, List.of()));
                vs.sort((a, b) -> cmp(b, a));
                Map<String, Object> promos = Json.obj(Json.obj(Json.parse(get(FORGE_PROMOS))).get("promos"));
                String rec = (String) promos.get(mc + "-recommended"), lat = (String) promos.get(mc + "-latest");
                String pick = rec != null && vs.contains(rec) ? rec : lat != null && vs.contains(lat) ? lat : vs.isEmpty() ? null : vs.get(0);
                for (String v : vs) out.add(Json.map("id", v, "recommended", v.equals(pick), "label", v + (v.equals(rec) ? " (recommended)" : v.equals(lat) ? " (latest)" : "")));
            }
            case "neoforge" -> {
                List<String> vs = new ArrayList<>(neoByMc().getOrDefault(mc, List.of()));
                vs.sort((a, b) -> cmp(b, a));
                String pick = vs.stream().filter(v -> !v.contains("-")).findFirst().orElse(vs.isEmpty() ? null : vs.get(0));
                for (String v : vs) out.add(Json.map("id", v, "recommended", v.equals(pick), "label", v + (v.contains("-") ? " (beta)" : "")));
            }
            default -> { }
        }
        return out;
    }

    private static Map<String, Object> mojangVersion(String mc) throws IOException {
        for (Object o : Json.arr(manifest().get("versions"))) {
            if (mc.equals(Json.str(o, "id"))) return Json.obj(Json.parse(get(Json.str(o, "url"))));
        }
        throw new Api(404, "Minecraft version " + mc + " not found");
    }

    static Map<String, Object> javaInfo(String mc) throws IOException {
        if (!TOKEN_OK.matcher(mc == null ? "" : mc).matches()) throw new Api(400, "Bad Minecraft version");
        Map<String, Object> v = mojangVersion(mc);
        int req = Json.num(v.get("javaVersion"), "majorVersion", 8);
        return Json.map("required", req, "installed", Runtime.version().feature(), "hasServer", Json.obj(Json.obj(v.get("downloads")).get("server")).get("url") != null);
    }

    /** Downloads the official vanilla server jar for a Minecraft version. */
    static void vanillaJar(String mc, Path dest, Job j) throws IOException {
        Map<String, Object> server = Json.obj(Json.obj(mojangVersion(mc).get("downloads")).get("server"));
        if (server.get("url") == null) throw new Api(400, "Mojang published no server download for " + mc);
        download(String.valueOf(server.get("url")), dest, Json.str(server, "sha1"), j);
    }

    // ---------- defaults ----------

    static Map<String, Object> defaults() {
        Set<Integer> used = usedPorts();
        int port = 6767;
        while (used.contains(port) || Main.sampler.pidForPort(port) != null) port++;
        return Json.map("port", port, "root", Main.serversRoot().toString(), "javaMajor", Runtime.version().feature(),
                "prefs", Main.cfg.root.get("createDefaults") instanceof Map ? Json.obj(Main.cfg.root.get("createDefaults")) : Json.map());
    }

    private static final Pattern RESERVED = Pattern.compile("(?i)^(con|prn|aux|nul|com[1-9]|lpt[1-9])(\\..*)?$");

    /** The folder a create request will install into: <parent>\<name>, where parent defaults to the configured servers folder. */
    static Path targetDir(Map<String, Object> req) {
        String name = String.valueOf(req.getOrDefault("name", "")).trim();
        if (!NAME_OK.matcher(name).matches() || name.endsWith(".") || RESERVED.matcher(name).matches())
            throw new Api(400, "Name may use letters, digits, spaces, - _ . (max 40 characters, not a reserved Windows name)");
        String ps = Json.str(req, "parent");
        Path parent = ps == null || ps.isBlank() ? Main.serversRoot() : Path.of(ps.trim());
        if (!parent.isAbsolute()) throw new Api(400, "The install location must be a full path such as D:\\Minecraft");
        parent = parent.normalize();
        Path dir = parent.resolve(name).normalize();
        if (!parent.equals(dir.getParent())) throw new Api(400, "Bad name");
        return dir;
    }

    /** The nearest existing ancestor must be a writable folder with enough free space. */
    static void checkLocation(Path dir) throws IOException {
        Path p = dir.getParent();
        while (p != null && !Files.exists(p)) p = p.getParent();
        if (p == null || !Files.isDirectory(p)) throw new Api(400, "That drive or folder does not exist: " + dir.getParent());
        if (!Files.isWritable(p)) throw new Api(400, "Cannot write to " + p + " (choose another folder)");
        long free = Files.getFileStore(p).getUsableSpace();
        if (free < (1536L << 20)) throw new Api(400, "Not enough free disk space on that drive (" + (free >> 20) + " MB free, about 1.5 GB needed)");
    }

    private static Set<Integer> usedPorts() { return Ports.allUsed(null); }

    // ---------- world / gameplay settings written before the first run ----------

    private static final Pattern WORLD_BAD = Pattern.compile("[\\\\/:*?\"<>|\\p{Cntrl}]");
    private static final List<String> LEVEL_TYPES = List.of("normal", "flat", "large_biomes", "amplified", "single_biome");
    private static final List<String> DIFFICULTIES = List.of("peaceful", "easy", "normal", "hard");
    private static final List<String> GAMEMODES = List.of("survival", "creative", "adventure", "spectator");

    /** Minecraft 1.19.3 and later spell world types with the minecraft: prefix; older versions use the old names. */
    static String levelTypeValue(String type, String mc) {
        boolean legacy = false;
        if (mc != null && mc.matches("\\d+(\\.\\d+)*")) legacy = cmp(mc, "1.19.3") < 0;
        if (legacy) return switch (type) { case "flat" -> "flat"; case "large_biomes" -> "largeBiomes"; case "amplified" -> "amplified"; default -> "default"; };
        return "minecraft:" + type;
    }

    // ---------- single-biome worlds ----------
    // The server has no setting that picks the biome of a single-biome world (the game only asks in its own world
    // screen), so a tiny data pack in the new world's folder replaces the "single_biome_surface" world preset
    // with one whose overworld uses the chosen biome. The world is then generated that way on first start.

    static final String SINGLE_BIOME_FROM = "1.20.2";
    private static final Pattern BIOME_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    /** group, id, label, first Minecraft version that has it ("" = all supported versions). Every vanilla biome is here; each one works as the only overworld biome. */
    private static final String[][] BIOMES = {
        {"Plains and forests", "plains", "Plains", ""}, {"Plains and forests", "sunflower_plains", "Sunflower Plains", ""},
        {"Plains and forests", "forest", "Forest", ""}, {"Plains and forests", "flower_forest", "Flower Forest", ""},
        {"Plains and forests", "birch_forest", "Birch Forest", ""}, {"Plains and forests", "old_growth_birch_forest", "Old Growth Birch Forest", ""},
        {"Plains and forests", "dark_forest", "Dark Forest", ""}, {"Plains and forests", "pale_garden", "Pale Garden", "1.21.4"},
        {"Plains and forests", "dappled_forest", "Dappled Forest", "26.3"},
        {"Plains and forests", "meadow", "Meadow", ""}, {"Plains and forests", "cherry_grove", "Cherry Grove", "1.20"},
        {"Cold and snowy", "taiga", "Taiga", ""}, {"Cold and snowy", "snowy_taiga", "Snowy Taiga", ""},
        {"Cold and snowy", "old_growth_pine_taiga", "Old Growth Pine Taiga", ""}, {"Cold and snowy", "old_growth_spruce_taiga", "Old Growth Spruce Taiga", ""},
        {"Cold and snowy", "snowy_plains", "Snowy Plains", ""}, {"Cold and snowy", "ice_spikes", "Ice Spikes", ""},
        {"Mountains", "grove", "Grove", ""}, {"Mountains", "snowy_slopes", "Snowy Slopes", ""}, {"Mountains", "frozen_peaks", "Frozen Peaks", ""},
        {"Mountains", "jagged_peaks", "Jagged Peaks", ""}, {"Mountains", "stony_peaks", "Stony Peaks", ""},
        {"Mountains", "windswept_hills", "Windswept Hills", ""}, {"Mountains", "windswept_gravelly_hills", "Windswept Gravelly Hills", ""},
        {"Mountains", "windswept_forest", "Windswept Forest", ""}, {"Mountains", "windswept_savanna", "Windswept Savanna", ""},
        {"Hot and dry", "desert", "Desert", ""}, {"Hot and dry", "savanna", "Savanna", ""}, {"Hot and dry", "savanna_plateau", "Savanna Plateau", ""},
        {"Hot and dry", "badlands", "Badlands", ""}, {"Hot and dry", "eroded_badlands", "Eroded Badlands", ""}, {"Hot and dry", "wooded_badlands", "Wooded Badlands", ""},
        {"Jungle and swamp", "jungle", "Jungle", ""}, {"Jungle and swamp", "sparse_jungle", "Sparse Jungle", ""}, {"Jungle and swamp", "bamboo_jungle", "Bamboo Jungle", ""},
        {"Jungle and swamp", "swamp", "Swamp", ""}, {"Jungle and swamp", "mangrove_swamp", "Mangrove Swamp", ""},
        {"Water and shores", "ocean", "Ocean", ""}, {"Water and shores", "deep_ocean", "Deep Ocean", ""}, {"Water and shores", "cold_ocean", "Cold Ocean", ""},
        {"Water and shores", "deep_cold_ocean", "Deep Cold Ocean", ""}, {"Water and shores", "frozen_ocean", "Frozen Ocean", ""},
        {"Water and shores", "deep_frozen_ocean", "Deep Frozen Ocean", ""}, {"Water and shores", "lukewarm_ocean", "Lukewarm Ocean", ""},
        {"Water and shores", "deep_lukewarm_ocean", "Deep Lukewarm Ocean", ""}, {"Water and shores", "warm_ocean", "Warm Ocean", ""},
        {"Water and shores", "river", "River", ""}, {"Water and shores", "frozen_river", "Frozen River", ""}, {"Water and shores", "beach", "Beach", ""},
        {"Water and shores", "snowy_beach", "Snowy Beach", ""}, {"Water and shores", "stony_shore", "Stony Shore", ""},
        {"Other", "mushroom_fields", "Mushroom Fields", ""},
        {"Caves", "dripstone_caves", "Dripstone Caves", ""}, {"Caves", "lush_caves", "Lush Caves", ""}, {"Caves", "deep_dark", "Deep Dark", ""},
        {"Caves", "sulfur_caves", "Sulfur Caves", "26.2"},
        {"Nether", "nether_wastes", "Nether Wastes", ""}, {"Nether", "soul_sand_valley", "Soul Sand Valley", ""}, {"Nether", "crimson_forest", "Crimson Forest", ""},
        {"Nether", "warped_forest", "Warped Forest", ""}, {"Nether", "basalt_deltas", "Basalt Deltas", ""},
        {"The End", "the_end", "The End", ""}, {"The End", "end_highlands", "End Highlands", ""}, {"The End", "end_midlands", "End Midlands", ""},
        {"The End", "end_barrens", "End Barrens", ""}, {"The End", "small_end_islands", "Small End Islands", ""},
        {"Empty", "the_void", "The Void (no trees, ores or mobs)", ""},
    };

    private static boolean numericVersion(String mc) { return mc != null && mc.matches("\\d+(\\.\\d+)*"); }

    static boolean singleBiomeSupported(String mc) { return !numericVersion(mc) || cmp(mc, SINGLE_BIOME_FROM) >= 0; }

    /** The biomes the wizard offers for a Minecraft version, as {id, name, group}. Snapshots only get the long-standing ones (their biome sets are unknown). */
    static List<Map<String, Object>> biomes(String mc) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String[] b : BIOMES) {
            String since = b[3];
            if (!since.isEmpty() && (numericVersion(mc) ? cmp(mc, since) < 0 : !since.equals("1.20"))) continue;
            out.add(Json.map("id", "minecraft:" + b[1], "name", b[2], "group", b[0]));
        }
        return out;
    }

    /** Validates the picked biome (a vanilla one for this version, or a mod's biome id on a modded server) and returns its full id. */
    static String chooseBiome(String raw, String mc, boolean modded) {
        String pick = raw == null || raw.isBlank() ? "minecraft:plains" : raw.strip().toLowerCase(Locale.ROOT);
        final String id = pick.contains(":") ? pick : "minecraft:" + pick;
        if (!BIOME_ID.matcher(id).matches()) throw new Api(400, "That is not a biome id. Use the form namespace:name, for example minecraft:cherry_grove.");
        if (id.startsWith("minecraft:")) {
            if (biomes(mc).stream().noneMatch(m -> id.equals(m.get("id")))) throw new Api(400, "Minecraft " + mc + " has no biome called" + id + ".");
        } else if (!modded) throw new Api(400, "Biomes from mods only work on a Fabric, Forge or NeoForge server with that mod installed.");
        return id;
    }

    /** Writes the data pack that makes the first world generated in worldDir use one biome everywhere. */
    static void writeSingleBiomePack(Path worldDir, String biome) throws IOException {
        Path pack = worldDir.resolve("datapacks").resolve("manager-single-biome");
        Path presets = pack.resolve("data").resolve("minecraft").resolve("worldgen").resolve("world_preset");
        Files.createDirectories(presets);
        Files.writeString(pack.resolve("pack.mcmeta"), "{\"pack\":{\"description\":\"Single biome world: " + biome + " (made by MC Server Manager)\","
                + "\"pack_format\":15,\"supported_formats\":{\"min_inclusive\":1,\"max_inclusive\":9999},\"min_format\":1,\"max_format\":9999}}", StandardCharsets.UTF_8);
        Files.writeString(presets.resolve("single_biome_surface.json"), "{\"dimensions\":{"
                + "\"minecraft:overworld\":{\"type\":\"minecraft:overworld\",\"generator\":{\"type\":\"minecraft:noise\",\"biome_source\":{\"type\":\"minecraft:fixed\",\"biome\":\"" + biome + "\"},\"settings\":\"minecraft:overworld\"}},"
                + "\"minecraft:the_end\":{\"type\":\"minecraft:the_end\",\"generator\":{\"type\":\"minecraft:noise\",\"biome_source\":{\"type\":\"minecraft:the_end\"},\"settings\":\"minecraft:end\"}},"
                + "\"minecraft:the_nether\":{\"type\":\"minecraft:the_nether\",\"generator\":{\"type\":\"minecraft:noise\",\"biome_source\":{\"type\":\"minecraft:multi_noise\",\"preset\":\"minecraft:nether\"},\"settings\":\"minecraft:nether\"}}}}",
                StandardCharsets.UTF_8);
    }

    /** Checks and normalises the gameplay settings of a create request. Throws an Api error that the wizard shows next to the form. */
    static Map<String, String> worldSettings(Map<String, Object> req, String serverName) {
        Map<String, String> out = new LinkedHashMap<>();
        String world = Json.str(req, "worldName");
        world = world == null || world.isBlank() ? "world" : world.strip();
        if (world.length() > 64) throw new Api(400, "The world name can be at most 64 characters.");
        if (WORLD_BAD.matcher(world).find()) throw new Api(400, "The world name cannot contain \\ / : * ? \" < > | (it becomes a folder name).");
        if (world.equals(".") || world.contains("..") || world.endsWith(".") || RESERVED.matcher(world).matches()) throw new Api(400, "That world name is not allowed (it must work as a Windows folder name).");
        out.put("level-name", world);
        String seed = Json.str(req, "seed");
        seed = seed == null ? "" : seed.strip();
        if (seed.length() > 64 || seed.matches(".*[\\r\\n].*")) throw new Api(400, "The seed must be one line of at most 64 characters.");
        out.put("level-seed", seed);
        String lt = Json.str(req, "levelType");
        lt = lt == null || lt.isBlank() ? "normal" : lt;
        if (!LEVEL_TYPES.contains(lt)) throw new Api(400, "Unknown world type");
        if (lt.equals("single_biome")) {
            String mcv = String.valueOf(req.get("mc"));
            if (!singleBiomeSupported(mcv)) throw new Api(400, "A single-biome world needs Minecraft " + SINGLE_BIOME_FROM + " or newer.");
            out.put("level-type", "minecraft:single_biome_surface");
            out.put("biome", chooseBiome(Json.str(req, "biome"), mcv, !"vanilla".equals(String.valueOf(req.get("type")))));
        } else out.put("level-type", levelTypeValue(lt, String.valueOf(req.get("mc"))));
        String diff = Json.str(req, "difficulty");
        diff = diff == null || diff.isBlank() ? "easy" : diff;
        if (!DIFFICULTIES.contains(diff)) throw new Api(400, "Unknown difficulty");
        out.put("difficulty", diff);
        String gm = Json.str(req, "gamemode");
        gm = gm == null || gm.isBlank() ? "survival" : gm;
        if (!GAMEMODES.contains(gm)) throw new Api(400, "Unknown game mode");
        out.put("gamemode", gm);
        int mp = req.get("maxPlayers") instanceof Number n ? n.intValue() : 20;
        if (mp < 1 || mp > 500) throw new Api(400, "Max players must be between 1 and 500.");
        out.put("max-players", Integer.toString(mp));
        int sp = req.get("spawnProtection") instanceof Number n ? n.intValue() : 0;
        if (sp < 0 || sp > 256) throw new Api(400, "Spawn protection must be between 0 and 256 blocks (0 turns it off).");
        out.put("spawn-protection", Integer.toString(sp));
        out.put("white-list", Boolean.TRUE.equals(req.get("whitelist")) ? "true" : "false");
        String motd = Json.str(req, "motd");
        motd = motd == null || motd.isBlank() ? serverName : motd.replaceAll("[\\r\\n]", " ").strip();
        if (motd.length() > 120) throw new Api(400, "The message of the day is too long (120 characters at most).");
        out.put("motd", motd);
        return out;
    }

    // ---------- install ----------

    /** Checks a create request and throws an Api error (shown immediately in the wizard) if it is not acceptable. */
    static void validate(Map<String, Object> req) throws IOException {
        String name = String.valueOf(req.getOrDefault("name", "")).trim();
        String type = String.valueOf(req.get("type")), mc = String.valueOf(req.get("mc")), loader = String.valueOf(req.getOrDefault("loader", ""));
        int port = Json.num(req, "port", 0), minMb = Json.num(req, "minMb", 1024), maxMb = Json.num(req, "maxMb", 4096);
        Path dir = targetDir(req);
        worldSettings(req, name);
        if (!Boolean.TRUE.equals(req.get("eula"))) throw new Api(400, "You must accept the Minecraft EULA to create a server");
        if (!TYPES.contains(type)) throw new Api(400, "Unknown server type");
        if (!TOKEN_OK.matcher(mc).matches() || (!type.equals("vanilla") && !TOKEN_OK.matcher(loader).matches())) throw new Api(400, "Bad version");
        if (port < 1024 || port > 65535) throw new Api(400, "Port must be between 1024 and 65535");
        Set<Integer> used = usedPorts();
        if (used.contains(port) || Main.sampler.pidForPort(port) != null) throw new Api(409, "Port " + port + " is already in use");
        if (minMb < 256 || maxMb < minMb || maxMb > 131072) throw new Api(400, "Invalid memory settings");
        checkLocation(dir);
        if (Files.exists(dir)) throw new Api(409, "The folder " + dir + " already exists. Pick another name or location.");
        for (Srv s : Main.servers) {
            if (s.name.equalsIgnoreCase(name)) throw new Api(409, "A server named \"" + name + "\" is already managed");
            if (s.dir.equals(dir)) throw new Api(409, "That folder is already used by \"" + s.name + "\"");
        }
        Map<String, Object> jv = javaInfo(mc);
        if (Json.num(jv, "required", 8) > Runtime.version().feature())
            throw new Api(400, "Minecraft " + mc + " needs Java " + jv.get("required") + " but this PC only has Java " + Runtime.version().feature());
        if (!Boolean.TRUE.equals(jv.get("hasServer"))) throw new Api(400, "Mojang published no server download for Minecraft " + mc);
    }

    static String install(Job j, Map<String, Object> req) throws Exception {
        validate(req);
        String name = String.valueOf(req.getOrDefault("name", "")).trim();
        String type = String.valueOf(req.get("type")), mc = String.valueOf(req.get("mc")), loader = String.valueOf(req.getOrDefault("loader", ""));
        int port = Json.num(req, "port", 0), minMb = Math.max(256, Json.num(req, "minMb", 1024)), maxMb = Json.num(req, "maxMb", 4096);
        Map<String, String> ws = worldSettings(req, name);
        Set<Integer> used = usedPorts();
        Path dir = targetDir(req);
        j.log("Creating " + type + " server \"" + name + "\" for Minecraft " + mc + (loader.isEmpty() ? "" : " with " + type + " " + loader));
        Files.createDirectories(dir);
        boolean registered = false;
        try {
            j.checkCancel();
            String startLine;
            String jvm = "-Xms" + minMb + "M -Xmx" + maxMb + "M";
            switch (type) {
                case "vanilla" -> {
                    vanillaJar(mc, dir.resolve("server.jar"), j);
                    startLine = "\"%MCM_JAVA%\" " + jvm + " -Djava.awt.headless=true -XX:+UseG1GC -jar server.jar nogui";
                }
                default -> startLine = installLoader(j, dir, type, mc, loader, jvm);
            }
            j.checkCancel();
            Files.writeString(dir.resolve("eula.txt"), "#By creating this server you accepted the Minecraft EULA (https://aka.ms/MinecraftEULA)\r\neula=true\r\n", StandardCharsets.ISO_8859_1);
            int rport = port + 1000;
            while (used.contains(rport) || rport == port || Main.sampler.pidForPort(rport) != null) rport++;
            Files.writeString(dir.resolve("server.properties"), String.join("\r\n",
                    "#Minecraft server properties (created by MC Server Manager)",
                    "server-port=" + port, "level-name=" + Srv.escape(ws.get("level-name")), "level-seed=" + Srv.escape(ws.get("level-seed")), "level-type=" + ws.get("level-type"),
                    "difficulty=" + ws.get("difficulty"), "gamemode=" + ws.get("gamemode"), "max-players=" + ws.get("max-players"), "spawn-protection=" + ws.get("spawn-protection"), "white-list=" + ws.get("white-list"), "enforce-whitelist=false",
                    "motd=" + Srv.escape(ws.get("motd")),
                    "enable-rcon=true", "rcon.port=" + rport, "rcon.password=" + randomPassword(), "broadcast-rcon-to-ops=false", "") , StandardCharsets.ISO_8859_1);
            if (ws.containsKey("biome")) {
                writeSingleBiomePack(dir.resolve(ws.get("level-name")), ws.get("biome"));
                j.log("Single-biome world: the whole overworld will be " + ws.get("biome") + " (a small data pack in the world folder does this).");
            }
            Files.writeString(dir.resolve("start.bat"), "@ECHO OFF\r\ncd /d \"%~dp0\"\r\nif not defined MCM_JAVA set \"MCM_JAVA=java\"\r\n" + startLine + "\r\n", StandardCharsets.ISO_8859_1);
            j.log("Wrote start.bat, eula.txt and server.properties (port " + port + ", RCON enabled for the manager).");

            Map<String, Object> entry = Json.map("id", slug(name), "name", name, "dir", dir.toString(), "task", "", "kind", type, "mc", mc,
                    "autostart", Boolean.TRUE.equals(req.get("autostart")), "autoUpdate", type.equals("fabric"));
            if (req.get("lanBroadcast") instanceof Boolean lan) entry.put("lanBroadcast", lan);
            if (req.get("autoRestart") instanceof Map<?, ?> ar) {
                try { entry.put("autoRestart", AutoRestart.clean(Json.obj(ar))); } catch (Api bad) { j.log("Automatic restart settings were not applied: " + bad.getMessage()); }
            }
            Srv s = Main.addServer(entry);
            registered = true;
            if (type.equals("fabric") && Boolean.TRUE.equals(req.get("pinMc"))) {
                s.setPinned(true);
                j.log("Minecraft version pinned: the updater will update Fabric and mods but keep Minecraft " + mc + ".");
            }
            j.log("Server added to the manager.");
            if (Boolean.TRUE.equals(req.get("geyser")) && List.of("fabric", "neoforge").contains(type)) {
                j.log("Installing Geyser and Floodgate so Bedrock players can join ...");
                try {
                    Modrinth.install(s, List.of("geyser", "floodgate"), j);
                    try { Geyser.seed(s, type); int[] gp = Ports.of(s); j.log("Geyser will use port " + gp[3] + " (TCP and UDP) and UDP " + gp[4] + " for Bedrock, and show this server's name and message of the day to Bedrock players."); }
                    catch (IOException e) { j.log("Could not prepare Geyser's settings: " + e.getMessage()); }
                    j.log("Geyser and Floodgate are installed. After the first start, open the Geyser tab to allow the Bedrock ports through the firewall and manage the whitelist.");
                } catch (Exception e) {
                    j.log("Could not install Geyser: " + e.getMessage() + " (the server itself was created; you can add Geyser later from the Mods tab).");
                }
            }
            if (Boolean.TRUE.equals(req.get("journeymap")) && List.of("fabric", "forge", "neoforge").contains(type)) {
                j.log("Installing JourneyMap (live map, waypoints and player radar) ...");
                try {
                    Modrinth.install(s, List.of("journeymap"), j);
                    j.log("JourneyMap is installed. Players need JourneyMap on their own computer to see the map; after the first start, open the JourneyMap tab to manage waypoints and map settings.");
                } catch (Exception e) {
                    j.log("Could not install JourneyMap: " + e.getMessage() + " (the server itself was created; you can add JourneyMap later from the Mods tab).");
                }
            }
            if (Boolean.TRUE.equals(req.get("startAfter"))) {
                j.log("Starting it for the first time (the world is generated now, this takes a minute)...");
                s.start(j);
            }
            return String.valueOf(entry.get("id"));
        } catch (Throwable t) {
            if (!registered) {
                j.log("Install failed; removing the partly created folder.");
                try (Stream<Path> w = Files.walk(dir)) {
                    w.sorted(Comparator.reverseOrder()).forEach(f -> { try { Files.delete(f); } catch (IOException ignored) { } });
                } catch (IOException ignored) { }
            }
            throw t;
        }
    }

    /** Runs the Fabric, Forge or NeoForge installer into a folder that already holds (or will get) the vanilla server, and returns the start script's java line. */
    static String installLoader(Job j, Path dir, String type, String mc, String loader, String jvm) throws Exception {
        Path cache = Main.DATA.resolve("cache").resolve("installers");
        String java = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
        if (type.equals("fabric")) {
            String inst = null;
            for (Object o : Json.arr(Json.parse(get(FABRIC + "/versions/installer")))) if (Boolean.TRUE.equals(Json.obj(o).get("stable"))) { inst = Json.str(o, "version"); break; }
            if (inst == null) throw new Api(502, "Could not find a Fabric installer");
            Path jar = cache.resolve("fabric-installer-" + inst + ".jar");
            String url = "https://maven.fabricmc.net/net/fabricmc/fabric-installer/" + inst + "/fabric-installer-" + inst + ".jar";
            if (!Files.exists(jar)) download(url, jar, mavenSha1(url), j);
            j.log("Running the Fabric installer (downloads Minecraft " + mc + ")...");
            runInstaller(j, dir, java, "-jar", jar.toString(), "server", "-dir", dir.toString(), "-mcversion", mc, "-loader", loader, "-downloadMinecraft");
            if (!Files.exists(dir.resolve("fabric-server-launch.jar")) || !Files.exists(dir.resolve("server.jar"))) throw new Api(500, "The Fabric installer did not produce the expected files");
            return "\"%MCM_JAVA%\" " + jvm + " -Djava.awt.headless=true -XX:+UseG1GC -jar fabric-server-launch.jar nogui";
        }
        boolean forge = type.equals("forge");
        String url = forge
                ? "https://maven.minecraftforge.net/net/minecraftforge/forge/" + mc + "-" + loader + "/forge-" + mc + "-" + loader + "-installer.jar"
                : "https://maven.neoforged.net/releases/net/neoforged/neoforge/" + loader + "/neoforge-" + loader + "-installer.jar";
        Path jar = cache.resolve(url.substring(url.lastIndexOf('/') + 1));
        if (!Files.exists(jar)) download(url, jar, mavenSha1(url), j);
        j.checkCancel();
        j.log("Running the " + (forge ? "Forge" : "NeoForge") + " installer (this downloads libraries and can take a few minutes)...");
        runInstaller(j, dir, java, "-jar", jar.toString(), "--installServer");
        String args = findWinArgs(dir);
        if (args == null) throw new Api(500, "The installer finished but no launch arguments were found (see the log above)");
        Files.writeString(dir.resolve("user_jvm_args.txt"), jvm + "\r\n-XX:+UseG1GC\r\n", StandardCharsets.ISO_8859_1);
        return "\"%MCM_JAVA%\" @user_jvm_args.txt @" + args + " nogui";
    }

    private static void runInstaller(Job j, Path dir, String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        Thread killer = Thread.startVirtualThread(() -> {
            try {
                if (!p.waitFor(20, java.util.concurrent.TimeUnit.MINUTES)) p.destroyForcibly();
            } catch (InterruptedException ignored) { }
        });
        int shown = 0;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String l;
            while ((l = r.readLine()) != null) {
                if (l.isBlank() || l.startsWith("WARNING") || l.trim().startsWith("Patching ") || l.trim().startsWith("Processing ")) continue;
                if (++shown <= 400) j.log("  " + (l.length() > 200 ? l.substring(0, 200) : l));
                j.checkCancel();
            }
        }
        int code = p.waitFor();
        killer.interrupt();
        if (code != 0) throw new Api(500, "The installer failed (exit code " + code + "); see the output above");
    }

    static String findWinArgs(Path dir) throws IOException {
        Path run = dir.resolve("run.bat");
        if (Files.exists(run)) {
            Matcher m = Pattern.compile("@(libraries\\S+win_args\\.txt)").matcher(Files.readString(run));
            if (m.find()) return m.group(1);
        }
        try (Stream<Path> w = Files.walk(dir.resolve("libraries"))) {
            return w.filter(f -> f.getFileName().toString().equals("win_args.txt")).findFirst().map(f -> dir.relativize(f).toString()).orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    static String slug(String name) {
        String base = name.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        if (base.isEmpty()) base = "server";
        String id = base;
        int i = 2;
        while (idTaken(id)) id = base + "-" + i++;
        return id;
    }

    private static boolean idTaken(String id) {
        for (Srv s : Main.servers) if (s.id.equals(id)) return true;
        return false;
    }

    private static String randomPassword() {
        String chars = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 24; i++) sb.append(chars.charAt(r.nextInt(chars.length())));
        return sb.toString();
    }
}
