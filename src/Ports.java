import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.Stream;

/** Knows every port the managed servers use (game, RCON, query, Geyser/Bedrock), finds free ones and reports clashes. */
final class Ports {
    private Ports() {}

    private static final Pattern SCRIPT_PORT = Pattern.compile("--port\\s+(\\d{2,5})");

    /** The Bedrock (Geyser) port from a server's Geyser config, or null. */
    static Integer bedrockPort(Path dir) {
        List<Path> cfgs = new ArrayList<>();
        for (String base : new String[] {"config", "plugins"}) {
            Path b = dir.resolve(base);
            if (!Files.isDirectory(b)) continue;
            try (Stream<Path> s = Files.list(b)) {
                s.filter(p -> p.getFileName().toString().toLowerCase().startsWith("geyser")).forEach(p -> cfgs.add(p.resolve("config.yml")));
            } catch (IOException ignored) { }
        }
        for (Path c : cfgs) {
            if (!Files.isRegularFile(c)) continue;
            try {
                boolean inBedrock = false;
                for (String line : Files.readAllLines(c, StandardCharsets.UTF_8)) {
                    if (line.matches("^bedrock:\\s*(#.*)?$")) { inBedrock = true; continue; }
                    if (!inBedrock) continue;
                    if (!line.isBlank() && !Character.isWhitespace(line.charAt(0)) && !line.startsWith("#")) { inBedrock = false; continue; }
                    Matcher m = Pattern.compile("^\\s+port:\\s*(\\d+)").matcher(line);
                    if (m.find()) return Integer.parseInt(m.group(1));
                }
            } catch (IOException | NumberFormatException ignored) { }
        }
        return null;
    }

    /** A "--port N" in a start script overrides server.properties, so it is the port players actually use. */
    static Integer scriptPort(Path dir, String script) {
        if (script == null) return null;
        try {
            Path f = dir.resolve(script);
            if (!Files.isRegularFile(f) || Files.size(f) > 100_000) return null;
            Matcher m = SCRIPT_PORT.matcher(Files.readString(f, StandardCharsets.ISO_8859_1));
            return m.find() ? Integer.parseInt(m.group(1)) : null;
        } catch (IOException | NumberFormatException e) {
            return null;
        }
    }

    private static int parse(String v, int def) {
        try { return Integer.parseInt(v.trim()); } catch (RuntimeException e) { return def; }
    }

    /** TCP and UDP ports one server uses: {game, rcon, query, bedrock} (-1 = not used). */
    static int[] of(Srv s) {
        Map<String, String> p = s.props();
        boolean rcon = "true".equalsIgnoreCase(p.get("enable-rcon")) || p.containsKey("rcon.port") && "true".equalsIgnoreCase(p.get("enable-rcon"));
        int rport = rcon ? parse(p.getOrDefault("rcon.port", "25575"), 25575) : -1;
        int query = "true".equalsIgnoreCase(p.get("enable-query")) ? parse(p.getOrDefault("query.port", "25565"), 25565) : -1;
        Integer b = bedrockPort(s.dir);
        if (b != null && GeyserCfg.cloneRemotePort(s.dir)) b = s.gamePort(); // Geyser reuses the Java port number for Bedrock (UDP)
        int web = -1;
        Path gc = GeyserCfg.find(s.dir, "geyser");
        if (gc != null) {
            try {
                List<String> gl = GeyserCfg.read(gc);
                String t = GeyserCfg.get(gl, "bedrock", "transport");
                int w = parse(String.valueOf(GeyserCfg.get(gl, "bedrock", "webrtc-port")), 0);
                if (t != null && !t.equalsIgnoreCase("raknet") && w > 0) web = w;
            } catch (IOException ignored) { }
        }
        return new int[] {s.gamePort(), rport, query, b == null ? -1 : b, web};
    }

    /** All ports used by managed servers other than {@code except}. */
    static Set<Integer> allUsed(Srv except) {
        Set<Integer> used = new HashSet<>();
        for (Srv s : Main.servers) {
            if (s == except) continue;
            for (int x : of(s)) if (x > 0) used.add(x);
        }
        return used;
    }

    static int freeRconPort(Srv s, Set<Integer> alsoUsed) {
        Set<Integer> used = allUsed(s);
        if (alsoUsed != null) used.addAll(alsoUsed);
        int game = s.gamePort();
        int port = game + 1000 > 65000 ? 25575 : game + 1000;
        while (used.contains(port) || port == game || Main.sampler.pidForPort(port) != null) port++;
        return port;
    }

    private static String label(int idx) {
        return switch (idx) { case 0 -> "game"; case 1 -> "RCON"; case 2 -> "query"; case 3 -> "Bedrock"; default -> "NetherNet"; };
    }

    /** Per-server ports plus a list of clashes (same protocol, same port, different owner). */
    static Map<String, Object> overview() {
        Main.sampler.refresh(false);
        List<Object> rows = new ArrayList<>();
        Map<String, List<String>> owners = new LinkedHashMap<>();
        for (Srv s : Main.servers) {
            int[] p = of(s);
            rows.add(Json.map("id", s.id, "name", s.name, "game", p[0], "rcon", p[1], "query", p[2], "bedrock", p[3], "running", s.running(false)));
            for (int i = 0; i < 5; i++) {
                if (p[i] <= 0) continue;
                String proto = (i == 2 || i >= 3) ? "UDP" : "TCP";
                owners.computeIfAbsent(proto + ":" + p[i], k -> new ArrayList<>()).add(s.name + " " + label(i));
            }
        }
        List<Object> clashes = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : owners.entrySet()) {
            if (e.getValue().size() > 1) {
                String[] k = e.getKey().split(":");
                clashes.add(Json.map("proto", k[0], "port", Integer.parseInt(k[1]), "who", e.getValue(), "rcon", e.getValue().stream().anyMatch(x -> x.endsWith(" RCON"))));
            }
        }
        return Json.map("servers", rows, "clashes", clashes);
    }

    /** Gives every server whose RCON port clashes with another server's TCP port a free one. RCON is only used by the manager, so this is safe. */
    static List<Object> fixRcon() throws IOException {
        List<Object> changes = new ArrayList<>();
        Set<Integer> assigned = new HashSet<>();
        for (Srv s : Main.servers) {
            int[] p = of(s);
            if (p[1] <= 0) continue;
            Set<Integer> others = allUsed(s);
            if (!others.contains(p[1]) && p[1] != p[0] && !assigned.contains(p[1])) {
                assigned.add(p[1]);
                continue;
            }
            int np = freeRconPort(s, assigned);
            assigned.add(np);
            s.writeProps(new LinkedHashMap<>(Map.of("rcon.port", Integer.toString(np))));
            s.rcon.close();
            changes.add(Json.map("name", s.name, "from", p[1], "to", np, "running", s.running(false)));
        }
        return changes;
    }
}
