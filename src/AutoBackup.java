import java.io.IOException;
import java.time.*;
import java.util.*;

/**
 * Scheduled backups. Per server (cfg "autoBackup"): enabled, mode interval | daily | weekly, hours, at (HH:MM), days (1 = Monday ... 7 = Sunday), scope full | world,
 * skipIfIdle. How many to keep is the existing "keep the newest N" setting. A schedule that was missed (the manager or PC was off) runs at the next check.
 */
final class AutoBackup {
    private AutoBackup() { }

    static Map<String, Object> get(Srv s) {
        Map<String, Object> a = s.cfg.get("autoBackup") instanceof Map ? Json.obj(s.cfg.get("autoBackup")) : new LinkedHashMap<>();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", Boolean.TRUE.equals(a.get("enabled")));
        out.put("mode", a.get("mode") instanceof String m && List.of("interval", "daily", "weekly").contains(m) ? m : "daily");
        out.put("hours", a.get("hours") instanceof Number n ? Math.max(1, Math.min(168, n.intValue())) : 6);
        out.put("at", a.get("at") instanceof String t && t.matches("([01]\\d|2[0-3]):[0-5]\\d") ? t : "04:00");
        List<Object> days = new ArrayList<>();
        for (Object d : Json.arr(a.get("days"))) if (d instanceof Number n && n.intValue() >= 1 && n.intValue() <= 7) days.add(n.intValue());
        if (days.isEmpty()) days = new ArrayList<>(List.of(1, 2, 3, 4, 5, 6, 7));
        out.put("days", days);
        out.put("scope", "world".equals(a.get("scope")) ? "world" : "full");
        out.put("skipIfIdle", !Boolean.FALSE.equals(a.get("skipIfIdle")));
        out.put("last", a.get("last") instanceof Number n ? n.longValue() : 0L);
        out.put("since", a.get("since") instanceof Number n ? n.longValue() : 0L);
        out.put("active", Boolean.TRUE.equals(a.get("active")));
        out.put("lastResult", a.get("lastResult") instanceof String r ? r : "");
        return out;
    }

    static void set(Srv s, Map<String, Object> b) throws IOException {
        Map<String, Object> cur = get(s);
        Map<String, Object> next = new LinkedHashMap<>(cur);
        boolean wasOn = Boolean.TRUE.equals(cur.get("enabled"));
        if (b.containsKey("enabled")) next.put("enabled", Boolean.TRUE.equals(b.get("enabled")));
        if (b.containsKey("mode")) {
            String m = String.valueOf(b.get("mode"));
            if (!List.of("interval", "daily", "weekly").contains(m)) throw new Api(400, "Choose every few hours, daily or weekly.");
            next.put("mode", m);
        }
        if (b.containsKey("hours")) {
            int h = b.get("hours") instanceof Number n ? n.intValue() : -1;
            if (h < 1 || h > 168) throw new Api(400, "Hours must be between 1 and 168.");
            next.put("hours", h);
        }
        if (b.containsKey("at")) {
            String t = String.valueOf(b.get("at"));
            if (!t.matches("([01]\\d|2[0-3]):[0-5]\\d")) throw new Api(400, "Use a time like 04:00.");
            next.put("at", t);
        }
        if (b.containsKey("days")) {
            List<Object> days = new ArrayList<>();
            for (Object d : Json.arr(b.get("days"))) if (d instanceof Number n && n.intValue() >= 1 && n.intValue() <= 7 && !days.contains(n.intValue())) days.add(n.intValue());
            if (days.isEmpty()) throw new Api(400, "Pick at least one day of the week.");
            next.put("days", days);
        }
        if (b.containsKey("scope")) next.put("scope", "world".equals(b.get("scope")) ? "world" : "full");
        if (b.containsKey("skipIfIdle")) next.put("skipIfIdle", Boolean.TRUE.equals(b.get("skipIfIdle")));
        if (Boolean.TRUE.equals(next.get("enabled")) && !wasOn) {
            next.put("since", System.currentTimeMillis());
            next.put("active", true);
        }
        s.cfg.put("autoBackup", next);
        Main.cfg.save();
    }

    /** The newest scheduled moment at or before now (null for interval mode). */
    static ZonedDateTime lastSlot(Map<String, Object> a, ZonedDateTime now) {
        String mode = String.valueOf(a.get("mode"));
        if (mode.equals("interval")) return null;
        LocalTime t = LocalTime.parse(String.valueOf(a.get("at")));
        Set<Integer> days = new HashSet<>();
        for (Object d : Json.arr(a.get("days"))) days.add(((Number) d).intValue());
        for (int back = 0; back < 8; back++) {
            ZonedDateTime slot = now.toLocalDate().minusDays(back).atTime(t).atZone(now.getZone());
            if (slot.isAfter(now)) continue;
            if (mode.equals("daily") || days.contains(slot.getDayOfWeek().getValue())) return slot;
        }
        return null;
    }

    /** True when a backup is due right now. */
    static boolean due(Map<String, Object> a, long nowMs) {
        long last = ((Number) a.get("last")).longValue(), since = ((Number) a.get("since")).longValue();
        String mode = String.valueOf(a.get("mode"));
        if (mode.equals("interval")) {
            long every = ((Number) a.get("hours")).longValue() * 3_600_000L;
            return nowMs - Math.max(last, 0) >= every && (last > 0 || nowMs - since >= 60_000);
        }
        ZonedDateTime slot = lastSlot(a, ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs), ZoneId.systemDefault()));
        return slot != null && slot.toInstant().toEpochMilli() > Math.max(last, since);
    }

    private static void store(Srv s, String key, Object value) {
        Map<String, Object> a = s.cfg.get("autoBackup") instanceof Map ? Json.obj(s.cfg.get("autoBackup")) : new LinkedHashMap<>();
        a.put(key, value);
        s.cfg.put("autoBackup", a);
    }

    static synchronized void tick() {
        long now = System.currentTimeMillis();
        boolean dirty = false;
        for (Srv s : Main.servers) {
            Map<String, Object> a = get(s);
            if (!Boolean.TRUE.equals(a.get("enabled"))) continue;
            if (s.sawPlayer) {
                s.sawPlayer = false;
                if (!Boolean.TRUE.equals(a.get("active"))) { store(s, "active", true); a.put("active", true); dirty = true; }
            }
            if (!due(a, now)) continue;
            if (s.job != null && s.job.active()) continue;
            boolean idle = !Boolean.TRUE.equals(a.get("active")) && ((Number) a.get("last")).longValue() > 0;
            if (Boolean.TRUE.equals(a.get("skipIfIdle")) && idle && s.online.isEmpty()) {
                store(s, "last", now);
                store(s, "lastResult", "skipped: nobody played since the last backup");
                dirty = true;
                continue;
            }
            String scope = String.valueOf(a.get("scope"));
            store(s, "last", now);
            dirty = true;
            try {
                Main.submit(s, "Automatic backup", j -> {
                    try {
                        java.nio.file.Path zip = Backups.create(j, s, scope, Backups.dirFor(s), "Automatic backup", "auto", "auto-backup");
                        synchronized (AutoBackup.class) {
                            store(s, "active", false);
                            store(s, "lastResult", "ok: " + zip.getFileName());
                            try { Main.cfg.save(); } catch (IOException ignored) { }
                        }
                        Users.audit("auto-backup", "", "automatic backup", s.id, zip.getFileName().toString());
                    } catch (Job.Cancelled c) {
                        throw c;
                    } catch (Exception e) {
                        synchronized (AutoBackup.class) {
                            store(s, "lastResult", "failed: " + e.getMessage());
                            try { Main.cfg.save(); } catch (IOException ignored) { }
                        }
                        Notify.postOnce("autobackup-fail:" + s.id, 6 * 3_600_000L, "backup", "bad", s.id, "Automatic backup of " + s.name + " failed", String.valueOf(e.getMessage()),
                                Json.map("type", "open", "server", s.id, "tab", "console", "label", "Open " + s.name));
                        throw e;
                    }
                });
            } catch (Api busy) {
                store(s, "last", ((Number) a.get("last")).longValue());
            }
        }
        if (dirty) {
            try { Main.cfg.save(); } catch (IOException e) { Main.log("auto-backup: could not save settings: " + e); }
        }
    }
}
