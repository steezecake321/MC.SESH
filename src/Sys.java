import java.io.IOException;
import java.nio.charset.Charset;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Process helpers plus a cheap sampler for "which PID listens on which port" and process memory. */
final class Sys {
    private Sys() {}

    record Result(int code, String out) {}

    static Result run(long timeoutMs, String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            Thread killer = Thread.startVirtualThread(() -> {
                try {
                    if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) p.destroyForcibly();
                } catch (InterruptedException ignored) { }
            });
            String out = new String(p.getInputStream().readAllBytes(), Charset.defaultCharset());
            int code = p.waitFor();
            killer.interrupt();
            return new Result(code, out);
        } catch (IOException | InterruptedException e) {
            return new Result(-1, String.valueOf(e.getMessage()));
        }
    }

    static final class Sampler {
        private long stamp;
        private final Map<Integer, Integer> portPid = new HashMap<>();
        private final Map<Integer, Long> memKb = new HashMap<>();

        synchronized void refresh(boolean force) {
            long now = System.currentTimeMillis();
            if (!force && now - stamp < 2000) return;
            stamp = now;
            Map<Integer, Integer> pp = new HashMap<>();
            for (String line : run(6000, "netstat", "-ano").out().split("\\R")) {
                String[] t = line.trim().split("\\s+");
                if (t.length == 5 && t[0].equals("TCP") && t[2].endsWith(":0")) {
                    try {
                        int port = Integer.parseInt(t[1].substring(t[1].lastIndexOf(':') + 1));
                        pp.put(port, Integer.parseInt(t[4]));
                    } catch (NumberFormatException ignored) { }
                }
            }
            Map<Integer, Long> mk = new HashMap<>();
            for (String line : run(6000, "tasklist", "/fo", "csv", "/nh", "/fi", "IMAGENAME eq java*").out().split("\\R")) {
                String[] t = line.split("\",\"");
                if (t.length >= 5) {
                    try {
                        mk.put(Integer.parseInt(t[1]), Long.parseLong(t[4].replaceAll("[^0-9]", "")));
                    } catch (NumberFormatException ignored) { }
                }
            }
            portPid.clear();
            portPid.putAll(pp);
            memKb.clear();
            memKb.putAll(mk);
        }

        synchronized Integer pidForPort(int port) { return portPid.get(port); }

        synchronized boolean alive(int pid) { return memKb.containsKey(pid); }

        synchronized long memMb(int pid) { return memKb.getOrDefault(pid, 0L) / 1024; }
    }
}
