import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Finds Geyser's and Floodgate's config files and reads or edits single settings in them without disturbing the rest of the file. */
final class GeyserCfg {
    private GeyserCfg() { }

    /** config.yml inside a config/ or plugins/ folder whose name starts with the given text (e.g. "geyser" -> config/Geyser-Fabric). */
    static Path find(Path serverDir, String prefix) {
        for (String base : new String[]{"config", "plugins"}) {
            Path b = serverDir.resolve(base);
            if (!Files.isDirectory(b)) continue;
            try (Stream<Path> s = Files.list(b)) {
                for (Path p : s.sorted().toList()) {
                    if (p.getFileName().toString().toLowerCase().startsWith(prefix) && Files.isRegularFile(p.resolve("config.yml"))) return p.resolve("config.yml");
                }
            } catch (IOException ignored) { }
        }
        return null;
    }

    static List<String> read(Path p) throws IOException {
        return new ArrayList<>(Files.readAllLines(p, StandardCharsets.UTF_8));
    }

    /** Index range [start, end) of the lines inside a top-level section, or null. */
    private static int[] section(List<String> lines, String name) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).matches("^" + Pattern.quote(name) + ":\\s*(#.*)?$")) {
                int end = lines.size();
                for (int j = i + 1; j < lines.size(); j++) {
                    String l = lines.get(j);
                    if (!l.isBlank() && !Character.isWhitespace(l.charAt(0)) && !l.startsWith("#")) { end = j; break; }
                }
                return new int[]{i + 1, end};
            }
        }
        return null;
    }

    /** Line index of a direct child key of a top-level section, or -1. */
    private static int keyLine(List<String> lines, String sec, String key) {
        int[] r = section(lines, sec);
        if (r == null) return -1;
        int childIndent = -1;
        for (int i = r[0]; i < r[1]; i++) {
            String l = lines.get(i);
            if (l.isBlank() || l.stripLeading().startsWith("#")) continue;
            int ind = l.length() - l.stripLeading().length();
            if (childIndent < 0) childIndent = ind;
            if (ind == childIndent && l.stripLeading().matches("^" + Pattern.quote(key) + ":(\\s.*|)$")) return i;
        }
        return -1;
    }

    static String get(List<String> lines, String sec, String key) {
        int i = keyLine(lines, sec, key);
        if (i < 0) return null;
        String v = lines.get(i).substring(lines.get(i).indexOf(':') + 1).strip();
        if (v.startsWith("\"")) {
            int end = v.indexOf('"', 1);
            while (end > 0 && v.charAt(end - 1) == '\\') end = v.indexOf('"', end + 1);
            return end > 0 ? v.substring(1, end).replace("\\\"", "\"").replace("\\\\", "\\") : v.substring(1);
        }
        if (v.startsWith("'")) {
            int end = v.indexOf('\'', 1);
            while (end > 0 && end + 1 < v.length() && v.charAt(end + 1) == '\'') end = v.indexOf('\'', end + 2);
            return end > 0 ? v.substring(1, end).replace("''", "'") : v.substring(1);
        }
        int hash = v.indexOf(" #");
        return (hash >= 0 ? v.substring(0, hash) : v).strip();
    }

    /** Replaces the value of a key (keeps indentation and any trailing comment). Returns false when the key does not exist. */
    static boolean set(List<String> lines, String sec, String key, String value, boolean quote) {
        int i = keyLine(lines, sec, key);
        if (i < 0) return false;
        String l = lines.get(i);
        int ind = l.length() - l.stripLeading().length();
        String old = l.substring(l.indexOf(':') + 1);
        String comment = "";
        Matcher m = Pattern.compile("\\s+#.*$").matcher(old.strip().startsWith("\"") || old.strip().startsWith("'") ? old.substring(Math.max(0, old.lastIndexOf(old.strip().charAt(0)) + 1)) : old);
        if (m.find()) comment = m.group();
        String v = quote ? "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" : value;
        lines.set(i, l.substring(0, ind) + key + ": " + v + comment);
        return true;
    }

    static void write(Path p, List<String> lines) throws IOException {
        Path bak = p.resolveSibling(p.getFileName() + ".mcm-bak");
        if (!Files.exists(bak)) Files.copy(p, bak);
        Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
        Files.writeString(tmp, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        Files.move(tmp, p, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    /** True when Geyser is told to reuse the Java port number for Bedrock (then the Bedrock UDP port equals the Java port). */
    static boolean cloneRemotePort(Path serverDir) {
        Path c = find(serverDir, "geyser");
        if (c == null) return false;
        try { return "true".equalsIgnoreCase(get(read(c), "bedrock", "clone-remote-port")); } catch (IOException e) { return false; }
    }
}
