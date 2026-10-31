import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.*;

/** /api/users/* and /api/audit: the account dashboard for administrators (OP5) and server operators (level 4). */
final class UsersApi {
    private UsersApi() { }

    /** Servers where the account is level 4 (or all servers for an administrator). */
    static Set<String> operated(Users.Acct me) {
        Set<String> out = new LinkedHashSet<>();
        for (Srv s : Main.servers) if (me.admin || me.level(s.id) >= 4) out.add(s.id);
        return out;
    }

    private static Users.Acct target(Map<String, Object> b) {
        Users.Acct t = Users.byId(Json.str(b, "user"));
        if (t == null) throw new Api(404, "No such account");
        return t;
    }

    private static void needOperator(Users.Acct me) {
        if (operated(me).isEmpty()) throw new Api(403, "Only administrators and server operators (level 4) can manage accounts");
    }

    private static void admin(Users.Acct me) {
        if (!me.admin) throw new Api(403, "Only the manager administrator (OP5) can do this");
    }

    private static String serverName(String id) {
        for (Srv s : Main.servers) if (s.id.equals(id)) return s.name;
        return id;
    }

    static void handle(HttpExchange ex, String method, String path, Users.Acct me) throws IOException {
        boolean get = method.equals("GET");
        String ip = ex.getRemoteAddress().getAddress().getHostAddress();
        switch (path) {
            case "/api/users" -> {
                if (!get) throw new Api(405, "GET only");
                needOperator(me);
                Set<String> mine = operated(me);
                List<Object> list = new ArrayList<>();
                for (Users.Acct a : Users.all()) {
                    if (me.admin) { list.add(a.toPublic(null)); continue; }
                    if (a.admin) continue;
                    boolean relevant = false;
                    for (String s : a.grants.keySet()) if (mine.contains(s)) relevant = true;
                    if (relevant || a.id.equals(me.id)) list.add(a.toPublic(mine));
                }
                List<Object> srv = new ArrayList<>();
                for (Srv s : Main.servers) if (mine.contains(s.id)) srv.add(Json.map("id", s.id, "name", s.name));
                sendOk(ex, Json.map("users", list, "servers", srv, "adminCount", Users.adminCount()));
            }
            case "/api/users/invite" -> {
                post(method);
                needOperator(me);
                Map<String, Object> b = Main.body(ex);
                boolean adminInvite = Boolean.TRUE.equals(b.get("admin"));
                int level = Json.num(b, "level", 0);
                long hours = Math.max(1, Math.min(720, Json.num(b, "hours", 24)));
                List<String> servers = new ArrayList<>();
                for (Object o : Json.arr(b.get("servers"))) servers.add(String.valueOf(o));
                String note = Json.str(b, "note"), mc = Json.str(b, "mcName");
                if (note != null && note.length() > 120) note = note.substring(0, 120);
                if (mc != null && !mc.isEmpty() && !Srv.validPlayerName(mc)) throw new Api(400, "That is not a valid Minecraft username");
                if (adminInvite) {
                    admin(me);
                    servers.clear();
                } else {
                    if (level < 0 || level > 4) throw new Api(400, "Level must be 0 to 4");
                    if (servers.isEmpty()) throw new Api(400, "Pick at least one server for this invite");
                    Set<String> mine = operated(me);
                    for (String s : servers) {
                        if (Main.servers.stream().noneMatch(x -> x.id.equals(s))) throw new Api(404, "Unknown server");
                        if (!mine.contains(s)) throw new Api(403, "You can only invite people to servers where you are level 4");
                    }
                    if (level == 4 && !me.admin) throw new Api(403, "Only the manager administrator (OP5) can create level 4 operators");
                }
                String token = Users.mintInvite(me, adminInvite, level, servers, hours, note, mc);
                Users.audit(me.username, ip, "invite created", servers.isEmpty() ? "" : servers.get(0), (adminInvite ? "administrator" : "level " + level + " on " + servers) + (note == null || note.isEmpty() ? "" : "; " + note));
                sendOk(ex, Json.map("token", token, "expires", System.currentTimeMillis() + hours * 3600_000L));
            }
            case "/api/users/invites" -> {
                if (!get) throw new Api(405, "GET only");
                needOperator(me);
                List<Object> out = new ArrayList<>();
                long now = System.currentTimeMillis();
                for (Map<String, Object> m : Users.invites()) {
                    if (!me.admin && !me.id.equals(Json.str(m, "createdBy"))) continue;
                    long exp = m.get("expires") instanceof Number n ? n.longValue() : 0;
                    List<Object> names = new ArrayList<>();
                    for (Object s : Json.arr(m.get("servers"))) names.add(serverName(String.valueOf(s)));
                    out.add(Json.map("id", m.get("id"), "admin", m.get("admin"), "level", m.get("level"), "servers", names, "createdByName", m.get("createdByName"), "created", m.get("created"),
                            "expires", exp, "note", m.get("note"), "usedBy", m.get("usedBy"), "usedAt", m.get("usedAt"), "status", m.get("usedBy") != null ? "used" : exp < now ? "expired" : "active"));
                }
                sendOk(ex, Json.map("invites", out));
            }
            case "/api/users/invite/revoke" -> {
                post(method);
                needOperator(me);
                String id = Json.str(Main.body(ex), "id");
                boolean allowed = me.admin;
                if (!allowed) for (Map<String, Object> m : Users.invites()) if (Objects.equals(id, Json.str(m, "id")) && me.id.equals(Json.str(m, "createdBy"))) allowed = true;
                if (!allowed) throw new Api(403, "You can only cancel invites you created");
                Users.revokeInvite(id);
                Users.audit(me.username, ip, "invite cancelled", "", String.valueOf(id));
                sendOk(ex, Json.map("ok", true));
            }
            case "/api/users/grant" -> {
                post(method);
                needOperator(me);
                Map<String, Object> b = Main.body(ex);
                Users.Acct t = target(b);
                String sid = Json.str(b, "server");
                int level = Json.num(b, "level", -1);
                if (Main.servers.stream().noneMatch(x -> x.id.equals(sid))) throw new Api(404, "Unknown server");
                if (level < -1 || level > 4) throw new Api(400, "Level must be 0 to 4, or -1 to remove access");
                if (t.admin) throw new Api(400, "Administrators already have full access to every server");
                if (!me.admin) {
                    if (me.level(sid) < 4) throw new Api(403, "You can only change access on servers where you are level 4");
                    if (level > 3) throw new Api(403, "Only the manager administrator (OP5) can make someone level 4");
                    if (t.id.equals(me.id)) throw new Api(403, "You cannot change your own access");
                    if (t.level(sid) >= 4) throw new Api(403, "Only the manager administrator (OP5) can change a level 4 operator");
                }
                Users.grant(t, sid, level);
                Users.audit(me.username, ip, level < 0 ? "access removed" : "access set to level " + level, sid, "for " + t.username);
                sendOk(ex, Json.map("ok", true));
            }
            case "/api/users/admin" -> {
                post(method);
                admin(me);
                Map<String, Object> b = Main.body(ex);
                Users.Acct t = target(b);
                boolean make = Boolean.TRUE.equals(b.get("admin"));
                if (make == t.admin) { sendOk(ex, Json.map("ok", true)); return; }
                if (!make) {
                    if (!t.id.equals(me.id)) throw new Api(403, "An administrator can only be removed by themselves, or by the person who owns this PC using the owner tools (Owner.bat)");
                    if (Users.adminCount() < 2) throw new Api(409, "You are the only administrator. Make someone else an administrator first.");
                }
                Users.setAdmin(t, make);
                Users.audit(me.username, ip, make ? "administrator granted" : "administrator removed", "", t.username);
                sendOk(ex, Json.map("ok", true));
            }
            case "/api/users/delete" -> {
                post(method);
                admin(me);
                Users.Acct t = target(Main.body(ex));
                if (t.admin) {
                    if (!t.id.equals(me.id)) throw new Api(403, "An administrator can only be deleted by themselves, or by the person who owns this PC using the owner tools (Owner.bat)");
                    if (Users.adminCount() < 2) throw new Api(409, "You are the only administrator, so your account cannot be deleted");
                }
                Users.delete(t);
                Users.audit(me.username, ip, "account deleted", "", t.username);
                sendOk(ex, Json.map("ok", true));
            }
            case "/api/users/disable" -> {
                post(method);
                admin(me);
                Map<String, Object> b = Main.body(ex);
                Users.Acct t = target(b);
                if (t.admin) throw new Api(403, "Administrator accounts can only be disabled with the owner tools (Owner.bat)");
                boolean dis = Boolean.TRUE.equals(b.get("disabled"));
                Users.setDisabled(t, dis);
                Users.audit(me.username, ip, dis ? "account disabled" : "account enabled", "", t.username);
                sendOk(ex, Json.map("ok", true));
            }
            case "/api/users/reset2fa" -> {
                post(method);
                admin(me);
                Users.Acct t = target(Main.body(ex));
                if (t.admin) throw new Api(403, "An administrator's two-step sign-in can only be reset with the owner tools (Owner.bat disable-2fa)");
                Users.disable2fa(t);
                Users.audit(me.username, ip, "two-step sign-in reset", "", t.username);
                sendOk(ex, Json.map("ok", true));
            }
            case "/api/users/signout" -> {
                post(method);
                admin(me);
                Users.Acct t = target(Main.body(ex));
                Users.setAdmin(t, t.admin); // bumps the session epoch, which signs the account out everywhere
                Users.audit(me.username, ip, "signed out everywhere", "", t.username);
                sendOk(ex, Json.map("ok", true));
            }
            case "/api/users/mcname" -> {
                post(method);
                Map<String, Object> b = Main.body(ex);
                Users.Acct t = target(b);
                if (!me.admin && !t.id.equals(me.id)) throw new Api(403, "You can only change your own Minecraft name");
                String mc = Json.str(b, "mcName");
                if (mc != null && !mc.isEmpty() && !Srv.validPlayerName(mc)) throw new Api(400, "That is not a valid Minecraft username");
                Users.setMcName(t, mc);
                sendOk(ex, Json.map("ok", true));
            }
            case "/api/audit" -> {
                if (!get) throw new Api(405, "GET only");
                needOperator(me);
                Map<String, String> q = Main.query(ex);
                int limit = 200;
                try { limit = Math.max(1, Math.min(1000, Integer.parseInt(q.getOrDefault("limit", "200")))); } catch (NumberFormatException ignored) { }
                Set<String> filter = me.admin ? null : operated(me);
                String sv = q.get("server");
                if (sv != null && !sv.isEmpty() && filter != null && !filter.contains(sv)) throw new Api(403, "You can only see the history of servers where you are level 4");
                sendOk(ex, Users.auditRead(filter, q.get("user"), sv, limit));
            }
            default -> throw new Api(404, "Unknown endpoint");
        }
    }

    private static void post(String method) {
        if (!method.equals("POST")) throw new Api(405, "POST only");
    }

    private static void sendOk(HttpExchange ex, Object o) throws IOException {
        Main.sendJson(ex, 200, o);
    }
}
