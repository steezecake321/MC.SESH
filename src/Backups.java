import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Backups and restores of a server: a zip of the world only, or of the whole server folder, with a manifest inside so a zip describes itself. */
final class Backups {
    private Backups() { }

    static final String MANIFEST = "mcm-backup.json";
    /** Top-level folders that are never backed up (and never deleted by a restore): logs and caches that the server recreates. */
    private static final Set<String> SKIP_TOP = Set.of("logs", "crash-reports", "heap-dumps", ".fabric", "cache", "backups", "debug");
    private static final Set<String> NOT_A_WORLD = Set.of("mods", "config", "libraries", "versions", "logs", "plugins", "crash-reports", "mods-removed", "updater-backups");
    private static final Pattern FILE_OK = Pattern.compile("[A-Za-z0-9 _.()\\-]{1,160}\\.zip");

    // ---------- locations and settings ----------

    static Path dirFor(Srv s) {
        String d = Json.str(s.cfg, "backupDir");
        return d == null || d.isBlank() ? Main.backupsRoot().resolve(s.id) : Path.of(d);
    }

    static int keep(Srv s) {
        return s.cfg.get("backupKeep") instanceof Number n ? Math.max(0, Math.min(1000, n.intValue())) : 0;
    }

    static void setSettings(Srv s, Map<String, Object> b) throws IOException {
        if (b.containsKey("keep")) {
            int k = b.get("keep") instanceof Number n ? n.intValue() : -1;
            if (k < 0 || k > 1000) throw new Api(400, "Keep between 1 and 1000 backups, or 0 to keep them all.");
            if (k == 0) s.cfg.remove("backupKeep"); else s.cfg.put("backupKeep", k);
        }
        if (b.containsKey("dir")) {
            String d = Json.str(b, "dir");
            if (d == null || d.isBlank()) s.cfg.remove("backupDir");
            else {
                Path p = Path.of(d.trim());
                if (!p.isAbsolute()) throw new Api(400, "Use a full folder path such as D:\\Minecraft backups.");
                p = p.normalize();
                if (p.startsWith(s.dir.toAbsolutePath().normalize())) throw new Api(400, "Backups cannot be kept inside the server's own folder (they would be backed up into themselves).");
                if (Files.exists(p) && !Files.isDirectory(p)) throw new Api(400, "That path is a file, not a folder.");
                Files.createDirectories(p);
                if (!Files.isWritable(p)) throw new Api(400, "Cannot write to that folder.");
                s.cfg.put("backupDir", p.toString());
            }
        }
        Main.cfg.save();
    }

    static long freeMb(Path dir) {
        Path p = dir.toAbsolutePath();
        while (p != null && !Files.exists(p)) p = p.getParent();
        try { return p == null ? 0 : Files.getFileStore(p).getUsableSpace() >> 20; } catch (IOException e) { return 0; }
    }

    // ---------- what gets backed up ----------

    static List<Path> worldDirs(Srv s) {
        List<Path> out = new ArrayList<>();
        String w = s.worldName();
        for (String n : new String[]{w, w + "_nether", w + "_the_end"}) {
            Path p = s.dir.resolve(n).normalize();
            if (p.getParent() != null && p.getParent().equals(s.dir.normalize()) && Files.isDirectory(p)) out.add(p);
        }
        return out;
    }

    private static boolean skipFile(Path root, Path p, Path exclude) {
        String n = p.getFileName().toString();
        if (n.equals("session.lock") || n.endsWith(".part")) return true;
        if (exclude != null && p.startsWith(exclude)) return true;
        return p.getParent() != null && p.getParent().equals(root) && n.toLowerCase().startsWith("hs_err_pid");
    }

    private static List<Path> collect(Srv s, String scope, Path exclude) throws IOException {
        Path root = s.dir.toAbsolutePath().normalize();
        List<Path> out = new ArrayList<>();
        if (scope.equals("world")) {
            for (Path w : worldDirs(s)) {
                try (Stream<Path> walk = Files.walk(w)) {
                    for (Path p : walk.toList()) if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS) && !skipFile(root, p, exclude)) out.add(p);
                }
            }
            return out;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes a) {
                if (d.equals(root)) return FileVisitResult.CONTINUE;
                if (d.getParent().equals(root) && SKIP_TOP.contains(d.getFileName().toString().toLowerCase())) return FileVisitResult.SKIP_SUBTREE;
                if (exclude != null && d.startsWith(exclude)) return FileVisitResult.SKIP_SUBTREE;
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path f, BasicFileAttributes a) {
                if (a.isRegularFile() && !skipFile(root, f, exclude)) out.add(f);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path f, IOException e) { return FileVisitResult.CONTINUE; }
        });
        return out;
    }

    private static long size(List<Path> files) {
        long t = 0;
        for (Path p : files) { try { t += Files.size(p); } catch (IOException ignored) { } }
        return t;
    }

    /** Sizes shown in the dialog so the user can see what each choice would save. */
    static Map<String, Object> sizes(Srv s) {
        long world = 0, full = 0;
        try { world = size(collect(s, "world", null)); full = size(collect(s, "full", dirFor(s).toAbsolutePath().normalize())); } catch (IOException ignored) { }
        return Json.map("worldMb", world >> 20, "fullMb", full >> 20);
    }

    // ---------- creating ----------

    private static String stamp() { return new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()); }

    /** Writes a backup zip into the folder and returns its path. A running server needs RCON so saving can be paused while the files are read. */
    static Path create(Job j, Srv s, String scope, Path dir, String note, String reason, String actor) throws Exception {
        if (!scope.equals("world") && !scope.equals("full")) throw new Api(400, "Unknown backup type");
        boolean live = s.running(true);
        if (live && !s.rconEnabled()) throw new Api(409, "The server is running and RCON is off, so the manager cannot pause saving to take a safe copy. Stop the server first (or turn RCON on in Properties and restart it).");
        if (scope.equals("world") && !live && worldDirs(s).isEmpty()) throw new Api(409, "There is no world folder yet. Start the server once so it creates the world.");
        Files.createDirectories(dir);
        Path exclude = dir.toAbsolutePath().normalize();
        String safe = s.name.replaceAll("[^A-Za-z0-9 _.-]", "_");
        Path zip = dir.resolve(safe + "-" + scope + "-" + stamp() + ".zip");
        for (int i = 2; Files.exists(zip); i++) zip = dir.resolve(safe + "-" + scope + "-" + stamp() + "-" + i + ".zip");
        Path tmp = zip.resolveSibling(zip.getFileName() + ".part");
        boolean paused = false;
        List<String> skipped = new ArrayList<>();
        try {
            if (live) {
                j.log("The server is running: pausing world saving while the files are copied (players stay connected).");
                s.command("save-off");
                paused = true;
                try { s.command("save-all flush"); } catch (Api e) { j.log("  the save command did not answer in time (" + e.getMessage() + "); waiting a little instead"); }
                j.sleep(2500);
            }
            List<Path> files = collect(s, scope, exclude);
            if (files.isEmpty()) throw new Api(409, "There is nothing to back up.");
            long total = size(files);
            long free = Files.getFileStore(dir).getUsableSpace();
            if (free < (long) (total * 0.7) + (64L << 20)) throw new Api(409, "Not enough free space for the backup: it needs about " + (((long) (total * 0.7) + (64L << 20)) >> 20) + " MB and the drive has " + (free >> 20) + " MB free.");
            j.log("Backing up " + (scope.equals("world") ? "the world" : "the whole server") + " (" + files.size() + " files, " + (total >> 20) + " MB) to " + zip + " ...");
            Path root = s.dir.toAbsolutePath().normalize();
            long done = 0;
            int lastPct = 0;
            try (ZipOutputStream out = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp), 1 << 16))) {
                out.setLevel(6);
                byte[] buf = new byte[1 << 16];
                for (Path p : files) {
                    j.checkCancel();
                    String entry = root.relativize(p).toString().replace('\\', '/');
                    InputStream in;
                    try { in = Files.newInputStream(p); } catch (IOException e) { skipped.add(entry); j.log("  skipped " + entry + ": " + e.getMessage()); continue; }
                    try (in) {
                        ZipEntry ze = new ZipEntry(entry);
                        try { ze.setTime(Files.getLastModifiedTime(p).toMillis()); } catch (IOException ignored) { }
                        out.putNextEntry(ze);
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            out.write(buf, 0, n);
                            done += n;
                        }
                        out.closeEntry();
                    }
                    int pct = total == 0 ? 100 : (int) (done * 100 / total);
                    if (pct >= lastPct + 20 && pct < 100) { lastPct = pct / 20 * 20; j.log("  " + lastPct + "% ..."); }
                }
                Map<String, Object> man = Json.map("app", "MC Server Manager", "format", 1, "serverId", s.id, "serverName", s.name, "scope", scope, "created", System.currentTimeMillis(),
                        "note", note == null ? "" : note, "reason", reason, "by", actor == null ? "" : actor, "mc", s.mcVersion(), "kind", s.kind(), "world", s.worldName(),
                        "script", s.script(), "autoUpdate", s.autoUpdate(), "pinned", s.pinned(), "skipped", skipped, "files", files.size() - skipped.size());
                out.putNextEntry(new ZipEntry(MANIFEST));
                out.write(Json.stringify(man).getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
            Files.move(tmp, zip, StandardCopyOption.REPLACE_EXISTING);
            j.log("Backup done: " + zip.getFileName() + " (" + (Files.size(zip) >> 20) + " MB)" + (skipped.isEmpty() ? "." : ", but " + skipped.size() + " file(s) could not be read and are missing."));
        } finally {
            if (paused) {
                try { s.command("save-on"); j.log("World saving is back on."); }
                catch (RuntimeException e) { j.log("WARNING: could not turn world saving back on (" + e.getMessage() + "). Type save-on in the console."); }
            }
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) { }
        }
        BackupManager.afterCreate(j, s, zip);
        if (dir.toAbsolutePath().normalize().equals(dirFor(s).toAbsolutePath().normalize())) prune(j, s, zip);
        return zip;
    }

    private static void prune(Job j, Srv s, Path justMade) {
        int keep = keep(s);
        if (keep <= 0) return;
        List<Map<String, Object>> all = new ArrayList<>();
        for (Map<String, Object> b : list(s)) if (!BackupManager.isLocked(s.id, String.valueOf(b.get("file")))) all.add(b); // pinned backups are never pruned and do not count
        for (int i = keep; i < all.size(); i++) {
            Path old = dirFor(s).resolve(String.valueOf(all.get(i).get("file")));
            if (old.equals(justMade)) continue;
            try { Files.deleteIfExists(old); j.log("Deleted the old backup " + old.getFileName() + " (keeping the newest " + keep + ")."); }
            catch (IOException e) { j.log("Could not delete the old backup " + old.getFileName() + ": " + e.getMessage()); }
        }
    }

    // ---------- listing ----------

    static Map<String, Object> manifest(Path zip) {
        try (ZipFile z = new ZipFile(zip.toFile())) {
            ZipEntry e = z.getEntry(MANIFEST);
            if (e == null) return null;
            return Json.obj(Json.parse(new String(z.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8)));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    static List<Map<String, Object>> list(Srv s) {
        List<Map<String, Object>> out = new ArrayList<>();
        Path dir = dirFor(s);
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> w = Files.list(dir)) {
            for (Path p : w.toList()) {
                String n = p.getFileName().toString();
                if (!FILE_OK.matcher(n).matches() || !Files.isRegularFile(p)) continue;
                Map<String, Object> m = manifest(p);
                if (m == null || !s.id.equals(Json.str(m, "serverId"))) continue;
                long created = m.get("created") instanceof Number c ? c.longValue() : 0;
                if (created == 0) created = Files.getLastModifiedTime(p).toMillis();
                out.add(Json.map("file", n, "size", Files.size(p), "created", created, "scope", String.valueOf(m.get("scope")), "note", BackupManager.noteFor(s.id, n) != null ? BackupManager.noteFor(s.id, n) : String.valueOf(m.getOrDefault("note", "")),
                        "reason", String.valueOf(m.getOrDefault("reason", "manual")), "mc", m.get("mc"), "kind", m.get("kind"), "by", String.valueOf(m.getOrDefault("by", "")),
                        "partial", !Json.arr(m.get("skipped")).isEmpty()));
            }
        } catch (IOException | UncheckedIOException ignored) { }
        out.sort((a, b) -> Long.compare(((Number) b.get("created")).longValue(), ((Number) a.get("created")).longValue()));
        return out;
    }

    static Path resolve(Srv s, String file) {
        if (file == null || !FILE_OK.matcher(file).matches()) throw new Api(400, "Bad backup name");
        Path p = dirFor(s).resolve(file).normalize();
        if (!p.getParent().equals(dirFor(s).normalize()) || !Files.isRegularFile(p)) throw new Api(404, "That backup no longer exists");
        Map<String, Object> m = manifest(p);
        if (m == null || !s.id.equals(Json.str(m, "serverId"))) throw new Api(404, "That backup does not belong to this server");
        return p;
    }

    static void delete(Srv s, String file) throws IOException {
        Files.delete(resolve(s, file));
    }

    // ---------- restoring ----------

    private static String topOf(String name) {
        int i = name.indexOf('/');
        return i < 0 ? name : name.substring(0, i);
    }

    private record Plan(String scope, List<ZipEntry> entries, Set<String> tops, long bytes, Map<String, Object> man) { }

    /** Reads and validates a zip: every entry must land inside the server folder. */
    private static Plan plan(Srv s, Path zip) throws IOException {
        Path root = s.dir.toAbsolutePath().normalize();
        Map<String, Object> man = manifest(zip);
        List<ZipEntry> entries = new ArrayList<>();
        Set<String> tops = new LinkedHashSet<>();
        long bytes = 0;
        boolean hasProps = false, hasLevel = false, looseFile = false;
        try (ZipFile z = new ZipFile(zip.toFile())) {
            for (Enumeration<? extends ZipEntry> en = z.entries(); en.hasMoreElements(); ) {
                ZipEntry e = en.nextElement();
                String n = e.getName().replace('\\', '/');
                if (n.equals(MANIFEST) || n.endsWith("/")) continue;
                if (n.startsWith("/") || n.contains(":") || n.contains("//") || Arrays.asList(n.split("/")).contains("..") || Arrays.asList(n.split("/")).contains("."))
                    throw new Api(400, "This zip contains an unsafe path (" + n + ") and was not restored.");
                if (!root.resolve(n).normalize().startsWith(root)) throw new Api(400, "This zip contains a path outside the server folder (" + n + ") and was not restored.");
                entries.add(e);
                tops.add(topOf(n));
                bytes += Math.max(0, e.getSize());
                if (n.equals("server.properties") || n.equals("eula.txt")) hasProps = true;
                if (n.matches("[^/]+/level\\.dat")) hasLevel = true;
                if (!n.contains("/")) looseFile = true;
            }
        } catch (java.util.zip.ZipException e) {
            throw new Api(400, "That file is not a readable zip (" + e.getMessage() + ").");
        }
        if (entries.isEmpty()) throw new Api(400, "That zip is empty.");
        String scope = man != null && List.of("world", "full").contains(Json.str(man, "scope")) ? Json.str(man, "scope") : hasProps ? "full" : hasLevel ? "world" : null;
        if (scope == null) throw new Api(400, "That zip does not look like a Minecraft server or world backup (no server.properties and no world folder with level.dat).");
        if (scope.equals("world")) {
            if (looseFile) throw new Api(400, "A world backup may only contain world folders; this zip also has loose files at the top level.");
            for (String t : tops) if (NOT_A_WORLD.contains(t.toLowerCase()) || SKIP_TOP.contains(t.toLowerCase())) throw new Api(400, "A world backup cannot contain a \"" + t + "\" folder.");
        }
        return new Plan(scope, entries, tops, bytes, man);
    }

    /** Validates a zip for this server without changing anything, so a bad file is refused right away. */
    static void check(Srv s, Path zip) throws IOException {
        plan(s, zip);
    }

    private static void deleteTree(Path p, Job j) throws IOException {
        if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) return;
        List<Path> all;
        try (Stream<Path> w = Files.walk(p)) { all = w.sorted(Comparator.reverseOrder()).toList(); }
        for (Path f : all) {
            try { Files.deleteIfExists(f); }
            catch (AccessDeniedException e) { f.toFile().setWritable(true); Files.deleteIfExists(f); }
        }
    }

    /** Replaces the current files with the zip's. Full: everything except logs and caches; world: just the world folders in the zip. */
    private static void apply(Srv s, Path zip, Plan pl, Job j) throws IOException {
        Path root = s.dir.toAbsolutePath().normalize();
        if (pl.scope().equals("full")) {
            try (Stream<Path> top = Files.list(root)) {
                for (Path p : top.toList()) {
                    String n = p.getFileName().toString().toLowerCase();
                    if (SKIP_TOP.contains(n) || n.startsWith("hs_err_pid")) continue;
                    deleteTree(p, j);
                }
            }
        } else {
            for (String t : pl.tops()) deleteTree(root.resolve(t), j);
        }
        try (ZipFile z = new ZipFile(zip.toFile())) {
            for (ZipEntry e : pl.entries()) {
                Path t = root.resolve(e.getName().replace('\\', '/')).normalize();
                Files.createDirectories(t.getParent());
                try (InputStream in = z.getInputStream(e)) { Files.copy(in, t, StandardCopyOption.REPLACE_EXISTING); }
                if (e.getTime() > 0) { try { Files.setLastModifiedTime(t, FileTime.fromMillis(e.getTime())); } catch (IOException ignored) { } }
            }
        }
    }

    /** The server must be stopped. Takes a safety copy of what is there now, replaces it, and puts the old state back if anything goes wrong. */
    static void restore(Job j, Srv s, Path zip, String actor) throws Exception {
        if (s.running(true)) throw new Api(409, "Stop " + s.name + " first. A running server would overwrite the restored files.");
        Plan pl = plan(s, zip);
        Map<String, Object> man = pl.man();
        j.log("Restoring " + (pl.scope().equals("world") ? "the world" : "the whole server") + " from " + zip.getFileName() + (man != null && man.get("created") instanceof Number c ? " (made " + new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(c.longValue())) + ")" : "") + ".");
        if (man != null && !s.id.equals(Json.str(man, "serverId"))) j.log("Note: this backup was made from the server \"" + Json.str(man, "serverName") + "\".");
        String bmc = man == null ? null : Json.str(man, "mc"), now = s.mcVersion();
        if (bmc != null && now != null && !bmc.equals(now)) j.log("Note: the backup is from Minecraft " + bmc + " and this server runs " + now + (pl.scope().equals("world") ? ". A world saved by a newer Minecraft cannot be opened by an older one." : "."));
        Path root = s.dir.toAbsolutePath().normalize();
        long need = pl.bytes() + (256L << 20), credit = 0;
        try {
            if (pl.scope().equals("full")) credit = size(collect(s, "full", dirFor(s).toAbsolutePath().normalize()));
            else if (pl.tops().stream().anyMatch(t -> Files.isDirectory(root.resolve(t)))) credit = size(collect(s, "world", null));
        } catch (IOException ignored) { }
        long free = Files.getFileStore(root).getUsableSpace();
        if (free + credit < need) throw new Api(409, "Not enough free space on the server's drive: the restore needs about " + (need >> 20) + " MB and only " + ((free + credit) >> 20) + " MB would be available.");

        Path safety = null;
        boolean anything = pl.scope().equals("full") || pl.tops().stream().anyMatch(t -> Files.isDirectory(root.resolve(t)));
        if (anything) {
            j.log("Saving a copy of the current state first, so this restore can be undone ...");
            safety = create(j, s, pl.scope(), dirFor(s), "Automatic copy of the state before restoring " + zip.getFileName(), "before-restore", actor);
        } else {
            j.log("There is nothing to replace, so no safety copy was needed.");
        }
        j.checkCancel();
        j.log("Replacing the files (this part cannot be cancelled) ...");
        try {
            apply(s, zip, pl, j);
        } catch (Exception e) {
            j.log("The restore failed: " + e.getMessage());
            if (safety != null) {
                j.log("Putting the previous state back from " + safety.getFileName() + " ...");
                try { apply(s, safety, plan(s, safety), j); j.log("The previous state is back. Nothing was changed."); }
                catch (Exception e2) { j.log("COULD NOT put the previous state back: " + e2.getMessage() + ". Your safety copy is " + safety + " - restore it from the Restore window."); }
            }
            throw e instanceof Api ? e : new Api(500, "The restore failed: " + e.getMessage());
        }
        if (pl.scope().equals("full") && man != null) {
            String kind = Json.str(man, "kind"), script = Json.str(man, "script");
            if (kind != null && !kind.equals("null")) s.cfg.put("kind", kind);
            if (script != null && script.matches("[^\\\\/:*?\"<>|]+\\.(bat|cmd)") && Files.exists(root.resolve(script))) s.cfg.put("script", script);
            if (man.get("autoUpdate") instanceof Boolean ab) s.cfg.put("autoUpdate", ab && s.isFabric());
            String mc = Json.str(man, "mc");
            if (mc != null && !mc.equals("null")) s.cfg.put("mc", mc);
            Main.cfg.save();
            j.log("Server type restored to " + s.kind() + ".");
        } else if (pl.scope().equals("world") && man != null) {
            String w = Json.str(man, "world");
            if (w != null && !w.equals("null") && pl.tops().contains(w) && !w.equals(s.worldName())) {
                s.writeProps(new LinkedHashMap<>(Map.of("level-name", w)));
                j.log("level-name is now \"" + w + "\" so the server loads the restored world.");
            }
        }
        j.log("Restore finished. Start the server when you are ready.");
    }
}
