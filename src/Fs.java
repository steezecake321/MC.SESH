import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Server-side folder browsing for the web UI's folder picker (the browser cannot see this PC's folders itself). */
final class Fs {
    private Fs() {}

    private static final Pattern NAME_OK = Pattern.compile("^[^\\\\/:*?\"<>|]{1,60}$");
    private static final Pattern RESERVED = Pattern.compile("(?i)^(con|prn|aux|nul|com[1-9]|lpt[1-9])(\\..*)?$");

    private static List<Object> shortcuts() {
        List<Object> l = new ArrayList<>();
        Path home = Path.of(System.getProperty("user.home"));
        l.add(Json.map("name", "Servers folder", "path", Main.serversRoot().toString()));
        for (String s : new String[] {"Desktop", "Documents", "Downloads"}) if (Files.isDirectory(home.resolve(s))) l.add(Json.map("name", s, "path", home.resolve(s).toString()));
        l.add(Json.map("name", "Home", "path", home.toString()));
        l.add(Json.map("name", "App folder", "path", Main.HOME.toString()));
        return l;
    }

    static Map<String, Object> list(String path, String filesExt) {
        List<Object> dirs = new ArrayList<>();
        List<Object> files = new ArrayList<>();
        boolean packs = "pack".equals(filesExt);
        boolean archives = "archive".equals(filesExt);
        String ext = archives || filesExt == null || !filesExt.matches("[A-Za-z0-9]{1,8}") ? null : "." + filesExt.toLowerCase();
        if (path == null || path.isBlank()) {
            for (Path r : FileSystems.getDefault().getRootDirectories()) {
                if (!Files.isDirectory(r)) continue;
                long free = 0;
                try { free = Files.getFileStore(r).getUsableSpace() >> 30; } catch (IOException ignored) { }
                dirs.add(Json.map("name", r.toString() + (free > 0 ? "  (" + free + " GB free)" : ""), "path", r.toString(), "server", false));
            }
            return Json.map("path", "", "parent", null, "dirs", dirs, "shortcuts", shortcuts(), "writable", false);
        }
        Path p = Path.of(path.trim());
        if (!p.isAbsolute()) throw new Api(400, "Use a full path such as D:\\Minecraft");
        p = p.normalize();
        if (!Files.isDirectory(p)) throw new Api(404, "That folder does not exist");
        try (Stream<Path> s = Files.list(p)) {
            int n = 0;
            for (Path c : s.sorted(Comparator.comparing(x -> x.getFileName().toString().toLowerCase())).toList()) {
                String name = c.getFileName().toString();
                String low = name.toLowerCase();
                if ((ext != null || archives || packs) && Files.isRegularFile(c) && (packs ? (low.endsWith(".zip") || low.endsWith(".mrpack")) : archives ? Archives.matches(low) : low.endsWith(ext)) && files.size() < 500) { files.add(Json.map("name", name, "path", c.toString())); continue; }
                if (!Files.isDirectory(c) || low.equals("$recycle.bin") || low.equals("system volume information")) continue;
                boolean server = Files.exists(c.resolve("server.properties")) || Files.exists(c.resolve("eula.txt"));
                dirs.add(Json.map("name", name, "path", c.toString(), "server", server));
                if (++n >= 2000) break;
            }
        } catch (IOException | java.io.UncheckedIOException e) {
            throw new Api(403, "Cannot read that folder: " + e.getMessage());
        }
        Path parent = p.getParent();
        return Json.map("path", p.toString(), "parent", parent == null ? "" : parent.toString(), "dirs", dirs, "files", files, "shortcuts", shortcuts(),
                "writable", Files.isWritable(p), "isServer", Files.exists(p.resolve("server.properties")));
    }

    static Map<String, Object> mkdir(String parent, String name) throws IOException {
        if (parent == null || !Path.of(parent).isAbsolute()) throw new Api(400, "Bad location");
        if (name == null || !NAME_OK.matcher(name.trim()).matches() || name.trim().endsWith(".") || RESERVED.matcher(name.trim()).matches()) throw new Api(400, "That is not a valid folder name");
        Path p = Path.of(parent).normalize().resolve(name.trim()).normalize();
        if (!p.getParent().equals(Path.of(parent).normalize())) throw new Api(400, "Bad folder name");
        if (!Files.isDirectory(p.getParent())) throw new Api(404, "The parent folder does not exist");
        Files.createDirectories(p);
        return Json.map("path", p.toString());
    }
}
