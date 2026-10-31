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
import java.util.stream.Stream;

/**
 * Finds and installs server-side mods from Modrinth. Only mods that run on a server are offered (client-only mods are filtered out),
 * the right file for the server's loader and Minecraft version is picked, required dependencies are added, and every download is
 * verified against the checksums Modrinth publishes.
 */
final class Modrinth {
    private Modrinth() { }

    private static final String API = "https://api.modrinth.com/v2";
    private static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(15)).build();
    private static final long MAX_BYTES = 300L << 20;
    private static final Pattern SAFE_FILE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+()\\- ]{0,120}\\.jar");
    /** Project ids (8 letters/digits) and slugs (letters, digits, - and _). */
    static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Map<String, Object[]> CACHE = new HashMap<>();

    static String loaderOf(Srv s) {
        String k = s.kind();
        if ("fabric".equals(k) || "forge".equals(k) || "neoforge".equals(k)) return k;
        throw new Api(409, "Mods need a Fabric, Forge or NeoForge server. " + s.name + " is " + ("vanilla".equals(k) ? "a vanilla server, which cannot load mods." : k + "."));
    }

    private static String ua() { return "MCServerManager/" + Main.version() + " (local server manager)"; }

    private static synchronized Object get(String url, boolean cache) throws IOException {
        if (cache) {
            Object[] c = CACHE.get(url);
            if (c != null && System.currentTimeMillis() - (Long) c[0] < 120_000) return c[1];
        }
        try {
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).header("User-Agent", ua()).header("Accept", "application/json").build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (r.statusCode() == 404) throw new Api(404, "Not found on Modrinth");
            if (r.statusCode() == 429) throw new Api(429, "Modrinth is asking us to slow down. Try again in a minute.");
            if (r.statusCode() != 200) throw new Api(502, "Modrinth answered HTTP " + r.statusCode());
            Object o = Json.parse(r.body());
            if (cache) CACHE.put(url, new Object[]{System.currentTimeMillis(), o});
            return o;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted");
        } catch (java.net.http.HttpTimeoutException e) {
            throw new Api(504, "Modrinth did not answer in time");
        } catch (java.net.ConnectException e) {
            throw new Api(502, "Cannot reach Modrinth. Check this PC's internet connection.");
        }
    }

    private static Object post(String url, Object body) throws IOException {
        try {
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).header("User-Agent", ua()).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(Json.stringify(body))).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (r.statusCode() != 200) throw new Api(502, "Modrinth answered HTTP " + r.statusCode());
            return Json.parse(r.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted");
        }
    }

    /** A GET against the Modrinth API (a path such as /project/abc/version), parsed. */
    static Object apiGet(String path) throws IOException { return get(API + path, false); }

    static String urlEnc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    private static String jsonArr(String... items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.length; i++) sb.append(i > 0 ? "," : "").append('"').append(items[i].replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        return sb.append(']').toString();
    }

    // ---------- what is already installed ----------

    private static final Map<String, String> SHA_CACHE = new HashMap<>();

    static String sha1(Path f) throws IOException {
        try {
            String key = f + ":" + Files.getLastModifiedTime(f).toMillis() + ":" + Files.size(f);
            synchronized (SHA_CACHE) {
                String c = SHA_CACHE.get(key);
                if (c != null) return c;
            }
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            try (InputStream in = Files.newInputStream(f)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            }
            String hex = HexFormat.of().formatHex(md.digest());
            synchronized (SHA_CACHE) { SHA_CACHE.put(key, hex); }
            return hex;
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    /** Modrinth project id -> installed file name, for every jar in the server's mods folder that Modrinth recognises. */
    static Map<String, String> installed(Srv s) {
        Map<String, String> out = new LinkedHashMap<>();
        Path md = s.dir.resolve("mods");
        if (!Files.isDirectory(md)) return out;
        try {
            Map<String, String> byHash = new LinkedHashMap<>();
            try (Stream<Path> st = Files.list(md)) {
                for (Path f : st.toList()) {
                    String fn = f.getFileName().toString();
                    if (fn.endsWith(".jar") || fn.endsWith(".jar.disabled")) byHash.put(sha1(f), fn);
                }
            }
            if (byHash.isEmpty()) return out;
            Map<String, Object> found = Json.obj(post(API + "/version_files", Json.map("hashes", new ArrayList<>(byHash.keySet()), "algorithm", "sha1")));
            for (Map.Entry<String, Object> e : found.entrySet()) {
                String pid = Json.str(e.getValue(), "project_id");
                if (pid != null) out.put(pid, byHash.get(e.getKey()));
            }
        } catch (IOException | RuntimeException e) {
            Main.log("modrinth installed lookup: " + e);
        }
        return out;
    }

    // ---------- search ----------

    static Map<String, Object> search(Srv s, String q, String sort, int page) throws IOException {
        String loader = loaderOf(s);
        String mc = s.mcVersion();
        if (mc == null) throw new Api(409, "Could not determine this server's Minecraft version.");
        String facets = "[[\"project_type:mod\"],[\"categories:" + loader + "\"],[\"versions:" + mc + "\"],[\"server_side:required\",\"server_side:optional\"]]";
        String index = switch (sort == null ? "" : sort) { case "downloads", "updated", "newest", "follows" -> sort; default -> q == null || q.isBlank() ? "downloads" : "relevance"; };
        int limit = 20;
        String url = API + "/search?query=" + enc(q == null ? "" : q.strip()) + "&facets=" + enc(facets) + "&index=" + index + "&limit=" + limit + "&offset=" + (Math.max(0, page) * limit);
        Map<String, Object> r = Json.obj(get(url, true));
        Map<String, String> have = installed(s);
        // The search index can lag behind the project records, so the client/server flags are re-read from the projects themselves.
        List<String> ids = new ArrayList<>();
        for (Object o : Json.arr(r.get("hits"))) { String pid = Json.str(o, "project_id"); if (pid != null && SAFE_ID.matcher(pid).matches()) ids.add(pid); }
        Map<String, Map<String, Object>> real = new HashMap<>();
        if (!ids.isEmpty()) {
            try {
                for (Object p : Json.arr(get(API + "/projects?ids=" + enc(jsonArr(ids.toArray(new String[0]))), true))) real.put(Json.str(p, "id"), Json.obj(p));
            } catch (IOException | RuntimeException e) {
                Main.log("modrinth project lookup: " + e);
            }
        }
        List<Object> hits = new ArrayList<>();
        for (Object o : Json.arr(r.get("hits"))) {
            String pid = Json.str(o, "project_id");
            Map<String, Object> p = real.get(pid);
            String client = p != null ? Json.str(p, "client_side") : Json.str(o, "client_side");
            String server = p != null ? Json.str(p, "server_side") : Json.str(o, "server_side");
            if ("unsupported".equals(server)) continue;
            hits.add(Json.map("id", pid, "slug", Json.str(o, "slug"), "title", Json.str(o, "title"), "summary", Json.str(o, "description"), "icon", Json.str(o, "icon_url"),
                    "downloads", Json.obj(o).get("downloads"), "author", Json.str(o, "author"), "categories", Json.arr(Json.obj(o).get("display_categories")),
                    "client", client, "server", server, "installed", pid != null && have.containsKey(pid)));
        }
        return Json.map("hits", hits, "total", Json.obj(r).get("total_hits"), "page", page, "loader", loader, "mc", mc);
    }

    // ---------- planning ----------

    static Map<String, Object> chooseVersion(String projectId, String loader, String mc, String preferredVersionId) throws IOException {
        if (!SAFE_ID.matcher(projectId).matches()) throw new Api(400, "Bad project id");
        if (preferredVersionId != null && SAFE_ID.matcher(preferredVersionId).matches()) {
            try {
                Map<String, Object> v = Json.obj(get(API + "/version/" + preferredVersionId, true));
                if (Json.arr(v.get("loaders")).contains(loader) && Json.arr(v.get("game_versions")).contains(mc)) return v;
            } catch (Api ignored) { }
        }
        String url = API + "/project/" + projectId + "/version?loaders=" + enc(jsonArr(loader)) + "&game_versions=" + enc(jsonArr(mc));
        List<Object> vs = Json.arr(get(url, true));
        for (String type : new String[]{"release", "beta", "alpha"}) for (Object v : vs) if (type.equals(Json.str(v, "version_type"))) return Json.obj(v);
        return null;
    }

    static Map<String, Object> primaryFile(Map<String, Object> version) {
        List<Object> files = Json.arr(version.get("files"));
        Map<String, Object> best = null;
        for (Object f : files) {
            Map<String, Object> m = Json.obj(f);
            String name = Json.str(m, "filename");
            if (name == null || !name.toLowerCase().endsWith(".jar")) continue;
            if (Boolean.TRUE.equals(m.get("primary"))) return m;
            if (best == null) best = m;
        }
        return best;
    }

    static Map<String, Object> plan(Srv s, String projectId) throws IOException {
        return plan(s, List.of(projectId == null ? "" : projectId));
    }

    /**
     * What would be installed for one or more mods: the mods themselves plus the required helper mods they need that are not installed yet
     * (shared helpers appear once). A single mod that is already installed is an error; among several it is skipped with a note.
     */
    static Map<String, Object> plan(Srv s, List<String> seeds) throws IOException {
        if (seeds == null || seeds.isEmpty()) throw new Api(400, "Pick at least one mod");
        if (seeds.size() > 30) throw new Api(400, "Pick at most 30 mods at a time");
        for (String sd : seeds) if (sd == null || !SAFE_ID.matcher(sd).matches()) throw new Api(400, "Bad project id");
        String loader = loaderOf(s);
        String mc = s.mcVersion();
        if (mc == null) throw new Api(409, "Could not determine this server's Minecraft version.");
        Map<String, String> have = installed(s);
        List<Object> items = new ArrayList<>();
        List<Object> warnings = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>(have.keySet());
        Deque<String[]> queue = new ArrayDeque<>();
        for (String sd : new LinkedHashSet<>(seeds)) queue.add(new String[]{sd, null, null, "seed"});
        boolean single = seeds.size() == 1;
        int guard = 0;
        while (!queue.isEmpty()) {
            if (++guard > 60) throw new Api(409, "These mods pull in too many other mods. Install fewer at a time.");
            String[] cur = queue.poll();
            boolean seed = cur[3] != null;
            Map<String, Object> proj;
            try {
                proj = Json.obj(get(API + "/project/" + enc(cur[0]), true));
            } catch (Api e) {
                if (seed && single) throw e;
                warnings.add("Could not look up " + (seed ? cur[0] : "a required mod (" + cur[0] + ")") + ": " + e.getMessage());
                continue;
            }
            String pid = Json.str(proj, "id");
            String title = Json.str(proj, "title");
            if (pid == null) continue;
            if (seen.contains(pid)) {
                if (seed && have.containsKey(pid)) {
                    if (single) throw new Api(409, title + " is already installed (" + have.get(pid) + ").");
                    warnings.add(title + " is already installed, so it was skipped.");
                }
                continue;
            }
            if (seed && "unsupported".equals(Json.str(proj, "server_side"))) {
                if (single) throw new Api(409, title + " is a client-only mod and does nothing on a server.");
                warnings.add(title + " is client-only, so it was skipped.");
                continue;
            }
            Map<String, Object> v = chooseVersion(pid, loader, mc, cur[1]);
            if (v == null) {
                if (seed && single) throw new Api(409, "There is no version of " + title + " for " + loader + " on Minecraft " + mc + " yet.");
                warnings.add(seed ? title + " has no version for " + loader + " on Minecraft " + mc + ", so it was skipped."
                        : "A required mod, " + title + ", has no version for " + loader + " on Minecraft " + mc + ", so the mod may not work.");
                seen.add(pid);
                continue;
            }
            Map<String, Object> f = primaryFile(v);
            if (f == null) throw new Api(409, title + " has no downloadable jar for this version.");
            seen.add(pid);
            String type = Json.str(v, "version_type");
            if (!"release".equals(type)) warnings.add(title + " " + Json.str(v, "version_number") + " is a " + type + " build, not a full release.");
            items.add(Json.map("projectId", pid, "title", title, "versionId", Json.str(v, "id"), "version", Json.str(v, "version_number"), "type", type, "filename", Json.str(f, "filename"),
                    "size", Json.obj(f).get("size"), "dependency", !seed, "icon", Json.str(proj, "icon_url"), "client", Json.str(proj, "client_side")));
            for (Object d : Json.arr(v.get("dependencies"))) {
                String dt = Json.str(d, "dependency_type"), dp = Json.str(d, "project_id");
                if (dp == null) continue;
                if ("required".equals(dt) && !seen.contains(dp)) queue.add(new String[]{dp, Json.str(d, "version_id"), pid, null});
                else if ("incompatible".equals(dt) && have.containsKey(dp)) warnings.add(title + " is marked incompatible with an installed mod (" + have.get(dp) + ").");
            }
        }
        if (items.isEmpty()) throw new Api(409, warnings.isEmpty() ? "Nothing to install." : String.valueOf(warnings.get(0)));
        return Json.map("items", items, "warnings", warnings, "loader", loader, "mc", mc);
    }

    /** True when Modrinth has a build of this project for the loader and Minecraft version (used to decide whether to offer Geyser). */
    static boolean available(String slug, String loader, String mc) {
        try {
            if (!SAFE_ID.matcher(slug).matches()) return false;
            List<Object> vs = Json.arr(get(API + "/project/" + enc(slug) + "/version?loaders=" + enc(jsonArr(loader)) + "&game_versions=" + enc(jsonArr(mc)), true));
            return !vs.isEmpty();
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }
    // ---------- install ----------

    static void checkUrl(String url) {
        URI u = URI.create(url);
        String host = u.getHost() == null ? "" : u.getHost().toLowerCase();
        if (!"https".equals(u.getScheme()) || !(host.equals("cdn.modrinth.com") || host.endsWith(".modrinth.com"))) throw new Api(502, "Refusing to download from an unexpected address: " + host);
    }

    private static void fetch(Map<String, Object> file, Path dest, Job j) throws IOException {
        String url = Json.str(file, "url");
        checkUrl(url);
        Path tmp = dest.resolveSibling(dest.getFileName() + ".part");
        try {
            HttpResponse<InputStream> r = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(10)).header("User-Agent", ua()).build(), HttpResponse.BodyHandlers.ofInputStream());
            if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode() + " downloading " + url);
            long total = r.headers().firstValueAsLong("Content-Length").orElse(-1);
            if (total > MAX_BYTES) throw new IOException("The file is unexpectedly large (" + (total >> 20) + " MB)");
            MessageDigest md1 = MessageDigest.getInstance("SHA-1"), md512 = MessageDigest.getInstance("SHA-512");
            long got = 0;
            try (InputStream in = r.body(); OutputStream out = Files.newOutputStream(tmp)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    got += n;
                    if (got > MAX_BYTES) throw new IOException("The file is unexpectedly large");
                    out.write(buf, 0, n);
                    md1.update(buf, 0, n);
                    md512.update(buf, 0, n);
                }
            }
            Map<String, Object> hashes = Json.obj(file.get("hashes"));
            String w1 = Json.str(hashes, "sha1"), w512 = Json.str(hashes, "sha512");
            if (w1 == null && w512 == null) throw new IOException("Modrinth gave no checksum for " + dest.getFileName() + ", so it was not installed.");
            if (w1 != null && !HexFormat.of().formatHex(md1.digest()).equalsIgnoreCase(w1)) throw new IOException("Checksum mismatch for " + dest.getFileName() + ", the download was discarded.");
            if (w512 != null && !HexFormat.of().formatHex(md512.digest()).equalsIgnoreCase(w512)) throw new IOException("Checksum mismatch for " + dest.getFileName() + ", the download was discarded.");
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
        } catch (InterruptedException | java.security.NoSuchAlgorithmException e) {
            throw new IOException(String.valueOf(e.getMessage()));
        } finally {
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) { }
        }
    }

    /** Re-plans on the server (the browser's plan is not trusted), then downloads and verifies every file. */
    static void install(Srv s, String projectId, Job j) throws Exception {
        install(s, List.of(projectId), j);
    }

    static void install(Srv s, List<String> projects, Job j) throws Exception {
        Map<String, Object> plan = plan(s, projects);
        List<Object> items = Json.arr(plan.get("items"));
        Path md = s.dir.resolve("mods");
        Files.createDirectories(md);
        j.log("Installing " + items.size() + " file(s) into " + md + " ...");
        for (Object w : Json.arr(plan.get("warnings"))) j.log("Note: " + w);
        List<Path> done = new ArrayList<>();
        try {
            for (Object it : items) {
                if (j.cancel) throw new Job.Cancelled();
                String title = Json.str(it, "title");
                Map<String, Object> v = Json.obj(get(API + "/version/" + Json.str(it, "versionId"), true));
                Map<String, Object> f = primaryFile(v);
                if (f == null) throw new IOException("No jar for " + title);
                String fn = Json.str(f, "filename");
                if (fn == null || !SAFE_FILE.matcher(fn).matches() || fn.contains("..")) throw new IOException("Modrinth gave an unsafe file name for " + title + ": " + fn);
                Path dest = md.resolve(fn).normalize();
                if (!dest.getParent().equals(md.normalize())) throw new IOException("Unsafe file name: " + fn);
                if (Files.exists(dest)) { j.log(fn + " is already there, skipping."); continue; }
                j.log((Boolean.TRUE.equals(Json.obj(it).get("dependency")) ? "Required mod: " : "") + title + " " + Json.str(it, "version") + " (" + fn + ") ...");
                fetch(f, dest, j);
                done.add(dest);
            }
        } catch (IOException | RuntimeException e) {
            for (Path p : done) { try { Files.deleteIfExists(p); } catch (IOException ignored) { } }
            if (!done.isEmpty()) j.log("Something went wrong, so the files added in this step were removed again.");
            throw e;
        }
        j.log("Done. " + (s.running(false) ? "Restart the server to load the new mod(s)." : "They load the next time the server starts."));
    }

    static void remove(Srv s, String file) throws IOException {
        if (file == null || file.contains("/") || file.contains("\\") || file.contains("..") || !(file.endsWith(".jar") || file.endsWith(".jar.disabled"))) throw new Api(400, "Bad file name");
        if (s.running(true)) throw new Api(409, "Stop the server first to remove a mod.");
        Path p = s.dir.resolve("mods").resolve(file).normalize();
        if (!p.getParent().equals(s.dir.resolve("mods").normalize()) || !Files.isRegularFile(p)) throw new Api(404, "No such mod file");
        Path trash = s.dir.resolve("mods-removed");
        Files.createDirectories(trash);
        Files.move(p, trash.resolve(file + "." + System.currentTimeMillis()), StandardCopyOption.REPLACE_EXISTING);
    }

    // ---------- lookups used by world import and the client modpack ----------

    /** SHA-1 of a file -> the Modrinth version that file belongs to (hashes Modrinth does not know are absent). */
    static Map<String, Map<String, Object>> versionsByHash(Collection<String> sha1s) throws IOException {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        if (sha1s.isEmpty()) return out;
        Map<String, Object> found = Json.obj(post(API + "/version_files", Json.map("hashes", new ArrayList<>(sha1s), "algorithm", "sha1")));
        for (Map.Entry<String, Object> e : found.entrySet()) out.put(e.getKey(), Json.obj(e.getValue()));
        return out;
    }

    /** Project id -> project record (title, slug, client_side, server_side, icon...). */
    static Map<String, Map<String, Object>> projectsById(Collection<String> ids) throws IOException {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        List<String> all = new ArrayList<>();
        for (String id : ids) if (id != null && SAFE_ID.matcher(id).matches()) all.add(id);
        for (int i = 0; i < all.size(); i += 50) {
            List<String> chunk = all.subList(i, Math.min(all.size(), i + 50));
            for (Object p : Json.arr(get(API + "/projects?ids=" + enc(jsonArr(chunk.toArray(new String[0]))), true))) out.put(Json.str(p, "id"), Json.obj(p));
        }
        return out;
    }

    static Map<String, Object> projectRecord(String idOrSlug) throws IOException {
        if (!SAFE_ID.matcher(idOrSlug).matches()) throw new Api(400, "Bad project id");
        return Json.obj(get(API + "/project/" + enc(idOrSlug), true));
    }}
