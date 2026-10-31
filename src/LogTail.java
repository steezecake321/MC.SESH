import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.function.Consumer;

/** Follows logs/latest.log: keeps a ring of recent lines and hands new lines to a sink. Handles rotation on server restart. */
final class LogTail {
    interface Sink {
        void lines(long firstSeq, List<String> lines);
        void reset();
    }

    private static final int MAX_LINES = 4000;
    private static final int INITIAL_BYTES = 3 << 20;
    private static final int CHUNK = 4 << 20;

    private final Path file;
    private final ArrayDeque<String> ring = new ArrayDeque<>();
    private long pos;
    private long created = -1;
    private long seq;
    private boolean initialized;
    /** The first bytes of the file as last seen: a new log with the same size and creation time (Windows keeps the old creation time for a recreated file) is still told apart. */
    private byte[] head = new byte[0];

    LogTail(Path file) { this.file = file; }

    synchronized long seq() { return seq; }

    synchronized List<String> tail(int n) {
        List<String> all = new ArrayList<>(ring);
        return all.subList(Math.max(0, all.size() - n), all.size());
    }

    synchronized Map<String, Object> snapshot(int n) {
        return Json.map("lines", tail(n), "seq", seq);
    }

    synchronized long startedMillis() { return created; }

    private byte[] readHead(int n) {
        if (n <= 0) return new byte[0];
        try (SeekableByteChannel ch = Files.newByteChannel(file, StandardOpenOption.READ)) {
            ByteBuffer bb = ByteBuffer.allocate(n);
            while (bb.hasRemaining() && ch.read(bb) > 0) { }
            return Arrays.copyOf(bb.array(), bb.position());
        } catch (IOException e) {
            return new byte[0];
        }
    }

    private boolean changedHead(long size) {
        if (head.length == 0 || size < head.length) return false;
        return !Arrays.equals(readHead(head.length), head);
    }

    synchronized void poll(Sink sink, Consumer<String> hook) {
        try {
            if (!Files.exists(file)) return;
            BasicFileAttributes at = Files.readAttributes(file, BasicFileAttributes.class);
            long size = at.size();
            long ctime = at.creationTime().toMillis();
            boolean first = !initialized;
            if (first) {
                pos = Math.max(0, size - INITIAL_BYTES);
            } else if (size < pos || ctime != created || changedHead(size)) {
                ring.clear();
                pos = 0;
                head = new byte[0];
                if (sink != null) sink.reset();
            }
            created = ctime;
            if (head.length < Math.min(64, size)) head = readHead((int) Math.min(256, size));
            if (size <= pos && !first) return;
            int want = (int) Math.min(size - pos, CHUNK);
            if (want <= 0) {
                initialized = true;
                return;
            }
            ByteBuffer bb = ByteBuffer.allocate(want);
            try (SeekableByteChannel ch = Files.newByteChannel(file, StandardOpenOption.READ)) {
                ch.position(pos);
                while (bb.hasRemaining() && ch.read(bb) > 0) { }
            }
            byte[] b = bb.array();
            int len = bb.position();
            int skip = 0;
            if (first && pos > 0) {
                while (skip < len && b[skip] != '\n') skip++;
                skip = Math.min(len, skip + 1);
            }
            int end = len;
            while (end > skip && b[end - 1] != '\n') end--;
            if (end <= skip) {
                if (first) { initialized = true; pos += len; }
                return;
            }
            String text = new String(b, skip, end - skip, StandardCharsets.UTF_8);
            pos += end;
            initialized = true;
            List<String> fresh = new ArrayList<>();
            for (String line : text.split("\n", -1)) {
                if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
                fresh.add(line);
            }
            if (!fresh.isEmpty() && fresh.get(fresh.size() - 1).isEmpty()) fresh.remove(fresh.size() - 1);
            long firstSeq = seq + 1;
            for (String line : fresh) {
                seq++;
                ring.addLast(line);
                if (ring.size() > MAX_LINES) ring.removeFirst();
                hook.accept(line);
            }
            if (!first && sink != null && !fresh.isEmpty()) sink.lines(firstSeq, fresh);
        } catch (IOException ignored) { }
    }
}
