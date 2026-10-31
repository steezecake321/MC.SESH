import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Deleting a server's folder from disk (Recycle Bin by default), with an optional world backup first and strict safety checks. */
final class ServerDelete {
    private ServerDelete() { }

    private static final Pattern LAUNCHER = Pattern.compile("(?i)(curseforge|atlauncher|ftb|feed-the-beast|prism|multimc|modrinthapp|modrinth app|technic|gdlauncher|polymc)");

    private static long size(Path dir) {
        long[] total = {0};
        try (Stream<Path> w = Files.walk(dir)) {
            w.forEach(p -> { try { BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); if (a.isRegularFile()) total[0] += a.size(); } catch (IOException ignored) { } });
        } catch (IOException | UncheckedIOException ignored) { }
        return total[0];
    }

    private static List<Path> worldDirs(Srv s) { return Backups.worldDirs(s); }

    /** Why this folder must not be deleted, or null. */
    static String blockedReason(Srv s) {
        Path d = s.dir.toAbsolutePath().normalize();
        if (!d.isAbsolute() || d.getParent() == null) return "This is a whole drive, which is never deleted.";
        if (!Files.isDirectory(d)) return "The folder no longer exists, so there is nothing to delete. Use Remove to take it out of the list.";
        Path home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        if (home.startsWith(d)) return "This folder contains your Windows user folder, so it is never deleted.";
        if (Main.HOME != null && Main.HOME.toAbsolutePath().normalize().startsWith(d)) return "This folder contains the manager itself, so it is never deleted.";
        Path root = Main.serversRoot().toAbsolutePath().normalize();
        if (d.equals(root)) return "This is the folder all new servers are created in, so it is never deleted.";
        for (Srv o : Main.servers) {
            if (o == s) continue;
            Path od = o.dir.toAbsolutePath().normalize();
            if (od.startsWith(d)) return "This folder also contains the server \"" + o.name + "\". Deleting it would remove that server too.";
            if (d.startsWith(od)) return "This folder is inside the server \"" + o.name + "\". Deleting it would damage that server.";
        }
        boolean looks = Files.exists(d.resolve("server.properties")) || Files.exists(d.resolve("eula.txt")) || Files.exists(d.resolve(s.script()));
        if (!looks) return "This folder does not look like a Minecraft server (no server.properties, eula.txt or start script), so it is not deleted.";
        return null;
    }

    static Map<String, Object> preview(Srv s) {
        Path d = s.dir.toAbsolutePath().normalize();
        List<Object> warnings = new ArrayList<>();
        boolean launcher = LAUNCHER.matcher(d.toString()).find();
        if (launcher) warnings.add("This folder looks like it belongs to another launcher (CurseForge, ATLauncher, FTB, Prism or similar). Deleting it may also remove that launcher's instance.");
        if (!s.task.isEmpty()) warnings.add("A Windows scheduled task (\"" + s.task + "\") starts this server. The task is not removed; delete it in Task Scheduler (or Settings > Startup) if you no longer want it.");
        if (s.autostart()) warnings.add("This server is set to start automatically with the manager; that goes away with the server.");
        long total = Files.isDirectory(d) ? size(d) : 0, world = 0;
        for (Path w : worldDirs(s)) world += size(w);
        return Json.map("name", s.name, "dir", d.toString(), "sizeMb", total >> 20, "worldMb", world >> 20, "running", s.running(true), "blocked", blockedReason(s),
                "launcher", launcher, "warnings", warnings, "defaultBackupDir", Main.backupsRoot().toString());
    }

    private static void deletePermanently(Path dir, Job j) throws IOException {
        j.log("Deleting " + dir + " permanently ...");
        List<Path> all;
        try (Stream<Path> w = Files.walk(dir)) { all = w.sorted(Comparator.reverseOrder()).toList(); }
        int failed = 0;
        for (Path p : all) {
            try {
                try { Files.deleteIfExists(p); } catch (java.nio.file.AccessDeniedException e) { p.toFile().setWritable(true); Files.deleteIfExists(p); }
            } catch (IOException e) {
                failed++;
                if (failed <= 5) j.log("  could not delete " + p + ": " + e.getMessage());
            }
        }
        if (failed > 0) throw new Api(500, failed + " item(s) could not be deleted (are they open in another program?). The server was NOT removed from the manager.");
    }

    private static void sendToRecycleBin(Path dir, Job j) throws IOException, InterruptedException {
        j.log("Moving " + dir + " to the Recycle Bin ...");
        String ps = "Add-Type -AssemblyName Microsoft.VisualBasic; "
                + "[Microsoft.VisualBasic.FileIO.FileSystem]::DeleteDirectory($env:MCM_TARGET, 'OnlyErrorDialogs', 'SendToRecycleBin', 'ThrowException')";
        ProcessBuilder pb = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", ps).redirectErrorStream(true);
        pb.environment().put("MCM_TARGET", dir.toString());
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (!p.waitFor(300, java.util.concurrent.TimeUnit.SECONDS)) { p.destroyForcibly(); throw new Api(500, "Moving the folder to the Recycle Bin took too long and was stopped."); }
        if (Files.exists(dir)) throw new Api(500, "Windows could not move the folder to the Recycle Bin" + (out.isEmpty() ? "." : ": " + out.split("\\R")[0]) + " The server was NOT removed from the manager. You can try again, or choose to delete permanently.");
    }

    static void delete(Job j, Srv s, Map<String, Object> req) throws Exception {
        String why = blockedReason(s);
        if (why != null) throw new Api(409, why);
        if (s.running(true)) throw new Api(409, "Stop " + s.name + " first.");
        if (!s.name.equals(Json.str(req, "confirmName"))) throw new Api(400, "The name you typed does not match \"" + s.name + "\".");
        Map<String, Object> pv = preview(s);
        if (Boolean.TRUE.equals(pv.get("launcher")) && !Boolean.TRUE.equals(req.get("confirmLauncher"))) throw new Api(400, "Please confirm that you understand this folder belongs to another launcher.");
        boolean permanent = Boolean.TRUE.equals(req.get("permanent"));
        Path dir = s.dir.toAbsolutePath().normalize();
        if (Boolean.TRUE.equals(req.get("backup"))) {
            String bd = Json.str(req, "backupDir");
            Path target = bd == null || bd.isBlank() ? Main.backupsRoot() : Path.of(bd.trim());
            if (!target.isAbsolute()) throw new Api(400, "Choose a full folder path for the backup.");
            if (target.toAbsolutePath().normalize().startsWith(dir)) throw new Api(400, "The backup cannot be saved inside the folder being deleted.");
            if (worldDirs(s).isEmpty()) j.log("No world folder found to back up.");
            else Backups.create(j, s, "world", target, "Backup taken before deleting the server", "before-delete", "manager");
        }
        j.checkCancel();
        if (permanent) deletePermanently(dir, j); else sendToRecycleBin(dir, j);
        Main.removeServer(s);
        Users.audit("manager", "", "server deleted from disk (" + (permanent ? "permanent" : "recycle bin") + ")", s.id, s.name + " at " + dir);
        j.log("Done. " + s.name + " was " + (permanent ? "deleted permanently" : "moved to the Recycle Bin") + " and removed from the manager.");
    }
}
