import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

final class Config {
    private final Path file;
    final Map<String, Object> root;

    private Config(Path file, Map<String, Object> root) {
        this.file = file;
        this.root = root;
    }

    static Config load(Path file) throws IOException {
        if (Files.exists(file)) {
            return new Config(file, Json.obj(Json.parse(Files.readString(file, StandardCharsets.UTF_8))));
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("bind", "0.0.0.0");
        r.put("port", Integer.getInteger("mgr.port", 8585));
        r.put("servers", new ArrayList<Object>());
        Config c = new Config(file, r);
        c.save();
        return c;
    }

    /** User-chosen locations (servers folder, Java override...). */
    synchronized Map<String, Object> paths() {
        Object p = root.get("paths");
        if (!(p instanceof Map)) {
            p = new LinkedHashMap<String, Object>();
            root.put("paths", p);
        }
        return Json.obj(p);
    }

    synchronized void save() throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, Json.stringify(root), StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    String bind() { return String.valueOf(root.getOrDefault("bind", "0.0.0.0")); }

    int port() { return Json.num(root, "port", 8585); }

    /** The updater script: an explicit config entry (legacy installs) or the one shipped in the app's tools folder. */
    Path updater() {
        String u = Json.str(root, "updater");
        if (u != null && !u.isBlank() && Files.exists(Path.of(u))) return Path.of(u);
        return Main.TOOLS.resolve("Update-FabricServer.ps1");
    }

    List<Map<String, Object>> servers() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : Json.arr(root.get("servers"))) out.add(Json.obj(o));
        return out;
    }

    @SuppressWarnings("unchecked")
    synchronized void addServer(Map<String, Object> entry) throws IOException {
        Object l = root.get("servers");
        if (!(l instanceof List)) {
            l = new ArrayList<Object>();
            root.put("servers", l);
        }
        ((List<Object>) l).add(entry);
        save();
    }

    synchronized void removeServer(String id) throws IOException {
        Object l = root.get("servers");
        if (l instanceof List<?> list) list.removeIf(o -> id.equals(Json.str(o, "id")));
        save();
    }

    synchronized Map<String, Object> auth() {
        Object a = root.get("auth");
        if (!(a instanceof Map)) {
            a = new LinkedHashMap<String, Object>();
            root.put("auth", a);
        }
        return Json.obj(a);
    }
}
