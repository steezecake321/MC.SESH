import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Accounts, invite tokens, sessions and the audit log. Everything lives in plain files under data\ (one file per account and per invite),
 * so the machine owner can inspect, copy or delete them at any time; running managers pick the changes up within a couple of seconds.
 *
 * Roles: an account is either a manager administrator (OP5: owns the manager itself) or holds a Minecraft-style level 0-4 per server
 * (0 = view only, 1-3 = may run commands up to that permission level, 4 = server operator).
 */
final class Users {
    private Users() { }

    static final String COOKIE = "mcm";
    static final long SESSION_MS = 7L * 24 * 3600 * 1000;
    static final int ITER = 150_000;
    static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.-]{3,32}");
    private static final SecureRandom RNG = new SecureRandom();
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    static final class Acct {
        String id, username, mcName = "";
        byte[] salt, hash;
        int iter = ITER;
        boolean admin, disabled;
        String totp = "", totpPending = "";
        long totpLast;
        final List<String> recovery = new ArrayList<>();
        int epoch;
        long created;
        String createdBy = "";
        final Map<String, Integer> grants = new LinkedHashMap<>();

        /** Effective level on a server: 5 for an administrator, 0-4 when granted, -1 for no access. */
        int level(String serverId) { return admin ? 5 : grants.getOrDefault(serverId, -1); }

        boolean opAnywhere() { return admin || grants.containsValue(4); }

        Map<String, Object> toFile() {
            return Json.map("id", id, "username", username, "mcName", mcName, "salt", B64.encodeToString(salt), "hash", B64.encodeToString(hash), "iter", iter,
                    "admin", admin, "disabled", disabled, "epoch", epoch, "created", created, "createdBy", createdBy, "grants", new LinkedHashMap<>(grants),
                    "totp", totp, "totpPending", totpPending, "totpLast", totpLast, "recovery", new ArrayList<>(recovery));
        }

        Map<String, Object> toPublic(Collection<String> onlyServers) {
            Map<String, Object> g = new LinkedHashMap<>();
            for (Map.Entry<String, Integer> e : grants.entrySet()) if (onlyServers == null || onlyServers.contains(e.getKey())) g.put(e.getKey(), e.getValue());
            return Json.map("id", id, "username", username, "mcName", mcName, "admin", admin, "disabled", disabled, "created", created, "createdBy", createdBy, "grants", g, "twoStep", !totp.isEmpty());
        }
    }

    // ---------- storage ----------

    private static Path dir(String sub) { return Main.DATA.resolve(sub); }

    private static final Map<String, Acct> accounts = new LinkedHashMap<>();
    private static String signature = "";
    private static long checkedAt;
    private static byte[] key;

    private static synchronized void ensureFresh() {
        long now = System.currentTimeMillis();
        if (now - checkedAt < 1500 && !signature.isEmpty()) return;
        checkedAt = now;
        try {
            Files.createDirectories(dir("accounts"));
            StringBuilder sig = new StringBuilder();
            List<Path> files = new ArrayList<>();
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir("accounts"), "*.json")) {
                for (Path p : ds) files.add(p);
            }
            Collections.sort(files);
            for (Path p : files) sig.append(p.getFileName()).append(Files.getLastModifiedTime(p).toMillis()).append(Files.size(p)).append(';');
            if (sig.toString().equals(signature)) return;
            accounts.clear();
            for (Path p : files) {
                try {
                    Map<String, Object> m = Json.obj(Json.parse(Files.readString(p, StandardCharsets.UTF_8)));
                    Acct a = new Acct();
                    a.id = Json.str(m, "id");
                    a.username = Json.str(m, "username");
                    a.mcName = m.get("mcName") == null ? "" : String.valueOf(m.get("mcName"));
                    a.salt = Base64.getUrlDecoder().decode(Json.str(m, "salt"));
                    a.hash = Base64.getUrlDecoder().decode(Json.str(m, "hash"));
                    a.iter = Json.num(m, "iter", ITER);
                    a.admin = Boolean.TRUE.equals(m.get("admin"));
                    a.disabled = Boolean.TRUE.equals(m.get("disabled"));
                    a.epoch = Json.num(m, "epoch", 0);
                    a.totp = m.get("totp") == null ? "" : String.valueOf(m.get("totp"));
                    a.totpPending = m.get("totpPending") == null ? "" : String.valueOf(m.get("totpPending"));
                    a.totpLast = m.get("totpLast") instanceof Number tl ? tl.longValue() : 0;
                    for (Object r : Json.arr(m.get("recovery"))) a.recovery.add(String.valueOf(r));
                    Object c = m.get("created");
                    a.created = c instanceof Number n ? n.longValue() : 0;
                    a.createdBy = m.get("createdBy") == null ? "" : String.valueOf(m.get("createdBy"));
                    for (Map.Entry<String, Object> e : Json.obj(m.get("grants")).entrySet()) if (e.getValue() instanceof Number n) a.grants.put(e.getKey(), n.intValue());
                    if (a.id != null && a.username != null) accounts.put(a.id, a);
                } catch (IOException | RuntimeException e) {
                    Main.log("skipping unreadable account file " + p.getFileName() + ": " + e);
                }
            }
            signature = sig.toString();
        } catch (IOException e) {
            Main.log("accounts: " + e);
        }
    }

    private static synchronized void save(Acct a) throws IOException {
        Files.createDirectories(dir("accounts"));
        Path p = dir("accounts").resolve(a.id + ".json");
        Path tmp = dir("accounts").resolve(a.id + ".tmp");
        Files.writeString(tmp, Json.stringify(a.toFile()), StandardCharsets.UTF_8);
        Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
        signature = "";
        checkedAt = 0;
        ensureFresh();
        OpsSync.accountsChanged();
    }

    static synchronized List<Acct> all() {
        ensureFresh();
        return new ArrayList<>(accounts.values());
    }

    static synchronized Acct byId(String id) {
        ensureFresh();
        return id == null ? null : accounts.get(id);
    }

    static synchronized Acct byName(String name) {
        ensureFresh();
        if (name == null) return null;
        for (Acct a : accounts.values()) if (a.username.equalsIgnoreCase(name.trim())) return a;
        return null;
    }

    static synchronized boolean any() { ensureFresh(); return !accounts.isEmpty(); }

    /** The upgrade hint stops showing once the migrated account has signed in. */
    static synchronized void markSeen(Acct a) throws IOException {
        if (a.createdBy != null && a.createdBy.startsWith("migrated") && !a.createdBy.contains("(signed in)")) {
            a.createdBy = a.createdBy + " (signed in)";
            save(a);
        }
    }

    /** Shown on the sign-in page right after an upgrade from the single-password version. */
    static synchronized String migratedHint() {
        ensureFresh();
        if (accounts.size() != 1) return null;
        Acct a = accounts.values().iterator().next();
        return a.createdBy != null && a.createdBy.startsWith("migrated") && !a.createdBy.contains("(signed in)")
                ? "Upgraded from an earlier version: sign in as \"" + a.username + "\" with your existing password. After that you can invite other people." : null;
    }

    static synchronized int adminCount() {
        ensureFresh();
        int n = 0;
        for (Acct a : accounts.values()) if (a.admin && !a.disabled) n++;
        return n;
    }

    /** One-time upgrade from the old single-password setup: the existing password becomes the first administrator, named "admin". */
    static synchronized void migrateLegacy(Config cfg) throws IOException {
        ensureFresh();
        Map<String, Object> old = cfg.auth();
        if (!accounts.isEmpty() || old.get("hash") == null) return;
        Acct a = new Acct();
        a.id = newId();
        a.username = "admin";
        a.salt = Base64.getUrlDecoder().decode(String.valueOf(old.get("salt")));
        a.hash = Base64.getUrlDecoder().decode(String.valueOf(old.get("hash")));
        a.iter = Json.num(old, "iter", ITER);
        a.admin = true;
        a.created = System.currentTimeMillis();
        a.createdBy = "migrated from the single password";
        save(a);
        old.clear();
        cfg.save();
        Main.log("migrated the existing password to administrator account 'admin'");
    }

    static String newId() {
        byte[] b = new byte[9];
        RNG.nextBytes(b);
        return B64.encodeToString(b).replace('-', 'a').replace('_', 'b');
    }

    // ---------- passwords ----------

    static byte[] pbkdf2(String pw, byte[] salt, int iter) {
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(new PBEKeySpec(pw.toCharArray(), salt, iter, 256)).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void setPassword(Acct a, String pw) {
        a.salt = new byte[16];
        RNG.nextBytes(a.salt);
        a.iter = ITER;
        a.hash = pbkdf2(pw, a.salt, ITER);
    }

    static void checkPassword(String pw) {
        if (pw == null || pw.length() < 8) throw new Api(400, "Password must be at least 8 characters");
        if (pw.length() > 200) throw new Api(400, "Password is too long");
    }

    static void checkName(String name) {
        if (name == null || !NAME.matcher(name.trim()).matches()) throw new Api(400, "Username must be 3-32 characters: letters, numbers, dot, dash or underscore");
    }

    // ---------- login attempts ----------

    private static final Map<String, int[]> fails = new HashMap<>();
    private static final Map<String, Long> lockedUntil = new HashMap<>();
    private static final byte[] DUMMY_SALT = new byte[16];
    private static final byte[] DUMMY_HASH = new byte[32];

    private static synchronized long lockLeft(String k) {
        Long l = lockedUntil.get(k);
        long now = System.currentTimeMillis();
        return l != null && l > now ? Math.max(1, (l - now) / 1000) : 0;
    }

    private static synchronized void failed(String k) {
        int[] f = fails.computeIfAbsent(k, x -> new int[1]);
        if (++f[0] >= 5) lockedUntil.put(k, System.currentTimeMillis() + Math.min(900_000L, 15_000L << Math.min(f[0] - 5, 6)));
        if (f[0] == 5) {
            String who = k.startsWith("ip:") ? "address " + k.substring(3) : k.startsWith("user:") ? "the account \"" + k.substring(5) + "\"" : "an invite code guess from " + k.substring(4);
            Notify.postOnce("lockout:" + k, 10 * 60_000L, "security", "warn", null, "Sign-in blocked after wrong passwords", "Five wrong attempts in a row for " + who + ". It is locked for a short time. If this was not you or someone you know, change the password.", null);
        }
    }

    private static synchronized void succeeded(String k) {
        fails.remove(k);
        lockedUntil.remove(k);
    }

    /** Throttle for invite-token guessing: {@code checkOnly} refuses when locked out; otherwise records a failure. */
    static void guess(String ip, boolean ok, boolean checkOnly) {
        String k = "inv:" + ip;
        if (checkOnly) {
            long w = lockLeft(k);
            if (w > 0) throw new Api(429, "Too many attempts. Try again in " + w + " seconds.");
            return;
        }
        if (ok) succeeded(k); else failed(k);
    }
    /** @return the account when the password is right; throws Api for lockouts and wrong credentials. */
    static Acct login(String ip, String name, String pw) {
        String k1 = "ip:" + ip, k2 = "user:" + (name == null ? "" : name.trim().toLowerCase());
        long w = Math.max(lockLeft(k1), lockLeft(k2));
        if (w > 0) throw new Api(429, "Too many failed attempts. Try again in " + w + " seconds.");
        Acct a = byName(name);
        boolean ok;
        if (a == null || pw == null) {
            pbkdf2(pw == null ? "" : pw, DUMMY_SALT, ITER);
            ok = false;
        } else {
            ok = MessageDigest.isEqual(a.hash, pbkdf2(pw, a.salt, a.iter));
        }
        if (!ok || a.disabled) {
            failed(k1);
            failed(k2);
            throw new Api(401, ok ? "This account is disabled" : "Wrong username or password");
        }
        // with two-step sign-in the counters are cleared only after the code is accepted (see loginOk)
        if (a.totp.isEmpty()) {
            succeeded(k1);
            succeeded(k2);
        }
        return a;
    }

    static void loginOk(String ip, String name) {
        succeeded("ip:" + ip);
        succeeded("user:" + (name == null ? "" : name.trim().toLowerCase()));
    }

    // ---------- sessions (stateless, signed; revoked by bumping the account's epoch) ----------

    private static synchronized byte[] key() {
        if (key != null) return key;
        try {
            Path p = Main.DATA.resolve("session.key");
            if (Files.exists(p)) key = Base64.getUrlDecoder().decode(Files.readString(p).trim());
            else {
                key = new byte[32];
                RNG.nextBytes(key);
                Files.writeString(p, B64.encodeToString(key));
            }
        } catch (IOException e) {
            key = new byte[32];
            RNG.nextBytes(key);
        }
        return key;
    }

    private static String sign(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key(), "HmacSHA256"));
            return B64.encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            return "invalid";
        }
    }

    static String token(Acct a) {
        String body = a.id + "." + Long.toString(System.currentTimeMillis() + SESSION_MS, 36) + "." + a.epoch;
        return body + "." + sign(body);
    }

    static Acct fromToken(String t) {
        if (t == null) return null;
        String[] p = t.split("\\.");
        if (p.length != 4) return null;
        String body = p[0] + "." + p[1] + "." + p[2];
        if (!MessageDigest.isEqual(sign(body).getBytes(StandardCharsets.UTF_8), p[3].getBytes(StandardCharsets.UTF_8))) return null;
        try {
            if (Long.parseLong(p[1], 36) < System.currentTimeMillis()) return null;
            Acct a = byId(p[0]);
            if (a == null || a.disabled || a.epoch != Integer.parseInt(p[2])) return null;
            return a;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---------- account operations ----------

    static synchronized Acct createFirstAdmin(String name, String pw) throws IOException {
        ensureFresh();
        if (!accounts.isEmpty()) throw new Api(403, "An account already exists");
        checkName(name);
        checkPassword(pw);
        Acct a = new Acct();
        a.id = newId();
        a.username = name.trim();
        setPassword(a, pw);
        a.admin = true;
        a.created = System.currentTimeMillis();
        a.createdBy = "first-run setup";
        save(a);
        return a;
    }

    static synchronized Acct createAdmin(String name, String pw, String by) throws IOException {
        ensureFresh();
        checkName(name);
        checkPassword(pw);
        if (byName(name) != null) throw new Api(409, "That username is taken");
        Acct a = new Acct();
        a.id = newId();
        a.username = name.trim();
        setPassword(a, pw);
        a.admin = true;
        a.created = System.currentTimeMillis();
        a.createdBy = by;
        save(a);
        return a;
    }

    static synchronized void changePassword(Acct a, String pw) throws IOException {
        checkPassword(pw);
        setPassword(a, pw);
        a.epoch++;
        save(a);
    }

    static synchronized void setAdmin(Acct a, boolean admin) throws IOException {
        a.admin = admin;
        a.epoch++;
        save(a);
    }

    static synchronized void setDisabled(Acct a, boolean disabled) throws IOException {
        a.disabled = disabled;
        a.epoch++;
        save(a);
    }

    static synchronized void setMcName(Acct a, String mc) throws IOException {
        a.mcName = mc == null ? "" : mc.trim();
        save(a);
    }

    static synchronized void grant(Acct a, String serverId, int level) throws IOException {
        if (level < 0) a.grants.remove(serverId);
        else a.grants.put(serverId, level);
        save(a);
    }

    static synchronized void delete(Acct a) throws IOException {
        Files.deleteIfExists(dir("accounts").resolve(a.id + ".json"));
        signature = "";
        checkedAt = 0;
        ensureFresh();
        OpsSync.accountsChanged();
    }

    /** Called when a server is removed from the manager. */
    static synchronized void dropServer(String serverId) {
        ensureFresh();
        for (Acct a : new ArrayList<>(accounts.values())) {
            if (a.grants.remove(serverId) != null) {
                try { save(a); } catch (IOException ignored) { }
            }
        }
    }

    // ---------- two-step sign-in (TOTP, RFC 6238: SHA-1, 6 digits, 30 seconds) ----------

    private static final String B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    static String base32(byte[] b) {
        StringBuilder sb = new StringBuilder();
        int buf = 0, bits = 0;
        for (byte x : b) {
            buf = (buf << 8) | (x & 0xff);
            bits += 8;
            while (bits >= 5) { sb.append(B32.charAt((buf >> (bits - 5)) & 31)); bits -= 5; }
        }
        if (bits > 0) sb.append(B32.charAt((buf << (5 - bits)) & 31));
        return sb.toString();
    }

    static byte[] unbase32(String s) {
        String t = s.replaceAll("[\\s=-]", "").toUpperCase();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int buf = 0, bits = 0;
        for (char c : t.toCharArray()) {
            int v = B32.indexOf(c);
            if (v < 0) throw new IllegalArgumentException("bad base32");
            buf = (buf << 5) | v;
            bits += 5;
            if (bits >= 8) { out.write((buf >> (bits - 8)) & 0xff); bits -= 8; }
        }
        return out.toByteArray();
    }

    static String totpAt(byte[] key, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] h = mac.doFinal(java.nio.ByteBuffer.allocate(8).putLong(step).array());
            int o = h[h.length - 1] & 0xf;
            int bin = ((h[o] & 0x7f) << 24) | ((h[o + 1] & 0xff) << 16) | ((h[o + 2] & 0xff) << 8) | (h[o + 3] & 0xff);
            return String.format("%06d", bin % 1_000_000);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** @return the matching time step, or -1. Allows one step either side for clock drift. */
    private static long matchTotp(String secret, String code, long notBefore) {
        if (secret == null || secret.isEmpty() || code == null || !code.matches("\\d{6}")) return -1;
        byte[] key = unbase32(secret);
        long now = System.currentTimeMillis() / 30000;
        for (long st = now - 1; st <= now + 1; st++) {
            if (st > notBefore && MessageDigest.isEqual(totpAt(key, st).getBytes(StandardCharsets.UTF_8), code.getBytes(StandardCharsets.UTF_8))) return st;
        }
        return -1;
    }

    static synchronized Map<String, Object> begin2fa(Acct a) throws IOException {
        byte[] k = new byte[20];
        RNG.nextBytes(k);
        a.totpPending = base32(k);
        save(a);
        String issuer = "MC Server Manager";
        String uri = "otpauth://totp/" + java.net.URLEncoder.encode(issuer, StandardCharsets.UTF_8).replace("+", "%20") + ":" + java.net.URLEncoder.encode(a.username, StandardCharsets.UTF_8)
                + "?secret=" + a.totpPending + "&issuer=" + java.net.URLEncoder.encode(issuer, StandardCharsets.UTF_8).replace("+", "%20") + "&digits=6&period=30";
        return Json.map("secret", a.totpPending, "uri", uri);
    }

    /** @return one-time recovery codes (shown once; only their hashes are kept). */
    static synchronized List<String> enable2fa(Acct a, String code) throws IOException {
        long st = matchTotp(a.totpPending, code == null ? "" : code.trim(), -1);
        if (st < 0) throw new Api(400, "That code is not right. Check the time on your phone and try the next code.");
        a.totp = a.totpPending;
        a.totpPending = "";
        a.totpLast = st;
        a.recovery.clear();
        List<String> plain = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            byte[] b = new byte[6];
            RNG.nextBytes(b);
            String c = HexFormat.of().formatHex(b).substring(0, 10);
            plain.add(c.substring(0, 5) + "-" + c.substring(5));
            a.recovery.add(sha256(c));
        }
        a.epoch++;
        save(a);
        return plain;
    }

    static synchronized void disable2fa(Acct a) throws IOException {
        a.totp = "";
        a.totpPending = "";
        a.totpLast = 0;
        a.recovery.clear();
        a.epoch++;
        save(a);
    }

    /** Checks an authenticator code or a recovery code; a code can only be used once. */
    static synchronized boolean check2fa(Acct a, String code) throws IOException {
        if (code == null) return false;
        String c = code.trim().replace(" ", "");
        long st = matchTotp(a.totp, c, a.totpLast);
        if (st >= 0) {
            a.totpLast = st;
            save(a);
            return true;
        }
        String norm = c.toLowerCase().replace("-", "");
        if (norm.matches("[0-9a-f]{10}") && a.recovery.remove(sha256(norm))) {
            save(a);
            return true;
        }
        return false;
    }

    /** Records a failed second step against the same lockout counters as a wrong password. */
    static void failedSecondStep(String ip, String name) {
        failed("ip:" + ip);
        failed("user:" + (name == null ? "" : name.trim().toLowerCase()));
    }

    // ---------- invites ----------

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Path invFile(String id) { return dir("invites").resolve(id + ".json"); }

    static List<Map<String, Object>> invites() {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            Files.createDirectories(dir("invites"));
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir("invites"), "*.json")) {
                for (Path p : ds) {
                    try { out.add(Json.obj(Json.parse(Files.readString(p, StandardCharsets.UTF_8)))); } catch (RuntimeException ignored) { }
                }
            }
        } catch (IOException e) {
            Main.log("invites: " + e);
        }
        out.sort(Comparator.comparingLong((Map<String, Object> m) -> m.get("created") instanceof Number n ? n.longValue() : 0).reversed());
        return out;
    }

    /** @return the one-time token (shown once; only its hash is stored). */
    static synchronized String mintInvite(Acct by, boolean admin, int level, List<String> servers, long hours, String note, String mcName) throws IOException {
        byte[] b = new byte[24];
        RNG.nextBytes(b);
        String token = "mcm-" + B64.encodeToString(b);
        String id = newId();
        long now = System.currentTimeMillis();
        Map<String, Object> inv = Json.map("id", id, "hash", sha256(token), "admin", admin, "level", level, "servers", new ArrayList<>(servers), "createdBy", by.id, "createdByName", by.username,
                "created", now, "expires", now + hours * 3600_000L, "note", note == null ? "" : note, "mcName", mcName == null ? "" : mcName);
        Files.createDirectories(dir("invites"));
        Files.writeString(invFile(id), Json.stringify(inv), StandardCharsets.UTF_8);
        return token;
    }

    static synchronized Map<String, Object> findInvite(String token) {
        if (token == null || token.length() < 20) return null;
        String h = sha256(token.trim());
        for (Map<String, Object> m : invites()) {
            if (!h.equals(Json.str(m, "hash"))) continue;
            long exp = m.get("expires") instanceof Number n ? n.longValue() : 0;
            if (m.get("usedBy") != null || exp < System.currentTimeMillis()) return null;
            return m;
        }
        return null;
    }

    static synchronized boolean revokeInvite(String id) throws IOException {
        if (id == null || !id.matches("[A-Za-z0-9]+")) return false;
        return Files.deleteIfExists(invFile(id));
    }

    /** Redeems an invite: creates the account and marks the invite used. */
    static synchronized Acct redeem(String token, String name, String pw, String ip) throws IOException {
        Map<String, Object> inv = findInvite(token);
        if (inv == null) throw new Api(400, "This invite is not valid. It may have expired or already been used.");
        checkName(name);
        checkPassword(pw);
        if (byName(name) != null) throw new Api(409, "That username is taken, pick another");
        Acct a = new Acct();
        a.id = newId();
        a.username = name.trim();
        setPassword(a, pw);
        a.created = System.currentTimeMillis();
        a.createdBy = Json.str(inv, "createdByName") + " (invite)";
        a.mcName = inv.get("mcName") == null ? "" : String.valueOf(inv.get("mcName"));
        if (Boolean.TRUE.equals(inv.get("admin"))) a.admin = true;
        else {
            int level = Json.num(inv, "level", 0);
            for (Object s : Json.arr(inv.get("servers"))) a.grants.put(String.valueOf(s), level);
        }
        save(a);
        inv.put("usedBy", a.username);
        inv.put("usedAt", System.currentTimeMillis());
        inv.put("usedIp", ip);
        Files.writeString(invFile(Json.str(inv, "id")), Json.stringify(inv), StandardCharsets.UTF_8);
        return a;
    }

    // ---------- audit log (hash-chained: each line commits to the one before it) ----------

    private static String lastHash = null;

    private static Path auditFile() { return Main.DATA.resolve("audit.log"); }

    private static String chainHash(String prev, String body) { return sha256(prev + "\n" + body); }

    static synchronized void audit(String user, String ip, String action, String server, String detail) {
        try {
            Path f = auditFile();
            if (lastHash == null) {
                lastHash = "0";
                if (Files.exists(f)) {
                    List<String> ls = Files.readAllLines(f, StandardCharsets.UTF_8);
                    for (int i = ls.size() - 1; i >= 0; i--) {
                        try { lastHash = Json.str(Json.parse(ls.get(i)), "h"); break; } catch (RuntimeException ignored) { }
                    }
                }
            }
            if (Files.exists(f) && Files.size(f) > 8L * 1024 * 1024) {
                Files.move(f, Main.DATA.resolve("audit-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()) + ".log"));
            }
            Map<String, Object> e = Json.map("t", System.currentTimeMillis(), "user", user == null ? "" : user, "ip", ip == null ? "" : ip, "action", action,
                    "server", server == null ? "" : server, "detail", detail == null ? "" : (detail.length() > 400 ? detail.substring(0, 400) : detail), "p", lastHash);
            String h = chainHash(lastHash, Json.stringify(e));
            e.put("h", h);
            Files.writeString(f, Json.stringify(e) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            lastHash = h;
        } catch (IOException | RuntimeException ex) {
            Main.log("audit: " + ex);
        }
    }

    /** Most recent entries first. {@code serverFilter} null = all servers. */
    static synchronized Map<String, Object> auditRead(Set<String> serverFilter, String userFilter, String serverOnly, int limit) {
        List<Object> out = new ArrayList<>();
        boolean intact = true;
        int total = 0;
        try {
            Path f = auditFile();
            if (Files.exists(f)) {
                List<String> ls = Files.readAllLines(f, StandardCharsets.UTF_8);
                String prev = null;
                for (String l : ls) {
                    Map<String, Object> m;
                    try { m = Json.obj(Json.parse(l)); } catch (RuntimeException ex) { intact = false; continue; }
                    String h = Json.str(m, "h"), p = Json.str(m, "p");
                    Map<String, Object> copy = new LinkedHashMap<>(m);
                    copy.remove("h");
                    if (h == null || p == null || !h.equals(chainHash(p, Json.stringify(copy))) || (prev != null && !prev.equals(p))) intact = false;
                    prev = h;
                    total++;
                }
                for (int i = ls.size() - 1; i >= 0 && out.size() < limit; i--) {
                    Map<String, Object> m;
                    try { m = Json.obj(Json.parse(ls.get(i))); } catch (RuntimeException ex) { continue; }
                    String sv = Json.str(m, "server");
                    if (serverFilter != null && !(sv != null && !sv.isEmpty() && serverFilter.contains(sv))) continue;
                    if (serverOnly != null && !serverOnly.isEmpty() && !serverOnly.equals(sv)) continue;
                    if (userFilter != null && !userFilter.isEmpty() && !userFilter.equalsIgnoreCase(Json.str(m, "user"))) continue;
                    m.remove("p");
                    m.remove("h");
                    out.add(m);
                }
            }
        } catch (IOException e) {
            Main.log("audit read: " + e);
        }
        return Json.map("entries", out, "intact", intact, "total", total);
    }
}
