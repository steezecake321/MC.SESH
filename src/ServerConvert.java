import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/** Adds a mod loader (Fabric, Forge or NeoForge) to an existing vanilla server without touching its world, settings, whitelist or ops. */
final class ServerConvert {
    private ServerConvert() { }

    static final List<String> LOADERS = List.of("fabric", "forge", "neoforge");

    /** Why this server cannot be converted, or null. */
    static String blockedReason(Srv s) { return blockedReason(s, false); }

    private static String blockedReason(Srv s, boolean inJob) {
        if (s.isFabric() || !"vanilla".equals(s.kind())) return "This server already has a mod loader (" + s.kind() + ").";
        if (!Files.isDirectory(s.dir)) return "The server folder no longer exists.";
        for (String f : new String[]{"bukkit.yml", "spigot.yml", "paper.yml", "purpur.yml"})
            if (Files.exists(s.dir.resolve(f))) return "This looks like a plugin server (Paper, Spigot or similar). Fabric, Forge and NeoForge cannot load plugins, so converting it would lose them. Create a new modded server instead.";
        if (Files.isDirectory(s.dir.resolve("plugins")) || Files.isDirectory(s.dir.resolve("config")) && Files.exists(s.dir.resolve("config").resolve("paper-global.yml")))
            return "This looks like a plugin server (it has a plugins folder). Fabric, Forge and NeoForge cannot load plugins, so converting it would lose them.";
        if (!s.task.isEmpty()) return "This server is started by the Windows scheduled task \"" + s.task + "\", which runs the old start script. Remove it from the manager and import it again without the task, then convert it.";
        if (s.running(true)) return "Stop " + s.name + " first.";
        if (!inJob && s.job != null && s.job.active()) return "Another action is in progress on " + s.name + ": " + s.job.title;
        String mc = s.mcVersion();
        if (mc == null || mc.isBlank()) return "The manager could not work out which Minecraft version this server runs (no server.jar it can read).";
        return null;
    }

    static Map<String, Object> info(Srv s) {
        Map<String, Object> out = Json.map("blocked", blockedReason(s), "mc", s.mcVersion(), "kind", s.kind(), "backupDir", Backups.dirFor(s).toString());
        try { if (s.mcVersion() != null) out.put("java", Installer.javaInfo(s.mcVersion())); } catch (Exception e) { out.put("javaError", e.getMessage()); }
        return out;
    }

    /** Synchronous checks, so a bad request is reported in the dialog before any work starts. */
    static void validate(Srv s, Map<String, Object> req) throws IOException { validate(s, req, false); }

    private static void validate(Srv s, Map<String, Object> req, boolean inJob) throws IOException {
        String why = blockedReason(s, inJob);
        if (why != null) throw new Api(409, why);
        String type = String.valueOf(req.get("type")), loader = String.valueOf(req.get("loader"));
        if (!LOADERS.contains(type)) throw new Api(400, "Choose Fabric, Forge or NeoForge.");
        String mc = s.mcVersion();
        boolean found = false;
        for (Object o : Installer.loaders(type, mc)) if (loader.equals(Json.str(o, "id"))) found = true;
        if (!found) throw new Api(400, (type.equals("fabric") ? "Fabric" : type.equals("forge") ? "Forge" : "NeoForge") + " " + loader + " is not available for Minecraft " + mc + ".");
        Map<String, Object> jv = Installer.javaInfo(mc);
        if (Json.num(jv, "required", 8) > Runtime.version().feature())
            throw new Api(400, "Minecraft " + mc + " needs Java " + jv.get("required") + " but this PC only has Java " + Runtime.version().feature());
    }

    private static Set<String> topNames(Path dir) throws IOException {
        Set<String> out = new HashSet<>();
        try (Stream<Path> w = Files.list(dir)) { for (Path p : w.toList()) out.add(p.getFileName().toString()); }
        return out;
    }

    static void convert(Job j, Srv s, Map<String, Object> req, String actor) throws Exception {
        validate(s, req, true);
        String type = String.valueOf(req.get("type")), loader = String.valueOf(req.get("loader")), mc = s.mcVersion();
        String label = type.equals("fabric") ? "Fabric" : type.equals("forge") ? "Forge" : "NeoForge";
        j.log("Adding " + label + " " + loader + " to \"" + s.name + "\" (Minecraft " + mc + "). The world, settings, whitelist and ops are not touched.");

        Map<String, Object> old = new LinkedHashMap<>();
        try { old = Launch.read(s); } catch (IOException | RuntimeException e) { j.log("Could not read the old memory settings (" + e.getMessage() + "); using defaults."); }
        boolean editable = Boolean.TRUE.equals(old.get("editable"));
        long min = editable && old.get("minMb") instanceof Number n && n.longValue() > 0 ? n.longValue() : 1024;
        long max = editable && old.get("maxMb") instanceof Number n && n.longValue() > 0 ? n.longValue() : 4096;
        if (min > max) min = max;

        if (!Boolean.FALSE.equals(req.get("backup"))) {
            Backups.create(j, s, "full", Backups.dirFor(s), "Automatic backup before adding " + label + " " + loader, "before-conversion", actor);
        } else {
            j.log("Skipping the backup, as requested.");
        }
        j.checkCancel();

        Path dir = s.dir.toAbsolutePath().normalize();
        Set<String> before = topNames(dir);
        String line;
        try {
            line = Installer.installLoader(j, dir, type, mc, loader, "-Xms" + min + "M -Xmx" + max + "M");
        } catch (Throwable t) {
            j.log("The installer did not finish; removing the files it added. Your server was not changed.");
            for (String n : topNames(dir)) {
                if (before.contains(n)) continue;
                try {
                    try (Stream<Path> w = Files.walk(dir.resolve(n))) { w.sorted(Comparator.reverseOrder()).forEach(f -> { try { Files.delete(f); } catch (IOException ignored) { } }); }
                } catch (IOException ignored) { }
            }
            throw t;
        }
        j.checkCancel();

        Path script = dir.resolve("manager-start.bat");
        Path bak = dir.resolve("manager-start.bat.mcm-bak");
        if (Files.exists(script) && !Files.exists(bak)) Files.copy(script, bak);
        Files.writeString(script, "@ECHO OFF\r\ncd /d \"%~dp0\"\r\nif not defined MCM_JAVA set \"MCM_JAVA=java\"\r\n" + line + "\r\n", StandardCharsets.ISO_8859_1);
        String oldScript = s.script();
        s.cfg.put("script", "manager-start.bat");
        s.cfg.put("kind", type);
        s.cfg.put("mc", mc);
        boolean auto = type.equals("fabric") && !Boolean.FALSE.equals(req.get("autoUpdate"));
        s.cfg.put("autoUpdate", auto);
        if (type.equals("fabric") && Boolean.TRUE.equals(req.get("pinMc"))) {
            s.setPinned(true);
            j.log("Minecraft version pinned: the updater will update Fabric and mods but keep Minecraft " + mc + ".");
        }
        Main.cfg.save();
        Files.createDirectories(dir.resolve("mods"));
        j.log("Wrote manager-start.bat. " + (oldScript.equals("manager-start.bat") ? "The previous copy is kept as manager-start.bat.mcm-bak." : "Your old " + oldScript + " is untouched but the manager no longer uses it."));

        if (editable) {
            try {
                String gc = String.valueOf(old.get("gc"));
                Launch.write(s, min, max, List.of("g1", "zgc", "parallel", "serial", "shenandoah", "none").contains(gc) ? gc : "keep", String.valueOf(old.getOrDefault("extra", "")),
                        old.get("toggles") instanceof Map ? Json.obj(old.get("toggles")) : null, true);
                j.log("Memory, garbage collector and tuning flags from the old script were carried over.");
            } catch (Exception e) {
                j.log("Could not carry over every old JVM setting (" + e.getMessage() + "); the memory size was kept. Review the Memory and Java options on the Properties tab.");
            }
        }
        if (auto) j.log("Automatic Fabric updates are ON. You can turn them off in the server's Settings.");
        j.log("Done. " + s.name + " is now a " + label + " server. Start it when you are ready; mods go in the mods folder (or use the Mods tab).");
    }
}
