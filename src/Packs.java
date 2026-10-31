import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Modpacks: find them on CurseForge or Modrinth (or take a .zip / .mrpack file), read what they need, and build a server from one: the right Minecraft and
 * loader, every mod that runs on a server (client-only ones are skipped), and the pack's config files. Downloads are checked against the published checksums.
 */
final class Packs {
    private Packs() { }

    private static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(15)).build();
    private static final Set<String> MRPACK_HOSTS = Set.of("cdn.modrinth.com", "github.com", "raw.githubusercontent.com", "gitlab.com");
    private static final Pattern VERSIONISH = Pattern.compile("^\\d+(\\.\\d+)+.*");
    private static final Set<String> SKIP_TOP = Set.of("resourcepacks", "shaderpacks", "screenshots", "saves", "logs", "crash-reports", "texturepacks", "backups", ".git");
    private static final Set<String> SKIP_FILES = Set.of("options.txt", "optionsof.txt", "optionsshaders.txt", "servers.dat", "servers.dat_old", "usercache.json", "realms_persistence.json");
    private static final long MAX_UNPACKED = 3L << 30;
    private static final int MAX_FILES = 60_000;

    record Pack(String format, String name, String version, String author, String mc, String loaderType, String loaderVersion, List<Map<String, Object>> files, String overrides, String root) { }

    // ---------- search and versions ----------

    static Map<String, Object> search(String source, String q, String sort, int page) throws IOException {
        if ("curseforge".equals(source)) return CurseForge.search("modpacks", q, null, null, sort, page);
        if (!"modrinth".equals(source)) throw new Api(400, "Unknown source");
        String idx = "downloads".equals(sort) ? "downloads" : "updated".equals(sort) ? "updated" : "newest".equals(sort) ? "newest" : (q == null || q.isBlank() ? "downloads" : "relevance");
        String facets = Modrinth.urlEnc("[[\"project_type:modpack\"]]");
        Map<String, Object> r = Json.obj(Modrinth.apiGet("/search?query=" + Modrinth.urlEnc(q == null ? "" : q.strip()) + "&facets=" + facets + "&limit=20&offset=" + Math.max(0, page) * 20 + "&index=" + idx));
        List<Object> hits = new ArrayList<>();
        for (Object o : Json.arr(r.get("hits"))) {
            Map<String, Object> m = Json.obj(o);
            hits.add(Json.map("id", Json.str(m, "project_id"), "slug", Json.str(m, "slug"), "title", Json.str(m, "title"), "summary", Json.str(m, "description"), "author", Json.str(m, "author"),
                    "downloads", m.get("downloads") instanceof Number n ? n.longValue() : 0L, "icon", Json.str(m, "icon_url"), "url", "https://modrinth.com/modpack/" + Json.str(m, "slug")));
        }
        return Json.map("hits", hits, "total", r.get("total_hits") instanceof Number n ? n.intValue() : hits.size());
    }

    static List<Object> versions(String source, String id) throws IOException {
        List<Object> out = new ArrayList<>();
        if ("curseforge".equals(source)) {
            if (!id.matches("\\d{1,10}")) throw new Api(400, "Bad pack id");
            for (Map<String, Object> f : CurseForge.files(Long.parseLong(id), null, null)) {
                List<String> mcs = new ArrayList<>();
                String loader = "";
                for (Object gv : Json.arr(f.get("gameVersions"))) {
                    String g = String.valueOf(gv);
                    if (VERSIONISH.matcher(g).matches()) mcs.add(g);
                    else if (List.of("Fabric", "Forge", "NeoForge", "Quilt").contains(g)) loader = g.toLowerCase(Locale.ROOT);
                }
                out.add(Json.map("id", String.valueOf(f.get("id")), "name", Json.str(f, "displayName"), "mc", mcs.isEmpty() ? "" : mcs.get(0), "loader", loader, "date", Json.str(f, "fileDate"),
                        "size", f.get("fileLength"), "downloadable", f.get("downloadUrl") instanceof String, "release", f.get("releaseType")));
            }
        } else if ("modrinth".equals(source)) {
            if (!Modrinth.SAFE_ID.matcher(id).matches()) throw new Api(400, "Bad pack id");
            for (Object o : Json.arr(Modrinth.apiGet("/project/" + id + "/version"))) {
                Map<String, Object> v = Json.obj(o);
                List<Object> gv = Json.arr(v.get("game_versions")), lo = Json.arr(v.get("loaders"));
                out.add(Json.map("id", Json.str(v, "id"), "name", Json.str(v, "name") + " (" + Json.str(v, "version_number") + ")", "mc", gv.isEmpty() ? "" : String.valueOf(gv.get(gv.size() - 1)), "loader", lo.isEmpty() ? "" : String.valueOf(lo.get(0)),
                        "date", Json.str(v, "date_published"), "size", null, "downloadable", true, "release", "release".equals(Json.str(v, "version_type")) ? 1 : 2));
            }
        } else throw new Api(400, "Unknown source");
        return out;
    }

    // ---------- fetching the pack file ----------

    static Path cacheDir() { return Main.DATA.resolve("cache").resolve("packs"); }

    private static void cleanCache() {
        try (Stream<Path> s = Files.isDirectory(cacheDir()) ? Files.list(cacheDir()) : Stream.<Path>empty()) {
            for (Path p : s.toList()) if (System.currentTimeMillis() - Files.getLastModifiedTime(p).toMillis() > 7 * 86_400_000L) Files.deleteIfExists(p);
        } catch (IOException | RuntimeException ignored) { }
    }

    private static void fetchUrl(String url, Path dest, String sha1, String sha512, long max, Set<String> hosts) throws IOException {
        URI u = URI.create(url);
        String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
        if (!"https".equals(u.getScheme()) || !hosts.contains(host)) throw new IOException("Refusing to download from an unexpected address: " + host);
        Path tmp = dest.resolveSibling(dest.getFileName() + ".part");
        try {
            HttpResponse<InputStream> r = HTTP.send(HttpRequest.newBuilder(u).timeout(Duration.ofMinutes(15)).header("User-Agent", "MCServerManager/" + Main.version()).build(), HttpResponse.BodyHandlers.ofInputStream());
            if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode() + " downloading " + dest.getFileName());
            MessageDigest m1 = MessageDigest.getInstance("SHA-1"), m512 = MessageDigest.getInstance("SHA-512");
            long got = 0;
            Files.createDirectories(dest.getParent());
            try (InputStream in = r.body(); OutputStream out = Files.newOutputStream(tmp)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    got += n;
                    if (got > max) throw new IOException("The file is unexpectedly large");
                    out.write(buf, 0, n);
                    m1.update(buf, 0, n);
                    m512.update(buf, 0, n);
                }
            }
            if (sha1 != null && !HexFormat.of().formatHex(m1.digest()).equalsIgnoreCase(sha1)) throw new IOException("Checksum mismatch for " + dest.getFileName());
            if (sha512 != null && !HexFormat.of().formatHex(m512.digest()).equalsIgnoreCase(sha512)) throw new IOException("Checksum mismatch for " + dest.getFileName());
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
        } catch (InterruptedException | java.security.NoSuchAlgorithmException e) {
            throw new IOException(String.valueOf(e.getMessage()));
        } finally {
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) { }
        }
    }

    /** The pack file on disk: a local file as given, or a download from CurseForge or Modrinth kept for a week. */
    static Path obtain(Map<String, Object> ref) throws IOException {
        String path = Json.str(ref, "path");
        if (path != null && !path.isBlank()) {
            Path p = Path.of(path.trim());
            String n = p.getFileName() == null ? "" : p.getFileName().toString().toLowerCase(Locale.ROOT);
            if (!p.isAbsolute() || !Files.isRegularFile(p) || !(n.endsWith(".zip") || n.endsWith(".mrpack"))) throw new Api(400, "Pick a modpack file (.zip from CurseForge or .mrpack from Modrinth).");
            return p.normalize();
        }
        String source = Json.str(ref, "source"), id = Json.str(ref, "id"), fileId = Json.str(ref, "fileId");
        if (id == null || fileId == null || !fileId.matches("[A-Za-z0-9_-]{1,40}") || !id.matches("[A-Za-z0-9_-]{1,40}")) throw new Api(400, "Choose a modpack and a version.");
        cleanCache();
        Path dest = cacheDir().resolve(source + "-" + id + "-" + fileId + ("modrinth".equals(source) ? ".mrpack" : ".zip"));
        if (Files.isRegularFile(dest) && Files.size(dest) > 0) return dest;
        if ("curseforge".equals(source)) {
            Map<String, Object> f = CurseForge.filesByIds(List.of(Long.parseLong(fileId))).stream().findFirst().orElse(null);
            if (f == null) throw new Api(404, "That modpack version no longer exists on CurseForge.");
            if (!(f.get("downloadUrl") instanceof String url))
                throw new Api(409, "The author of this modpack does not allow downloads through CurseForge's API. Download it from its CurseForge page in your browser and use \"From a file\" instead.");
            CurseForge.downloadPack(url, dest);
        } else if ("modrinth".equals(source)) {
            Map<String, Object> v = Json.obj(Modrinth.apiGet("/version/" + fileId));
            Map<String, Object> file = null;
            for (Object o : Json.arr(v.get("files"))) { Map<String, Object> m = Json.obj(o); if (file == null || Boolean.TRUE.equals(m.get("primary"))) file = m; }
            if (file == null || Json.str(file, "url") == null) throw new Api(404, "That Modrinth version has no pack file.");
            Map<String, Object> h = Json.obj(file.get("hashes"));
            fetchUrl(Json.str(file, "url"), dest, Json.str(h, "sha1"), Json.str(h, "sha512"), 700L << 20, Set.of("cdn.modrinth.com"));
        } else throw new Api(400, "Unknown source");
        return dest;
    }

    // ---------- reading the pack ----------

    private static String norm(String n) { return n.replace('\\', '/'); }

    static Pack parse(Path file) throws IOException {
        try (ZipFile z = new ZipFile(file.toFile())) {
            Map<String, ZipEntry> idx = new LinkedHashMap<>();
            for (Enumeration<? extends ZipEntry> en = z.entries(); en.hasMoreElements(); ) { ZipEntry e = en.nextElement(); if (!e.isDirectory()) idx.put(norm(e.getName()), e); }
            String root = "";
            String mrKey = null, cfKey = null;
            for (String n : idx.keySet()) {
                if (n.equals("modrinth.index.json") || n.endsWith("/modrinth.index.json") && n.indexOf('/') == n.lastIndexOf('/')) { mrKey = n; break; }
            }
            if (mrKey == null) for (String n : idx.keySet()) if (n.equals("manifest.json") || n.endsWith("/manifest.json") && n.indexOf('/') == n.lastIndexOf('/')) { cfKey = n; break; }
            if (mrKey != null) {
                root = mrKey.contains("/") ? mrKey.substring(0, mrKey.lastIndexOf('/') + 1) : "";
                Map<String, Object> m = Json.obj(Json.parse(new String(z.getInputStream(idx.get(mrKey)).readAllBytes(), StandardCharsets.UTF_8)));
                Map<String, Object> dep = Json.obj(m.get("dependencies"));
                String type = null, ver = null;
                for (String k : new String[]{"fabric-loader", "forge", "neoforge", "quilt-loader"}) if (dep.get(k) != null) { type = k.replace("-loader", ""); ver = String.valueOf(dep.get(k)); }
                List<Map<String, Object>> files = new ArrayList<>();
                for (Object o : Json.arr(m.get("files"))) files.add(Json.obj(o));
                return new Pack("modrinth", Json.str(m, "name"), Json.str(m, "versionId"), null, Json.str(dep, "minecraft"), type, ver, files, "overrides", root);
            }
            if (cfKey != null) {
                root = cfKey.contains("/") ? cfKey.substring(0, cfKey.lastIndexOf('/') + 1) : "";
                Map<String, Object> m = Json.obj(Json.parse(new String(z.getInputStream(idx.get(cfKey)).readAllBytes(), StandardCharsets.UTF_8)));
                Map<String, Object> mcSec = Json.obj(m.get("minecraft"));
                String type = null, ver = null;
                for (Object ml : Json.arr(mcSec.get("modLoaders"))) {
                    String id = Json.str(ml, "id");
                    if (id == null || !id.contains("-")) continue;
                    if (type == null || Boolean.TRUE.equals(Json.obj(ml).get("primary"))) { type = id.substring(0, id.indexOf('-')); ver = id.substring(id.indexOf('-') + 1); }
                }
                List<Map<String, Object>> files = new ArrayList<>();
                for (Object o : Json.arr(m.get("files"))) files.add(Json.obj(o));
                String ov = Json.str(m, "overrides");
                return new Pack("curseforge", Json.str(m, "name"), Json.str(m, "version"), Json.str(m, "author"), Json.str(mcSec, "version"), type, ver, files, ov == null ? "overrides" : ov, root);
            }
        } catch (java.util.zip.ZipException e) {
            throw new Api(400, "That file is not a readable zip.");
        }
        throw new Api(400, "That file is not a modpack (it has no manifest.json or modrinth.index.json).");
    }

    /** What the wizard needs to know about a pack. */
    static Map<String, Object> inspect(Path file) throws IOException {
        Pack p = parse(file);
        boolean supported = p.loaderType() != null && Installer.TYPES.contains(p.loaderType()) && !p.loaderType().equals("vanilla");
        String problem = null;
        if (p.loaderType() == null) problem = "This pack does not say which mod loader it uses.";
        else if (p.loaderType().equals("quilt")) problem = "This pack needs the Quilt loader, which the manager cannot run (it runs Fabric, Forge and NeoForge).";
        else if (!supported) problem = "This pack needs the \"" + p.loaderType() + "\" loader, which the manager cannot run.";
        boolean loaderThere = false;
        if (supported && p.mc() != null) {
            try { for (Object l : Installer.loaders(p.loaderType(), p.mc())) if (p.loaderVersion() != null && p.loaderVersion().equals(Json.str(l, "id"))) loaderThere = true; } catch (IOException | RuntimeException ignored) { }
        }
        return Json.map("format", p.format(), "name", p.name(), "version", p.version(), "author", p.author(), "mc", p.mc(), "loader", p.loaderType(), "loaderVersion", p.loaderVersion(), "files", p.files().size(),
                "loaderAvailable", loaderThere, "problem", problem);
    }

    // ---------- building the server ----------

    private static boolean safeRel(String rel) {
        if (rel.isEmpty() || rel.startsWith("/") || rel.contains(":") || rel.contains("\\")) return false;
        for (String part : rel.split("/")) if (part.equals("..") || part.isEmpty() && !rel.endsWith("/")) return false;
        return true;
    }

    private static boolean skipOverride(String rel) {
        String top = rel.contains("/") ? rel.substring(0, rel.indexOf('/')) : rel;
        return SKIP_TOP.contains(top.toLowerCase(Locale.ROOT)) || (!rel.contains("/") && SKIP_FILES.contains(rel.toLowerCase(Locale.ROOT)));
    }

    private static int unpackOverrides(Job j, Path pack, Pack p, Path serverDir, String folder) throws IOException {
        String prefix = p.root() + folder + "/";
        long total = 0;
        int n = 0;
        Path base = serverDir.toAbsolutePath().normalize();
        try (ZipFile z = new ZipFile(pack.toFile())) {
            for (Enumeration<? extends ZipEntry> en = z.entries(); en.hasMoreElements(); ) {
                ZipEntry e = en.nextElement();
                String name = norm(e.getName());
                if (e.isDirectory() || !name.startsWith(prefix)) continue;
                String rel = name.substring(prefix.length());
                if (!safeRel(rel)) throw new Api(400, "The pack contains an unsafe path: " + name);
                if (skipOverride(rel)) continue;
                Path t = base.resolve(rel).normalize();
                if (!t.startsWith(base)) throw new Api(400, "The pack contains an unsafe path: " + name);
                total += Math.max(0, e.getSize());
                if (total > MAX_UNPACKED || ++n > MAX_FILES) throw new Api(400, "The pack's config files are unreasonably large.");
                j.checkCancel();
                Files.createDirectories(t.getParent());
                try (InputStream in = z.getInputStream(e)) { Files.copy(in, t, StandardCopyOption.REPLACE_EXISTING); }
            }
        }
        return n;
    }

    private static final class Fetched { String file, title; boolean manual; String page; }

    /** Creates the server, installs the mods and config files, and returns the new server's id. req carries the wizard's fields plus "pack". */
    static String install(Job j, Map<String, Object> req0, String actor) throws Exception {
        Map<String, Object> req = new LinkedHashMap<>(req0);
        Path file = obtain(Json.obj(req.get("pack")));
        Pack p = parse(file);
        if (p.loaderType() == null || !Installer.TYPES.contains(p.loaderType()) || p.loaderType().equals("vanilla")) throw new Api(400, "This pack's mod loader (" + p.loaderType() + ") is not supported here.");
        if (p.mc() == null) throw new Api(400, "This pack does not say which Minecraft version it is for.");
        req.put("type", p.loaderType());
        req.put("mc", p.mc());
        String lv = p.loaderVersion();
        boolean there = false;
        for (Object l : Installer.loaders(p.loaderType(), p.mc())) if (lv != null && lv.equals(Json.str(l, "id"))) there = true;
        if (!there) {
            String rec = null;
            for (Object l : Installer.loaders(p.loaderType(), p.mc())) if (Boolean.TRUE.equals(Json.obj(l).get("recommended"))) rec = Json.str(l, "id");
            if (rec == null) throw new Api(409, "There is no " + p.loaderType() + " loader for Minecraft " + p.mc() + " yet.");
            j.log("The pack asks for " + p.loaderType() + " " + lv + ", which is not available; using " + rec + " instead.");
            lv = rec;
        }
        req.put("loader", lv);
        boolean startAfter = Boolean.TRUE.equals(req.get("startAfter"));
        req.put("startAfter", false);
        req.remove("pack");
        req.put("geyser", false);
        req.put("journeymap", false);
        j.log("Modpack: " + p.name() + (p.version() == null ? "" : " " + p.version()) + " for Minecraft " + p.mc() + " with " + p.loaderType() + " " + lv + " (" + p.files().size() + " listed files).");
        String id = Installer.install(j, req);
        Srv s = Main.find(id);
        try {
            Path mods = s.dir.resolve("mods");
            Files.createDirectories(mods);
            List<Fetched> manual = new ArrayList<>();
            int installed = 0, clientOnly = 0, skippedOther = 0;
            if (p.format().equals("curseforge")) {
                Map<Long, Map<String, Object>> byFile = new LinkedHashMap<>();
                List<Long> ids = new ArrayList<>(), modIds = new ArrayList<>();
                for (Map<String, Object> f : p.files()) if (f.get("fileID") instanceof Number n) { ids.add(n.longValue()); if (f.get("projectID") instanceof Number m) modIds.add(m.longValue()); }
                j.log("Looking up " + ids.size() + " files on CurseForge ...");
                for (Map<String, Object> f : CurseForge.filesByIds(ids)) if (f.get("id") instanceof Number n) byFile.put(n.longValue(), f);
                Map<Long, Map<String, Object>> projects = new HashMap<>();
                for (Map<String, Object> m : CurseForge.mods(new LinkedHashSet<>(modIds))) if (m.get("id") instanceof Number n) projects.put(n.longValue(), m);
                // Modrinth knows whether a mod is client-only; CurseForge does not
                Map<String, Map<String, Object>> mrv = new HashMap<>();
                Map<String, Map<String, Object>> mrp = new HashMap<>();
                try {
                    Set<String> hashes = new LinkedHashSet<>();
                    for (Map<String, Object> f : byFile.values()) { String h = CurseForge.sha1Of(f); if (h != null) hashes.add(h); }
                    mrv.putAll(Modrinth.versionsByHash(hashes));
                    Set<String> pids = new LinkedHashSet<>();
                    for (Map<String, Object> v : mrv.values()) if (Json.str(v, "project_id") != null) pids.add(Json.str(v, "project_id"));
                    mrp.putAll(Modrinth.projectsById(pids));
                } catch (IOException | RuntimeException e) { j.log("Could not ask Modrinth which mods are client-only (" + e.getMessage() + "); installing every mod of the pack."); }
                ExecutorService pool = Executors.newFixedThreadPool(4);
                List<Future<String>> futures = new ArrayList<>();
                List<String> problems = Collections.synchronizedList(new ArrayList<>());
                for (Map<String, Object> pf : p.files()) {
                    long fid = pf.get("fileID") instanceof Number n ? n.longValue() : -1, pid = pf.get("projectID") instanceof Number n ? n.longValue() : -1;
                    Map<String, Object> f = byFile.get(fid);
                    Map<String, Object> proj = projects.get(pid);
                    String title = proj == null ? "Mod " + pid : Json.str(proj, "name");
                    if (f == null) { problems.add(title + ": this file no longer exists on CurseForge"); continue; }
                    int cls = proj != null && proj.get("classId") instanceof Number n ? n.intValue() : CurseForge.CLASS_MODS;
                    if (cls != CurseForge.CLASS_MODS) { skippedOther++; continue; }
                    String h = CurseForge.sha1Of(f);
                    Map<String, Object> v = h == null ? null : mrv.get(h);
                    Map<String, Object> mp = v == null ? null : mrp.get(Json.str(v, "project_id"));
                    if (mp != null && "unsupported".equals(Json.str(mp, "server_side"))) { clientOnly++; continue; }
                    String fn = Json.str(f, "fileName");
                    if (fn == null || !fn.matches("[A-Za-z0-9][A-Za-z0-9._+()\\[\\]\\- ]{0,150}\\.jar")) { problems.add(title + ": unsafe file name " + fn); continue; }
                    Path dest = mods.resolve(fn).normalize();
                    if (!dest.getParent().equals(mods.normalize())) { problems.add(title + ": unsafe file name"); continue; }
                    if (!(f.get("downloadUrl") instanceof String url)) {
                        Fetched m = new Fetched();
                        m.file = fn; m.title = title; m.manual = true;
                        Map<String, Object> links = proj != null && proj.get("links") instanceof Map ? Json.obj(proj.get("links")) : null;
                        m.page = links == null ? null : Json.str(links, "websiteUrl");
                        synchronized (manual) { manual.add(m); }
                        continue;
                    }
                    futures.add(pool.submit(() -> {
                        try { CurseForge.downloadMod(url, dest, h); return null; }
                        catch (IOException e) { return title + ": " + e.getMessage(); }
                    }));
                }
                int done = 0, last = 0;
                for (Future<String> fu : futures) {
                    j.checkCancel();
                    String err = fu.get();
                    if (err != null) problems.add(err); else installed++;
                    done++;
                    int pct = done * 100 / Math.max(1, futures.size());
                    if (pct >= last + 20) { last = pct / 20 * 20; j.log("  " + last + "% of the mods downloaded ..."); }
                }
                pool.shutdown();
                for (String pr : problems) j.log("Problem: " + pr);
                if (!problems.isEmpty()) j.log(problems.size() + " mod(s) could not be installed; see the lines above.");
            } else {
                ExecutorService pool = Executors.newFixedThreadPool(4);
                List<Future<String>> futures = new ArrayList<>();
                List<String> problems = Collections.synchronizedList(new ArrayList<>());
                Path base = s.dir.toAbsolutePath().normalize();
                for (Map<String, Object> f : p.files()) {
                    String path = Json.str(f, "path");
                    if (path == null || !safeRel(path)) { problems.add("unsafe path " + path); continue; }
                    Map<String, Object> env = f.get("env") instanceof Map ? Json.obj(f.get("env")) : Map.of();
                    if ("unsupported".equals(Json.str(env, "server"))) { clientOnly++; continue; }
                    if (skipOverride(path)) { skippedOther++; continue; }
                    List<Object> dl = Json.arr(f.get("downloads"));
                    Map<String, Object> hs = Json.obj(f.get("hashes"));
                    Path dest = base.resolve(path).normalize();
                    if (!dest.startsWith(base) || dl.isEmpty()) { problems.add(path + ": no usable download"); continue; }
                    String url = String.valueOf(dl.get(0));
                    futures.add(pool.submit(() -> {
                        try { fetchUrl(url, dest, Json.str(hs, "sha1"), Json.str(hs, "sha512"), 400L << 20, MRPACK_HOSTS); return null; }
                        catch (IOException e) { return path + ": " + e.getMessage(); }
                    }));
                }
                int done = 0, last = 0;
                for (Future<String> fu : futures) {
                    j.checkCancel();
                    String err = fu.get();
                    if (err != null) problems.add(err); else installed++;
                    done++;
                    int pct = done * 100 / Math.max(1, futures.size());
                    if (pct >= last + 20) { last = pct / 20 * 20; j.log("  " + last + "% of the files downloaded ..."); }
                }
                pool.shutdown();
                for (String pr : problems) j.log("Problem: " + pr);
            }
            j.log("Installed " + installed + " mod/file(s)" + (clientOnly > 0 ? ", skipped " + clientOnly + " client-only mod(s)" : "") + (skippedOther > 0 ? ", skipped " + skippedOther + " resource/shader pack(s)" : "") + ".");
            int o = unpackOverrides(j, file, p, s.dir, p.overrides());
            int so = p.format().equals("modrinth") ? unpackOverrides(j, file, p, s.dir, "server-overrides") : 0;
            j.log("Copied " + (o + so) + " config and data file(s) from the pack.");
            if (!manual.isEmpty()) {
                j.log("These mods do not allow downloads through CurseForge's API. Download each file from its page and put it in " + mods + ":");
                for (Fetched m : manual) j.log("  " + m.title + " (" + m.file + ")" + (m.page == null ? "" : " - " + m.page));
                Notify.post("system", "warn", s.id, s.name + ": " + manual.size() + " mod" + (manual.size() == 1 ? "" : "s") + " need a manual download",
                        "The modpack lists mods whose authors do not allow downloads through CurseForge's API: " + String.join(", ", manual.stream().limit(6).map(m -> m.title).toList()) + (manual.size() > 6 ? " and more" : "")
                                + ". The job log has the links.", Json.map("type", "open", "server", s.id, "tab", "mods", "label", "Open " + s.name));
            }
            // a modpack is tested as a whole: do not let the updater move Minecraft or mods on its own
            s.cfg.put("autoUpdate", false);
            s.cfg.put("modpack", Json.map("name", p.name(), "version", p.version(), "format", p.format(), "installed", System.currentTimeMillis()));
            Main.cfg.save();
            if (p.loaderType().equals("fabric")) s.setPinned(true);
            Users.audit(actor, "", "server created from a modpack", s.id, p.name());
            j.log("Done. " + s.name + " is ready" + (startAfter ? "." : ". The first start of a big modpack can take several minutes."));
            if (startAfter) s.start(j);
        } catch (Throwable t) {
            j.log("The modpack install failed; removing the new server again.");
            try { Main.removeServer(s); } catch (Exception ignored) { }
            Archives.deleteTree(s.dir);
            throw t;
        }
        return id;
    }
}
