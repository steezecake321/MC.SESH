import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * First-install setup, run by the installer:  java -jar mcmanager.jar --provision <options.json>
 * The file holds the folders and the "create server" defaults the person chose. Secrets (the owner password and the CurseForge key) arrive
 * as environment variables of this one process, so they are never written to disk or to the installer's log.
 */
final class Provision {
    private Provision() { }

    static void run(Path file) throws Exception {
        Map<String, Object> o = Json.obj(Json.parse(Files.readString(file, StandardCharsets.UTF_8)));
        String servers = Json.str(o, "serversRoot"), backups = Json.str(o, "backupsRoot");
        if (servers != null && !servers.isBlank()) {
            Files.createDirectories(Path.of(servers));
            Main.cfg.paths().put("serversRoot", Path.of(servers).normalize().toString());
        }
        if (backups != null && !backups.isBlank()) {
            Files.createDirectories(Path.of(backups));
            Main.cfg.paths().put("backupsRoot", Path.of(backups).normalize().toString());
        }
        if (o.get("createDefaults") instanceof Map) Main.cfg.root.put("createDefaults", new LinkedHashMap<>(Json.obj(o.get("createDefaults"))));
        Main.cfg.save();
        System.out.println("Folders and create-server defaults saved.");

        String cf = System.getenv("MCSESH_CF_KEY");
        if (cf != null && !cf.isBlank()) {
            try {
                CurseForge.setKey(cf);
                System.out.println("CurseForge key saved and checked.");
            } catch (Exception e) {
                String k = cf.strip();
                String m = String.valueOf(e.getMessage());
                if (e instanceof Api && (m.startsWith("That does not look") || m.contains("did not accept"))) {
                    System.out.println("The CurseForge key was not saved: " + m + " Add it later under Settings > CurseForge.");
                } else {
                    Main.cfg.root.put("curseforge", Json.map("apiKey", k));
                    Main.cfg.save();
                    System.out.println("CurseForge key saved, but it could not be checked right now (" + e.getMessage() + ").");
                }
            }
        }

        String user = System.getenv("MCSESH_OWNER_USER"), pw = System.getenv("MCSESH_OWNER_PASS");
        if (user != null && !user.isBlank() && pw != null && !pw.isEmpty()) {
            if (Users.any()) System.out.println("An account already exists, so no new owner account was created.");
            else {
                try {
                    Users.Acct a = Users.createFirstAdmin(user.trim(), pw);
                    Users.audit(a.username, "installer", "administrator account created", "", "created by the installer");
                    System.out.println("Owner account " + a.username + " created (OP5).");
                } catch (Api e) {
                    System.out.println("The owner account was not created: " + e.getMessage());
                    System.exit(2);
                }
            }
        }
    }
}
