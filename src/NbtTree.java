import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** A small reader and writer for Minecraft's NBT files (gzip, big-endian). Values keep their exact type, so a file can be read, changed and written back unchanged apart from the change. */
final class NbtTree {
    private NbtTree() { }

    static final int END = 0, BYTE = 1, SHORT = 2, INT = 3, LONG = 4, FLOAT = 5, DOUBLE = 6, BYTE_ARRAY = 7, STRING = 8, LIST = 9, COMPOUND = 10, INT_ARRAY = 11, LONG_ARRAY = 12;

    /** One value. compound -> LinkedHashMap<String, Tag>, list -> List<Tag> (all of type elem), numbers -> Number, string -> String, arrays -> byte[] / int[] / long[]. */
    static final class Tag {
        final int type;
        Object v;
        int elem;

        Tag(int type, Object v) { this.type = type; this.v = v; }

        @SuppressWarnings("unchecked") Map<String, Tag> map() { return (Map<String, Tag>) v; }
        @SuppressWarnings("unchecked") List<Tag> list() { return (List<Tag>) v; }
        String str() { return v instanceof String s ? s : null; }
        double num() { return v instanceof Number n ? n.doubleValue() : 0; }
        Tag get(String k) { return type == COMPOUND ? map().get(k) : null; }
    }

    static Tag compound() { return new Tag(COMPOUND, new LinkedHashMap<String, Tag>()); }

    static Tag string(String s) { return new Tag(STRING, s); }

    /** A deep copy, so a branch can be put in another file without sharing anything with the original. */
    static Tag copy(Tag t) {
        Object v = t.v;
        if (t.type == COMPOUND) {
            Map<String, Tag> m = new LinkedHashMap<>();
            for (Map.Entry<String, Tag> e : t.map().entrySet()) m.put(e.getKey(), copy(e.getValue()));
            v = m;
        } else if (t.type == LIST) {
            List<Tag> l = new ArrayList<>();
            for (Tag e : t.list()) l.add(copy(e));
            v = l;
        } else if (v instanceof byte[] a) v = a.clone();
        else if (v instanceof int[] a) v = a.clone();
        else if (v instanceof long[] a) v = a.clone();
        Tag c = new Tag(t.type, v);
        c.elem = t.elem;
        return c;
    }

    /** Reads a file; the root's name is returned in rootName[0] when given. */
    static Tag read(Path file, String[] rootName) throws IOException {
        byte[] raw = Files.readAllBytes(file);
        InputStream in = new ByteArrayInputStream(raw);
        if (raw.length > 2 && (raw[0] & 0xff) == 0x1f && (raw[1] & 0xff) == 0x8b) in = new GZIPInputStream(in);
        try (DataInputStream d = new DataInputStream(new BufferedInputStream(in))) {
            int t = d.readUnsignedByte();
            if (t != COMPOUND) throw new IOException("Not an NBT compound file");
            String name = d.readUTF();
            if (rootName != null && rootName.length > 0) rootName[0] = name;
            return payload(d, COMPOUND);
        }
    }

    private static Tag payload(DataInputStream d, int t) throws IOException {
        switch (t) {
            case BYTE: return new Tag(t, d.readByte());
            case SHORT: return new Tag(t, d.readShort());
            case INT: return new Tag(t, d.readInt());
            case LONG: return new Tag(t, d.readLong());
            case FLOAT: return new Tag(t, d.readFloat());
            case DOUBLE: return new Tag(t, d.readDouble());
            case BYTE_ARRAY: { byte[] b = new byte[d.readInt()]; d.readFully(b); return new Tag(t, b); }
            case STRING: return new Tag(t, d.readUTF());
            case LIST: {
                int et = d.readUnsignedByte(), n = d.readInt();
                List<Tag> l = new ArrayList<>(Math.max(0, Math.min(n, 100000)));
                for (int i = 0; i < n; i++) l.add(payload(d, et));
                Tag tag = new Tag(t, l);
                tag.elem = et;
                return tag;
            }
            case COMPOUND: {
                Map<String, Tag> m = new LinkedHashMap<>();
                while (true) {
                    int et = d.readUnsignedByte();
                    if (et == END) break;
                    String k = d.readUTF();
                    m.put(k, payload(d, et));
                }
                return new Tag(t, m);
            }
            case INT_ARRAY: { int[] a = new int[d.readInt()]; for (int i = 0; i < a.length; i++) a[i] = d.readInt(); return new Tag(t, a); }
            case LONG_ARRAY: { long[] a = new long[d.readInt()]; for (int i = 0; i < a.length; i++) a[i] = d.readLong(); return new Tag(t, a); }
            default: throw new IOException("Unknown NBT tag type " + t);
        }
    }

    /** Writes the file gzip-compressed, replacing it atomically (a temp file is moved over the old one). */
    static void write(Path file, String rootName, Tag root) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".mcm-tmp");
        try (DataOutputStream o = new DataOutputStream(new GZIPOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp))))) {
            o.writeByte(COMPOUND);
            o.writeUTF(rootName == null ? "" : rootName);
            out(o, root);
        }
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void out(DataOutputStream o, Tag t) throws IOException {
        switch (t.type) {
            case BYTE -> o.writeByte(((Number) t.v).intValue());
            case SHORT -> o.writeShort(((Number) t.v).intValue());
            case INT -> o.writeInt(((Number) t.v).intValue());
            case LONG -> o.writeLong(((Number) t.v).longValue());
            case FLOAT -> o.writeFloat(((Number) t.v).floatValue());
            case DOUBLE -> o.writeDouble(((Number) t.v).doubleValue());
            case BYTE_ARRAY -> { byte[] b = (byte[]) t.v; o.writeInt(b.length); o.write(b); }
            case STRING -> o.writeUTF((String) t.v);
            case LIST -> {
                List<Tag> l = t.list();
                o.writeByte(l.isEmpty() ? END : t.elem);
                o.writeInt(l.size());
                for (Tag e : l) out(o, e);
            }
            case COMPOUND -> {
                for (Map.Entry<String, Tag> e : t.map().entrySet()) {
                    o.writeByte(e.getValue().type);
                    o.writeUTF(e.getKey());
                    out(o, e.getValue());
                }
                o.writeByte(END);
            }
            case INT_ARRAY -> { int[] a = (int[]) t.v; o.writeInt(a.length); for (int x : a) o.writeInt(x); }
            case LONG_ARRAY -> { long[] a = (long[]) t.v; o.writeInt(a.length); for (long x : a) o.writeLong(x); }
            default -> throw new IOException("Unknown NBT tag type " + t.type);
        }
    }

    /** A readable outline of a tree, for debugging and tests. */
    static void dump(Tag t, String name, String pad, int maxDepth, StringBuilder sb) {
        String[] names = {"end", "byte", "short", "int", "long", "float", "double", "byte[]", "string", "list", "compound", "int[]", "long[]"};
        sb.append(pad).append(name).append(" : ").append(names[t.type]);
        if (t.type == COMPOUND) {
            sb.append(" {").append(t.map().size()).append("}\n");
            if (maxDepth > 0) for (Map.Entry<String, Tag> e : t.map().entrySet()) dump(e.getValue(), e.getKey(), pad + "  ", maxDepth - 1, sb);
        } else if (t.type == LIST) {
            sb.append(" [").append(t.list().size()).append(" x ").append(t.elem == 0 ? "?" : names[t.elem]).append("]\n");
            if (maxDepth > 0 && !t.list().isEmpty()) dump(t.list().get(0), "[0]", pad + "  ", maxDepth - 1, sb);
        } else if (t.v instanceof byte[] || t.v instanceof int[] || t.v instanceof long[]) sb.append(" (array)\n");
        else sb.append(" = ").append(t.v).append('\n');
    }
}
