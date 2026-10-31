import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Archives other than .zip (7-Zip, RAR, tar and its compressed forms) are unpacked with the tar.exe that comes with Windows 10 and 11 (it is built on libarchive),
 * into a temporary folder that is then used like any other world folder. .zip files are read in place and never need this.
 */
final class Archives {
    private Archives() { }

    private static final String[] ENDINGS = {".zip", ".7z", ".rar", ".tar", ".tar.gz", ".tgz", ".tar.xz", ".txz", ".tar.bz2", ".tbz2", ".tar.zst", ".tzst"};
    private static final long MAX_AGE_MS = 24 * 3_600_000L;
    private static final long TIMEOUT_MIN = 30;

    static boolean isArchive(Path p) {
        String n = p.getFileName() == null ? "" : p.getFileName().toString().toLowerCase();
        for (String e : ENDINGS) if (n.endsWith(e)) return true;
        return false;
    }

    static boolean isZip(Path p) { return p.getFileName() != null && p.getFileName().toString().toLowerCase().endsWith(".zip"); }

    static String listing() { return String.join(", ", ENDINGS); }

    /** True for the file endings the folder picker offers when it is asked for "archive". */
    static boolean matches(String fileName) {
        String n = fileName.toLowerCase();
        for (String e : ENDINGS) if (n.endsWith(e)) return true;
        return false;
    }

    static Path root() { return Main.DATA.resolve("tmp"); }

    static boolean isExtracted(Path p) {
        Path r = root().toAbsolutePath().normalize();
        return p.toAbsolutePath().normalize().startsWith(r);
    }

    /** The extraction folder (root/import-xxxx) a path inside it belongs to, or null. */
    static Path extractionOf(Path inside) {
        Path r = root().toAbsolutePath().normalize(), p = inside.toAbsolutePath().normalize();
        if (!p.startsWith(r) || p.equals(r)) return null;
        return r.resolve(r.relativize(p).getName(0).toString());
    }

    private static Path tarExe() {
        String sys = System.getenv("SystemRoot");
        Path p = Path.of(sys == null ? "C:\\Windows" : sys, "System32", "tar.exe");
        return Files.isRegularFile(p) ? p : null;
    }

    private static String id(Path archive) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            md.update((archive.toAbsolutePath().normalize() + "|" + Files.size(archive) + "|" + Files.getLastModifiedTime(archive).toMillis()).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder("import-");
            byte[] d = md.digest();
            for (int i = 0; i < 6; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Deletes an extraction folder together with its "finished" marker. */
    static void remove(Path extraction) {
        deleteTree(extraction);
        try { Files.deleteIfExists(root().resolve(extraction.getFileName() + ".done")); } catch (IOException ignored) { }
    }

    static void deleteTree(Path dir) {
        try (Stream<Path> w = Files.walk(dir)) {
            w.sorted(Comparator.reverseOrder()).forEach(f -> { try { Files.deleteIfExists(f); } catch (IOException ignored) { } });
        } catch (IOException | UncheckedIOException ignored) { }
    }

    /** Removes unpacked archives that were never used (the person closed the wizard), once they are a day old. */
    static void cleanOld() {
        Path r = root();
        if (!Files.isDirectory(r)) return;
        try (Stream<Path> s = Files.list(r)) {
            for (Path d : s.toList()) {
                if (!d.getFileName().toString().startsWith("import-")) continue;
                try {
                    if (Files.isDirectory(d) && System.currentTimeMillis() - Files.getLastModifiedTime(d).toMillis() > MAX_AGE_MS) remove(d);
                } catch (IOException ignored) { }
            }
        } catch (IOException | UncheckedIOException ignored) { }
    }

    /** Unpacks the archive (once; a second request for the same file reuses the folder) and returns the folder. */
    static Path extract(Path archive) throws IOException {
        Path tar = tarExe();
        if (tar == null) throw new Api(501, "This PC has no tar.exe (it comes with Windows 10 and 11), so the archive cannot be opened here. Unpack it yourself and pick the folder.");
        cleanOld();
        Path dest = root().resolve(id(archive));
        Path done = root().resolve(dest.getFileName() + ".done");
        if (Files.isRegularFile(done)) return dest;
        if (Files.exists(dest)) remove(dest);
        Files.createDirectories(dest);
        long size = Files.size(archive);
        long free = Files.getFileStore(dest).getUsableSpace();
        if (free < size * 2 + (2L << 30)) {
            deleteTree(dest);
            throw new Api(409, "There is not enough free space to unpack that archive (about " + ((size * 2 + (2L << 30)) >> 20) + " MB needed on the manager's drive, " + (free >> 20) + " MB free).");
        }
        Process p = new ProcessBuilder(tar.toString(), "-xf", archive.toString(), "-C", dest.toString()).redirectErrorStream(true).start();
        StringBuilder out = new StringBuilder();
        Thread reader = Thread.startVirtualThread(() -> {
            try { out.append(new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8)); } catch (IOException ignored) { }
        });
        long deadline = System.currentTimeMillis() + TIMEOUT_MIN * 60_000;
        try {
            while (!p.waitFor(2, TimeUnit.SECONDS)) {
                if (System.currentTimeMillis() > deadline) { p.destroyForcibly(); throw new Api(504, "Unpacking the archive took too long and was stopped."); }
                if (Files.getFileStore(dest).getUsableSpace() < (512L << 20)) { p.destroyForcibly(); throw new Api(409, "The drive ran out of space while unpacking the archive."); }
            }
            reader.join(2000);
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new Api(500, "Unpacking was interrupted.");
        } catch (Api a) {
            deleteTree(dest);
            throw a;
        }
        if (p.exitValue() != 0) {
            String msg = out.toString().strip();
            deleteTree(dest);
            if (msg.length() > 300) msg = msg.substring(0, 300) + "...";
            throw new Api(400, "Windows could not unpack that archive" + (msg.isEmpty() ? "." : ": " + msg + (msg.endsWith(".") ? "" : ".")) + " Older Windows 10 builds cannot open every format (RAR and 7-Zip need a recent build); unpack it with 7-Zip or WinRAR and pick the folder instead.");
        }
        stripLinks(dest);
        Files.writeString(done, "ok", StandardCharsets.UTF_8);
        return dest;
    }

    /** Symbolic links and junctions inside an archive could point outside the folder; they are removed. */
    private static void stripLinks(Path dir) throws IOException {
        List<Path> bad = new ArrayList<>();
        try (Stream<Path> w = Files.walk(dir)) {
            for (Iterator<Path> it = w.iterator(); it.hasNext(); ) {
                Path f = it.next();
                BasicFileAttributes a = Files.readAttributes(f, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (a.isSymbolicLink() || a.isOther()) bad.add(f);
            }
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        for (Path f : bad) { try { Files.deleteIfExists(f); } catch (IOException ignored) { } }
    }
}
