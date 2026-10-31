import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** The backup manager: every backup of every server in one list (including backups of servers that were removed), pinning, verifying, downloading and bulk delete. */
final class BackupManager {
    private BackupManager() { }

    private static final Pattern FILE_OK = Pattern.compile("[A-Za-z0-9 _.()\\-]{1,160}\\.zip");
    private static final Set<String> locked = new HashSet<>();
    private static boolean loaded;

    // ---------- pins (a pinned backup is never deleted by "keep only the newest N") ----------

    private static Path lockFile() { return Main.DATA.resolve("backup-locks.json"); }

    private static synchronized void load() {
        if (loaded) return;
        loaded = true;
        try {
            if (Files.exists(lockFile())) for (Object o : Json.arr(Json.parse(Files.readString(lockFile(), StandardCharsets.UTF_8)))) locked.add(String.valueOf(o));
        } catch (IOException | RuntimeException ignored) { }
    }

    static synchronized boolean isLocked(String serverId, String file) {
        load();
        return locked.contains(serverId + "/" + file);
    }

    static synchronized void setLocked(String serverId, String file, boolean on) throws IOException {
        load();
        if (on) locked.add(serverId + "/" + file); else locked.remove(serverId + "/" + file);
        Files.writeString(lockFile(), Json.stringify(new ArrayList<>(new TreeSet<>(locked))), StandardCharsets.UTF_8);
    }

    // ---------- listing ----------

    private static Srv server(String id) {
        for (Srv s : Main.servers) if (s.id.equals(id)) return s;
        return null;
    }

    /** Folders that may hold backups: every server's backup folder plus the default one. */
    private static Set<Path> dirs() {
        Set<Path> d = new LinkedHashSet<>();
        for (Srv s : Main.servers) d.add(Backups.dirFor(s).toAbsolutePath().normalize());
        Path def = Main.backupsRoot();
        if (Files.isDirectory(def)) {
            try (Stream<Path> w = Files.list(def)) { for (Path p : w.toList()) if (Files.isDirectory(p)) d.add(p.toAbsolutePath().normalize()); }
            catch (IOException | UncheckedIOException ignored) { }
        }
        return d;
    }

    /** Backups of servers the account manages (level 4+), and, for administrators, backups whose server is no longer managed. */
    static Map<String, Object> all(Users.Acct me) {
        load();
        List<Object> rows = new ArrayList<>();
        Set<String> known = new HashSet<>();
        for (Srv s : Main.servers) {
            known.add(s.id);
            if (me.level(s.id) < 4) continue;
            for (Map<String, Object> b : Backups.list(s)) {
                Map<String, Object> r = new LinkedHashMap<>(b);
                r.put("server", s.id);
                r.put("serverName", s.name);
                r.put("locked", isLocked(s.id, String.valueOf(b.get("file"))));
                r.put("orphan", false);
                rows.add(r);
            }
        }
        if (me.admin) {
            for (Path dir : dirs()) {
                try (Stream<Path> w = Files.list(dir)) {
                    for (Path p : w.toList()) {
                        String n = p.getFileName().toString();
                        if (!FILE_OK.matcher(n).matches() || !Files.isRegularFile(p)) continue;
                        Map<String, Object> m = Backups.manifest(p);
                        String sid = m == null ? null : Json.str(m, "serverId");
                        if (sid == null || known.contains(sid)) continue;
                        long created = m.get("created") instanceof Number c ? c.longValue() : Files.getLastModifiedTime(p).toMillis();
                        rows.add(Json.map("file", n, "size", Files.size(p), "created", created, "scope", String.valueOf(m.get("scope")), "note", String.valueOf(m.getOrDefault("note", "")),
                                "reason", String.valueOf(m.getOrDefault("reason", "manual")), "mc", m.get("mc"), "kind", m.get("kind"), "by", String.valueOf(m.getOrDefault("by", "")),
                                "partial", !Json.arr(m.get("skipped")).isEmpty(), "server", sid, "serverName", sid + " (removed)", "locked", isLocked(sid, n), "orphan", true, "dir", dir.toString()));
                    }
                } catch (IOException | UncheckedIOException ignored) { }
            }
        }
        rows.sort((a, b) -> Long.compare(((Number) Json.obj(b).get("created")).longValue(), ((Number) Json.obj(a).get("created")).longValue()));
        List<Object> drives = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Srv s : Main.servers) {
            if (me.level(s.id) < 4) continue;
            Path d = Backups.dirFor(s);
            String key = d.toAbsolutePath().normalize().toString();
            if (seen.add(key)) drives.add(Json.map("dir", key, "freeMb", Backups.freeMb(d)));
        }
        return Json.map("backups", rows, "drives", drives, "admin", me.admin);
    }

    // ---------- finding one ----------

    /** The file for a backup of a managed server (needs level 4 there) or of a removed server (administrators only). */
    static Path resolve(Users.Acct me, String serverId, String file) {
        if (file == null || !FILE_OK.matcher(file).matches()) throw new Api(400, "Bad backup name");
        Srv s = server(serverId);
        if (s != null) {
            if (me.level(s.id) < 4) throw new Api(403, "You cannot manage backups of " + s.name);
            return Backups.resolve(s, file);
        }
        if (!me.admin) throw new Api(403, "Only administrators can work with backups of removed servers");
        for (Path dir : dirs()) {
            Path p = dir.resolve(file).normalize();
            if (!p.getParent().equals(dir) || !Files.isRegularFile(p)) continue;
            Map<String, Object> m = Backups.manifest(p);
            if (m != null && serverId.equals(Json.str(m, "serverId"))) return p;
        }
        throw new Api(404, "That backup no longer exists");
    }

    // ---------- actions ----------

    static Map<String, Object> deleteMany(Users.Acct me, List<Object> items, boolean includePinned) throws IOException {
        int deleted = 0;
        long freed = 0;
        List<Object> skipped = new ArrayList<>();
        for (Object o : items) {
            String sid = Json.str(o, "server"), file = Json.str(o, "file");
            try {
                Path p = resolve(me, sid, file);
                if (isLocked(sid, file) && !includePinned) { skipped.add(file + " (pinned)"); continue; }
                long size = Files.size(p);
                Files.delete(p);
                if (isLocked(sid, file)) setLocked(sid, file, false);
                deleted++;
                freed += size;
            } catch (Api e) {
                skipped.add(file + " (" + e.getMessage() + ")");
            }
        }
        return Json.map("deleted", deleted, "freedMb", freed / 1048576, "skipped", skipped);
    }

    /** Reads every file inside the zip to the end so damaged data is found (the zip format checks each file's checksum while reading). */
    static void verify(Job j, Path zip) throws IOException {
        j.log("Checking " + zip.getFileName() + " (" + Files.size(zip) / 1048576 + " MB) ...");
        long files = 0, bytes = 0;
        byte[] buf = new byte[1 << 16];
        try (ZipFile z = new ZipFile(zip.toFile())) {
            for (Enumeration<? extends ZipEntry> en = z.entries(); en.hasMoreElements(); ) {
                ZipEntry e = en.nextElement();
                j.checkCancel();
                if (e.isDirectory()) continue;
                try (InputStream in = z.getInputStream(e)) {
                    int n;
                    while ((n = in.read(buf)) > 0) bytes += n;
                } catch (IOException ex) {
                    throw new Api(500, "Damaged: " + e.getName() + " cannot be read (" + ex.getMessage() + "). Do not rely on this backup.");
                }
                files++;
            }
        } catch (java.util.zip.ZipException ex) {
            throw new Api(500, "This backup is not a readable zip (" + ex.getMessage() + ").");
        }
        j.log("All good: " + files + " files, " + bytes / 1048576 + " MB read back without errors.");
    }

    static void download(com.sun.net.httpserver.HttpExchange ex, Path zip) throws IOException {
        long size = Files.size(zip);
        ex.getResponseHeaders().add("Content-Type", "application/zip");
        ex.getResponseHeaders().add("Content-Disposition", "attachment; filename=\"" + zip.getFileName() + "\"");
        ex.sendResponseHeaders(200, size);
        try (OutputStream out = ex.getResponseBody(); InputStream in = Files.newInputStream(zip)) {
            in.transferTo(out);
        }
    }

    // ---------- notes (a note can be edited later; it is kept next to the manager's data because the zip itself never changes) ----------

    private static final Map<String, String> notes = new HashMap<>();
    private static boolean notesLoaded;

    private static Path noteFile() { return Main.DATA.resolve("backup-notes.json"); }

    private static synchronized void loadNotes() {
        if (notesLoaded) return;
        notesLoaded = true;
        try {
            if (Files.exists(noteFile())) for (Map.Entry<String, Object> e : Json.obj(Json.parse(Files.readString(noteFile(), StandardCharsets.UTF_8))).entrySet()) notes.put(e.getKey(), String.valueOf(e.getValue()));
        } catch (IOException | RuntimeException ignored) { }
    }

    /** The edited note for a backup, or null when it was never edited. */
    static synchronized String noteFor(String serverId, String file) {
        loadNotes();
        return notes.get(serverId + "/" + file);
    }

    static synchronized void setNote(String serverId, String file, String note) throws IOException {
        loadNotes();
        String n = note == null ? "" : note.replaceAll("\\p{Cntrl}", " ").strip();
        if (n.length() > 200) n = n.substring(0, 200);
        notes.put(serverId + "/" + file, n);
        Files.writeString(noteFile(), Json.stringify(new LinkedHashMap<String, Object>(notes)), StandardCharsets.UTF_8);
    }

    // ---------- servers, for picking and for schedules ----------

    static List<Object> servers(Users.Acct me) {
        List<Object> out = new ArrayList<>();
        for (Srv s : Main.servers) {
            if (me.level(s.id) < 4) continue;
            List<Object> mods = new ArrayList<>();
            for (Map<String, Object> m : s.mods()) {
                String nm = String.valueOf(m.getOrDefault("name", m.getOrDefault("id", m.get("file"))));
                if (!nm.isBlank()) mods.add(nm);
            }
            Map<String, Object> st = Json.obj(s.status());
            out.add(Json.map("id", s.id, "name", s.name, "state", st.get("state"), "mc", s.mcVersion(), "kind", s.kind(), "loader", s.loaderVersion(), "port", st.get("port"),
                    "mods", mods, "rcon", s.rconEnabled(), "auto", AutoBackup.get(s), "keep", Backups.keep(s), "dir", Backups.dirFor(s).toString(),
                    "mirror", String.valueOf(s.cfg.getOrDefault("backupMirror", "")), "verify", Boolean.TRUE.equals(s.cfg.get("backupVerify")), "count", Backups.list(s).size()));
        }
        return out;
    }

    /** Applies a schedule (and optional retention, mirror folder, verify-after) to several servers. */
    static int setSchedule(Users.Acct me, List<Object> ids, Map<String, Object> schedule, Map<String, Object> extra) throws IOException {
        int n = 0;
        for (Object o : ids) {
            Srv s = server(String.valueOf(o));
            if (s == null || me.level(s.id) < 4) continue;
            if (schedule != null && !schedule.isEmpty()) AutoBackup.set(s, schedule);
            if (extra != null) {
                if (extra.containsKey("keep") || extra.containsKey("mirror") || extra.containsKey("verify")) {
                    if (!me.admin) throw new Api(403, "Only administrators can change how many backups are kept, the copy folder, or checking.");
                }
                if (extra.containsKey("keep")) Backups.setSettings(s, Json.map("keep", extra.get("keep")));
                if (extra.containsKey("verify")) { if (Boolean.TRUE.equals(extra.get("verify"))) s.cfg.put("backupVerify", true); else s.cfg.remove("backupVerify"); }
                if (extra.containsKey("mirror")) {
                    String d = String.valueOf(extra.get("mirror")).strip();
                    if (d.isEmpty() || d.equals("null")) s.cfg.remove("backupMirror");
                    else {
                        Path p = Path.of(d);
                        if (!p.isAbsolute()) throw new Api(400, "Use a full folder path such as E:\\Backup copies.");
                        Files.createDirectories(p);
                        if (!Files.isWritable(p)) throw new Api(400, "Cannot write to " + p);
                        s.cfg.put("backupMirror", p.normalize().toString());
                    }
                }
                Main.cfg.save();
            }
            n++;
        }
        return n;
    }

    /** Runs after every backup: copy to the mirror folder and/or read the zip back, as set for the server. */
    static void afterCreate(Job j, Srv s, Path zip) {
        String mirror = String.valueOf(s.cfg.getOrDefault("backupMirror", "")).strip();
        if (!mirror.isEmpty()) {
            try {
                Path dir = Path.of(mirror);
                Files.createDirectories(dir);
                Files.copy(zip, dir.resolve(zip.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                j.log("Copied the backup to " + dir + ".");
            } catch (IOException | RuntimeException e) {
                j.log("Could not copy the backup to " + mirror + ": " + e.getMessage());
                Notify.postOnce("backup-mirror:" + s.id, 3_600_000L, "backup", "bad", s.id, "Backup copy failed for " + s.name, "The backup was made but could not be copied to " + mirror + ": " + e.getMessage(), null);
            }
        }
        if (Boolean.TRUE.equals(s.cfg.get("backupVerify"))) {
            try { verify(j, zip); }
            catch (IOException | RuntimeException e) {
                j.log("CHECK FAILED: " + e.getMessage());
                Notify.post("backup", "bad", s.id, "A new backup of " + s.name + " is damaged", e.getMessage(), null);
            }
        }
    }

    // ---------- batch backup, copy, clone ----------

    static void createMany(Job j, Users.Acct me, List<Object> ids, String scope, String note) throws Exception {
        int ok = 0, bad = 0;
        for (Object o : ids) {
            Srv s = server(String.valueOf(o));
            j.checkCancel();
            if (s == null || me.level(s.id) < 4) continue;
            j.log("");
            j.log("=== " + s.name + " ===");
            try {
                if (s.running(true) && !s.rconEnabled()) { j.log("Skipped: it is running and RCON is off, so saving cannot be paused for a safe copy."); bad++; continue; }
                if (s.job != null && s.job.active()) { j.log("Skipped: busy with " + s.job.title + "."); bad++; continue; }
                Backups.create(j, s, scope, Backups.dirFor(s), note, "manual", me.username);
                ok++;
            } catch (Job.Cancelled c) {
                throw c;
            } catch (Exception e) {
                j.log("FAILED: " + e.getMessage());
                bad++;
            }
        }
        j.log("");
        j.log(ok + " backed up" + (bad > 0 ? ", " + bad + " not done (see above)" : "") + ".");
        if (bad > 0) throw new Api(500, bad + " server(s) were not backed up (see above).");
    }

    static void copyMany(Job j, Users.Acct me, List<Object> items, Path dir) throws Exception {
        if (!dir.isAbsolute()) throw new Api(400, "Use a full folder path such as E:\\Backup copies.");
        Files.createDirectories(dir);
        int n = 0;
        for (Object o : items) {
            j.checkCancel();
            Path p = resolve(me, Json.str(o, "server"), Json.str(o, "file"));
            Path t = dir.resolve(p.getFileName());
            if (Files.exists(t) && Files.size(t) == Files.size(p)) { j.log(p.getFileName() + " is already there."); continue; }
            j.log("Copying " + p.getFileName() + " (" + Files.size(p) / 1048576 + " MB) ...");
            Files.copy(p, t, StandardCopyOption.REPLACE_EXISTING);
            n++;
        }
        j.log("Copied " + n + " file" + (n == 1 ? "" : "s") + " to " + dir + ".");
    }

    private static final Pattern NAME_BAD = Pattern.compile("[\\\\/:*?\"<>|\\p{Cntrl}]");

    /** Makes a new server from a whole-server backup: unpacked next to the original (or in the servers folder), with its own ports and not started. */
    static void cloneToNew(Job j, Users.Acct me, String serverId, String file, String name) throws Exception {
        if (!me.admin) throw new Api(403, "Only administrators can create servers.");
        Path zip = resolve(me, serverId, file);
        Map<String, Object> man = Backups.manifest(zip);
        if (man == null || !"full".equals(Json.str(man, "scope"))) throw new Api(400, "Only a whole-server backup can become a new server (a world-only backup has no mods or settings).");
        name = name == null ? "" : name.strip();
        if (name.isEmpty() || name.length() > 60 || NAME_BAD.matcher(name).find() || name.endsWith(".")) throw new Api(400, "Give the new server a name (up to 60 characters, no \\ / : * ? \" < > |).");
        for (Srv s : Main.servers) if (s.name.equalsIgnoreCase(name)) throw new Api(409, "A server named \"" + name + "\" already exists.");
        Srv orig = server(serverId);
        Path parent = orig != null && orig.dir.getParent() != null ? orig.dir.getParent() : Main.serversRoot();
        Path target = parent.resolve(name).normalize();
        if (!target.getParent().equals(parent.normalize())) throw new Api(400, "Bad server name.");
        if (Files.exists(target)) throw new Api(409, "The folder " + target + " already exists.");
        j.log("Unpacking " + zip.getFileName() + " into " + target + " ...");
        Files.createDirectories(target);
        try {
            int files = 0;
            try (ZipFile z = new ZipFile(zip.toFile())) {
                for (Enumeration<? extends ZipEntry> en = z.entries(); en.hasMoreElements(); ) {
                    ZipEntry e = en.nextElement();
                    String n = e.getName().replace('\\', '/');
                    if (n.equals(Backups.MANIFEST) || e.isDirectory()) continue;
                    Path t = target.resolve(n).normalize();
                    if (n.startsWith("/") || n.contains(":") || !t.startsWith(target)) throw new Api(400, "The backup contains an unsafe path (" + n + ").");
                    j.checkCancel();
                    Files.createDirectories(t.getParent());
                    try (InputStream in = z.getInputStream(e)) { Files.copy(in, t, StandardCopyOption.REPLACE_EXISTING); }
                    files++;
                }
            }
            j.log("Unpacked " + files + " files.");
            Set<Integer> used = Ports.allUsed(null);
            Path propsFile = target.resolve("server.properties");
            int port = 25565;
            if (Files.exists(propsFile)) {
                String props = Files.readString(propsFile, StandardCharsets.ISO_8859_1);
                java.util.regex.Matcher m = Pattern.compile("(?m)^server-port=(\\d+)").matcher(props);
                if (m.find()) port = Integer.parseInt(m.group(1));
            }
            Path script = target.resolve("start.bat");
            if (Files.exists(script)) { java.util.regex.Matcher m = Pattern.compile("--port\\s+(\\d+)").matcher(Files.readString(script, StandardCharsets.ISO_8859_1)); if (m.find()) port = Integer.parseInt(m.group(1)); }
            int np = port + 1;
            while (used.contains(np) || Main.sampler.pidForPort(np) != null || !Geyser.tcpFree(np)) np++;
            int rp = np + 1000 > 65000 ? 25575 : np + 1000;
            while (used.contains(rp) || rp == np || Main.sampler.pidForPort(rp) != null) rp++;
            if (Files.exists(propsFile)) {
                String props = Files.readString(propsFile, StandardCharsets.ISO_8859_1).replaceAll("(?m)^server-port=\\d+", "server-port=" + np).replaceAll("(?m)^rcon\\.port=\\d+", "rcon.port=" + rp)
                        .replaceAll("(?m)^query\\.port=\\d+", "query.port=" + np);
                Files.writeString(propsFile, props, StandardCharsets.ISO_8859_1);
            }
            if (Files.exists(script)) Files.writeString(script, Files.readString(script, StandardCharsets.ISO_8859_1).replaceAll("--port\\s+\\d+", "--port " + np), StandardCharsets.ISO_8859_1);
            j.log("The new server uses port " + np + " (RCON " + rp + ").");
            if (!Files.exists(script)) throw new Api(400, "The backup has no start.bat, so the manager cannot tell how to start it. Add the server by folder instead (Add server > Add an existing server): " + target);
            Srv s = Detector.add(Json.map("dir", target.toString(), "name", name, "script", "start.bat"));
            j.log("Done. \"" + s.name + "\" is in the server list. It is not started; check its settings, then start it.");
        } catch (Exception e) {
            if (!Main.servers.stream().anyMatch(s -> s.dir.equals(target))) {
                try (Stream<Path> w = Files.walk(target)) { for (Path p : w.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p); } catch (IOException ignored) { }
            }
            throw e;
        }
    }
}
