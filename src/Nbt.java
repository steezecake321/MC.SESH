import java.io.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Minimal reader and writer for Minecraft's NBT format (gzip files such as level.dat and playerdata). Compounds are Maps, lists are Lst, arrays are byte[]/int[]/long[]. */
final class Nbt {
    private Nbt() { }

    record Lst(byte type, List<Object> items) { }

    private static final int MAX_BYTES = 128 << 20;
    private static final int MAX_DEPTH = 256;

    /** Reads a gzip-compressed NBT file and returns the contents of its root compound. */
    static Map<String, Object> readGzip(InputStream raw) throws IOException {
        byte[] data;
        try (InputStream in = new GZIPInputStream(raw)) {
            data = in.readNBytes(MAX_BYTES + 1);
        }
        if (data.length > MAX_BYTES) throw new IOException("NBT file is too large");
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        if (in.readByte() != 10) throw new IOException("Not an NBT compound");
        in.readUTF();
        return compound(in, 0);
    }

    @SuppressWarnings("unchecked")
    private static Object payload(DataInputStream in, int type, int depth) throws IOException {
        if (depth > MAX_DEPTH) throw new IOException("NBT nesting is too deep");
        switch (type) {
            case 1: return in.readByte();
            case 2: return in.readShort();
            case 3: return in.readInt();
            case 4: return in.readLong();
            case 5: return in.readFloat();
            case 6: return in.readDouble();
            case 7: { int n = len(in, 1); byte[] b = new byte[n]; in.readFully(b); return b; }
            case 8: return in.readUTF();
            case 9: {
                byte t = in.readByte();
                int n = in.readInt();
                if (n < 0 || n > MAX_BYTES / 4 || (t == 0 && n > 0)) throw new IOException("Bad NBT list");
                List<Object> l = new ArrayList<>(Math.min(n, 4096));
                for (int i = 0; i < n; i++) l.add(payload(in, t, depth + 1));
                return new Lst(t, l);
            }
            case 10: return compound(in, depth + 1);
            case 11: { int n = len(in, 4); int[] a = new int[n]; for (int i = 0; i < n; i++) a[i] = in.readInt(); return a; }
            case 12: { int n = len(in, 8); long[] a = new long[n]; for (int i = 0; i < n; i++) a[i] = in.readLong(); return a; }
            default: throw new IOException("Unknown NBT tag " + type);
        }
    }

    private static int len(DataInputStream in, int size) throws IOException {
        int n = in.readInt();
        if (n < 0 || (long) n * size > MAX_BYTES) throw new IOException("Bad NBT array length");
        return n;
    }

    private static Map<String, Object> compound(DataInputStream in, int depth) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        while (true) {
            int t = in.readByte();
            if (t == 0) return m;
            String name = in.readUTF();
            m.put(name, payload(in, t, depth));
        }
    }

    /** Writes a root compound (with the empty root name) as a gzip-compressed NBT file. */
    static byte[] writeGzip(Map<String, Object> root) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bo))) {
            out.writeByte(10);
            out.writeUTF("");
            writeCompound(out, root);
        }
        return bo.toByteArray();
    }

    @SuppressWarnings("unchecked")
    private static int typeOf(Object v) {
        if (v instanceof Byte) return 1;
        if (v instanceof Short) return 2;
        if (v instanceof Integer) return 3;
        if (v instanceof Long) return 4;
        if (v instanceof Float) return 5;
        if (v instanceof Double) return 6;
        if (v instanceof byte[]) return 7;
        if (v instanceof String) return 8;
        if (v instanceof Lst) return 9;
        if (v instanceof Map) return 10;
        if (v instanceof int[]) return 11;
        if (v instanceof long[]) return 12;
        throw new IllegalArgumentException("Unsupported NBT value " + v.getClass());
    }

    @SuppressWarnings("unchecked")
    private static void writePayload(DataOutputStream out, Object v) throws IOException {
        switch (typeOf(v)) {
            case 1 -> out.writeByte((Byte) v);
            case 2 -> out.writeShort((Short) v);
            case 3 -> out.writeInt((Integer) v);
            case 4 -> out.writeLong((Long) v);
            case 5 -> out.writeFloat((Float) v);
            case 6 -> out.writeDouble((Double) v);
            case 7 -> { byte[] b = (byte[]) v; out.writeInt(b.length); out.write(b); }
            case 8 -> out.writeUTF((String) v);
            case 9 -> { Lst l = (Lst) v; out.writeByte(l.type()); out.writeInt(l.items().size()); for (Object o : l.items()) writePayload(out, o); }
            case 10 -> writeCompound(out, (Map<String, Object>) v);
            case 11 -> { int[] a = (int[]) v; out.writeInt(a.length); for (int x : a) out.writeInt(x); }
            default -> { long[] a = (long[]) v; out.writeInt(a.length); for (long x : a) out.writeLong(x); }
        }
    }

    private static void writeCompound(DataOutputStream out, Map<String, Object> m) throws IOException {
        for (Map.Entry<String, Object> e : m.entrySet()) {
            out.writeByte(typeOf(e.getValue()));
            out.writeUTF(e.getKey());
            writePayload(out, e.getValue());
        }
        out.writeByte(0);
    }

    // ---------- small helpers for reading values ----------

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object o) { return o instanceof Map ? (Map<String, Object>) o : null; }

    static Map<String, Object> child(Map<String, Object> m, String key) { return m == null ? null : map(m.get(key)); }

    static Long lng(Object o) { return o instanceof Number n ? n.longValue() : null; }

    static String str(Object o) { return o instanceof String s ? s : null; }
}
