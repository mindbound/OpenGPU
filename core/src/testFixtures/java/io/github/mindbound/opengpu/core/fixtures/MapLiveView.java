package io.github.mindbound.opengpu.core.fixtures;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import io.github.mindbound.opengpu.core.Handles;
import io.github.mindbound.opengpu.core.SurfaceDesc;
import io.github.mindbound.opengpu.core.cmd.Formats;
import io.github.mindbound.opengpu.core.cmd.FrameLimits;
import io.github.mindbound.opengpu.core.cmd.HandleTypes;
import io.github.mindbound.opengpu.core.cmd.LiveView;

/**
 * A test live view (28 §2.2): a surface, its caps and a handle table in a map. {@link #create} allocates texture
 * handles as 28 §6.1 does, so a new view's first texture is {@code 0x08000000}; {@link #put} enters any handle the
 * test needs, such as a texture of the other format for {@code WRONG_FORMAT} (28 §3.6 row 19). It boxes on every
 * lookup, which a test view may do; the allocation test and the bench use their own array-backed views, since S7's
 * {@code Device} must not allocate (28 §3.5). The callbacks' caps (256 live textures, VRAM, 28 §6.2) are S7's and not
 * checked here. Not thread-safe.
 */
public final class MapLiveView implements LiveView {

    /** Texture sides {@code createTexture} accepts (28 §6.2). */
    public static final int MAX_TEXTURE_SIDE = 1024;

    private static final int SLOTS = Handles.MAX_SLOT + 1;

    private final SurfaceDesc surface;
    private final FrameLimits limits;
    /** Live handle to {w, h, format}, in the order the handles became live. */
    private final Map<Integer, int[]> live = new LinkedHashMap<>();
    /** 28 §6.1: the texture type's FIFO free queue, initially 0-2 047 in order. */
    private final ArrayDeque<Integer> freeSlots = new ArrayDeque<>();
    private final int[] generation = new int[SLOTS];
    /** The handle {@link #create} gave each slot while it is allocated, else 0. */
    private final int[] allocated = new int[SLOTS];

    /** @throws NullPointerException for a null surface or null limits */
    public MapLiveView(SurfaceDesc surface, FrameLimits limits) {
        if (surface == null || limits == null) {
            throw new NullPointerException("surface and limits");
        }
        this.surface = surface;
        this.limits = limits;
        for (int slot = 0; slot < SLOTS; slot++) {
            freeSlots.addLast(slot);
        }
    }

    /**
     * Allocates a texture as {@code createTexture} does (28 §6.1, §6.2): type 1, the slot at the head of the FIFO free
     * queue and that slot's current generation, in the surface's format, so a new view's first handle is
     * {@code 0x08000000}.
     *
     * @return the new handle
     * @throws IllegalArgumentException for a side outside 1-1 024
     * @throws IllegalStateException    if all 2 048 slots are allocated, or the new handle is already live through
     *                                  {@link #put}
     */
    public int create(int w, int h) {
        if (w < 1 || w > MAX_TEXTURE_SIDE || h < 1 || h > MAX_TEXTURE_SIDE) {
            throw new IllegalArgumentException("texture " + w + "x" + h + " has a side outside 1.." + MAX_TEXTURE_SIDE);
        }
        Integer slot = freeSlots.peekFirst();
        if (slot == null) {
            throw new IllegalStateException("all " + SLOTS + " texture slots are allocated");
        }
        int handle = Handles.pack(HandleTypes.TEXTURE, slot, generation[slot]);
        if (live.containsKey(handle)) {
            throw new IllegalStateException(hex(handle) + " is already live through put()");
        }
        freeSlots.removeFirst();
        allocated[slot] = handle;
        live.put(handle, new int[] { w, h, surface.format });
        return handle;
    }

    /**
     * Enters any handle as live with the given size and format, replacing an entry it may already have; the allocator
     * is not touched. The handle may be of any type: the validator asks the view only about well-formed type-1
     * handles (28 §3.6 rows 15-18), so another type is still {@code WRONG_TYPE}.
     *
     * @throws IllegalArgumentException for a side outside 1-{@link SurfaceDesc#MAX_SIDE}, or format 0, which
     *                                  {@link #textureFormat} reserves for "not live"
     */
    public void put(int handle, int w, int h, int format) {
        if (w < 1 || w > SurfaceDesc.MAX_SIDE || h < 1 || h > SurfaceDesc.MAX_SIDE) {
            throw new IllegalArgumentException(
                "texture " + w + "x" + h + " has a side outside 1.." + SurfaceDesc.MAX_SIDE);
        }
        if (format == Formats.INVALID) {
            throw new IllegalArgumentException("format 0 means not live");
        }
        live.put(handle, new int[] { w, h, format });
    }

    /**
     * Makes the handle not live. A handle {@link #create} gave out also frees its slot as 28 §6.1 does: the slot's
     * generation grows by 1, wrapping 65 535 to 0, and the slot goes to the queue's tail. A {@link #put} entry is only
     * removed.
     *
     * @throws IllegalStateException if the handle is not live
     */
    public void free(int handle) {
        if (live.remove(handle) == null) {
            throw new IllegalStateException(hex(handle) + " is not live");
        }
        if (Handles.type(handle) == HandleTypes.TEXTURE) {
            int slot = Handles.slot(handle);
            if (allocated[slot] == handle) {
                allocated[slot] = 0;
                generation[slot] = (generation[slot] + 1) & Handles.MAX_GENERATION;
                freeSlots.addLast(slot);
            }
        }
    }

    /**
     * A {@code computer.stopped}/{@code started} reset (28 §2.2, §6.1): every live handle freed as {@link #free} does,
     * in ascending slot order, each slot going to the free queue's tail after the slots already queued; then the
     * {@link #put} entries are dropped. 28 §6.1 leaves the order open; S7's device frees in this order too, so the
     * creates after a reset give the same handles in both. Generations are kept.
     */
    public void clear() {
        for (int slot = 0; slot < SLOTS; slot++) {
            if (allocated[slot] != 0) {
                free(allocated[slot]);
            }
        }
        live.clear();
    }

    /** A copy of the live handles, in the order they became live (for the naive validator's bridge). */
    public Set<Integer> handles() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(live.keySet()));
    }

    /** Whether the exact handle (slot and generation) is live. */
    public boolean isLive(int handle) {
        return live.containsKey(handle);
    }

    public int liveCount() {
        return live.size();
    }

    @Override
    public SurfaceDesc surface() {
        return surface;
    }

    @Override
    public FrameLimits limits() {
        return limits;
    }

    /** The live entry's format, or 0 if the exact handle is not live. */
    @Override
    public int textureFormat(int handle) {
        int[] entry = live.get(handle);
        return entry == null ? 0 : entry[2];
    }

    /** The live entry's width, or 0 if the handle is not live (the validator never asks then). */
    @Override
    public int textureWidth(int handle) {
        int[] entry = live.get(handle);
        return entry == null ? 0 : entry[0];
    }

    /** The live entry's height, or 0 if the handle is not live (the validator never asks then). */
    @Override
    public int textureHeight(int handle) {
        int[] entry = live.get(handle);
        return entry == null ? 0 : entry[1];
    }

    @Override
    public String toString() {
        return "MapLiveView[" + surface + ", " + limits + ", " + live.size() + " live]";
    }

    private static String hex(int handle) {
        return String.format(Locale.ROOT, "handle 0x%08X", handle);
    }
}
