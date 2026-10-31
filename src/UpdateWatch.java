import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Looks for things managers want to hear about without being asked: a new Minecraft release, a server whose mods all support a newer Minecraft
 * ("Geyser now supports 26.3 - update now?"), mod and loader updates, and drives that are nearly full. Fabric servers are checked with the updater's
 * dry run, which changes nothing. Each finding is reported once (and again only if it changes), not on every check.
 */
final class UpdateWatch {
    private UpdateWatch() { }

    private static final Pattern MC_CHANGE = Pattern.compile("^Minecraft (\\S+) -> (\\S+)$");
    private static final Pattern WAITING = Pattern.compile("^Minecraft (\\S+) is available but not installed yet; waiting on: (.+?)\\.?$");
    private static final Pattern CHANGE = Pattern.compile("^(.+) (\\S+) -> (\\S+)$");
    private static final long CHECK_EVERY_MS = 6 * 3_600_000L;
    private static final long DISK_EVERY_MS = 10 * 60_000L;

    private static long lastCheck, lastDisk;
    private static boolean checking;
    private static Map<String, Object> state;

    private static Path file() { return Main.DATA.resolve("updatewatch.json"); }

    private static synchronized Map<String, Object> state() {
        if (state == null) {
            try { state = Files.isRegularFile(file()) ? Json.obj(Json.parse(Files.readString(file(), StandardCharsets.UTF_8))) : new LinkedHashMap<>(); }
            catch (IOException | RuntimeException e) { state = new LinkedHashMap<>(); }
        }
        return state;
    }

    private static synchronized void save() {
        try { Files.writeString(file(), Json.stringify(state()), StandardCharsets.UTF_8); } catch (IOException e) { Main.log("update watch: could not save state: " + e); }
    }

    @SuppressWarnings("unchecked")
    private static synchronized Map<String, Object> forServer(Srv s) {
        Map<String, Object> all = Json.obj(state().computeIfAbsent("servers", k -> new LinkedHashMap<String, Object>()));
        return (Map<String, Object>) all.computeIfAbsent(s.id, k -> new LinkedHashMap<String, Object>());
    }

    /** Called every minute: runs the slow checks when they are due. */
    static void tick() {
        long now = System.currentTimeMillis();
        if (now - lastDisk > DISK_EVERY_MS) {
            lastDisk = now;
            try { disks(); } catch (RuntimeException e) { Main.log("disk check: " + e); }
        }
        if (now - lastCheck > CHECK_EVERY_MS && Main.uptimeMs() > 3 * 60_000L) checkAll();
    }

    /** Starts a check in the background (the "check now" button). */
    static boolean checkNow() {
        synchronized (UpdateWatch.class) { if (checking) return false; }
        Thread.startVirtualThread(UpdateWatch::checkAll);
        return true;
    }

    static void checkAll() {
        synchronized (UpdateWatch.class) {
            if (checking) return;
            checking = true;
        }
        try {
            lastCheck = System.currentTimeMillis();
            try { newRelease(); } catch (IOException | RuntimeException e) { Main.log("update watch (release): " + e); }
            for (Srv s : List.copyOf(Main.servers)) {
                if (!s.isFabric() || (s.job != null && s.job.active())) continue;
                try { check(s); } catch (Exception e) { Main.log("update watch " + s.id + ": " + e); }
            }
            save();
        } finally {
            synchronized (UpdateWatch.class) { checking = false; }
        }
    }

    private static void newRelease() throws IOException {
        Map<String, Object> v = Installer.versions("vanilla", false);
        String latest = Json.str(v, "latest");
        if (latest == null || latest.isBlank()) return;
        String seen = Json.str(state(), "lastRelease");
        synchronized (UpdateWatch.class) { state().put("lastRelease", latest); }
        if (seen == null || seen.equals(latest)) return;
        Notify.post("update", "info", null, "Minecraft " + latest + " is out", "Servers set to update automatically move to it as soon as all of their mods support it. You will be told here when a server can.", null);
    }

    private static Map<String, Object> updateAction(Srv s, String label) {
        return Json.map("type", "update", "server", s.id, "label", label);
    }

    private static void check(Srv s) throws Exception {
        Job j = new Job(s.id, "update check");
        List<String> out = s.runUpdater(j, true);
        String mcFrom = null, mcTo = null, waitMc = null, waitBy = null;
        List<String> changes = new ArrayList<>();
        for (String l : out) {
            Matcher m = MC_CHANGE.matcher(l);
            if (m.matches()) { mcFrom = m.group(1); mcTo = m.group(2); continue; }
            m = WAITING.matcher(l);
            if (m.matches() && waitMc == null) { waitMc = m.group(1); waitBy = m.group(2); continue; }
            m = CHANGE.matcher(l);
            if (m.matches() && !l.startsWith("Minecraft ") && !l.startsWith("Not found")) changes.add(l);
        }
        Map<String, Object> st = forServer(s);
        if (mcTo != null) {
            Object prev = st.get("blocked");
            String who = prev instanceof Map<?, ?> pm && mcTo.equals(pm.get("mc")) ? String.valueOf(pm.get("by")) : null;
            String title = who != null ? who + " now supports Minecraft " + mcTo : s.name + " can update to Minecraft " + mcTo;
            String text = (who != null ? "Every mod on " + s.name + " now has a build for Minecraft " + mcTo + ". " : "Every mod on " + s.name + " has a build for Minecraft " + mcTo + ". ")
                    + "It is on " + mcFrom + ". Updating stops the server for a few minutes, takes a backup first and starts it again.";
            Notify.postOnce("mc:" + s.id + ":" + mcTo, 3 * 24 * 3_600_000L, "update", "good", s.id, title, text, updateAction(s, "Update now"));
            synchronized (UpdateWatch.class) { st.remove("blocked"); }
        } else if (waitMc != null) {
            String key = waitMc + "|" + waitBy;
            if (!key.equals(st.get("waitKey"))) {
                synchronized (UpdateWatch.class) { st.put("waitKey", key); st.put("blocked", Json.map("mc", waitMc, "by", waitBy)); }
                Notify.post("update", "info", s.id, s.name + " is waiting on " + waitBy + " for Minecraft " + waitMc,
                        "Minecraft " + waitMc + " is out, but " + waitBy + " has no build for it yet, so " + s.name + " stays on its current version. You will be told here as soon as it can update.", null);
            }
        } else {
            synchronized (UpdateWatch.class) { st.remove("blocked"); st.remove("waitKey"); }
        }
        if (!changes.isEmpty()) {
            String sig = Integer.toHexString(String.join("|", changes).hashCode());
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < changes.size() && i < 6; i++) sb.append(i == 0 ? "" : "; ").append(changes.get(i));
            if (changes.size() > 6) sb.append("; and ").append(changes.size() - 6).append(" more");
            Notify.postOnce("mods:" + s.id + ":" + sig, 7 * 24 * 3_600_000L, "update", "info", s.id, s.name + ": " + changes.size() + " update" + (changes.size() == 1 ? "" : "s") + " available", sb.toString(), updateAction(s, "Update now"));
        }
    }

    // ---------- drives ----------

    private static void disks() {
        for (Srv s : Main.servers) {
            Set<Path> roots = new LinkedHashSet<>();
            roots.add(s.dir.toAbsolutePath().getRoot());
            Path b = Backups.dirFor(s).toAbsolutePath();
            roots.add(b.getRoot());
            for (Path root : roots) {
                if (root == null || !Files.exists(root)) continue;
                try {
                    java.nio.file.FileStore fs = Files.getFileStore(root);
                    long free = fs.getUsableSpace(), total = fs.getTotalSpace();
                    if (total > 0 && (free < (5L << 30) || free < total * 0.06)) {
                        Notify.postOnce("disk:" + root + ":" + s.id, 12 * 3_600_000L, "system", "warn", s.id, "Drive " + root + " is nearly full",
                                "Only " + String.format(Locale.ROOT, "%.1f", free / 1073741824.0) + " GB is free on " + root + ", where " + s.name + (b.getRoot() != null && b.getRoot().equals(root) ? " keeps its backups" : " lives") + ". A full drive can damage the world. Free some space or move backups elsewhere.", null);
                    }
                } catch (IOException ignored) { }
            }
        }
    }
}
