package io.github.mindbound.opengpu.core.device;

import java.util.Locale;

import io.github.mindbound.opengpu.core.Handles;
import io.github.mindbound.opengpu.core.SurfaceDesc;
import io.github.mindbound.opengpu.core.cmd.Formats;
import io.github.mindbound.opengpu.core.cmd.HandleTypes;

/**
 * The texture type's handle table (28 §6.1): 2 048 slots, a FIFO free queue, per-slot generations and the live
 * textures' sizes and formats, all in {@code int} arrays indexed by slot, so the lookups never allocate or box (28:287;
 * S6 G20) and never throw for any int. A free slot's entries are all 0, and a live handle is never 0. Not
 * thread-safe: the device's monitor guards it.
 */
final class TextureTable {

    static final int SLOTS = Handles.MAX_SLOT + 1;

    private final int[] liveHandle = new int[SLOTS];
    private final int[] liveFormat = new int[SLOTS];
    private final int[] liveWidth = new int[SLOTS];
    private final int[] liveHeight = new int[SLOTS];
    private final int[] generation = new int[SLOTS];
    /** The free queue as a ring: {@code count} slots from {@code head}. */
    private final int[] queue = new int[SLOTS];
    private int head;
    private int count;
    private int liveCount;

    /** The FIFO is 0, 1, ..., 2 047; every generation 0. */
    TextureTable() {
        this(SLOTS);
    }

    /**
     * The FIFO is 0 to slots − 1 only (the generation-wrap test); the arrays are 2 048 long whatever slots is.
     *
     * @throws IllegalArgumentException for slots outside 1-2 048
     */
    TextureTable(int slots) {
        if (slots < 1 || slots > SLOTS) {
            throw new IllegalArgumentException("slots " + slots + " is outside 1.." + SLOTS);
        }
        for (int slot = 0; slot < slots; slot++) {
            queue[slot] = slot;
        }
        count = slots;
    }

    /**
     * Takes the queue's head slot and makes it live with its current generation: a type-1 handle (28 §6.1).
     *
     * @throws IllegalArgumentException for a side outside 1-{@link SurfaceDesc#MAX_SIDE} or an undefined format
     * @throws IllegalStateException    if the queue is empty
     */
    int allocate(int w, int h, int format) {
        if (w < 1 || w > SurfaceDesc.MAX_SIDE || h < 1 || h > SurfaceDesc.MAX_SIDE || !Formats.isDefined(format)) {
            throw new IllegalArgumentException("texture " + w + "x" + h + " format " + format);
        }
        if (count == 0) {
            throw new IllegalStateException("no free texture slot");
        }
        int slot = queue[head];
        head = (head + 1) % SLOTS;
        count--;
        int handle = Handles.pack(HandleTypes.TEXTURE, slot, generation[slot]);
        liveHandle[slot] = handle;
        liveFormat[slot] = format;
        liveWidth[slot] = w;
        liveHeight[slot] = h;
        liveCount++;
        return handle;
    }

    /**
     * Frees the exactly live handle: the slot's generation becomes (g + 1) &amp; 0xFFFF, its entries 0, and the slot
     * goes to the queue's tail (28 §6.1).
     *
     * @throws IllegalStateException unless the handle is live
     */
    void free(int handle) {
        int slot = Handles.slot(handle);
        if (handle == 0 || liveHandle[slot] != handle) {
            throw new IllegalStateException(String.format(Locale.ROOT, "0x%08X is not live", handle));
        }
        liveHandle[slot] = 0;
        liveFormat[slot] = 0;
        liveWidth[slot] = 0;
        liveHeight[slot] = 0;
        generation[slot] = (generation[slot] + 1) & Handles.MAX_GENERATION;
        queue[(head + count) % SLOTS] = slot;
        count++;
        liveCount--;
    }

    /** Frees every live slot in ascending slot order, each as {@link #free} does (28:1061, S6's reset order). */
    void freeAllAscending() {
        for (int slot = 0; slot < SLOTS; slot++) {
            if (liveHandle[slot] != 0) {
                free(liveHandle[slot]);
            }
        }
    }

    /** The format of the live texture with exactly this handle, or 0; any int, no allocation. */
    int format(int handle) {
        int slot = Handles.slot(handle);
        return liveHandle[slot] == handle ? liveFormat[slot] : 0;
    }

    /** Its width, or 0 unless exactly live. */
    int width(int handle) {
        int slot = Handles.slot(handle);
        return liveHandle[slot] == handle ? liveWidth[slot] : 0;
    }

    /** Its height, or 0 unless exactly live. */
    int height(int handle) {
        int slot = Handles.slot(handle);
        return liveHandle[slot] == handle ? liveHeight[slot] : 0;
    }

    /** Its w·h·bpp, or 0 unless exactly live. */
    int byteSize(int handle) {
        int format = format(handle);
        return format == 0 ? 0 : width(handle) * height(handle) * Formats.bytesPerPixel(format);
    }

    int liveCount() {
        return liveCount;
    }

    /** The live handles, ascending by slot. */
    int[] liveHandles() {
        int[] handles = new int[liveCount];
        int i = 0;
        for (int slot = 0; slot < SLOTS; slot++) {
            if (liveHandle[slot] != 0) {
                handles[i++] = liveHandle[slot];
            }
        }
        return handles;
    }

    /** The free queue, head first. */
    int[] freeSlots() {
        int[] slots = new int[count];
        for (int i = 0; i < count; i++) {
            slots[i] = queue[(head + i) % SLOTS];
        }
        return slots;
    }

    /** A copy of the generations, 2 048 entries by slot. */
    int[] generations() {
        return generation.clone();
    }
}
