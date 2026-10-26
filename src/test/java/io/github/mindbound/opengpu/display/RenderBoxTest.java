package io.github.mindbound.opengpu.display;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import net.minecraft.network.play.server.S35PacketUpdateTileEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraftforge.common.util.ForgeDirection;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.github.mindbound.opengpu.fixtures.BareWorld;

/**
 * The display's render box (25 R-9, 25:461-467; S12 plan P8.7 with amendment I7; Q-S12-6 (a)): one box per tile
 * entity, the same instance from its first call on, whatever thread asks and whatever description comes later, the
 * block grown by 7 on every side. The tile entities are a client's, with no node.
 */
@Timeout(60)
class RenderBoxTest {

    private static DisplayTile at(DisplayTile display, int x, int y, int z) {
        display.xCoord = x;
        display.yCoord = y;
        display.zCoord = z;
        return display;
    }

    // I7: Celeritas' meshing threads ask for the box as well as the client thread, and every caller gets one instance
    @Test
    void theBoxIsTheSameInstanceOnEveryCallAndOnEveryThread() throws Exception {
        DisplayTile display = at(Displays.withoutNode(), 4, 70, 9);
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<AxisAlignedBB>> asked = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                asked.add(pool.submit(() -> {
                    go.await();
                    return display.getRenderBoundingBox();
                }));
            }
            go.countDown();
            AxisAlignedBB first = asked.get(0)
                .get();
            for (Future<AxisAlignedBB> box : asked) {
                Assertions.assertSame(first, box.get());
            }
            Assertions.assertSame(first, display.getRenderBoundingBox());
            Assertions.assertSame(first, display.getRenderBoundingBox());
        } finally {
            pool.shutdownNow();
        }
        Assertions.assertNotSame(
            display.getRenderBoundingBox(),
            at(Displays.withoutNode(), 4, 70, 9).getRenderBoundingBox(),
            "one box per tile entity");
    }

    // R-9: Angelica caches the box per instance, so a description that turns the display changes neither the box nor
    // its bounds
    @Test
    void theBoxDoesNotChangeAfterADescriptionWithAnotherFacing() {
        BareWorld remote = BareWorld.allocate(true);
        DisplayTile client = at(Displays.withoutNode(), -3, 12, 40);
        remote.put(client);
        AxisAlignedBB box = client.getRenderBoundingBox();
        String before = box.toString();
        DisplayTile server = at(Displays.withoutNode(), -3, 12, 40);
        server.place(DisplayShape.placed(ForgeDirection.UP, 2));
        client.onDataPacket(null, (S35PacketUpdateTileEntity) server.getDescriptionPacket());
        Assertions.assertEquals(ForgeDirection.UP, client.facing());
        Assertions.assertSame(box, client.getRenderBoundingBox());
        Assertions.assertEquals(
            before,
            client.getRenderBoundingBox()
                .toString());
    }

    // Q-S12-6 (a): 7 blocks past the block on every side, M1.5's default 8 x 6 wall around any of its blocks
    @Test
    void theBoxReachesSevenBlocksPastTheBlockOnEverySide() {
        Assertions.assertEquals(7, DisplayTile.RENDER_REACH);
        AxisAlignedBB box = at(Displays.withoutNode(), 10, 64, -20).getRenderBoundingBox();
        Assertions.assertArrayEquals(
            new double[] { 3, 57, -27, 18, 72, -12 },
            new double[] { box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ });
        Assertions.assertEquals(15, box.maxX - box.minX);
        Assertions.assertEquals(15, box.maxY - box.minY);
        Assertions.assertEquals(15, box.maxZ - box.minZ);
    }
}
