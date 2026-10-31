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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Bedrock support through Geyser and Floodgate: installation check, settings, the Bedrock whitelist helper, the approval list for
 * Bedrock players who are turned away by the whitelist, and the Windows Firewall rule for the Bedrock (UDP) port.
 */
final class Geyser {
    private Geyser() { }

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static final Pattern DENIED = Pattern.compile("Disconnecting\\s+(\\S+)\\s+\\(/?[^)]*\\):\\s*You are not whitelisted on this server");
    private static final Pattern GAMERTAG = Pattern.compile("[A-Za-z0-9 _-]{1,20}");
    private static final Pattern STARTED = Pattern.compile("Started Geyser on UDP port (\\d+)");
    static final String BEDROCK_UUID_PREFIX = "00000000-0000-0000-";

    // ---------- what is installed ----------

    private static boolean hasJar(Srv s, String word) {
        for (String base : new String[]{"mods", "plugins"}) {
            Path d = s.dir.resolve(base);
            if (!Files.isDirectory(d)) continue;
            try (Stream<Path> st = Files.list(d)) {
                if (st.anyMatch(p -> { String n = p.getFileName().toString().toLowerCase(); return n.contains(word) && (n.endsWith(".jar") || n.endsWith(".jar.disabled")); })) return true;
            } catch (IOException ignored) { }
        }
        return false;
    }

    static boolean installed(Srv s) { return hasJar(s, "geyser") || GeyserCfg.find(s.dir, "geyser") != null; }

    // ---------- Floodgate naming ----------

    static String prefix(Srv s) {
        try {
            Path f = GeyserCfg.find(s.dir, "floodgate");
            if (f != null) {
                for (String l : GeyserCfg.read(f)) {
                    Matcher m = Pattern.compile("^username-prefix:\\s*[\"']?([^\"'#\\s]*)").matcher(l);
                    if (m.find() && !m.group(1).isEmpty()) return m.group(1);
                }
            }
        } catch (IOException ignored) { }
        return ".";
    }

    static boolean replaceSpaces(Srv s) {
        try {
            Path f = GeyserCfg.find(s.dir, "floodgate");
            if (f != null) for (String l : GeyserCfg.read(f)) if (l.matches("^replace-spaces:\\s*false.*")) return false;
        } catch (IOException ignored) { }
        return true;
    }

    // ---------- Xbox lookup (GeyserMC's public API) ----------

    /** The Xbox user id for a gamertag, or null when the name is unknown or the service is unavailable. */
    static Long xuid(String gamertag) {
        try {
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create("https://api.geysermc.org/v2/xbox/xuid/" + java.net.URLEncoder.encode(gamertag, StandardCharsets.UTF_8).replace("+", "%20")))
                    .timeout(Duration.ofSeconds(12)).header("User-Agent", "MCServerManager/" + Main.version()).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (r.statusCode() != 200) return null;
            Object x = Json.obj(Json.parse(r.body())).get("xuid");
            return x instanceof Number n ? n.longValue() : null;
        } catch (IOException | RuntimeException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** Floodgate gives every Bedrock player a Java UUID made from their Xbox id. */
    static String floodgateUuid(long xuid) { return new UUID(0L, xuid).toString(); }

    // ---------- Bedrock whitelist ----------

    private static Path whitelistFile(Srv s) { return s.dir.resolve("whitelist.json"); }

    private static List<Object> readWhitelist(Srv s) throws IOException {
        Path f = whitelistFile(s);
        return Files.exists(f) ? new ArrayList<>(Json.arr(Json.parse(Files.readString(f, StandardCharsets.UTF_8)))) : new ArrayList<>();
    }

    private static void writeWhitelist(Srv s, List<Object> entries) throws IOException {
        Path tmp = s.dir.resolve("whitelist.json.tmp");
        Files.writeString(tmp, Json.stringify(entries), StandardCharsets.UTF_8);
        Files.move(tmp, whitelistFile(s), StandardCopyOption.REPLACE_EXISTING);
        if (s.running(false)) {
            try { s.command("whitelist reload"); } catch (RuntimeException e) { Main.log("geyser: whitelist reload failed: " + e.getMessage()); }
        }
    }

    static List<Map<String, Object>> bedrockWhitelist(Srv s) {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            for (Object o : readWhitelist(s)) {
                String uuid = Json.str(o, "uuid");
                if (uuid != null && uuid.startsWith(BEDROCK_UUID_PREFIX)) out.add(Json.map("uuid", uuid, "name", Json.str(o, "name")));
            }
        } catch (IOException | RuntimeException ignored) { }
        return out;
    }

    /** Adds a Bedrock player (gamertag, with or without the Floodgate prefix) to the whitelist. */
    static synchronized Map<String, Object> whitelistAdd(Srv s, String input) throws IOException {
        String pre = prefix(s);
        String tag = input == null ? "" : input.strip();
        if (!pre.isEmpty() && tag.startsWith(pre)) tag = tag.substring(pre.length());
        if (!GAMERTAG.matcher(tag).matches()) throw new Api(400, "That does not look like an Xbox gamertag (letters, digits, spaces, - and _).");
        Long x = xuid(tag);
        if (x == null && tag.contains("_")) x = xuid(tag.replace('_', ' '));
        if (x == null) throw new Api(404, "Could not find the gamertag \"" + tag + "\". Check the spelling (spaces count), or try again in a moment: the lookup service is run by GeyserMC and is sometimes unavailable.");
        String uuid = floodgateUuid(x);
        String shown = pre + (replaceSpaces(s) ? tag.replace(' ', '_') : tag);
        List<Object> entries = readWhitelist(s);
        for (Object o : entries) if (uuid.equalsIgnoreCase(Json.str(o, "uuid"))) throw new Api(409, shown + " is already whitelisted.");
        entries.add(Json.map("uuid", uuid, "name", shown));
        writeWhitelist(s, entries);
        return Json.map("name", shown, "uuid", uuid);
    }

    static synchronized void whitelistRemove(Srv s, String uuid) throws IOException {
        if (uuid == null || !uuid.startsWith(BEDROCK_UUID_PREFIX)) throw new Api(400, "Only Bedrock entries can be removed here.");
        List<Object> entries = readWhitelist(s);
        boolean removed = entries.removeIf(o -> uuid.equalsIgnoreCase(Json.str(o, "uuid")));
        if (!removed) throw new Api(404, "That player is not on the whitelist.");
        writeWhitelist(s, entries);
    }

    // ---------- approvals for Bedrock players the whitelist turned away ----------

    static String mode(Srv s) {
        String m = Json.str(s.cfg, "geyserMode");
        if ("auto".equals(m)) {
            long until = s.cfg.get("geyserAutoUntil") instanceof Number n ? n.longValue() : 0;
            if (until != 0 && until < System.currentTimeMillis()) return "queue";
            return "auto";
        }
        return "queue".equals(m) ? "queue" : "off";
    }

    static long autoUntil(Srv s) { return s.cfg.get("geyserAutoUntil") instanceof Number n ? n.longValue() : 0; }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> pendingList(Srv s) {
        Object o = s.cfg.get("geyserPending");
        List<Map<String, Object>> l = new ArrayList<>();
        if (o instanceof List<?> li) for (Object x : li) if (x instanceof Map<?, ?> m) l.add((Map<String, Object>) m);
        return l;
    }

    static List<Map<String, Object>> pending(Srv s) { return pendingList(s); }

    static synchronized void setMode(Srv s, String mode, int minutes) throws IOException {
        if (!List.of("off", "queue", "auto").contains(mode)) throw new Api(400, "Unknown mode");
        s.cfg.put("geyserMode", mode);
        if (mode.equals("auto")) {
            if (minutes < 1 || minutes > 7 * 24 * 60) throw new Api(400, "Choose how long auto-approve stays on (1 minute to 7 days).");
            s.cfg.put("geyserAutoUntil", System.currentTimeMillis() + minutes * 60_000L);
        } else s.cfg.remove("geyserAutoUntil");
        Main.cfg.save();
    }

    private static synchronized void addPending(Srv s, String name) {
        List<Map<String, Object>> l = pendingList(s);
        long now = System.currentTimeMillis();
        for (Map<String, Object> e : l) {
            if (name.equalsIgnoreCase(Json.str(e, "name"))) {
                e.put("last", now);
                e.put("count", Json.num(e, "count", 1) + 1);
                save(s, l);
                return;
            }
        }
        l.add(Json.map("name", name, "first", now, "last", now, "count", 1));
        while (l.size() > 50) l.remove(0);
        save(s, l);
    }

    private static void save(Srv s, List<Map<String, Object>> l) {
        s.cfg.put("geyserPending", new ArrayList<>(l));
        try { Main.cfg.save(); } catch (IOException e) { Main.log("geyser: could not save pending list: " + e); }
    }

    static synchronized void dismiss(Srv s, String name) {
        List<Map<String, Object>> l = pendingList(s);
        l.removeIf(e -> name.equalsIgnoreCase(Json.str(e, "name")));
        save(s, l);
    }

    /** Called for every new console line of a server. Cheap unless the line is a whitelist rejection. */
    static void onLine(Srv s, String line) {
        if (line.indexOf("not whitelisted") < 0) return;
        String m = mode(s);
        if (m.equals("off")) return;
        Matcher d = DENIED.matcher(line);
        if (!d.find()) return;
        String name = d.group(1);
        String pre = prefix(s);
        if (pre.isEmpty() || !name.startsWith(pre)) return; // a Java player: not handled here
        addPending(s, name);
        if (m.equals("queue") && Main.uptimeMs() > 15_000) {
            Notify.postOnce("bedrock-wait:" + s.id + ":" + name, 3_600_000L, "player", "info", s.id, name + " (Bedrock) is waiting to be approved",
                    name + " tried to join " + s.name + " but is not on the whitelist. If you know them, approve them on the Bedrock tab.", Json.map("type", "open", "server", s.id, "tab", "geyser", "label", "Open the Bedrock tab"));
        }
        if (m.equals("auto")) {
            Thread.startVirtualThread(() -> {
                try {
                    whitelistAdd(s, name);
                    dismiss(s, name);
                    Users.audit("geyser", "", "bedrock player auto-approved", s.id, name);
                    Main.log("geyser: auto-approved " + name + " on " + s.id);
                } catch (IOException | RuntimeException e) {
                    Main.log("geyser: could not auto-approve " + name + ": " + e.getMessage());
                }
            });
        }
    }

    // ---------- Windows Firewall (UDP) ----------

    static String ruleName(int port) { return "MC Bedrock " + port; }

    static boolean tcpRule(int port) {
        return Sys.run(8000, "netsh", "advfirewall", "firewall", "show", "rule", "name=" + ruleName(port) + " TCP").code() == 0;
    }

    static boolean firewallRule(int port) {
        return Sys.run(8000, "netsh", "advfirewall", "firewall", "show", "rule", "name=" + ruleName(port)).code() == 0;
    }

    // ---------- status for the tab ----------

    static Map<String, Object> status(Srv s) {
        Map<String, Object> out = new LinkedHashMap<>();
        boolean gey = installed(s), flood = hasJar(s, "floodgate") || GeyserCfg.find(s.dir, "floodgate") != null;
        out.put("geyser", gey);
        out.put("floodgate", flood);
        Path cfg = GeyserCfg.find(s.dir, "geyser");
        out.put("configFound", cfg != null);
        int game = s.gamePort();
        Map<String, Object> set = new LinkedHashMap<>();
        if (cfg != null) {
            try {
                List<String> l = GeyserCfg.read(cfg);
                set.put("port", GeyserCfg.get(l, "bedrock", "port"));
                set.put("cloneRemotePort", "true".equalsIgnoreCase(GeyserCfg.get(l, "bedrock", "clone-remote-port")));
                set.put("transport", GeyserCfg.get(l, "bedrock", "transport"));
                set.put("authType", GeyserCfg.get(l, "java", "auth-type"));
                set.put("primaryMotd", GeyserCfg.get(l, "motd", "primary-motd"));
                set.put("secondaryMotd", GeyserCfg.get(l, "motd", "secondary-motd"));
                set.put("passthroughMotd", "true".equalsIgnoreCase(GeyserCfg.get(l, "motd", "passthrough-motd")));
                set.put("passthroughCounts", "true".equalsIgnoreCase(GeyserCfg.get(l, "motd", "passthrough-player-counts")));
                set.put("maxPlayers", GeyserCfg.get(l, "motd", "max-players"));
                set.put("serverName", GeyserCfg.get(l, "gameplay", "server-name"));
            } catch (IOException e) {
                out.put("configError", e.getMessage());
            }
        }
        out.put("settings", set);
        out.put("syncNames", namesSync(s));
        boolean clone = Boolean.TRUE.equals(set.get("cloneRemotePort"));
        int bedrock = clone ? game : parsePort(set.get("port"), 19132);
        out.put("bedrockPort", cfg != null ? bedrock : null);
        String clash = null;
        if (cfg != null) for (Srv o : Main.servers) if (o != s && Ports.of(o)[3] == bedrock) clash = o.name;
        out.put("bedrockClash", clash);
        // what Geyser says it is actually doing
        Integer started = null;
        try {
            List<String> tail = s.log.tail(600);
            for (String ln : tail) { Matcher m = STARTED.matcher(ln); if (m.find()) started = Integer.parseInt(m.group(1)); }
        } catch (RuntimeException ignored) { }
        out.put("startedOnPort", started);
        String transport = set.get("transport") == null ? null : String.valueOf(set.get("transport"));
        int web = cfg == null ? -1 : Ports.of(s)[4];
        out.put("transport", transport);
        out.put("nethernet", transport != null && !transport.equalsIgnoreCase("raknet") && !clone);
        out.put("tooOld", cfg != null && transport == null);
        out.put("webrtcPort", web > 0 ? web : null);
        out.put("prefix", prefix(s));
        out.put("whitelistOn", "true".equalsIgnoreCase(s.props().getOrDefault("white-list", "false").trim()));
        out.put("mode", mode(s));
        out.put("autoUntil", autoUntil(s));
        out.put("pending", pending(s));
        out.put("whitelist", bedrockWhitelist(s));
        out.put("firewall", cfg != null ? firewallRule(bedrock) && (web <= 0 || firewallRule(web)) && (!Boolean.TRUE.equals(out.get("nethernet")) || tcpRule(bedrock)) : null);
        out.put("ruleName", ruleName(bedrock));
        int online = 0;
        String pre = prefix(s);
        for (Object p : Json.arr(s.status().get("players"))) { String n = Json.str(p, "name"); if (n != null && !pre.isEmpty() && n.startsWith(pre)) online++; }
        out.put("bedrockOnline", online);
        return out;
    }

    private static int parsePort(Object o, int def) {
        try { return Integer.parseInt(String.valueOf(o).trim()); } catch (NumberFormatException e) { return def; }
    }

    // ---------- Bedrock names that follow the Java server ----------

    /** On unless switched off: Bedrock's server name and the two server-list lines are copied from the Java server. */
    static boolean namesSync(Srv s) { return !Boolean.FALSE.equals(s.cfg.get("geyserNames")); }

    private static boolean setIfDifferent(List<String> l, String sec, String key, String value) {
        String cur = GeyserCfg.get(l, sec, key);
        if (cur == null || cur.equals(value)) return false;
        return GeyserCfg.set(l, sec, key, value, true);
    }

    /** Copies the Java server name and MOTD lines into Geyser's settings. Returns true when the file changed (Geyser reads it at its next start). */
    static boolean syncNames(Srv s) throws IOException {
        if (!namesSync(s)) return false;
        Path cfg = GeyserCfg.find(s.dir, "geyser");
        if (cfg == null) return false;
        String[] lines = s.props().getOrDefault("motd", "").split("\\r?\\n", -1);
        String first = lines.length > 0 ? lines[0].strip() : "", second = lines.length > 1 ? lines[1].strip() : "";
        if (first.isEmpty()) first = s.name;
        if (first.length() > 100) first = first.substring(0, 100);
        if (second.length() > 100) second = second.substring(0, 100);
        List<String> l = GeyserCfg.read(cfg);
        boolean ch = setIfDifferent(l, "motd", "primary-motd", first);
        ch |= setIfDifferent(l, "motd", "secondary-motd", second);
        ch |= setIfDifferent(l, "gameplay", "server-name", s.name);
        if (ch) {
            GeyserCfg.write(cfg, l);
            Main.log("geyser: Bedrock names of " + s.id + " now follow the Java server (" + first + ")");
        }
        return ch;
    }

    // ---------- the Bedrock (UDP) port ----------

    static boolean udpFree(int port) {
        try (java.net.DatagramSocket d = new java.net.DatagramSocket(null)) {
            d.setReuseAddress(false);
            d.bind(new java.net.InetSocketAddress(port));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Geyser that cannot open its UDP port shuts the whole server down, so this runs before every start. When the port is taken and the Java port is free for UDP,
     * Geyser is switched to the Java port (as the other servers already do); otherwise the start is refused with an explanation. Returns a line for the job log, or null.
     */
    static String prepareBedrockPort(Srv s) throws IOException {
        Path cfg = GeyserCfg.find(s.dir, "geyser");
        if (cfg == null) return null;
        String migrated = migrateNetherNet(s);
        int[] ports = Ports.of(s);
        int port = ports[3];
        if (port <= 0) return migrated;
        if (isModern(cfg)) {
            // NetherNet: signaling listens on TCP of the Bedrock port, gameplay on UDP of the Bedrock port and of the NetherNet port. Any clash shuts the server down.
            int web = ports[4];
            if (tcpFree(port) && udpFree(port) && (web <= 0 || udpFree(web))) return migrated;
            List<String> l = GeyserCfg.read(cfg);
            String msg = applyNetherNet(s, l, cfg, -1, "Bedrock port " + port + " (or " + web + ") was already in use");
            return msg;
        }
        if (udpFree(port)) return migrated;
        String who = "another program";
        for (Srv o : Main.servers) if (o != s && Ports.of(o)[3] == port && o.running(false)) who = o.name;
        int game = s.gamePort();
        List<String> l = GeyserCfg.read(cfg);
        boolean clone = "true".equalsIgnoreCase(GeyserCfg.get(l, "bedrock", "clone-remote-port"));
        if (!clone && udpFree(game) && !Ports.allUsed(s).contains(game)) {
            GeyserCfg.set(l, "bedrock", "clone-remote-port", "true", false);
            GeyserCfg.write(cfg, l);
            String msg = "Bedrock port " + port + " (UDP) is already used by " + who + ", so Geyser now uses this server's Java port " + game + " for Bedrock. Allow UDP " + game + " in the Windows Firewall from the Bedrock tab.";
            Notify.post("health", "info", s.id, "Bedrock port changed on " + s.name, msg, Json.map("type", "open", "server", s.id, "tab", "geyser", "label", "Open the Bedrock tab"));
            return msg;
        }
        throw new Api(409, "Bedrock (Geyser) on " + s.name + " needs UDP port " + port + ", but " + who + " is already using it, and Geyser would shut the server down. "
                + "Open the Bedrock tab and give Geyser a free port (or tick the option that reuses the Java port), then start again.");
    }

    // ---------- NetherNet (the new Bedrock connection method; Bedrock 26.60 drops the old RakNet one) ----------

    static boolean tcpFree(int port) {
        try (java.net.ServerSocket ss = new java.net.ServerSocket()) {
            ss.setReuseAddress(false);
            ss.bind(new java.net.InetSocketAddress(port));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** True when Geyser's settings accept NetherNet (transport "both" or "nethernet"). */
    static boolean isModern(Path cfg) {
        try {
            String t = GeyserCfg.get(GeyserCfg.read(cfg), "bedrock", "transport");
            return t != null && !t.equalsIgnoreCase("raknet");
        } catch (IOException e) {
            return false;
        }
    }

    /** Picks a Bedrock port (TCP signaling + UDP) and a NetherNet UDP port that no managed server uses and that are free right now. */
    static int[] chooseNetherNetPorts(Srv s, int keepPort) {
        Set<Integer> taken = new HashSet<>(Ports.allUsed(s));
        int[] mine = Ports.of(s);
        for (int i = 0; i < 3; i++) if (mine[i] > 0) taken.add(mine[i]);
        taken.add(s.gamePort());
        int x = -1;
        if (keepPort >= 1024 && !taken.contains(keepPort) && tcpFree(keepPort) && udpFree(keepPort)) x = keepPort;
        if (x < 0) {
            x = s.gamePort() + 10000 > 60000 ? 20000 : s.gamePort() + 10000;
            while (taken.contains(x) || !tcpFree(x) || !udpFree(x)) x++;
        }
        taken.add(x);
        int y = x + 1000 > 65000 ? x - 1000 : x + 1000;
        while (taken.contains(y) || !udpFree(y)) y++;
        return new int[]{x, y};
    }

    /** Writes the NetherNet settings (both methods on, own Bedrock port, own NetherNet port) and tells the managers. Returns a line for the job log. */
    private static String applyNetherNet(Srv s, List<String> l, Path cfg, int keepPort, String why) throws IOException {
        boolean clone = "true".equalsIgnoreCase(GeyserCfg.get(l, "bedrock", "clone-remote-port"));
        int before = clone ? s.gamePort() : parsePort(GeyserCfg.get(l, "bedrock", "port"), 19132);
        int[] p = chooseNetherNetPorts(s, keepPort);
        String t = GeyserCfg.get(l, "bedrock", "transport");
        if (t == null || t.equalsIgnoreCase("raknet")) GeyserCfg.set(l, "bedrock", "transport", "both", false);
        GeyserCfg.set(l, "bedrock", "clone-remote-port", "false", false);
        GeyserCfg.set(l, "bedrock", "port", Integer.toString(p[0]), false);
        GeyserCfg.set(l, "bedrock", "webrtc-port", Integer.toString(p[1]), false);
        GeyserCfg.write(cfg, l);
        String msg = "Bedrock on " + s.name + " now uses the new connection method (NetherNet) that Bedrock 26.60 requires. " + why + ". Bedrock players connect to port " + p[0]
                + (p[0] != before ? " (it was " + before + ")" : "") + "; allow TCP and UDP " + p[0] + " and UDP " + p[1] + " in the Windows Firewall from the Bedrock tab.";
        Main.log("geyser: " + msg);
        Notify.post("health", "info", s.id, "Bedrock settings changed on " + s.name, msg, Json.map("type", "open", "server", s.id, "tab", "geyser", "label", "Open the Bedrock tab"));
        return msg;
    }

    /**
     * Moves a Geyser server to NetherNet when it is not there yet: both connection methods on, the Bedrock port separate from the Java port (NetherNet's
     * signaling needs the TCP port too), and a second UDP port for NetherNet itself. Returns a line for the job log, or null when nothing changed.
     */
    static String migrateNetherNet(Srv s) throws IOException {
        Path cfg = GeyserCfg.find(s.dir, "geyser");
        if (cfg == null) return null;
        List<String> l = GeyserCfg.read(cfg);
        String transport = GeyserCfg.get(l, "bedrock", "transport");
        if (transport == null) {
            if (GeyserCfg.get(l, "bedrock", "port") != null)
                Notify.postOnce("nethernet-old:" + s.id, 86_400_000L, "health", "bad", s.id, "Geyser on " + s.name + " is too old for Bedrock 26.60",
                        "This Geyser build does not know the new Bedrock connection method (NetherNet), so Bedrock players will not be able to join once their game updates. Update Geyser (Mods tab, or Check for updates).",
                        Json.map("type", "open", "server", s.id, "tab", "mods", "label", "Open the Mods tab"));
            return null;
        }
        boolean modern = !transport.equalsIgnoreCase("raknet");
        boolean clone = "true".equalsIgnoreCase(GeyserCfg.get(l, "bedrock", "clone-remote-port"));
        int port = parsePort(GeyserCfg.get(l, "bedrock", "port"), 19132);
        int web = parsePort(GeyserCfg.get(l, "bedrock", "webrtc-port"), 0);
        Set<Integer> others = Ports.allUsed(s);
        boolean portOk = !clone && port != s.gamePort() && !others.contains(port);
        boolean webOk = transport.equalsIgnoreCase("nethernet") || (web > 0 && web != port && web != s.gamePort() && !others.contains(web));
        if (modern && portOk && webOk) return null;
        String why = !modern ? "Geyser was still on the old method only" : "its ports overlapped the Java port or another server";
        return applyNetherNet(s, l, cfg, portOk ? port : -1, why);
    }

    private static String yaml(String v) { return "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }

    /**
     * Writes the start of Geyser's settings file for a new server: the Java port is reused for Bedrock (so it can never clash with another server's Bedrock port)
     * and the Bedrock names follow the Java server. Geyser fills in every other setting when it first starts and keeps these.
     */
    static void seed(Srv s, String loader) throws IOException {
        Path f = s.dir.resolve("config").resolve(loader.equals("neoforge") ? "Geyser-NeoForge" : "Geyser-Fabric").resolve("config.yml");
        // another loader's settings file is fixed up by reconcile() once Geyser has made it
        if (Files.exists(f)) return;
        String[] lines = s.props().getOrDefault("motd", "").split("\\r?\\n", -1);
        String first = lines.length > 0 && !lines[0].isBlank() ? lines[0].strip() : s.name, second = lines.length > 1 ? lines[1].strip() : "";
        Files.createDirectories(f.getParent());
        int[] np = chooseNetherNetPorts(s, -1);
        Files.writeString(f, "bedrock:\n  port: " + np[0] + "\n  webrtc-port: " + np[1] + "\n  transport: both\n  clone-remote-port: false\nmotd:\n  primary-motd: " + yaml(first) + "\n  secondary-motd: " + yaml(second) + "\ngameplay:\n  server-name: " + yaml(s.name) + "\n", StandardCharsets.UTF_8);
    }
    /** Applies the things that depend on Geyser's settings file existing: the default Bedrock port for new servers, and the Bedrock names. */
    static void reconcile(Srv s) {
        try {
            Path cfg = GeyserCfg.find(s.dir, "geyser");
            if (cfg == null) return;
            if (s.cfg.containsKey("geyserCloneDefault")) { s.cfg.remove("geyserCloneDefault"); Main.cfg.save(); }
            if (!s.running(false)) migrateNetherNet(s);
            syncNames(s);
        } catch (IOException | RuntimeException e) {
            Main.log("geyser reconcile " + s.id + ": " + e);
        }
    }

    // ---------- settings ----------

    private static final Set<String> AUTH = Set.of("floodgate", "online", "offline");

    static void applySettings(Srv s, Map<String, Object> b) throws IOException {
        Path cfg = GeyserCfg.find(s.dir, "geyser");
        if (cfg == null) throw new Api(409, "Geyser has not created its settings file yet. Start the server once, then come back here.");
        if (b.containsKey("syncNames")) {
            s.cfg.put("geyserNames", Boolean.TRUE.equals(b.get("syncNames")));
            Main.cfg.save();
            if (Boolean.TRUE.equals(b.get("syncNames"))) syncNames(s);
            if (b.size() == 1) return;
        }
        List<String> l = GeyserCfg.read(cfg);
        int changed = 0;
        if (Boolean.TRUE.equals(b.get("cloneRemotePort")) && isModern(cfg))
            throw new Api(400, "Reusing the Java port does not work with the new Bedrock connection method (NetherNet): its signaling needs the same TCP port, which the Java server already holds.");
        if (b.containsKey("cloneRemotePort")) { if (GeyserCfg.set(l, "bedrock", "clone-remote-port", Boolean.TRUE.equals(b.get("cloneRemotePort")) ? "true" : "false", false)) changed++; }
        if (b.containsKey("port")) {
            int p = parsePort(b.get("port"), -1);
            if (p < 1024 || p > 65535) throw new Api(400, "The Bedrock port must be between 1024 and 65535.");
            Set<Integer> used = Ports.allUsed(s);
            if (used.contains(p)) throw new Api(409, "Port " + p + " is already used by another server.");
            if (GeyserCfg.set(l, "bedrock", "port", Integer.toString(p), false)) changed++;
        }
        if (b.containsKey("authType")) {
            String a = Json.str(b, "authType");
            if (a == null || !AUTH.contains(a)) throw new Api(400, "Authentication must be floodgate, online or offline.");
            if (GeyserCfg.set(l, "java", "auth-type", a, false)) changed++;
        }
        for (String[] k : new String[][]{{"primaryMotd", "motd", "primary-motd"}, {"secondaryMotd", "motd", "secondary-motd"}, {"serverName", "gameplay", "server-name"}}) {
            if (!b.containsKey(k[0])) continue;
            String v = String.valueOf(b.get(k[0])).replaceAll("[\\r\\n]", " ").strip();
            if (v.length() > 100) throw new Api(400, "That text is too long (100 characters at most).");
            if (GeyserCfg.set(l, k[1], k[2], v, true)) changed++;
        }
        for (String[] k : new String[][]{{"passthroughMotd", "motd", "passthrough-motd"}, {"passthroughCounts", "motd", "passthrough-player-counts"}}) {
            if (b.containsKey(k[0]) && GeyserCfg.set(l, k[1], k[2], Boolean.TRUE.equals(b.get(k[0])) ? "true" : "false", false)) changed++;
        }
        if (b.containsKey("maxPlayers")) {
            int mp = parsePort(b.get("maxPlayers"), -1);
            if (mp < 1 || mp > 100000) throw new Api(400, "Max players must be a positive number.");
            if (GeyserCfg.set(l, "motd", "max-players", Integer.toString(mp), false)) changed++;
        }
        if (changed == 0) throw new Api(400, "Nothing to change.");
        GeyserCfg.write(cfg, l);
    }

    // ---------- installing it on a new server ----------

    /** Whether Modrinth has Geyser and Floodgate for this loader and Minecraft version, with a reason when it does not. */
    static Map<String, Object> availability(String type, String mc) {
        if (!List.of("fabric", "neoforge").contains(type))
            return Json.map("available", false, "reason", "Geyser has no " + ("forge".equals(type) ? "Forge" : "Vanilla") + " build, only Fabric and NeoForge. Bedrock players cannot join this server type.");
        if (mc == null || mc.isBlank()) return Json.map("available", false, "reason", "");
        boolean g = Modrinth.available("geyser", type, mc), f = Modrinth.available("floodgate", type, mc);
        if (g && f) return Json.map("available", true, "reason", "");
        return Json.map("available", false, "reason", "Bedrock support is not available for " + type + " on Minecraft " + mc + " yet" + (g ? " (Floodgate is missing)." : " (Geyser has no build for this version)."));
    }
}
