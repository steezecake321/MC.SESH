import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.*;

/**
 * Optional, per server: keeps the in-game operator list (ops.json) in step with the manager's accounts. An account that has a Minecraft
 * username and level 1-4 on a server (or is an administrator) becomes an operator of that level; level 0 or no access means no operator
 * entry. Only entries this feature created are ever changed or removed; operators added by hand are left alone. A running server keeps its
 * own copy of the list in memory, so file changes are applied at the next start; people who lose access are also de-opped immediately.
 */
final class OpsSync {
    private OpsSync() { }

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static final Map<String, String> UUIDS = new HashMap<>();
    private static final java.util.concurrent.atomic.AtomicBoolean SCHEDULED = new java.util.concurrent.atomic.AtomicBoolean();

    /** On unless someone switched it off for this server: a server that never had the setting counts as on. */
    static boolean enabled(Srv s) { return !Boolean.FALSE.equals(s.cfg.get("opsSync")); }

    private static Path cacheFile() { return Main.DATA.resolve("uuid-cache.json"); }

    static synchronized String uuidFor(Srv s, String name) {
        String key = name.toLowerCase();
        if (UUIDS.isEmpty()) {
            try {
                if (Files.exists(cacheFile())) for (Map.Entry<String, Object> e : Json.obj(Json.parse(Files.readString(cacheFile()))).entrySet()) UUIDS.put(e.getKey(), String.valueOf(e.getValue()));
            } catch (IOException | RuntimeException ignored) { }
        }
        boolean online = !"false".equalsIgnoreCase(s.props().getOrDefault("online-mode", "true").trim());
        String ck = (online ? "on:" : "off:") + key;
        if (UUIDS.containsKey(ck)) return UUIDS.get(ck);
        String id = null;
        if (!online) {
            id = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8)).toString();
        } else {
            id = lookupMojang(name);
        }
        if (id != null) {
            UUIDS.put(ck, id);
            try { Files.writeString(cacheFile(), Json.stringify(new LinkedHashMap<String, Object>(UUIDS)), StandardCharsets.UTF_8); } catch (IOException ignored) { }
        }
        return id;
    }

    /** The Mojang account UUID (with dashes) for a Minecraft username, or null when it does not exist or cannot be looked up. */
    static String lookupMojang(String name) {
        try {
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create("https://api.mojang.com/users/profiles/minecraft/" + java.net.URLEncoder.encode(name, StandardCharsets.UTF_8)))
                    .timeout(Duration.ofSeconds(10)).header("User-Agent", "MCServerManager/" + Main.version()).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (r.statusCode() == 200) {
                String raw = Json.str(Json.parse(r.body()), "id");
                if (raw != null && raw.matches("[0-9a-fA-F]{32}"))
                    return raw.substring(0, 8) + "-" + raw.substring(8, 12) + "-" + raw.substring(12, 16) + "-" + raw.substring(16, 20) + "-" + raw.substring(20);
            }
        } catch (IOException | RuntimeException e) {
            Main.log("could not look up " + name + " at Mojang: " + e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return null;
    }
    /** Wanted operator level per Minecraft name (lower case) for one server. */
    private static Map<String, Integer> desired(Srv s) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Users.Acct a : Users.all()) {
            if (a.disabled || a.mcName == null || a.mcName.isBlank() || !Srv.validPlayerName(a.mcName)) continue;
            int lvl = a.admin ? 4 : Math.min(4, a.grants.getOrDefault(s.id, -1));
            if (lvl >= 1) out.merge(a.mcName.toLowerCase(), lvl, Math::max);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<String> managed(Srv s) {
        Object o = s.cfg.get("opsManaged");
        List<String> l = new ArrayList<>();
        if (o instanceof List<?> li) for (Object x : li) l.add(String.valueOf(x).toLowerCase());
        return l;
    }

    private static void saveManaged(Srv s, List<String> names) throws IOException {
        s.cfg.put("opsManaged", new ArrayList<>(names));
        Main.cfg.save();
    }

    /** Brings ops.json in line when the server is stopped; when it is running only removes people who lost access (via RCON deop). */
    static synchronized void apply(Srv s) {
        if (!enabled(s)) return;
        try {
            Map<String, Integer> want = desired(s);
            List<String> mine = managed(s);
            boolean running = s.running(false);
            if (running) {
                for (String n : new ArrayList<>(mine)) {
                    if (want.containsKey(n)) continue;
                    try { s.command("deop " + n); mine.remove(n); } catch (RuntimeException e) { Main.log("ops sync: deop " + n + " failed: " + e.getMessage()); }
                }
                saveManaged(s, mine);
                return;
            }
            Path f = s.dir.resolve("ops.json");
            List<Object> entries = Files.exists(f) ? new ArrayList<>(Json.arr(Json.parse(Files.readString(f, StandardCharsets.UTF_8)))) : new ArrayList<>();
            boolean changed = false;
            Set<String> present = new HashSet<>();
            for (Iterator<Object> it = entries.iterator(); it.hasNext(); ) {
                Map<String, Object> e = Json.obj(it.next());
                String n = String.valueOf(e.get("name")).toLowerCase();
                present.add(n);
                if (!mine.contains(n)) continue;
                Integer lvl = want.get(n);
                if (lvl == null) { it.remove(); mine.remove(n); changed = true; }
                else if (Json.num(e, "level", 0) != lvl) { e.put("level", lvl); changed = true; }
            }
            for (Map.Entry<String, Integer> w : want.entrySet()) {
                if (present.contains(w.getKey())) continue;
                String display = null;
                for (Users.Acct a : Users.all()) if (a.mcName != null && a.mcName.equalsIgnoreCase(w.getKey())) display = a.mcName;
                String uuid = display == null ? null : uuidFor(s, display);
                if (uuid == null) { Main.log("ops sync: no UUID for " + w.getKey() + ", skipped"); continue; }
                entries.add(Json.map("uuid", uuid, "name", display, "level", w.getValue(), "bypassesPlayerLimit", false));
                mine.add(w.getKey());
                changed = true;
            }
            if (changed) {
                Path tmp = s.dir.resolve("ops.json.tmp");
                Files.writeString(tmp, Json.stringify(entries), StandardCharsets.UTF_8);
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
                Main.log("ops sync: updated ops.json for " + s.id);
            }
            saveManaged(s, mine);
        } catch (IOException | RuntimeException e) {
            Main.log("ops sync " + s.id + ": " + e);
        }
    }

    static void applyAll() {
        for (Srv s : Main.servers) apply(s);
    }

    /** Called after any account change; runs in the background and coalesces bursts of changes. */
    static void accountsChanged() {
        // no locks here: this is called while the accounts lock is held, and apply() takes the accounts lock too
        if (!SCHEDULED.compareAndSet(false, true)) return;
        Thread.startVirtualThread(() -> {
            try { Thread.sleep(1500); } catch (InterruptedException ignored) { }
            SCHEDULED.set(false);
            try { applyAll(); } catch (RuntimeException e) { Main.log("ops sync: " + e); }
        });
    }
}
