import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * Notices a server that went down without anyone asking it to, and starts it again. A stop from the manager, a /stop typed in the game, or a stop
 * from the console is never treated as a crash. After {@link #MAX} crashes within {@link #WINDOW} ms the manager stops trying and says so.
 */
final class CrashWatch {
    private CrashWatch() { }

    static final int MAX = 3;
    static final long WINDOW = 15 * 60_000L;
    private static final long DELAY_MS = 10_000L;

    private static final class Track {
        boolean up;
        long upSince;
        long crashMtime;
        long pid;
        int downTicks;
        final Deque<Long> restarts = new ArrayDeque<>();
    }

    private static final Map<String, Track> tracks = new HashMap<>();

    /** On unless switched off for the server. */
    static boolean enabled(Srv s) { return !Boolean.FALSE.equals(s.cfg.get("crashRestart")); }

    static long newestCrashReport(Srv s) {
        Path d = s.dir.resolve("crash-reports");
        if (!Files.isDirectory(d)) return 0;
        long best = 0;
        try (Stream<Path> st = Files.list(d)) {
            for (Path p : st.toList()) {
                String n = p.getFileName().toString().toLowerCase();
                if (n.startsWith("crash-") && n.endsWith(".txt")) best = Math.max(best, Files.getLastModifiedTime(p).toMillis());
            }
        } catch (IOException | RuntimeException ignored) { }
        return best;
    }

    /** Called by a start that has just seen the server open its port, so a crash in the first seconds is not missed between two checks. */
    static synchronized void markUp(Srv s) {
        Track t = tracks.computeIfAbsent(s.id, k -> new Track());
        if (t.up) return;
        Integer pid = s.listenerPid();
        t.up = true;
        t.upSince = System.currentTimeMillis();
        t.crashMtime = newestCrashReport(s);
        t.pid = pid == null ? 0 : pid;
        t.downTicks = 0;
    }

    static synchronized void tick() {
        Main.sampler.refresh(false);
        long now = System.currentTimeMillis();
        for (Srv s : Main.servers) {
            Track t = tracks.computeIfAbsent(s.id, k -> new Track());
            Integer pid = s.listenerPid();
            boolean up = pid != null;
            if (up) {
                t.downTicks = 0;
                t.pid = pid;
                if (!t.up) {
                    t.up = true;
                    t.upSince = now;
                    t.crashMtime = newestCrashReport(s);
                }
            } else if (t.up) {
                // the port list can be wrong for a moment (a slow netstat): only believe it when the process is really gone, and twice in a row
                boolean alive = t.pid > 0 && ProcessHandle.of(t.pid).map(ProcessHandle::isAlive).orElse(false);
                if (alive) { t.downTicks = 0; continue; }
                if (++t.downTicks < 2) continue;
                t.up = false;
                t.downTicks = 0;
                try { onDown(s, t, now); } catch (RuntimeException e) { Main.log("crash watch " + s.id + ": " + e); }
            }
        }
    }

    private static Map<String, Object> openServer(Srv s, String tab, String label) {
        return Json.map("type", "open", "server", s.id, "tab", tab, "label", label);
    }

    private static void onDown(Srv s, Track t, long now) {
        boolean asked = s.stopRequestedAt >= t.upSince - 5000;
        boolean report = newestCrashReport(s) > t.crashMtime;
        boolean inGameStop = s.sawStopLine && !report;
        boolean crashed = report || !(asked || inGameStop);
        if (!crashed || asked) return;
        String why = report ? "It wrote a crash report." : "Its process ended without a stop request.";
        if (!enabled(s)) {
            Notify.post("health", "bad", s.id, s.name + " crashed", why + " Automatic restart is switched off for this server.", openServer(s, "files", "Open log files"));
            return;
        }
        while (!t.restarts.isEmpty() && now - t.restarts.peekFirst() > WINDOW) t.restarts.pollFirst();
        if (t.restarts.size() >= MAX) {
            Notify.post("health", "bad", s.id, s.name + " keeps crashing",
                    "It crashed " + (MAX + 1) + " times within " + (WINDOW / 60_000) + " minutes, so the manager is not restarting it again. " + why + " Look at the newest crash report.",
                    openServer(s, "files", "Open crash reports"));
            return;
        }
        t.restarts.addLast(now);
        int attempt = t.restarts.size();
        Notify.post("health", "warn", s.id, s.name + " crashed - restarting", why + " Restarting in " + (DELAY_MS / 1000) + " seconds (attempt " + attempt + " of " + MAX + ").", openServer(s, "files", "Open crash reports"));
        Thread.startVirtualThread(() -> {
            try {
                Thread.sleep(DELAY_MS);
                if (s.running(true) || (s.job != null && s.job.active())) return;
                Job j = Main.submit(s, "Restart after crash", s::start);
                while (j.active()) Thread.sleep(2000);
                if ("done".equals(j.state)) Notify.post("health", "good", s.id, s.name + " is back up", "It was restarted automatically after the crash.", null);
                else Notify.post("health", "bad", s.id, s.name + " could not be restarted", String.join(" ", j.lines.subList(Math.max(0, j.lines.size() - 2), j.lines.size())), openServer(s, "console", "Open " + s.name));
            } catch (InterruptedException ignored) {
            } catch (RuntimeException e) {
                Main.log("restart after crash of " + s.id + ": " + e);
            }
        });
    }
}
