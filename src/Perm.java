import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.GZIPInputStream;

/**
 * Works out which Minecraft permission level (0-4) a console command needs, from the server's own command tree when it is cached,
 * and from a built-in table otherwise. Anything the manager cannot classify needs level 4 (fail closed).
 */
final class Perm {
    private Perm() { }

    private static final Map<String, Integer> FALLBACK = new HashMap<>();
    private static final Map<String, Map<String, Object>> TREES = new HashMap<>();

    static {
        for (String c : "help list me msg tell w teammsg tm trigger".split(" ")) FALLBACK.put(c, 0);
        for (String c : ("advancement attribute clear clone damage data datapack defaultgamemode difficulty effect enchant execute experience xp fill fillbiome forceload function "
                + "gamemode gamerule give item kill locate loot particle place playsound recipe reload return ride say schedule scoreboard seed setblock setworldspawn spawnpoint "
                + "spectate spreadplayers stopsound summon tag team teleport tp tellraw time title weather worldborder random").split(" ")) FALLBACK.put(c, 2);
        for (String c : "ban ban-ip banlist deop kick op pardon pardon-ip whitelist setidletimeout transfer".split(" ")) FALLBACK.put(c, 3);
        for (String c : "stop save-all save-on save-off debug perf jfr publish".split(" ")) FALLBACK.put(c, 4);
    }

    private static int levelName(String n) {
        return switch (n) {
            case "all", "none" -> 0;
            case "moderators" -> 1;
            case "gamemasters" -> 2;
            case "admins" -> 3;
            default -> 4;
        };
    }

    /** Highest level named anywhere inside a node's permissions block; 0 if it has none. */
    private static int permLevel(Object perms) {
        if (perms == null) return 0;
        if (perms instanceof Number n) return Math.max(0, Math.min(4, n.intValue()));
        if (perms instanceof Map<?, ?> m) {
            int best = 0;
            boolean found = false;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                Object v = e.getValue();
                if ("level".equals(e.getKey()) && v instanceof String s) { best = Math.max(best, levelName(s)); found = true; }
                else if ("level".equals(e.getKey()) && v instanceof Number n) { best = Math.max(best, n.intValue()); found = true; }
                else if (v instanceof Map || v instanceof List) { int x = permLevel(v); if (x > 0) best = Math.max(best, x); }
            }
            return found || best > 0 ? best : 0;
        }
        if (perms instanceof List<?> l) {
            int best = 0;
            for (Object o : l) best = Math.max(best, permLevel(o));
            return best;
        }
        return 4;
    }

    @SuppressWarnings("unchecked")
    private static synchronized Map<String, Object> tree(Srv s) {
        String ver = s.mcVersion();
        if (ver == null) return null;
        if (TREES.containsKey(ver)) return TREES.get(ver);
        Map<String, Object> t = null;
        Path gz = Main.DATA.resolve("cache").resolve(ver).resolve("ac.json.gz");
        if (Files.exists(gz)) {
            try (InputStream in = new GZIPInputStream(Files.newInputStream(gz))) {
                t = Json.obj(Json.obj(Json.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8))).get("tree"));
            } catch (Exception e) {
                t = null;
            }
        }
        if (t != null) TREES.put(ver, t);
        return t;
    }

    /** Splits like Brigadier does for our purposes: spaces separate words except inside quotes, [] {} and (). */
    static List<String> words(String cmd) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int depth = 0;
        char quote = 0;
        for (int i = 0; i < cmd.length(); i++) {
            char c = cmd.charAt(i);
            if (quote != 0) {
                cur.append(c);
                if (c == '\\' && i + 1 < cmd.length()) cur.append(cmd.charAt(++i));
                else if (c == quote) quote = 0;
                continue;
            }
            if (c == '"' || c == '\'') { quote = c; cur.append(c); continue; }
            if (c == '[' || c == '{' || c == '(') depth++;
            if ((c == ']' || c == '}' || c == ')') && depth > 0) depth--;
            if (Character.isWhitespace(c) && depth == 0) {
                if (cur.length() > 0) { out.add(cur.toString()); cur.setLength(0); }
            } else cur.append(c);
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    /** The permission level a console command needs (0-4). */
    static int required(Srv s, String command) {
        List<String> w = words(command.strip());
        if (w.isEmpty()) return 4;
        Map<String, Object> tree = tree(s);
        if (tree == null || !hasPerms(tree)) return fallback(w);
        return walkRoot(tree, w, 0);
    }

    private static boolean hasPerms(Map<String, Object> tree) {
        Map<String, Object> ch = Json.obj(tree.get("children"));
        for (Object c : ch.values()) if (Json.obj(c).containsKey("permissions")) return true;
        return false;
    }

    private static int fallback(List<String> w) {
        String first = w.get(0).replaceFirst("^minecraft:", "").toLowerCase();
        int lvl = FALLBACK.getOrDefault(first, 4);
        if (first.equals("execute") || first.equals("return")) {
            int run = w.indexOf("run");
            if (run >= 0 && run + 1 < w.size()) lvl = Math.max(lvl, fallback(w.subList(run + 1, w.size())));
            else if (run < 0 && first.equals("execute")) { /* no run: a pure condition check, level 2 */ }
        }
        return lvl;
    }

    private static int walkRoot(Map<String, Object> root, List<String> w, int start) {
        if (start >= w.size()) return 4;
        Map<String, Object> top = Json.obj(root.get("children"));
        String name = w.get(start).replaceFirst("^minecraft:", "");
        Map<String, Object> node = Json.obj(top.get(name));
        if (node.isEmpty() || !"literal".equals(Json.str(node, "type"))) return 4;
        return walk(root, node, w, start + 1, permLevel(node.get("permissions")), 0);
    }

    private static Map<String, Object> follow(Map<String, Object> root, Map<String, Object> node) {
        List<Object> red = Json.arr(node.get("redirect"));
        if (red.isEmpty()) return null;
        Map<String, Object> cur = root;
        for (Object p : red) cur = Json.obj(Json.obj(cur.get("children")).get(String.valueOf(p)));
        return cur;
    }

    private static int walk(Map<String, Object> root, Map<String, Object> node, List<String> w, int i, int level, int depth) {
        if (depth > 40) return 4;
        Map<String, Object> cur = node;
        while (i < w.size()) {
            // a redirect with an empty target (execute ... run) starts a brand-new command
            if (cur.get("redirect") instanceof List<?> r && r.isEmpty()) return Math.max(level, walkRoot(root, w, i));
            Map<String, Object> target = follow(root, cur);
            Map<String, Object> children = Json.obj((target != null ? target : cur).get("children"));
            if (target != null) level = Math.max(level, permLevel(target.get("permissions")));
            if (children.isEmpty()) return Math.max(level, 4);
            String tok = w.get(i);
            Map<String, Object> next = null;
            Object lit = children.get(tok);
            if (lit instanceof Map<?, ?> lm && "literal".equals(Json.str(lm, "type"))) next = Json.obj(lit);
            if (next == null) for (Object c : children.values()) if ("argument".equals(Json.str(c, "type"))) { next = Json.obj(c); break; }
            if (next == null) return Math.max(level, 4);
            level = Math.max(level, permLevel(next.get("permissions")));
            // "run" has no children and no redirect: Brigadier serialises its redirect to the root dispatcher by leaving it out
            if (tok.equals("run") && "literal".equals(Json.str(next, "type")) && !next.containsKey("children") && !next.containsKey("redirect")) return Math.max(level, walkRoot(root, w, i + 1));
            cur = next;
            i++;
            if ("argument".equals(Json.str(cur, "type")) && "greedy".equals(Json.str(Json.obj(cur.get("properties")), "type"))) return level;
        }
        return level;
    }

    static String describe(int level) {
        return switch (level) { case 0 -> "everyone"; case 1 -> "moderators (1)"; case 2 -> "gamemasters (2)"; case 3 -> "admins (3)"; default -> "owners (4)"; };
    }
}
