import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;

/**
 * CurseForge as a second source of mods and modpacks. It needs the person's own API key (Settings), which is kept in the manager's config on this PC only.
 * Files whose author does not allow downloads through the API are never fetched by a guessed address: they are listed so the person can get them from the
 * mod's page by hand.
 */
final class CurseForge {
    private CurseForge() { }

    static final int GAME = 432, CLASS_MODS = 6, CLASS_PACKS = 4471;
    private static final String API = "https://api.curseforge.com/v1";
    private static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(15)).build();
    private static final long MAX_MOD = 300L << 20, MAX_PACK = 700L << 20;
    private static final Pattern SAFE_FILE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+()\\[\\]\\- ]{0,150}\\.jar");

    // ---------- the API key ----------

    private static synchronized Map<String, Object> section() {
        Object o = Main.cfg.root.get("curseforge");
        return o instanceof Map ? Json.obj(o) : new LinkedHashMap<>();
    }

    static String key() {
        String k = Json.str(section(), "apiKey");
        return k == null ? "" : k.strip();
    }

    static boolean configured() { return !key().isEmpty(); }

    /** Enough of the key to recognise it, never the key itself. */
    static String masked() {
        String k = key();
        return k.length() < 10 ? "" : k.substring(0, 4) + "..." + k.substring(k.length() - 4);
    }

    static synchronized void setKey(String raw) throws IOException {
        String k = raw == null ? "" : raw.strip();
        if (k.isEmpty()) {
            Main.cfg.root.remove("curseforge");
            Main.cfg.save();
            return;
        }
        if (k.length() < 20 || k.length() > 200 || !k.matches("[A-Za-z0-9$./_=-]+")) throw new Api(400, "That does not look like a CurseForge API key.");
        Object before = Main.cfg.root.get("curseforge");
        Main.cfg.root.put("curseforge", Json.map("apiKey", k));
        try {
            get("/games/" + GAME);
        } catch (IOException | RuntimeException e) {
            if (before == null) Main.cfg.root.remove("curseforge"); else Main.cfg.root.put("curseforge", before);
            throw e;
        }
        Main.cfg.save();
    }
    // ---------- HTTP ----------

    private static String ua() { return "MCServerManager/" + Main.version() + " (local server manager)"; }

    private static void needKey() {
        if (!configured()) throw new Api(409, "CurseForge needs your own API key. An administrator can add it under Settings > CurseForge.");
    }

    private static Object send(HttpRequest.Builder b) throws IOException {
        needKey();
        try {
            HttpResponse<String> r = HTTP.send(b.timeout(Duration.ofSeconds(30)).header("x-api-key", key()).header("Accept", "application/json").header("User-Agent", ua()).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int c = r.statusCode();
            if (c == 401 || c == 403) throw new Api(502, "CurseForge did not accept the API key. Check it under Settings > CurseForge.");
            if (c == 404) throw new Api(404, "Not found on CurseForge");
            if (c == 429) throw new Api(429, "CurseForge is asking us to slow down. Try again in a minute.");
            if (c / 100 != 2) throw new Api(502, "CurseForge answered HTTP " + c);
            return Json.parse(r.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted");
        } catch (java.net.http.HttpTimeoutException e) {
            throw new Api(504, "CurseForge did not answer in time");
        } catch (java.net.ConnectException e) {
            throw new Api(502, "Cannot reach CurseForge. Check this PC's internet connection.");
        }
    }

    static Map<String, Object> get(String path) throws IOException {
        return Json.obj(send(HttpRequest.newBuilder(URI.create(API + path)).GET()));
    }

    static Map<String, Object> post(String path, Object body) throws IOException {
        return Json.obj(send(HttpRequest.newBuilder(URI.create(API + path)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(Json.stringify(body)))));
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    /** CurseForge's number for a mod loader (0 = any). */
    static int loaderType(String loader) {
        return switch (loader == null ? "" : loader) { case "forge" -> 1; case "fabric" -> 4; case "quilt" -> 5; case "neoforge" -> 6; default -> 0; };
    }

    // ---------- search ----------

    private static final Map<String, Integer> SORT = Map.of("downloads", 6, "updated", 3, "newest", 11, "name", 4);

    private static Map<String, Object> hit(Map<String, Object> m) {
        List<Object> authors = Json.arr(m.get("authors"));
        Map<String, Object> logo = m.get("logo") instanceof Map ? Json.obj(m.get("logo")) : null;
        Map<String, Object> links = m.get("links") instanceof Map ? Json.obj(m.get("links")) : null;
        return Json.map("id", m.get("id"), "slug", Json.str(m, "slug"), "title", Json.str(m, "name"), "summary", Json.str(m, "summary"),
                "author", authors.isEmpty() ? "" : Json.str(authors.get(0), "name"), "downloads", m.get("downloadCount") instanceof Number n ? n.longValue() : 0L,
                "icon", logo == null ? null : Json.str(logo, "thumbnailUrl"), "url", links == null ? null : Json.str(links, "websiteUrl"),
                "distribution", !Boolean.FALSE.equals(m.get("allowModDistribution")));
    }

    /** kind = mods or modpacks. mc and loader narrow the results (both optional). */
    static Map<String, Object> search(String kind, String q, String mc, String loader, String sort, int page) throws IOException {
        int cls = "modpacks".equals(kind) ? CLASS_PACKS : CLASS_MODS;
        StringBuilder u = new StringBuilder("/mods/search?gameId=" + GAME + "&classId=" + cls + "&pageSize=20&index=" + Math.max(0, page) * 20);
        if (q != null && !q.isBlank()) u.append("&searchFilter=").append(enc(q.strip()));
        if (mc != null && !mc.isBlank() && mc.matches("[A-Za-z0-9._-]{1,32}")) u.append("&gameVersion=").append(enc(mc));
        if (loaderType(loader) > 0) u.append("&modLoaderType=").append(loaderType(loader));
        u.append("&sortField=").append(SORT.getOrDefault(sort == null ? "" : sort, 2)).append("&sortOrder=desc");
        Map<String, Object> r = get(u.toString());
        List<Object> hits = new ArrayList<>();
        for (Object o : Json.arr(r.get("data"))) hits.add(hit(Json.obj(o)));
        Map<String, Object> pg = r.get("pagination") instanceof Map ? Json.obj(r.get("pagination")) : Map.of();
        return Json.map("hits", hits, "total", pg.get("totalCount") instanceof Number n ? n.intValue() : hits.size());
    }

    static List<Map<String, Object>> mods(Collection<? extends Number> ids) throws IOException {
        List<Map<String, Object>> out = new ArrayList<>();
        List<Number> all = new ArrayList<>(ids);
        for (int i = 0; i < all.size(); i += 50) {
            Map<String, Object> r = post("/mods", Json.map("modIds", new ArrayList<Object>(all.subList(i, Math.min(all.size(), i + 50)))));
            for (Object o : Json.arr(r.get("data"))) out.add(Json.obj(o));
        }
        return out;
    }

    static List<Map<String, Object>> filesByIds(Collection<? extends Number> ids) throws IOException {
        List<Map<String, Object>> out = new ArrayList<>();
        List<Number> all = new ArrayList<>(ids);
        for (int i = 0; i < all.size(); i += 200) {
            Map<String, Object> r = post("/mods/files", Json.map("fileIds", new ArrayList<Object>(all.subList(i, Math.min(all.size(), i + 200)))));
            for (Object o : Json.arr(r.get("data"))) out.add(Json.obj(o));
        }
        return out;
    }

    /** Files of one mod or modpack, newest first, optionally only those for a Minecraft version and loader. */
    static List<Map<String, Object>> files(long modId, String mc, String loader) throws IOException {
        StringBuilder u = new StringBuilder("/mods/" + modId + "/files?pageSize=50");
        if (mc != null && !mc.isBlank() && mc.matches("[A-Za-z0-9._-]{1,32}")) u.append("&gameVersion=").append(enc(mc));
        if (loaderType(loader) > 0) u.append("&modLoaderType=").append(loaderType(loader));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : Json.arr(get(u.toString()).get("data"))) {
            Map<String, Object> f = Json.obj(o);
            if (Boolean.FALSE.equals(f.get("isAvailable"))) continue;
            out.add(f);
        }
        out.sort((a, b) -> String.valueOf(b.get("fileDate")).compareTo(String.valueOf(a.get("fileDate"))));
        return out;
    }

    /** The newest release (else beta, else alpha) among a list of files. */
    static Map<String, Object> best(List<Map<String, Object>> files) {
        Map<String, Object> pick = null;
        int pickRank = 9;
        for (Map<String, Object> f : files) {
            int rt = f.get("releaseType") instanceof Number n ? n.intValue() : 3;
            if (rt < pickRank) { pick = f; pickRank = rt; }
        }
        return pick;
    }

    static String sha1Of(Map<String, Object> file) {
        for (Object h : Json.arr(file.get("hashes"))) if (h instanceof Map<?, ?> m && Integer.valueOf(1).equals(m.get("algo") instanceof Number n ? n.intValue() : -1)) return String.valueOf(m.get("value"));
        return null;
    }

    // ---------- installing mods on a server ----------

    private static List<Object> tracked(Srv s) { return s.cfg.get("curseMods") instanceof List<?> l ? new ArrayList<>(l) : new ArrayList<>(); }

    static Set<Long> installedIds(Srv s) {
        Set<Long> ids = new HashSet<>();
        for (Object o : tracked(s)) if (Json.obj(o).get("modId") instanceof Number n) ids.add(n.longValue());
        return ids;
    }

    /** What installing these mods would add: the best file for this server's Minecraft and loader, and the mods it requires. Nothing is downloaded. */
    static Map<String, Object> plan(Srv s, List<Long> seeds) throws IOException {
        String loader = Modrinth.loaderOf(s), mc = s.mcVersion();
        if (mc == null || mc.isBlank()) throw new Api(409, "The Minecraft version of " + s.name + " is not known yet. Start it once, then try again.");
        List<Object> items = new ArrayList<>(), warnings = new ArrayList<>();
        Set<Long> seen = new HashSet<>(installedIds(s));
        Deque<long[]> queue = new ArrayDeque<>();
        for (long id : seeds) queue.add(new long[]{id, 0});
        while (!queue.isEmpty() && items.size() < 40) {
            long[] cur = queue.poll();
            long id = cur[0];
            if (!seen.add(id)) { if (cur[1] == 0) warnings.add("Mod " + id + " is already installed on " + s.name + "."); continue; }
            Map<String, Object> f = best(files(id, mc, loader));
            Map<String, Object> mod = mods(List.of(id)).stream().findFirst().orElse(null);
            String title = mod == null ? "Mod " + id : Json.str(mod, "name");
            if (f == null) { warnings.add(title + " has no file for " + loader + " on Minecraft " + mc + "."); continue; }
            String url = f.get("downloadUrl") instanceof String u ? u : null;
            String fn = Json.str(f, "fileName");
            Map<String, Object> links = mod != null && mod.get("links") instanceof Map ? Json.obj(mod.get("links")) : null;
            items.add(Json.map("modId", id, "fileId", f.get("id"), "title", title, "version", Json.str(f, "displayName"), "filename", fn, "size", f.get("fileLength"),
                    "dependency", cur[1] > 0, "manual", url == null, "page", links == null ? null : Json.str(links, "websiteUrl"),
                    "icon", mod != null && mod.get("logo") instanceof Map ? Json.str(mod.get("logo"), "thumbnailUrl") : null, "sha1", sha1Of(f)));
            if (url == null) warnings.add(title + " does not allow downloads through CurseForge's API. Download " + fn + " from its page and put it in the mods folder yourself.");
            for (Object d : Json.arr(f.get("dependencies"))) {
                Map<String, Object> dep = Json.obj(d);
                if (dep.get("relationType") instanceof Number n && n.intValue() == 3 && dep.get("modId") instanceof Number m && !seen.contains(m.longValue())) queue.add(new long[]{m.longValue(), 1});
            }
        }
        // CurseForge does not say whether a mod runs on a server; Modrinth often does
        try {
            Map<String, String> byHash = new LinkedHashMap<>();
            for (Object it : items) { String h = Json.str(it, "sha1"); if (h != null) byHash.put(h, Json.str(it, "title")); }
            Map<String, Map<String, Object>> vs = Modrinth.versionsByHash(byHash.keySet());
            Set<String> pids = new LinkedHashSet<>();
            for (Map<String, Object> v : vs.values()) if (Json.str(v, "project_id") != null) pids.add(Json.str(v, "project_id"));
            Map<String, Map<String, Object>> projects = Modrinth.projectsById(pids);
            for (Object it : items) {
                Map<String, Object> v = vs.get(Json.str(it, "sha1"));
                Map<String, Object> p = v == null ? null : projects.get(Json.str(v, "project_id"));
                if (p != null && "unsupported".equals(Json.str(p, "server_side"))) {
                    Json.obj(it).put("clientOnly", true);
                    warnings.add(Json.str(it, "title") + " is a client-side mod (it does not run on servers) and can stop a server from starting.");
                }
            }
        } catch (IOException | RuntimeException ignored) { }
        return Json.map("items", items, "warnings", warnings, "loader", loader, "mc", mc);
    }

    static void checkUrl(String url) {
        URI u = URI.create(url);
        String host = u.getHost() == null ? "" : u.getHost().toLowerCase();
        if (!"https".equals(u.getScheme()) || !(host.equals("forgecdn.net") || host.endsWith(".forgecdn.net") || host.equals("curseforge.com") || host.endsWith(".curseforge.com")))
            throw new Api(502, "Refusing to download from an unexpected address: " + host);
    }

    /** Downloads a file to dest, optionally checking its SHA-1. */
    static void download(String url, Path dest, String sha1, long max) throws IOException {
        checkUrl(url);
        Path tmp = dest.resolveSibling(dest.getFileName() + ".part");
        try {
            HttpResponse<InputStream> r = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(15)).header("User-Agent", ua()).build(), HttpResponse.BodyHandlers.ofInputStream());
            if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode() + " downloading " + url);
            long total = r.headers().firstValueAsLong("Content-Length").orElse(-1);
            if (total > max) throw new IOException("The file is unexpectedly large (" + (total >> 20) + " MB)");
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            long got = 0;
            Files.createDirectories(dest.getParent());
            try (InputStream in = r.body(); OutputStream out = Files.newOutputStream(tmp)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    got += n;
                    if (got > max) throw new IOException("The file is unexpectedly large");
                    out.write(buf, 0, n);
                    md.update(buf, 0, n);
                }
            }
            if (sha1 != null && !sha1.isBlank() && !HexFormat.of().formatHex(md.digest()).equalsIgnoreCase(sha1)) throw new IOException("Checksum mismatch for " + dest.getFileName() + ", the download was discarded.");
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
        } catch (InterruptedException | java.security.NoSuchAlgorithmException e) {
            throw new IOException(String.valueOf(e.getMessage()));
        } finally {
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) { }
        }
    }

    static void downloadMod(String url, Path dest, String sha1) throws IOException { download(url, dest, sha1, MAX_MOD); }

    static void downloadPack(String url, Path dest) throws IOException { download(url, dest, null, MAX_PACK); }

    /** Plans again on the server (the browser's plan is not trusted), downloads and checks the files, and remembers them for later. */
    static List<Object> install(Srv s, List<Long> seeds, Job j) throws Exception {
        Map<String, Object> plan = plan(s, seeds);
        Path md = s.dir.resolve("mods");
        Files.createDirectories(md);
        List<Object> manual = new ArrayList<>(), items = Json.arr(plan.get("items"));
        for (Object w : Json.arr(plan.get("warnings"))) j.log("Note: " + w);
        List<Path> done = new ArrayList<>();
        List<Object> trackedNow = tracked(s);
        try {
            for (Object it : items) {
                j.checkCancel();
                Map<String, Object> m = Json.obj(it);
                String fn = Json.str(m, "filename"), title = Json.str(m, "title");
                if (Boolean.TRUE.equals(m.get("manual"))) { manual.add(Json.map("title", title, "file", fn, "page", m.get("page"))); continue; }
                if (fn == null || !SAFE_FILE.matcher(fn).matches() || fn.contains("..")) throw new IOException("CurseForge gave an unsafe file name for " + title + ": " + fn);
                Path dest = md.resolve(fn).normalize();
                if (!dest.getParent().equals(md.normalize())) throw new IOException("Unsafe file name: " + fn);
                if (Files.exists(dest)) {
                    j.log(fn + " is already there, skipping.");
                    trackedNow.removeIf(t -> Json.obj(t).get("modId") instanceof Number n && n.equals(m.get("modId")));
                    trackedNow.add(Json.map("modId", m.get("modId"), "fileId", m.get("fileId"), "file", fn, "title", title));
                    continue;
                }
                Map<String, Object> file = filesByIds(List.of(((Number) m.get("fileId")).longValue())).stream().findFirst().orElse(null);
                String url = file == null ? null : file.get("downloadUrl") instanceof String u ? u : null;
                if (url == null) { manual.add(Json.map("title", title, "file", fn, "page", m.get("page"))); continue; }
                j.log((Boolean.TRUE.equals(m.get("dependency")) ? "Required mod: " : "") + title + " (" + fn + ") ...");
                downloadMod(url, dest, sha1Of(file));
                done.add(dest);
                trackedNow.removeIf(t -> Json.obj(t).get("modId") instanceof Number n && n.equals(m.get("modId")));
                trackedNow.add(Json.map("modId", m.get("modId"), "fileId", m.get("fileId"), "file", fn, "title", title));
            }
        } catch (IOException | RuntimeException e) {
            for (Path p : done) { try { Files.deleteIfExists(p); } catch (IOException ignored) { } }
            if (!done.isEmpty()) j.log("Something went wrong, so the files added in this step were removed again.");
            throw e;
        }
        s.cfg.put("curseMods", trackedNow);
        Main.cfg.save();
        for (Object o : manual) j.log("Download by hand: " + Json.str(o, "title") + " (" + Json.str(o, "file") + ") from " + Json.str(o, "page"));
        j.log("Done. " + (s.running(false) ? "Restart the server to load the new mod(s)." : "They load the next time the server starts."));
        return manual;
    }

    // ---------- fingerprints (which CurseForge file a jar is) ----------

    /** CurseForge's file fingerprint: MurmurHash2 (seed 1) over the bytes with whitespace removed. */
    static long fingerprint(Path f) throws IOException {
        // two streaming passes (the manager runs with a very small heap): count the bytes that are not whitespace, then hash them
        long count = 0;
        byte[] buf = new byte[1 << 16];
        try (InputStream in = Files.newInputStream(f)) {
            int n;
            while ((n = in.read(buf)) > 0) for (int i = 0; i < n; i++) if (buf[i] != 9 && buf[i] != 10 && buf[i] != 13 && buf[i] != 32) count++;
        }
        final int m = 0x5bd1e995, r = 24;
        int h = 1 ^ (int) count, k = 0, cnt = 0;
        try (InputStream in = Files.newInputStream(f)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                for (int i = 0; i < n; i++) {
                    byte x = buf[i];
                    if (x == 9 || x == 10 || x == 13 || x == 32) continue;
                    k |= (x & 0xff) << (8 * cnt);
                    if (++cnt == 4) {
                        k *= m;
                        k ^= k >>> r;
                        k *= m;
                        h *= m;
                        h ^= k;
                        k = 0;
                        cnt = 0;
                    }
                }
            }
        }
        switch (cnt) {
            case 3: h ^= ((k >>> 16) & 0xff) << 16;
            case 2: h ^= ((k >>> 8) & 0xff) << 8;
            case 1: h ^= (k & 0xff); h *= m;
            default: break;
        }
        h ^= h >>> 13;
        h *= m;
        h ^= h >>> 15;
        return h & 0xFFFFFFFFL;
    }
    /** For each jar in the server's mods folder that CurseForge recognises: {modId, fileId, file, title}. */
    static List<Map<String, Object>> identify(Srv s) throws IOException {
        Path md = s.dir.resolve("mods");
        List<Map<String, Object>> out = new ArrayList<>();
        if (!Files.isDirectory(md)) return out;
        Map<Long, String> byPrint = new LinkedHashMap<>();
        try (var st = Files.list(md)) {
            for (Path p : st.sorted().toList()) if (p.getFileName().toString().endsWith(".jar") && Files.isRegularFile(p) && Files.size(p) < MAX_MOD) byPrint.put(fingerprint(p), p.getFileName().toString());
        }
        if (byPrint.isEmpty()) return out;
        Map<String, Object> r = post("/fingerprints/" + GAME, Json.map("fingerprints", new ArrayList<Object>(byPrint.keySet())));
        Map<String, Object> data = r.get("data") instanceof Map ? Json.obj(r.get("data")) : Map.of();
        Map<Long, String> names = new HashMap<>();
        List<Number> modIds = new ArrayList<>();
        for (Object em : Json.arr(data.get("exactMatches"))) { Map<String, Object> f = Json.obj(Json.obj(em).get("file")); if (f.get("modId") instanceof Number n) modIds.add(n.longValue()); }
        for (Map<String, Object> mod : mods(modIds)) if (mod.get("id") instanceof Number n) names.put(n.longValue(), Json.str(mod, "name"));
        for (Object em : Json.arr(data.get("exactMatches"))) {
            Map<String, Object> f = Json.obj(Json.obj(em).get("file"));
            long modId = f.get("modId") instanceof Number n ? n.longValue() : 0;
            long fp = f.get("fileFingerprint") instanceof Number n ? n.longValue() : -1;
            out.add(Json.map("modId", modId, "fileId", f.get("id"), "file", byPrint.getOrDefault(fp, Json.str(f, "fileName")), "title", names.getOrDefault(modId, Json.str(f, "displayName")), "fingerprint", fp));
        }
        return out;
    }
}
