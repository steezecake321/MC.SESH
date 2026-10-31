import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.*;

/**
 * Builds the in-game command tree + registries for a Minecraft version by running the server jar's own data generator
 * (once per version, ~10 s) and caches the result as gzip JSON.
 */
final class Autocomplete {
    private Autocomplete() {}

    private static final Map<String, byte[]> MEM = new HashMap<>();
    private static final Set<String> STATIC_KEEP = Set.of("item", "block", "entity_type", "particle_type", "mob_effect", "sound_event",
            "attribute", "potion", "game_rule", "custom_stat", "villager_profession", "trigger_type", "fluid");
    private static final List<String> DYNAMIC_ALWAYS = List.of("enchantment", "worldgen/biome", "damage_type", "dimension", "loot_table",
            "predicate", "item_modifier", "advancement", "recipe", "worldgen/structure", "timeline", "world_clock");

    static synchronized byte[] get(Srv s) throws Exception {
        String ver = s.mcVersion();
        if (ver == null) throw new Api(500, "Could not determine the Minecraft version of " + s.name);
        byte[] b = MEM.get(ver);
        if (b != null) return b;
        Path cache = Main.DATA.resolve("cache").resolve(ver);
        Path gz = cache.resolve("ac.json.gz");
        if (!Files.exists(gz)) build(s, ver, cache, gz);
        b = Files.readAllBytes(gz);
        MEM.put(ver, b);
        return b;
    }

    private static void build(Srv s, String ver, Path cache, Path gz) throws Exception {
        Path work = cache.resolve("work");
        deleteTree(work);
        Files.createDirectories(work);
        try {
            Path serverJar = s.dir.resolve("server.jar");
            if (!Files.exists(serverJar)) {
                serverJar = cache.resolve("vanilla-" + ver + ".jar");
                if (!Files.exists(serverJar)) Installer.vanillaJar(ver, serverJar, null);
            }
            String java = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
            Process p = new ProcessBuilder(java, "-Xmx768m", "-DbundlerMainClass=net.minecraft.data.Main", "-jar",
                    serverJar.toString(), "--reports", "--output", "out")
                    .directory(work.toFile()).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (!p.waitFor(180, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new Api(500, "Generating command data timed out");
            }
            Path rep = work.resolve("out").resolve("reports");
            if (!Files.exists(rep.resolve("commands.json"))) throw new Api(500, "The server jar did not produce command data");
            Map<String, Object> tree = Json.obj(Json.parse(Files.readString(rep.resolve("commands.json"), StandardCharsets.UTF_8)));
            Map<String, Object> reg = Json.obj(Json.parse(Files.readString(rep.resolve("registries.json"), StandardCharsets.UTF_8)));

            Set<String> wanted = new TreeSet<>(STATIC_KEEP);
            collectRegistries(tree, wanted);
            Map<String, Object> regs = new LinkedHashMap<>();
            Set<String> missing = new TreeSet<>(DYNAMIC_ALWAYS);
            for (String w : wanted) {
                Object e = Json.obj(reg.get("minecraft:" + w)).get("entries");
                if (e instanceof Map<?, ?> m) regs.put(w, strip(m.keySet()));
                else missing.add(w);
            }
            Path inner = work.resolve("versions").resolve(ver).resolve("server-" + ver + ".jar");
            if (!Files.exists(inner)) inner = s.dir.resolve("versions").resolve(ver).resolve("server-" + ver + ".jar");
            if (Files.exists(inner)) regs.putAll(scanJar(inner, missing));

            Files.createDirectories(cache);
            try (Writer w = new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(gz)), StandardCharsets.UTF_8)) {
                w.write(Json.stringify(Json.map("version", ver, "tree", tree, "registries", regs)));
            }
        } finally {
            deleteTree(work);
        }
    }

    private static List<String> strip(Collection<?> keys) {
        List<String> out = new ArrayList<>(keys.size());
        for (Object k : keys) out.add(String.valueOf(k).replaceFirst("^minecraft:", ""));
        Collections.sort(out);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void collectRegistries(Object node, Set<String> out) {
        if (!(node instanceof Map)) return;
        Map<String, Object> m = (Map<String, Object>) node;
        String r = Json.str(m.get("properties"), "registry");
        if (r != null) out.add(r.replaceFirst("^minecraft:", ""));
        Object ch = m.get("children");
        if (ch instanceof Map<?, ?> cm) for (Object c : cm.values()) collectRegistries(c, out);
    }

    private static Map<String, Object> scanJar(Path jar, Set<String> names) throws IOException {
        Map<String, List<String>> found = new TreeMap<>();
        Map<String, String> prefixes = new HashMap<>();
        for (String n : names) prefixes.put("data/minecraft/" + n + "/", n);
        try (ZipFile z = new ZipFile(jar.toFile())) {
            for (Enumeration<? extends ZipEntry> en = z.entries(); en.hasMoreElements(); ) {
                String e = en.nextElement().getName();
                if (!e.endsWith(".json") || !e.startsWith("data/minecraft/")) continue;
                for (Map.Entry<String, String> p : prefixes.entrySet()) {
                    if (e.startsWith(p.getKey())) {
                        found.computeIfAbsent(p.getValue(), k -> new ArrayList<>()).add(e.substring(p.getKey().length(), e.length() - 5));
                        break;
                    }
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : found.entrySet()) {
            Collections.sort(e.getValue());
            out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    private static void deleteTree(Path p) {
        if (!Files.exists(p)) return;
        try (Stream<Path> s = Files.walk(p)) {
            s.sorted(Comparator.reverseOrder()).forEach(f -> {
                try { Files.delete(f); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }
}
