import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads and edits the JVM options (memory, garbage collector, tuning toggles) in a server's start script or user_jvm_args.txt. */
final class Launch {
    private Launch() { }

    private static final Pattern JAVA_LINE = Pattern.compile("(?i)^(.*?(?:\"%MCM_JAVA%\"|%MCM_JAVA%|\\bjavaw?(?:\\.exe)?\"?))(\\s+)(.*?)(\\s+-jar\\b|\\s+@)(.*)$");
    private static final Pattern GC = Pattern.compile("-XX:\\+Use(G1|Z|Parallel|Serial|Shenandoah)GC");
    private static final Pattern MEM = Pattern.compile("-Xm([sx])(\\d+)([kKmMgG]?)");
    private static final Pattern SAFE_EXTRA = Pattern.compile("[A-Za-z0-9_.:+\\-=/,@\\\\ ]*");
    private static final Map<String, String> GC_FLAG = Map.of("g1", "-XX:+UseG1GC", "zgc", "-XX:+UseZGC", "parallel", "-XX:+UseParallelGC", "serial", "-XX:+UseSerialGC", "shenandoah", "-XX:+UseShenandoahGC");

    /** Tuning toggles the manager owns. A token belongs to a toggle when it starts with one of the prefixes. */
    private static final String[] TOGGLES = {"aikar", "reserve", "compact", "oom", "heapdump", "gclog", "utf8"};
    private static final Map<String, List<String>> PREFIX = Map.of(
            "aikar", List.of("-XX:+ParallelRefProcEnabled", "-XX:MaxGCPauseMillis=", "-XX:+DisableExplicitGC", "-XX:G1NewSizePercent=", "-XX:G1MaxNewSizePercent=",
                    "-XX:G1HeapRegionSize=", "-XX:G1ReservePercent=", "-XX:G1HeapWastePercent=", "-XX:G1MixedGCCountTarget=", "-XX:InitiatingHeapOccupancyPercent=",
                    "-XX:G1MixedGCLiveThresholdPercent=", "-XX:G1RSetUpdatingPauseTimePercent=", "-XX:SurvivorRatio=", "-XX:+PerfDisableSharedMem",
                    "-XX:MaxTenuringThreshold=", "-Dusing.aikars.flags=", "-Daikars.new.flags="),
            "reserve", List.of("-XX:+AlwaysPreTouch"),
            "compact", List.of("-XX:+UseCompactObjectHeaders"),
            "oom", List.of("-XX:+ExitOnOutOfMemoryError"),
            "heapdump", List.of("-XX:+HeapDumpOnOutOfMemoryError", "-XX:HeapDumpPath="),
            "gclog", List.of("-Xlog:gc"),
            "utf8", List.of("-Dfile.encoding="));
    private static final String UNLOCK = "-XX:+UnlockExperimentalVMOptions";

    private record Seg(int lineNo, String head, String sep, List<String> tokens, String tail) { }

    private static final class Parsed {
        Path file;
        String source; // "script" or "user_jvm_args.txt"
        List<String> lines = new ArrayList<>();
        String eol = "\r\n";
        List<Seg> segs = new ArrayList<>();
        String text = "";
    }

    static long systemMb() {
        try {
            return ((com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean()).getTotalMemorySize() / (1024 * 1024);
        } catch (Throwable t) {
            return 0;
        }
    }

    static List<String> tokenize(String s) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("\"[^\"]*\"|\\S+").matcher(s);
        while (m.find()) out.add(m.group());
        return out;
    }

    static long toMb(String num, String unit) {
        long n = Long.parseLong(num);
        return switch (unit.toLowerCase()) {
            case "g" -> n * 1024;
            case "m" -> n;
            case "k" -> n / 1024;
            default -> n / (1024 * 1024);
        };
    }

    static String fmtMb(long mb) { return mb % 1024 == 0 ? (mb / 1024) + "G" : mb + "M"; }

    private static Parsed parse(Srv s) throws IOException {
        Path script = s.dir.resolve(s.script());
        if (!Files.isRegularFile(script)) return null;
        Parsed p = new Parsed();
        String text = new String(Files.readAllBytes(script), StandardCharsets.ISO_8859_1);
        p.text = text;
        p.eol = text.contains("\r\n") ? "\r\n" : "\n";
        boolean endsWithEol = text.endsWith("\n");
        for (String l : text.split("\r?\n", -1)) p.lines.add(l);
        if (endsWithEol && !p.lines.isEmpty()) p.lines.remove(p.lines.size() - 1);
        p.file = script;
        p.source = "script";
        for (int i = 0; i < p.lines.size(); i++) {
            String l = p.lines.get(i), t = l.stripLeading().toLowerCase();
            if (t.startsWith("rem ") || t.startsWith("::") || t.equals("rem")) continue;
            Matcher m = JAVA_LINE.matcher(l);
            if (!m.matches()) continue;
            p.segs.add(new Seg(i, m.group(1), m.group(2), tokenize(m.group(3)), m.group(4) + m.group(5)));
        }
        Path ja = s.dir.resolve("user_jvm_args.txt");
        if (Files.isRegularFile(ja) && text.contains("user_jvm_args.txt")) {
            Parsed q = new Parsed();
            q.file = ja;
            q.source = "user_jvm_args.txt";
            q.text = text;
            String t2 = new String(Files.readAllBytes(ja), StandardCharsets.ISO_8859_1);
            q.eol = t2.contains("\r\n") ? "\r\n" : "\n";
            List<String> toks = new ArrayList<>();
            for (String l : t2.split("\r?\n", -1)) {
                q.lines.add(l);
                if (!l.isBlank() && !l.stripLeading().startsWith("#")) toks.addAll(tokenize(l));
            }
            if (q.lines.size() > 1 && q.lines.get(q.lines.size() - 1).isEmpty()) q.lines.remove(q.lines.size() - 1);
            q.segs.add(new Seg(-1, "", "", toks, ""));
            return q;
        }
        return p;
    }

    private static boolean isMem(String t) { return MEM.matcher(t).matches(); }

    private static boolean isGc(String t) { return GC.matcher(t).matches(); }

    private static String gcName(String t) {
        Matcher m = GC.matcher(t);
        if (!m.matches()) return null;
        return switch (m.group(1)) { case "G1" -> "g1"; case "Z" -> "zgc"; case "Parallel" -> "parallel"; case "Serial" -> "serial"; default -> "shenandoah"; };
    }

    private static String toggleOf(String t, boolean aikarLine) {
        if (aikarLine && t.equals(UNLOCK)) return "aikar";
        for (String id : TOGGLES) for (String pre : PREFIX.get(id)) if (t.startsWith(pre)) return id;
        return null;
    }

    private static boolean hasAikar(List<String> tokens) {
        for (String t : tokens) if (t.startsWith("-Daikars.new.flags=")) return true;
        return false;
    }

    /** Tokens other than memory, GC flags and managed tuning flags that appear in every java line. */
    private static List<String> commonExtras(Parsed p) {
        List<String> common = null;
        for (Seg g : p.segs) {
            boolean ak = hasAikar(g.tokens());
            List<String> others = new ArrayList<>();
            for (String t : g.tokens()) if (!isMem(t) && !isGc(t) && toggleOf(t, ak) == null && !t.startsWith("-Djava.awt.headless")) others.add(t);
            if (common == null) common = others;
            else common.retainAll(others);
        }
        return common == null ? new ArrayList<>() : common;
    }

    /** Major version of the Java this server will run with (0 if unknown). */
    static int javaMajor(Srv s, String scriptText) {
        try {
            if (scriptText.contains("MCM_JAVA")) {
                String path = JavaRuntimes.javaFor(s);
                for (Map<String, Object> r : JavaRuntimes.detect(false)) if (path != null && path.equalsIgnoreCase(String.valueOf(r.get("path")))) return (Integer) r.get("major");
            }
            String out = Sys.run(8000, "java", "-version").out();
            Matcher m = Pattern.compile("version \"(\\d+)(?:\\.(\\d+))?").matcher(out);
            if (m.find()) {
                int a = Integer.parseInt(m.group(1));
                return a == 1 && m.group(2) != null ? Integer.parseInt(m.group(2)) : a;
            }
        } catch (RuntimeException ignored) { }
        return 0;
    }

    /** A rough memory suggestion from the mod count, player slots and loader. */
    private static long suggestMb(Srv s, long sysMb) {
        int mods = 0;
        try { mods = s.mods().size(); } catch (RuntimeException ignored) { }
        int slots = 20;
        try { slots = Integer.parseInt(s.props().getOrDefault("max-players", "20").trim()); } catch (NumberFormatException ignored) { }
        double gb = (mods > 0 || !"vanilla".equals(s.kind()) ? 3.0 : 2.0) + mods / 15.0 + Math.max(0, Math.min(slots, 100) - 10) / 20.0;
        gb = Math.ceil(gb * 2) / 2.0;
        gb = Math.min(gb, 16);
        long mb = (long) (gb * 1024);
        if (sysMb > 0) mb = Math.min(mb, sysMb / 2);
        return Math.max(1024, mb / 512 * 512);
    }

    static Map<String, Object> read(Srv s) throws IOException {
        Map<String, Object> out = new LinkedHashMap<>();
        long sys = systemMb();
        long others = 0;
        for (Srv o : Main.servers) {
            if (o == s) continue;
            try {
                Parsed q = parse(o);
                if (q != null && !q.segs.isEmpty()) for (String t : q.segs.get(0).tokens()) {
                    Matcher m = MEM.matcher(t);
                    if (m.matches() && m.group(1).equals("x")) others += toMb(m.group(2), m.group(3));
                }
            } catch (IOException | RuntimeException ignored) { }
        }
        out.put("systemMb", sys);
        out.put("othersMaxMb", others);
        out.put("priority", s.priority());
        Parsed p = parse(s);
        if (p == null) { out.put("editable", false); out.put("reason", "The start script " + s.script() + " was not found in the server folder."); return out; }
        if (p.segs.isEmpty()) {
            out.put("editable", false);
            out.put("script", s.script());
            out.put("reason", "The manager could not find a java command in " + s.script() + ". Edit the file by hand to change memory settings.");
            return out;
        }
        long min = 0, max = 0;
        String gc = "none";
        boolean mixed = false;
        for (int i = 0; i < p.segs.size(); i++) {
            long mn = 0, mx = 0;
            String g = "none";
            for (String t : p.segs.get(i).tokens()) {
                Matcher m = MEM.matcher(t);
                if (m.matches()) {
                    long v = toMb(m.group(2), m.group(3));
                    if (m.group(1).equals("s")) mn = v; else mx = v;
                } else if (isGc(t)) g = gcName(t);
                else if (GC.matcher(t).find()) g = "other";
            }
            if (i == 0) { min = mn; max = mx; gc = g; }
            else if (mn != min || mx != max || !g.equals(gc)) mixed = true;
        }
        Map<String, Object> toggles = new LinkedHashMap<>();
        for (String id : TOGGLES) toggles.put(id, false);
        for (Seg g : p.segs) {
            boolean ak = hasAikar(g.tokens());
            for (String t : g.tokens()) { String id = toggleOf(t, ak); if (id != null) toggles.put(id, true); }
        }
        out.put("editable", true);
        out.put("source", p.source);
        out.put("script", p.file.getFileName().toString());
        out.put("minMb", min);
        out.put("maxMb", max);
        out.put("gc", gc);
        out.put("extra", String.join(" ", commonExtras(p)));
        out.put("toggles", toggles);
        out.put("mixed", mixed);
        out.put("lines", p.segs.size());
        out.put("javaMajor", javaMajor(s, p.text));
        long sug = suggestMb(s, sys);
        out.put("suggestMb", sug);
        out.put("modCount", s.mods().size());
        List<Object> preview = new ArrayList<>();
        for (Seg g : p.segs) preview.add(p.source.equals("script") ? p.lines.get(g.lineNo()).strip() : String.join(" ", g.tokens()));
        out.put("preview", preview);
        return out;
    }

    private static List<String> aikarTokens(long maxMb) {
        boolean big = maxMb > 12 * 1024;
        List<String> t = new ArrayList<>(List.of("-XX:+ParallelRefProcEnabled", "-XX:MaxGCPauseMillis=200", UNLOCK, "-XX:+DisableExplicitGC", "-XX:+AlwaysPreTouch",
                "-XX:G1NewSizePercent=" + (big ? 40 : 30), "-XX:G1MaxNewSizePercent=" + (big ? 50 : 40), "-XX:G1HeapRegionSize=" + (big ? "16M" : "8M"),
                "-XX:G1ReservePercent=" + (big ? 15 : 20), "-XX:G1HeapWastePercent=5", "-XX:G1MixedGCCountTarget=4", "-XX:InitiatingHeapOccupancyPercent=" + (big ? 20 : 15),
                "-XX:G1MixedGCLiveThresholdPercent=90", "-XX:G1RSetUpdatingPauseTimePercent=5", "-XX:SurvivorRatio=32", "-XX:+PerfDisableSharedMem", "-XX:MaxTenuringThreshold=1",
                "-Dusing.aikars.flags=https://mcflags.emc.gs", "-Daikars.new.flags=true"));
        return t;
    }

    static void write(Srv s, long minMb, long maxMb, String gc, String extraText, Map<String, Object> toggles) throws IOException {
        write(s, minMb, maxMb, gc, extraText, toggles, false);
    }

    /** inJob: the caller is itself the running job on this server (a conversion) and has already checked that the server is stopped. */
    static void write(Srv s, long minMb, long maxMb, String gc, String extraText, Map<String, Object> toggles, boolean inJob) throws IOException {
        if (maxMb < 256 || maxMb > 4_194_304) throw new Api(400, "Maximum memory must be at least 256 MB.");
        if (minMb < 0 || minMb > maxMb) throw new Api(400, "Minimum memory cannot be larger than the maximum.");
        if (gc != null && !gc.isEmpty() && !List.of("keep", "none").contains(gc) && !GC_FLAG.containsKey(gc)) throw new Api(400, "Unknown garbage collector");
        String extra = extraText == null ? "" : extraText.strip();
        if (!SAFE_EXTRA.matcher(extra).matches()) throw new Api(400, "Extra JVM flags may only contain letters, numbers and . : + - = / , @ \\ (no quotes or % & | < > ^).");
        List<String> newExtra = tokenize(extra);
        for (String t : newExtra) {
            if (!t.startsWith("-")) throw new Api(400, "Each extra flag must start with a dash, e.g. -XX:+UseStringDeduplication (got '" + t + "').");
            if (t.equals("-jar") || t.equals("-cp") || t.equals("-classpath") || isMem(t)) throw new Api(400, "Use the memory fields for " + t + "; -jar and -cp cannot be changed here.");
            if (toggleOf(t, true) != null) throw new Api(400, "'" + t + "' is controlled by a toggle above; use the toggle instead.");
        }
        if (s.running(true) || (!inJob && s.job != null && s.job.active())) throw new Api(409, "Stop the server first. Editing a start script while it is running can break it.");
        Parsed p = parse(s);
        if (p == null || p.segs.isEmpty()) throw new Api(409, "No java command was found in the start script, so it cannot be edited here.");

        Map<String, Boolean> on = new LinkedHashMap<>();
        for (String id : TOGGLES) on.put(id, toggles != null && Boolean.TRUE.equals(toggles.get(id)));
        int major = javaMajor(s, p.text);
        if (on.get("compact") && major < 25) throw new Api(400, "Compact object headers need Java 25 or newer" + (major > 0 ? " (this server uses Java " + major + ")" : "") + ".");
        if (on.get("aikar")) {
            String cur = null;
            for (String t : p.segs.get(0).tokens()) if (isGc(t)) cur = gcName(t);
            if (gc == null || gc.isEmpty() || gc.equals("keep")) gc = cur == null || cur.equals("g1") ? "g1" : cur;
            if (!gc.equals("g1")) throw new Api(400, "Aikar's flags are tuned for the G1 collector. Choose G1 or turn Aikar's flags off.");
            on.put("reserve", true);
        }
        if (on.get("reserve") && maxMb > 0) minMb = maxMb;

        List<String> oldCommon = commonExtras(p);

        Path bak = p.file.resolveSibling(p.file.getFileName() + ".mcm-bak");
        if (!Files.exists(bak)) Files.copy(p.file, bak);
        if (on.get("heapdump")) Files.createDirectories(s.dir.resolve("heap-dumps"));
        if (on.get("gclog")) Files.createDirectories(s.dir.resolve("logs"));

        List<String> managed = new ArrayList<>();
        if (on.get("aikar")) managed.addAll(aikarTokens(maxMb));
        if (on.get("reserve") && !managed.contains("-XX:+AlwaysPreTouch")) managed.add("-XX:+AlwaysPreTouch");
        if (on.get("compact")) managed.add("-XX:+UseCompactObjectHeaders");
        if (on.get("oom")) managed.add("-XX:+ExitOnOutOfMemoryError");
        if (on.get("heapdump")) { managed.add("-XX:+HeapDumpOnOutOfMemoryError"); managed.add("-XX:HeapDumpPath=heap-dumps"); }
        if (on.get("gclog")) managed.add("-Xlog:gc*:file=logs/gc.log:time,uptime:filecount=5,filesize=10m");
        if (on.get("utf8")) managed.add("-Dfile.encoding=UTF-8");

        List<List<String>> rebuilt = new ArrayList<>();
        for (Seg g : p.segs) {
            boolean ak = hasAikar(g.tokens());
            List<String> keepGc = new ArrayList<>(), rest = new ArrayList<>();
            for (String t : g.tokens()) {
                if (isMem(t) || toggleOf(t, ak) != null) continue;
                if (isGc(t)) { keepGc.add(t); continue; }
                rest.add(t);
            }
            for (String o : oldCommon) rest.remove(o);
            for (String n : newExtra) if (!rest.contains(n)) rest.add(n);
            List<String> toks = new ArrayList<>();
            if (minMb > 0) toks.add("-Xms" + fmtMb(minMb));
            toks.add("-Xmx" + fmtMb(maxMb));
            if (gc == null || gc.isEmpty() || gc.equals("keep")) toks.addAll(keepGc);
            else if (!gc.equals("none")) toks.add(GC_FLAG.get(gc));
            toks.addAll(managed);
            toks.addAll(rest);
            rebuilt.add(toks);
        }

        List<String> out = new ArrayList<>();
        if (p.source.equals("script")) {
            out.addAll(p.lines);
            for (int i = 0; i < p.segs.size(); i++) {
                Seg g = p.segs.get(i);
                out.set(g.lineNo(), g.head() + g.sep() + String.join(" ", rebuilt.get(i)) + g.tail());
            }
        } else {
            for (String l : p.lines) if (l.stripLeading().startsWith("#")) out.add(l);
            out.addAll(rebuilt.get(0));
        }
        String text = String.join(p.eol, out) + p.eol;
        Files.write(p.file, text.getBytes(StandardCharsets.ISO_8859_1));
    }
}
