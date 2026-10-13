package dev.survivalcore.util;

import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Just enough of Minecraft's NBT format to read other plugins' save files (BetterEnderChest) and
 * hand items to Paper. Compounds become {@code Map<String, Tag>}, lists {@code List<Tag>}.
 */
public final class Nbt {

    private Nbt() {}

    /** One NBT value together with its type id. */
    public record Tag(byte type, Object value) {
        @SuppressWarnings("unchecked")
        public Map<String, Tag> compound() {
            return (Map<String, Tag>) value;
        }

        public int asInt() {
            return ((Number) value).intValue();
        }
    }

    public static final byte END = 0, BYTE = 1, SHORT = 2, INT = 3, LONG = 4, FLOAT = 5, DOUBLE = 6,
            BYTE_ARRAY = 7, STRING = 8, LIST = 9, COMPOUND = 10, INT_ARRAY = 11, LONG_ARRAY = 12;

    /** Reads a gzip-compressed file whose root is a named compound. */
    public static Tag readCompressed(InputStream in) throws IOException {
        DataInputStream data = new DataInputStream(new GZIPInputStream(in));
        byte type = data.readByte();
        if (type != COMPOUND) throw new IOException("Root tag is not a compound");
        data.readUTF(); // root name
        return new Tag(COMPOUND, readPayload(data, COMPOUND, 0));
    }

    /** Writes a compound as gzip-compressed NBT with an empty root name. */
    public static byte[] writeCompressed(Map<String, Tag> root) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bytes))) {
            out.writeByte(COMPOUND);
            out.writeUTF("");
            writePayload(out, new Tag(COMPOUND, root));
        }
        return bytes.toByteArray();
    }

    /** A corrupt length could otherwise ask for gigabytes of memory. */
    private static int length(DataInput in) throws IOException {
        int n = in.readInt();
        if (n < 0 || n > 16_777_216) throw new IOException("Corrupt NBT (length " + n + ")");
        return n;
    }

    private static Object readPayload(DataInput in, byte type, int depth) throws IOException {
        if (depth > 512) throw new IOException("NBT nested too deep");
        switch (type) {
            case BYTE: return in.readByte();
            case SHORT: return in.readShort();
            case INT: return in.readInt();
            case LONG: return in.readLong();
            case FLOAT: return in.readFloat();
            case DOUBLE: return in.readDouble();
            case BYTE_ARRAY: {
                byte[] b = new byte[length(in)];
                in.readFully(b);
                return b;
            }
            case STRING: return in.readUTF();
            case LIST: {
                byte elementType = in.readByte();
                int length = length(in);
                List<Tag> list = new ArrayList<>(Math.max(0, length));
                for (int i = 0; i < length; i++) list.add(new Tag(elementType, readPayload(in, elementType, depth + 1)));
                return new ListValue(elementType, list);
            }
            case COMPOUND: {
                Map<String, Tag> map = new LinkedHashMap<>();
                while (true) {
                    byte t = in.readByte();
                    if (t == END) break;
                    String name = in.readUTF();
                    map.put(name, new Tag(t, readPayload(in, t, depth + 1)));
                }
                return map;
            }
            case INT_ARRAY: {
                int[] a = new int[length(in)];
                for (int i = 0; i < a.length; i++) a[i] = in.readInt();
                return a;
            }
            case LONG_ARRAY: {
                long[] a = new long[length(in)];
                for (int i = 0; i < a.length; i++) a[i] = in.readLong();
                return a;
            }
            default: throw new IOException("Unknown NBT tag type " + type);
        }
    }

    /** A list keeps its element type so empty lists can be written back. */
    private record ListValue(byte elementType, List<Tag> items) {}

    /** Lists read from a file; use this to get the elements. */
    public static List<Tag> items(Tag listTag) {
        Object v = listTag.value();
        if (v instanceof ListValue lv) return lv.items();
        if (v instanceof List<?> l) {
            @SuppressWarnings("unchecked") List<Tag> tags = (List<Tag>) l;
            return tags;
        }
        return List.of();
    }

    private static void writePayload(DataOutput out, Tag tag) throws IOException {
        Object v = tag.value();
        switch (tag.type()) {
            case BYTE -> out.writeByte((Byte) v);
            case SHORT -> out.writeShort((Short) v);
            case INT -> out.writeInt((Integer) v);
            case LONG -> out.writeLong((Long) v);
            case FLOAT -> out.writeFloat((Float) v);
            case DOUBLE -> out.writeDouble((Double) v);
            case BYTE_ARRAY -> {
                byte[] b = (byte[]) v;
                out.writeInt(b.length);
                out.write(b);
            }
            case STRING -> out.writeUTF((String) v);
            case LIST -> {
                ListValue lv = (ListValue) v;
                out.writeByte(lv.items().isEmpty() ? lv.elementType() : lv.items().get(0).type());
                out.writeInt(lv.items().size());
                for (Tag t : lv.items()) writePayload(out, t);
            }
            case COMPOUND -> {
                for (Map.Entry<String, Tag> e : tag.compound().entrySet()) {
                    out.writeByte(e.getValue().type());
                    out.writeUTF(e.getKey());
                    writePayload(out, e.getValue());
                }
                out.writeByte(END);
            }
            case INT_ARRAY -> {
                int[] a = (int[]) v;
                out.writeInt(a.length);
                for (int i : a) out.writeInt(i);
            }
            case LONG_ARRAY -> {
                long[] a = (long[]) v;
                out.writeInt(a.length);
                for (long l : a) out.writeLong(l);
            }
            default -> throw new IOException("Unknown NBT tag type " + tag.type());
        }
    }
}
