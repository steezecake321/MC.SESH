import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.Stream;

/** Finds installed Java runtimes and picks a suitable one for a server (older modpacks need older Java). */
final class JavaRuntimes {
    private JavaRuntimes() {}

    private static List<Map<String, Object>> cache;
    private static long cacheAt;

    static synchronized List<Map<String, Object>> detect(boolean force) {
        if (!force && cache != null && System.currentTimeMillis() - cacheAt < 300_000) return cache;
        Map<String, Map<String, Object>> found = new LinkedHashMap<>();
        List<Path> candidates = new ArrayList<>();
        candidates.add(Main.RUNTIME.resolve("bin").resolve("java.exe"));
        String jh = System.getenv("JAVA_HOME");
        if (jh != null) candidates.add(Path.of(jh, "bin", "java.exe"));
        String pf = System.getenv("ProgramFiles"), pf86 = System.getenv("ProgramFiles(x86)"), home = System.getProperty("user.home");
        String appData = System.getenv("APPDATA");
        for (String base : new String[] {pf, pf86}) {
            if (base == null) continue;
            for (String vendor : new String[] {"Java", "Eclipse Adoptium", "Eclipse Foundation", "Microsoft", "Zulu", "Amazon Corretto", "BellSoft", "Semeru", "AdoptOpenJDK", "OpenJDK"}) {
                addChildren(candidates, Path.of(base, vendor), 1);
            }
        }
        addChildren(candidates, Path.of(home, ".jdks"), 1);
        if (appData != null) addChildren(candidates, Path.of(appData, ".minecraft", "runtime"), 3);
        for (String line : Sys.run(5000, "where", "java").out().split("\\R")) if (line.trim().toLowerCase().endsWith("java.exe")) candidates.add(Path.of(line.trim()));
        try (Stream<Path> w = Files.walk(Path.of(home, "curseforge"), 8)) {
            w.filter(p -> p.getFileName().toString().equals("java.exe") && p.getParent().getFileName().toString().equals("bin")).limit(10).forEach(candidates::add);
        } catch (IOException | RuntimeException ignored) { }
        for (Path c : candidates) {
            try {
                if (!Files.isRegularFile(c)) continue;
                String low = c.toString().toLowerCase();
                if (low.contains("\\javapath\\") || low.contains("\\java8path\\") || low.contains("\\javacommon\\")) continue;
                String key = c.toRealPath().toString().toLowerCase();
                if (found.containsKey(key)) continue;
                int major = majorOf(c);
                if (major <= 0) continue;
                boolean bundled = c.startsWith(Main.RUNTIME);
                found.put(key, Json.map("path", c.toString(), "major", major, "label", "Java " + major + (bundled ? " (bundled)" : "") + " - " + c.getParent().getParent().getFileName()));
            } catch (IOException | RuntimeException ignored) { }
        }
        List<Map<String, Object>> out = new ArrayList<>(found.values());
        out.sort((a, b) -> Integer.compare((Integer) b.get("major"), (Integer) a.get("major")));
        cache = out;
        cacheAt = System.currentTimeMillis();
        return out;
    }

    private static void addChildren(List<Path> out, Path dir, int depth) {
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> w = Files.walk(dir, depth + 3)) {
            w.filter(p -> p.getFileName().toString().equals("java.exe") && p.getParent() != null && p.getParent().getFileName().toString().equals("bin")).limit(40).forEach(out::add);
        } catch (IOException | RuntimeException ignored) { }
    }

    /** Reads the runtime's release file (fast) and falls back to running "java -version". */
    private static int majorOf(Path javaExe) {
        Path rel = javaExe.getParent().getParent().resolve("release");
        try {
            if (Files.exists(rel)) {
                Matcher m = Pattern.compile("JAVA_VERSION=\"([^\"]+)\"").matcher(Files.readString(rel, StandardCharsets.ISO_8859_1));
                if (m.find()) return parseMajor(m.group(1));
            }
        } catch (IOException ignored) { }
        Matcher m = Pattern.compile("version \"([^\"]+)\"").matcher(Sys.run(8000, javaExe.toString(), "-version").out());
        return m.find() ? parseMajor(m.group(1)) : 0;
    }

    private static int parseMajor(String v) {
        try {
            if (v.startsWith("1.")) return Integer.parseInt(v.substring(2).replaceAll("\\D.*$", ""));
            return Integer.parseInt(v.replaceAll("\\D.*$", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Minimum Java major version a Minecraft version needs. */
    static int requiredFor(String mc) {
        if (mc == null) return 8;
        Matcher m = Pattern.compile("^(\\d+)\\.(\\d+)(?:\\.(\\d+))?").matcher(mc);
        if (!m.find()) return 8;
        int a = Integer.parseInt(m.group(1)), b = Integer.parseInt(m.group(2));
        int c = m.group(3) == null ? 0 : Integer.parseInt(m.group(3));
        if (a >= 26) return 25;
        if (a == 1) {
            if (b > 20 || (b == 20 && c >= 5)) return 21;
            if (b >= 18) return 17;
            if (b == 17) return 16;
        }
        return 8;
    }

    /** The java.exe to use for a server: its explicit setting, else the best detected match, else whatever "java" resolves to. */
    static String javaFor(Srv s) {
        String explicit = Json.str(s.cfg, "java");
        if (explicit != null && !explicit.isBlank() && Files.exists(Path.of(explicit))) return explicit;
        int req = requiredFor(s.mcVersion());
        List<Map<String, Object>> all = detect(false);
        String best = null;
        int bestMajor = Integer.MAX_VALUE;
        for (Map<String, Object> r : all) {
            int mj = (Integer) r.get("major");
            if (mj >= req && mj < bestMajor) {
                bestMajor = mj;
                best = (String) r.get("path");
            }
        }
        if (best == null && !all.isEmpty()) best = (String) all.get(0).get("path");
        return best != null ? best : "java";
    }
}
