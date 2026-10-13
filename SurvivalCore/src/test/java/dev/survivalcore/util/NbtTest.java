package dev.survivalcore.util;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NbtTest {

    @Test
    void roundTrip() throws Exception {
        Map<String, Nbt.Tag> root = new LinkedHashMap<>();
        root.put("id", new Nbt.Tag(Nbt.STRING, "minecraft:diamond"));
        root.put("count", new Nbt.Tag(Nbt.INT, 12));
        root.put("Slot", new Nbt.Tag(Nbt.BYTE, (byte) 30));
        root.put("arr", new Nbt.Tag(Nbt.INT_ARRAY, new int[]{1, 2, 3}));
        Map<String, Nbt.Tag> nested = new LinkedHashMap<>();
        nested.put("x", new Nbt.Tag(Nbt.DOUBLE, 1.5));
        root.put("components", new Nbt.Tag(Nbt.COMPOUND, nested));

        byte[] bytes = Nbt.writeCompressed(root);
        Nbt.Tag read = Nbt.readCompressed(new ByteArrayInputStream(bytes));
        Map<String, Nbt.Tag> r = read.compound();
        assertEquals("minecraft:diamond", r.get("id").value());
        assertEquals(12, r.get("count").asInt());
        assertEquals(30, r.get("Slot").asInt());
        assertArrayEquals(new int[]{1, 2, 3}, (int[]) r.get("arr").value());
        assertEquals(1.5, r.get("components").compound().get("x").value());
        // and it writes back identically
        assertArrayEquals(bytes, Nbt.writeCompressed(r));
    }
}
