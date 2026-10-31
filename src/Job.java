import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/** A background action (start, stop, restart, update...) whose progress the browser polls. */
final class Job {
    interface Body { void run(Job j) throws Exception; }

    static final class Cancelled extends RuntimeException {
        Cancelled() { super("Cancelled"); }
    }

    final String id = Long.toString(System.nanoTime(), 36);
    final String title;
    final String serverId;
    final long started = System.currentTimeMillis();
    volatile long finished;
    volatile String state = "running";
    volatile boolean cancel;
    volatile Object result;
    final List<String> lines = new CopyOnWriteArrayList<>();

    Job(String serverId, String title) {
        this.serverId = serverId;
        this.title = title;
    }

    void log(String s) {
        lines.add(s);
        while (lines.size() > 1000) lines.remove(0);
    }

    void checkCancel() {
        if (cancel) throw new Cancelled();
    }

    void sleep(long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            checkCancel();
            Thread.sleep(Math.min(250, Math.max(1, end - System.currentTimeMillis())));
        }
    }

    boolean active() { return "running".equals(state); }

    Map<String, Object> toMap(int from) {
        List<String> l = new ArrayList<>(lines);
        return Json.map("id", id, "title", title, "server", serverId, "state", state, "started", started,
                "finished", finished, "from", Math.min(from, l.size()), "lines", l.subList(Math.min(from, l.size()), l.size()),
                "total", l.size(), "result", result == null ? null : (result instanceof List<?> r ? new ArrayList<>(r) : result));
    }
}
