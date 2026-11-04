import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Start-with-Windows settings for the manager, and taking over old per-server scheduled tasks. Changes run in an elevated helper. */
final class Startup {
    private Startup() {}

    /** The startup task and the firewall rule are called MC.SESH. Installs from before the rename keep "MC Server Manager" until they are migrated. */
    static final String TASK_NEW = "MC.SESH", TASK_OLD = "MC Server Manager";
    private static String[] names;
    private static long namesAt;

    /** {task name, firewall rule name} that this PC actually uses (cached for a few seconds). */
    private static synchronized String[] names() {
        if (names != null && System.currentTimeMillis() - namesAt < 10000) return names;
        boolean tNew = Sys.run(8000, "schtasks", "/Query", "/TN", TASK_NEW).code() == 0;
        boolean tOld = !tNew && Sys.run(8000, "schtasks", "/Query", "/TN", TASK_OLD).code() == 0;
        boolean rNew = Sys.run(8000, "netsh", "advfirewall", "firewall", "show", "rule", "name=" + TASK_NEW).code() == 0;
        boolean rOld = !rNew && Sys.run(8000, "netsh", "advfirewall", "firewall", "show", "rule", "name=" + TASK_OLD).code() == 0;
        names = new String[]{tOld ? TASK_OLD : TASK_NEW, rOld ? TASK_OLD : TASK_NEW};
        namesAt = System.currentTimeMillis();
        return names;
    }

    private static synchronized void forgetNames() { names = null; }

    static String task() { return names()[0]; }

    static boolean available() {
        return Files.exists(Main.HOME.resolve("Start.bat")) && Files.exists(Main.TOOLS.resolve("Set-Startup.ps1"));
    }

    private static String groups() { return Sys.run(8000, "whoami", "/groups").out(); }

    static boolean elevated() { return groups().contains("S-1-16-12288"); }

    static boolean interactive() { return groups().contains("S-1-5-4"); }

    static String user() {
        String d = System.getenv("USERDOMAIN");
        return (d == null || d.isBlank() ? "" : d + "\\") + System.getProperty("user.name");
    }

    /** off | logon | boot | other */
    static String mode() {
        Sys.Result r = Sys.run(10000, "schtasks", "/Query", "/TN", task(), "/XML");
        if (r.code() != 0) return "off";
        String x = r.out();
        if (x.contains("<Enabled>false</Enabled>")) return "off";
        if (x.contains("<BootTrigger")) return "boot";
        if (x.contains("<LogonTrigger")) return "logon";
        return "other";
    }

    static boolean helperPresent() { return Files.exists(Main.TOOLS.resolve("Set-Startup.ps1")); }

    static boolean firewallRule() {
        return Sys.run(8000, "netsh", "advfirewall", "firewall", "show", "rule", "name=" + names()[1]).code() == 0;
    }

    static String gameRule(int port) { return "MC.SESH server " + port; }

    /** True when the manager's own allow rule for this server port exists. A rule tied to one java.exe path stops working when Java is updated or moved, a port rule does not. */
    static boolean gameRuleOpen(int port) {
        return Sys.run(8000, "netsh", "advfirewall", "firewall", "show", "rule", "name=" + gameRule(port)).code() == 0;
    }

    /** Game ports of the managed servers with whether the manager's allow rule exists for each. */
    static List<Object> gamePorts() {
        List<Object> out = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (Srv s : Main.servers) {
            int p = s.gamePort();
            if (p < 1024 || !seen.add(p)) continue;
            out.add(Json.map("id", s.id, "name", s.name, "port", p, "open", gameRuleOpen(p)));
        }
        return out;
    }

    /** What Windows itself says about the startup task: when it last ran and how that ended. Empty when there is no task. */
    static Map<String, Object> taskInfo() {
        Sys.Result r = Sys.run(10000, "schtasks", "/Query", "/TN", task(), "/V", "/FO", "LIST");
        Map<String, Object> out = new LinkedHashMap<>();
        if (r.code() != 0) return out;
        for (String line : r.out().split("\\R")) {
            int i = line.indexOf(':');
            if (i < 0) continue;
            String k = line.substring(0, i).trim(), v = line.substring(i + 1).trim();
            switch (k) {
                case "Last Run Time" -> out.put("lastRun", v);
                case "Last Result" -> out.put("lastResult", v.equals("267009") ? "running" : v.equals("0") ? "ok" : v);
                case "Status" -> out.put("taskStatus", v);
                case "Next Run Time" -> out.put("nextRun", v);
                default -> { }
            }
        }
        return out;
    }

    static Map<String, Object> status() {
        return Json.map("available", available(), "mode", available() ? mode() : "off", "elevated", elevated(), "interactive", interactive(), "user", user(), "task", task(),
                "firewallAvailable", helperPresent(), "firewall", firewallRule(), "gamePorts", gamePorts(), "port", Main.cfg.port(), "taskInfo", available() ? taskInfo() : Map.of(),
                "managerSince", System.currentTimeMillis() - Main.uptimeMs());
    }

    /** Scheduled tasks (other than ours) that launch one of the managed servers. */
    static List<Object> legacy() {
        String ps = "Get-ScheduledTask | ForEach-Object { $a = $_.Actions | Select-Object -First 1; [pscustomobject]@{ name = $_.TaskName; state = [string]$_.State; "
                + "exec = [string]$a.Execute; args = [string]$a.Arguments; dir = [string]$a.WorkingDirectory; "
                + "trig = (($_.Triggers | ForEach-Object { $_.CimClass.CimClassName -replace 'MSFT_Task', '' -replace 'Trigger', '' }) -join ',') } } | ConvertTo-Json -Compress";
        String out = Sys.run(40000, "powershell", "-NoProfile", "-NonInteractive", "-Command", ps).out().trim();
        List<Object> found = new ArrayList<>();
        if (out.isEmpty() || !(out.startsWith("[") || out.startsWith("{"))) return found;
        Object parsed;
        try { parsed = Json.parse(out); } catch (RuntimeException e) { return found; }
        List<Object> tasks = parsed instanceof List ? Json.arr(parsed) : List.of(parsed);
        for (Object t : tasks) {
            String name = Json.str(t, "name");
            if (name == null || name.equals(TASK_NEW) || name.equals(TASK_OLD)) continue;
            String text = (Json.str(t, "exec") + " " + Json.str(t, "args") + " " + Json.str(t, "dir")).toLowerCase().replace("\"", "");
            for (Srv s : Main.servers) {
                String d = s.dir.toString().toLowerCase();
                if (text.contains(d) || name.equals(s.task)) {
                    found.add(Json.map("name", name, "state", Json.str(t, "state"), "trigger", Json.str(t, "trig"), "server", s.id, "serverName", s.name));
                    break;
                }
            }
        }
        return found;
    }

    static void apply(Job j, String mode, boolean asSystem) throws Exception {
        if (!List.of("off", "logon", "boot", "firewall", "gameFirewall").contains(mode)) throw new Api(400, "Unknown startup mode");
        if (mode.equals("gameFirewall")) {
            if (!helperPresent()) throw new Api(409, "The firewall helper is part of the installed version of the manager (run Setup.ps1).");
            StringBuilder ports = new StringBuilder();
            for (Object o : gamePorts()) ports.append(ports.length() == 0 ? "" : ",").append(Json.num(o, "port", 0));
            if (ports.length() == 0) throw new Api(400, "No servers to open ports for.");
            runElevated(j, "-Action", "gameFirewall", "-Ports", ports.toString());
            return;
        }
        if (mode.equals("firewall")) {
            if (!helperPresent()) throw new Api(409, "The firewall helper is part of the installed version of the manager (run Setup.ps1).");
            runElevated(j, "-Action", "firewall", "-Port", Integer.toString(Main.cfg.port()));
            return;
        }
        if (!available()) throw new Api(409, "Start-with-Windows is available in the installed version of the manager (run Setup.ps1).");
        List<String> a = new ArrayList<>(List.of("-Action", mode, "-InstallDir", Main.HOME.toString(), "-Port", Integer.toString(Main.cfg.port()), "-User", user()));
        if (asSystem && mode.equals("boot")) a.add("-AsSystem");
        runElevated(j, a.toArray(new String[0]));
    }

    /** Opens the Bedrock (UDP) port in Windows Firewall for private networks; Windows asks the person to approve it. */
    static void bedrockFirewall(Job j, int port, int port2) throws Exception {
        if (!helperPresent()) throw new Api(409, "The firewall helper is part of the installed version of the manager (run Setup.ps1).");
        if (port < 1024 || port > 65535) throw new Api(400, "Bad port");
        runElevated(j, "-Action", "bedrockFirewall", "-Port", Integer.toString(port), "-Port2", Integer.toString(Math.max(0, port2)));
    }

    static void takeover(Job j, String taskName, Srv s) throws Exception {
        Map<String, Object> match = null;
        for (Object o : legacy()) if (taskName.equals(Json.str(o, "name")) && s.id.equals(Json.str(o, "server"))) match = Json.obj(o);
        if (match == null) throw new Api(404, "That task does not start this server");
        if (s.running(true)) throw new Api(409, "Stop " + s.name + " first (use Stop), then move it. Removing the old task will not touch a stopped server.");
        if ("Running".equalsIgnoreCase(Json.str(match, "state"))) throw new Api(409, "The old task is still running. Wait until the server has fully stopped, then try again.");
        j.log("Removing the Windows task '" + taskName + "' (" + s.name + ")...");
        runElevated(j, "-Action", "removeTask", "-Tasks", taskName);
        s.task = "";
        s.cfg.put("task", "");
        s.cfg.put("autostart", true);
        Main.cfg.save();
        j.log(s.name + " now starts with the manager. Make sure the manager itself starts with Windows (Settings > Startup).");
    }

    /** Runs Set-Startup.ps1 with administrator rights and returns when it has finished; the result comes back through a small file. */
    private static void runElevated(Job j, String... psArgs) throws Exception {
        forgetNames();
        try { runElevated0(j, psArgs); } finally { forgetNames(); }
    }

    private static void runElevated0(Job j, String... psArgs) throws Exception {
        Path script = Main.TOOLS.resolve("Set-Startup.ps1");
        Path result = Main.DATA.resolve("startup-result.json");
        Files.deleteIfExists(result);
        List<String> list = new ArrayList<>(List.of("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", script.toString()));
        list.addAll(Arrays.asList(psArgs));
        list.add("-ResultFile");
        list.add(result.toString());
        list.add("-LogFile");
        list.add(Main.DATA.resolve("startup-last.log").toString());
        if (elevated()) {
            j.log("Running with administrator rights...");
            List<String> cmd = new ArrayList<>(List.of("powershell.exe"));
            cmd.addAll(list);
            cmd.add("-NoPause");
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String l;
                while ((l = r.readLine()) != null) if (!l.isBlank()) j.log(l);
            }
            p.waitFor();
        } else {
            if (!interactive()) throw new Api(409, "The manager is running without a desktop, so Windows cannot show the approval prompt. Run this once from the PC (start the manager with Start.bat while signed in).");
            j.log("Look at the PC's screen: Windows will ask you to approve this change (and, for 'start before sign-in', for your Windows password).");
            Path launcher = Main.DATA.resolve("elevate-" + System.nanoTime() + ".ps1");
            StringBuilder args = new StringBuilder();
            for (String a : list) args.append(args.length() > 0 ? " " : "").append(a.startsWith("-") ? a : "\"" + a + "\"");
            Files.writeString(launcher, "Start-Process -FilePath powershell.exe -Verb RunAs -Wait -ArgumentList '" + args.toString().replace("'", "''") + "'\r\n", StandardCharsets.UTF_8);
            try {
                Process p = new ProcessBuilder("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", launcher.toString()).redirectErrorStream(true).start();
                if (!p.waitFor(10, java.util.concurrent.TimeUnit.MINUTES)) {
                    p.destroyForcibly();
                    throw new Api(504, "Timed out waiting for approval on the PC");
                }
            } finally {
                Files.deleteIfExists(launcher);
            }
        }
        if (!Files.exists(result)) {
            String detail = "";
            Path log = Main.DATA.resolve("startup-last.log");
            try {
                if (Files.exists(log)) {
                    List<String> ls = Files.readAllLines(log, StandardCharsets.UTF_8);
                    detail = " Last helper message: " + ls.stream().filter(x -> !x.isBlank()).reduce((x, y) -> y).orElse("(none)");
                }
            } catch (IOException ignored) { }
            throw new Api(500, "No result came back. The approval prompt may have been declined or closed." + detail + " (Details: " + log + ")");
        }
        Object r = Json.parse(Files.readString(result, StandardCharsets.UTF_8));
        Files.deleteIfExists(result);
        if (!Boolean.TRUE.equals(Json.obj(r).get("ok"))) throw new Api(500, String.valueOf(Json.obj(r).get("message")));
        j.log(String.valueOf(Json.obj(r).get("message")));
    }
}
