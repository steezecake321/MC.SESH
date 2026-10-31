import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * What players need on their own game to join a modded server: a readable list (to paste into a chat) and a Modrinth modpack file (.mrpack) that
 * Modrinth App and Prism Launcher can import in one step. Built from the mods actually installed on the server, looked up by checksum on Modrinth.
 */
final class ClientPack {
    private ClientPack() { }

    private record E(String projectId, String title, String slug, String versionId, String versionNumber, String file, long size, String url, String sha1, String sha512,
                     String icon, String client, String server, boolean dependency, List<Object> deps) { }

    static String loaderVersion(Srv s) {
        String kind = s.kind();
        try {
            if (kind.equals("fabric")) return s.loaderVersion();
            Path base = s.dir.resolve("libraries").resolve("net").resolve(kind.equals("neoforge") ? "neoforged" : "minecraftforge").resolve(kind.equals("neoforge") ? "neoforge" : "forge");
            if (!Files.isDirectory(base)) return null;
            try (Stream<Path> st = Files.list(base)) {
                List<String> names = st.filter(Files::isDirectory).map(p -> p.getFileName().toString()).sorted().toList();
                if (names.isEmpty()) return null;
                String v = names.get(names.size() - 1), mc = s.mcVersion();
                return kind.equals("forge") && mc != null && v.startsWith(mc + "-") ? v.substring(mc.length() + 1) : v;
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static E entry(Map<String, Object> proj, Map<String, Object> version, boolean dependency) {
        Map<String, Object> f = Modrinth.primaryFile(version);
        if (f == null) return null;
        Map<String, Object> h = Json.obj(f.get("hashes"));
        return new E(Json.str(proj, "id"), Json.str(proj, "title"), Json.str(proj, "slug"), Json.str(version, "id"), Json.str(version, "version_number"), Json.str(f, "filename"),
                Json.obj(f).get("size") instanceof Number n ? n.longValue() : 0, Json.str(f, "url"), Json.str(h, "sha1"), Json.str(h, "sha512"), Json.str(proj, "icon_url"),
                Json.str(proj, "client_side"), Json.str(proj, "server_side"), dependency, Json.arr(version.get("dependencies")));
    }

    private static Map<String, Object> view(E e, boolean selected) {
        return Json.map("projectId", e.projectId(), "title", e.title(), "slug", e.slug(), "version", e.versionNumber(), "file", e.file(), "size", e.size(), "icon", e.icon(),
                "url", "https://modrinth.com/mod/" + e.slug(), "dependency", e.dependency(), "selected", selected, "client", e.client());
    }

    /** The result of working out a client list: the model shown in the UI, and the files that go into a modpack. */
    record Result(Map<String, Object> model, List<E> files) { }

    static Result build(Srv s, Set<String> optIds, Set<String> extraIds) throws IOException {
        String loader = Modrinth.loaderOf(s), mc = s.mcVersion();
        if (mc == null) throw new Api(409, "Could not determine this server's Minecraft version.");
        List<Object> warnings = new ArrayList<>();
        Map<String, Path> byHash = new LinkedHashMap<>();
        for (Path j : Worlds.jars(s.dir.resolve("mods"))) byHash.put(Modrinth.sha1(j), j);
        Map<String, Map<String, Object>> versions = Modrinth.versionsByHash(byHash.keySet());
        Set<String> pids = new LinkedHashSet<>();
        for (Map<String, Object> v : versions.values()) if (Json.str(v, "project_id") != null) pids.add(Json.str(v, "project_id"));
        List<Object> extrasCfg = s.cfg.get("clientExtras") instanceof List<?> l ? new ArrayList<Object>(l) : new ArrayList<>();
        Map<String, Map<String, Object>> extraVersions = new LinkedHashMap<>();
        if (!extrasCfg.isEmpty()) {
            List<String> hs = new ArrayList<>();
            for (Object x : extrasCfg) if (Json.str(x, "sha1") != null) hs.add(Json.str(x, "sha1"));
            extraVersions = Modrinth.versionsByHash(hs);
            for (Map<String, Object> v : extraVersions.values()) if (Json.str(v, "project_id") != null) pids.add(Json.str(v, "project_id"));
        }
        Map<String, Map<String, Object>> projects = Modrinth.projectsById(pids);

        Map<String, E> required = new LinkedHashMap<>(), optional = new LinkedHashMap<>(), installedAll = new LinkedHashMap<>();
        List<Object> serverOnly = new ArrayList<>(), unknown = new ArrayList<>();
        for (Map.Entry<String, Path> en : byHash.entrySet()) {
            Map<String, Object> v = versions.get(en.getKey());
            Map<String, Object> proj = v == null ? null : projects.get(Json.str(v, "project_id"));
            E e = proj == null ? null : entry(proj, v, false);
            if (e == null) { unknown.add(en.getValue().getFileName().toString()); continue; }
            if (installedAll.containsKey(e.projectId())) continue;
            installedAll.put(e.projectId(), e);
            switch (e.client() == null ? "" : e.client()) {
                case "required" -> required.put(e.projectId(), e);
                case "unsupported" -> serverOnly.add(e.title());
                default -> optional.put(e.projectId(), e);
            }
        }

        // extras: mods from the player's own game that the server does not run
        Map<String, E> extras = new LinkedHashMap<>();
        Set<String> incompatible = new HashSet<>();
        for (Object x : extrasCfg) {
            Map<String, Object> v = extraVersions.get(Json.str(x, "sha1"));
            Map<String, Object> proj = v == null ? null : projects.get(Json.str(v, "project_id"));
            E e = proj == null ? null : entry(proj, v, false);
            if (e == null || installedAll.containsKey(e.projectId())) continue;
            extras.put(e.projectId(), e);
            if (!Json.arr(v.get("loaders")).contains(loader) || !Json.arr(v.get("game_versions")).contains(mc)) incompatible.add(e.projectId());
        }

        // what is selected, plus the helper mods those need on the client
        Map<String, E> chosen = new LinkedHashMap<>(required);
        Set<String> selOpt = new LinkedHashSet<>(), selExtra = new LinkedHashSet<>();
        for (String id : optIds) if (optional.containsKey(id)) { chosen.put(id, optional.get(id)); selOpt.add(id); }
        for (String id : extraIds) if (extras.containsKey(id) && !incompatible.contains(id)) { chosen.put(id, extras.get(id)); selExtra.add(id); }
        Deque<E> queue = new ArrayDeque<>(chosen.values());
        int guard = 0;
        while (!queue.isEmpty()) {
            if (++guard > 120) { warnings.add("Some mods need very many helper mods; the list may be incomplete."); break; }
            E cur = queue.poll();
            for (Object d : cur.deps()) {
                String dp = Json.str(d, "project_id");
                if (dp == null || !"required".equals(Json.str(d, "dependency_type")) || chosen.containsKey(dp)) continue;
                E dep = installedAll.get(dp);
                if (dep != null) {
                    dep = new E(dep.projectId(), dep.title(), dep.slug(), dep.versionId(), dep.versionNumber(), dep.file(), dep.size(), dep.url(), dep.sha1(), dep.sha512(), dep.icon(), dep.client(), dep.server(), true, dep.deps());
                } else {
                    Map<String, Object> v = Modrinth.chooseVersion(dp, loader, mc, Json.str(d, "version_id"));
                    Map<String, Object> proj;
                    try { proj = Modrinth.projectRecord(dp); } catch (Api ex) { warnings.add("Could not look up a required helper mod (" + dp + ")."); continue; }
                    if (v == null) { warnings.add(Json.str(proj, "title") + " is needed by " + cur.title() + " but has no version for " + loader + " on Minecraft " + mc + "."); continue; }
                    dep = entry(proj, v, true);
                    if (dep == null) continue;
                }
                chosen.put(dep.projectId(), dep);
                required.putIfAbsent(dep.projectId(), dep);
                optional.remove(dep.projectId());
                queue.add(dep);
            }
        }
        for (String id : required.keySet()) { selOpt.remove(id); }

        List<E> files = new ArrayList<>(chosen.values());
        List<Object> reqView = new ArrayList<>(), optView = new ArrayList<>(), exView = new ArrayList<>();
        for (E e : required.values()) reqView.add(view(e, true));
        for (E e : optional.values()) optView.add(view(e, selOpt.contains(e.projectId())));
        for (E e : extras.values()) { Map<String, Object> m = view(e, selExtra.contains(e.projectId())); m.put("compatible", !incompatible.contains(e.projectId())); exView.add(m); }
        String lv = loaderVersion(s);
        Map<String, Object> model = Json.map("name", s.name, "mc", mc, "loader", loader, "loaderVersion", lv, "required", reqView, "optional", optView, "extras", exView, "serverOnly", serverOnly,
                "unknown", unknown, "warnings", warnings);
        model.put("text", text(s, mc, loader, lv, files));
        return new Result(model, files);
    }

    private static String text(Srv s, String mc, String loader, String lv, List<E> files) {
        String ln = loader.equals("fabric") ? "Fabric" : loader.equals("forge") ? "Forge" : "NeoForge";
        String where = loader.equals("fabric") ? "https://fabricmc.net/use/installer/" : loader.equals("forge") ? "https://files.minecraftforge.net/" : "https://neoforged.net/";
        StringBuilder sb = new StringBuilder();
        sb.append("To join \"").append(s.name).append("\" you need Minecraft ").append(mc).append(" with ").append(ln).append(lv == null ? "" : " " + lv).append(" and these mods.\n\n");
        sb.append("Easiest way: Modrinth App or Prism Launcher can create a profile for Minecraft ").append(mc).append(" + ").append(ln).append(" for you (the loader installer is also at ").append(where).append(").\n");
        sb.append("Then add each mod below (Modrinth App has a button for it, or download the file and put it in the profile's mods folder).\n\n");
        if (files.isEmpty()) sb.append("No extra mods are needed on your side.\n");
        else {
            sb.append("Mods to install:\n");
            for (E e : files) sb.append(" - ").append(e.title()).append(" ").append(e.versionNumber()).append("  https://modrinth.com/mod/").append(e.slug()).append(e.dependency() ? "  (needed by another mod)" : "").append('\n');
        }
        return sb.toString();
    }

    private static String sanitize(String f) {
        String n = f == null ? "mod.jar" : f.replaceAll("[^A-Za-z0-9._+()\\- ]", "_");
        return n.isEmpty() || n.startsWith(".") ? "mod.jar" : n;
    }

    /** A Modrinth modpack (.mrpack): an index of the files with their checksums and download addresses. Players' launchers download the mods themselves. */
    static byte[] mrpack(Srv s, Result r) throws IOException {
        String loader = Modrinth.loaderOf(s), mc = s.mcVersion(), lv = loaderVersion(s);
        if (lv == null) throw new Api(409, "The manager could not work out the " + loader + " version this server runs, so it cannot describe it in a modpack.");
        Map<String, Object> deps = new LinkedHashMap<>();
        deps.put("minecraft", mc);
        deps.put(loader.equals("fabric") ? "fabric-loader" : loader, lv);
        List<Object> files = new ArrayList<>();
        for (E e : r.files()) {
            if (e.url() == null || e.sha1() == null || e.sha512() == null) throw new Api(502, "Modrinth gave no checksum or address for " + e.title() + ".");
            Modrinth.checkUrl(e.url());
            files.add(Json.map("path", "mods/" + sanitize(e.file()), "hashes", Json.map("sha1", e.sha1(), "sha512", e.sha512()), "env", Json.map("client", "required", "server", e.server() == null ? "optional" : e.server()),
                    "downloads", List.of(e.url()), "fileSize", e.size()));
        }
        Map<String, Object> index = Json.map("formatVersion", 1, "game", "minecraft", "versionId", "1." + new SimpleDateFormat("yyyyMMdd").format(new Date()), "name", s.name + " (client)",
                "summary", "The game setup for joining " + s.name + " (made by MC Server Manager)", "files", files, "dependencies", deps);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bo)) {
            z.putNextEntry(new ZipEntry("modrinth.index.json"));
            z.write(Json.stringify(index).getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        return bo.toByteArray();
    }

    /** The same client list as a CurseForge modpack (.zip with manifest.json). Only mods CurseForge recognises by fingerprint can be listed; the others are returned in missing. */
    static byte[] curseforgeZip(Srv s, Result r, List<String> missing) throws IOException {
        String loader = Modrinth.loaderOf(s), mc = s.mcVersion(), lv = loaderVersion(s);
        if (lv == null) throw new Api(409, "The manager could not work out the " + loader + " version this server runs, so it cannot describe it in a modpack.");
        Map<String, Map<String, Object>> byFile = new HashMap<>();
        for (Map<String, Object> m : CurseForge.identify(s)) byFile.put(Json.str(m, "file"), m);
        List<Object> files = new ArrayList<>();
        StringBuilder html = new StringBuilder("<ul>\n");
        Set<Object> seenMods = new HashSet<>();
        for (E e : r.files()) {
            Map<String, Object> m = byFile.get(e.file());
            if (m == null) { missing.add(e.title()); continue; }
            if (!seenMods.add(m.get("modId"))) continue;
            files.add(Json.map("projectID", m.get("modId"), "fileID", m.get("fileId"), "required", true));
            html.append("<li>").append(e.title().replace("&", "&amp;").replace("<", "&lt;")).append("</li>\n");
        }
        html.append("</ul>\n");
        Map<String, Object> manifest = Json.map("minecraft", Json.map("version", mc, "modLoaders", List.of(Json.map("id", loader + "-" + lv, "primary", true))), "manifestType", "minecraftModpack", "manifestVersion", 1,
                "name", s.name + " (client)", "version", "1." + new SimpleDateFormat("yyyyMMdd").format(new Date()), "author", "MC Server Manager", "files", files, "overrides", "overrides");
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bo)) {
            z.putNextEntry(new ZipEntry("manifest.json"));
            z.write(Json.stringify(manifest).getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
            z.putNextEntry(new ZipEntry("modlist.html"));
            z.write(html.toString().getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
            z.putNextEntry(new ZipEntry("overrides/"));
            z.closeEntry();
        }
        return bo.toByteArray();
    }
    static Set<String> ids(String csv) {
        Set<String> out = new LinkedHashSet<>();
        if (csv == null) return out;
        for (String p : csv.split(",")) {
            String t = p.trim();
            if (!t.isEmpty() && Modrinth.SAFE_ID.matcher(t).matches() && out.size() < 300) out.add(t);
        }
        return out;
    }
}
