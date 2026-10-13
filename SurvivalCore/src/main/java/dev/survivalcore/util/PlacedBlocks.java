package dev.survivalcore.util;

import org.bukkit.Chunk;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.Arrays;

/**
 * Remembers which blocks of some kind were placed by players (or otherwise made by hand), stored in
 * each chunk's own data so it's saved with the world. Kept sorted for fast lookups.
 */
public final class PlacedBlocks {

    private final NamespacedKey key;

    public PlacedBlocks(NamespacedKey key) {
        this.key = key;
    }

    private static long pack(Block b) {
        return ((long) (b.getX() & 15) << 40) | ((long) (b.getZ() & 15) << 32) | (b.getY() & 0xFFFFFFFFL);
    }

    private long[] read(Chunk chunk) {
        long[] data = chunk.getPersistentDataContainer().get(key, PersistentDataType.LONG_ARRAY);
        return data == null ? new long[0] : data;
    }

    public boolean contains(Block block) {
        return Arrays.binarySearch(read(block.getChunk()), pack(block)) >= 0;
    }

    public void set(Block block, boolean placed) {
        Chunk chunk = block.getChunk();
        PersistentDataContainer pdc = chunk.getPersistentDataContainer();
        long[] current = read(chunk);
        long k = pack(block);
        int index = Arrays.binarySearch(current, k);
        if (placed && index < 0) {
            int at = -index - 1;
            long[] next = new long[current.length + 1];
            System.arraycopy(current, 0, next, 0, at);
            next[at] = k;
            System.arraycopy(current, at, next, at + 1, current.length - at);
            pdc.set(key, PersistentDataType.LONG_ARRAY, next);
        } else if (!placed && index >= 0) {
            long[] next = new long[current.length - 1];
            System.arraycopy(current, 0, next, 0, index);
            System.arraycopy(current, index + 1, next, index, current.length - index - 1);
            if (next.length == 0) pdc.remove(key);
            else pdc.set(key, PersistentDataType.LONG_ARRAY, next);
        }
    }
}
