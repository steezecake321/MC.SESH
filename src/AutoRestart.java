import java.io.IOException;
import java.time.*;
import java.util.*;

/**
 * Automatic restarts. Per server (cfg "autoRestart"):
 *  - by time: timeOn, at (HH:MM), days (1 = Monday ... 7 = Sunday), and what to do when players are online at that moment:
 *    mode "countdown" (warn them and restart after warnMin minutes) or "wait" (wait until the server is empty, and after waitMin minutes restart anyway with the countdown);
 *  - when empty: idleOn, idleMin (nobody online for this many minutes), minUpHours (and the server has been running at least this long);
 *  - both can be on together.
 * A scheduled time that passed while the server was stopped is not made up for (a server started after the time is left alone), and one missed by more than two hours is skipped.
 */
final class AutoRestart {
    private AutoRestart() { }

    private static final String TIME_RE = "([01]\\d|2[0-3]):[0-5]\\d";
    private static final long MISSED_AFTER_MS = 2 * 3_600_000L;
    private static final Map<String, Long> emptySince = new HashMap<>();

    private static int clamp(Object v, int lo, int hi, int def) { return v instanceof Number n ? Math.max(lo, Math.min(hi, n.intValue())) : def; }

    /** Validated settings with defaults filled in. */
    static Map<String, Object> normalize(Map<String, Object> a) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("timeOn", Boolean.TRUE.equals(a.get("timeOn")));
        out.put("at", a.get("at") instanceof String t && t.matches(TIME_RE) ? t : "04:00");
        List<Object> days = new ArrayList<>();
        for (Object d : Json.arr(a.get("days"))) if (d instanceof Number n && n.intValue() >= 1 && n.intValue() <= 7 && !days.contains(n.intValue())) days.add(n.intValue());
        if (days.isEmpty()) days = new ArrayList<>(List.of(1, 2, 3, 4, 5, 6, 7));
        out.put("days", days);
        out.put("mode", "wait".equals(a.get("mode")) ? "wait" : "countdown");
        out.put("waitMin", clamp(a.get("waitMin"), 1, 720, 30));
        out.put("warnMin", clamp(a.get("warnMin"), 1, 30, 5));
        out.put("idleOn", Boolean.TRUE.equals(a.get("idleOn")));
        out.put("idleMin", clamp(a.get("idleMin"), 1, 1440, 30));
        out.put("minUpHours", clamp(a.get("minUpHours"), 0, 168, 6));
        return out;
    }

    /** For defaults and API input: rejects values that are plainly wrong instead of quietly fixing them. */
    static Map<String, Object> clean(Map<String, Object> b) {
        if (b.containsKey("at") && !(b.get("at") instanceof String t && t.matches(TIME_RE))) throw new Api(400, "Use a time like 04:00.");
        if (b.containsKey("days") && Json.arr(b.get("days")).isEmpty()) throw new Api(400, "Pick at least one day of the week.");
        if (b.containsKey("mode") && !List.of("countdown", "wait").contains(String.valueOf(b.get("mode")))) throw new Api(400, "Choose countdown or wait-until-empty.");
        for (String[] r : new String[][]{{"waitMin", "1", "720"}, {"warnMin", "1", "30"}, {"idleMin", "1", "1440"}, {"minUpHours", "0", "168"}}) {
            if (!b.containsKey(r[0])) continue;
            int v = b.get(r[0]) instanceof Number n ? n.intValue() : -1;
            if (v < Integer.parseInt(r[1]) || v > Integer.parseInt(r[2])) throw new Api(400, r[0] + " must be between " + r[1] + " and " + r[2] + ".");
        }
        return normalize(b);
    }

    static Map<String, Object> get(Srv s) {
        Map<String, Object> a = s.cfg.get("autoRestart") instanceof Map ? Json.obj(s.cfg.get("autoRestart")) : new LinkedHashMap<>();
        Map<String, Object> out = normalize(a);
        out.put("last", a.get("last") instanceof Number n ? n.longValue() : 0L);
        out.put("lastResult", a.get("lastResult") instanceof String r ? r : "");
        Long next = Boolean.TRUE.equals(out.get("timeOn")) ? nextSlot(out, System.currentTimeMillis()) : null;
        out.put("next", next == null ? 0L : next);
        return out;
    }

    static synchronized void set(Srv s, Map<String, Object> b) throws IOException {
        Map<String, Object> cur = get(s);
        Map<String, Object> merged = new LinkedHashMap<>(cur);
        for (Map.Entry<String, Object> e : b.entrySet()) if (cur.containsKey(e.getKey()) && !List.of("last", "lastResult", "next").contains(e.getKey())) merged.put(e.getKey(), e.getValue());
        Map<String, Object> next = clean(merged);
        next.put("last", cur.get("last"));
        next.put("lastResult", cur.get("lastResult"));
        s.cfg.put("autoRestart", next);
        Main.cfg.save();
    }

    // ---------- the schedule ----------

    private static Set<Integer> dayset(Map<String, Object> a) {
        Set<Integer> days = new HashSet<>();
        for (Object d : Json.arr(a.get("days"))) days.add(((Number) d).intValue());
        return days;
    }

    /** The newest scheduled moment at or before now, or null. */
    static Long lastSlot(Map<String, Object> a, long nowMs) {
        ZonedDateTime now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs), ZoneId.systemDefault());
        LocalTime t = LocalTime.parse(String.valueOf(a.get("at")));
        Set<Integer> days = dayset(a);
        for (int back = 0; back < 8; back++) {
            ZonedDateTime slot = now.toLocalDate().minusDays(back).atTime(t).atZone(now.getZone());
            if (!slot.isAfter(now) && days.contains(slot.getDayOfWeek().getValue())) return slot.toInstant().toEpochMilli();
        }
        return null;
    }

    /** The next scheduled moment after now, or null. */
    static Long nextSlot(Map<String, Object> a, long nowMs) {
        ZonedDateTime now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs), ZoneId.systemDefault());
        LocalTime t = LocalTime.parse(String.valueOf(a.get("at")));
        Set<Integer> days = dayset(a);
        for (int ahead = 0; ahead < 8; ahead++) {
            ZonedDateTime slot = now.toLocalDate().plusDays(ahead).atTime(t).atZone(now.getZone());
            if (slot.isAfter(now) && days.contains(slot.getDayOfWeek().getValue())) return slot.toInstant().toEpochMilli();
        }
        return null;
    }

    private static void store(Srv s, String key, Object value) {
        Map<String, Object> a = s.cfg.get("autoRestart") instanceof Map ? Json.obj(s.cfg.get("autoRestart")) : new LinkedHashMap<>(get(s));
        a.put(key, value);
        s.cfg.put("autoRestart", a);
    }

    private static void save() {
        try { Main.cfg.save(); } catch (IOException e) { Main.log("auto-restart: could not save settings: " + e); }
    }

    // ---------- the check (runs every 30 seconds) ----------

    static synchronized void tick() {
        long now = System.currentTimeMillis();
        Set<String> seen = new HashSet<>();
        for (Srv s : Main.servers) {
            Map<String, Object> a = get(s);
            boolean timeOn = Boolean.TRUE.equals(a.get("timeOn")), idleOn = Boolean.TRUE.equals(a.get("idleOn"));
            if (!timeOn && !idleOn) continue;
            Map<String, Object> st = s.status();
            if (!"running".equals(st.get("state"))) continue;
            seen.add(s.id);
            long uptime = st.get("uptime") instanceof Number n ? n.longValue() : 0;
            int players = st.get("players") instanceof List<?> l ? l.size() : 0;
            if (uptime < 90 || (s.job != null && s.job.active())) continue;
            long last = ((Number) a.get("last")).longValue();
            int warn = ((Number) a.get("warnMin")).intValue() * 60;

            // when empty
            if (players > 0) emptySince.remove(s.id);
            else emptySince.putIfAbsent(s.id, now);
            if (idleOn && players == 0) {
                long idleFor = now - emptySince.get(s.id);
                if (idleFor >= ((Number) a.get("idleMin")).longValue() * 60_000L && uptime >= ((Number) a.get("minUpHours")).longValue() * 3600L) {
                    restart(s, 0, "nobody was online for " + a.get("idleMin") + " minutes");
                    continue;
                }
            }

            // by time
            if (!timeOn) continue;
            Long slot = lastSlot(a, now);
            if (slot == null || slot <= last) continue;
            long startedAt = now - uptime * 1000L;
            if (startedAt >= slot) continue;                       // the server started after that time: leave it alone
            if (now - slot > MISSED_AFTER_MS) {                    // missed by a long way (the manager or PC was off)
                store(s, "last", now);
                store(s, "lastResult", "skipped: the scheduled time was missed");
                save();
                continue;
            }
            if ("wait".equals(a.get("mode"))) {
                if (players == 0) restart(s, 0, "scheduled, the server was empty");
                else if (now - slot >= ((Number) a.get("waitMin")).longValue() * 60_000L) restart(s, warn, "scheduled, players did not leave within " + a.get("waitMin") + " minutes");
            } else {
                restart(s, players > 0 ? warn : 0, players > 0 ? "scheduled, players warned " + a.get("warnMin") + " minutes ahead" : "scheduled");
            }
        }
        emptySince.keySet().removeIf(id -> !seen.contains(id));
    }

    private static void restart(Srv s, int delaySec, String why) {
        long now = System.currentTimeMillis();
        store(s, "last", now);
        store(s, "lastResult", "restarting: " + why);
        save();
        emptySince.remove(s.id);
        Users.audit("auto-restart", "", "automatic restart", s.id, why);
        try {
            Main.submit(s, "Automatic restart", j -> {
                try {
                    s.stop(j, delaySec, "restart");
                    s.start(j);
                    synchronized (AutoRestart.class) { store(s, "lastResult", "ok: " + why); save(); }
                } catch (Job.Cancelled c) {
                    throw c;
                } catch (Exception e) {
                    synchronized (AutoRestart.class) { store(s, "lastResult", "failed: " + e.getMessage()); save(); }
                    Notify.postOnce("autorestart-fail:" + s.id, 6 * 3_600_000L, "health", "bad", s.id, "Automatic restart of " + s.name + " failed", String.valueOf(e.getMessage()),
                            Json.map("type", "open", "server", s.id, "tab", "console", "label", "Open " + s.name));
                    throw e;
                }
            });
        } catch (Api busy) {
            store(s, "lastResult", "skipped: the server was busy");
            save();
        }
    }
}
