package io.github.mindbound.opengpu.card;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import io.github.mindbound.opengpu.core.render.FakeRenderHost;
import io.github.mindbound.opengpu.display.DisplayTile;
import io.github.mindbound.opengpu.fixtures.FakeNodes;
import io.github.mindbound.opengpu.fixtures.OcCalls;
import io.github.mindbound.opengpu.fixtures.RecordingContext;

/**
 * Cards for tests of other packages, through {@link CardRig}'s package-private session (S11 amendments B1), as
 * {@code Displays} and {@code TestSessions} are doors to theirs: one rig's FakeNodes network, a FakeRenderHost on the
 * oracle, and cards bound to displays through OC's own dispatch of {@code bind}, as Lua binds them (S11 plan U5.9).
 */
public final class Cards {

    private final CardRig rig = new CardRig();

    /** The rig's node factory and network, in which displays join beside the cards. */
    public FakeNodes nodes() {
        return rig.nodes;
    }

    /** The rig's host, which maps the displays. */
    public FakeRenderHost host() {
        return rig.host;
    }

    /** A card of OpenGPU's tier, 2 or 3, whose node has joined the network as OC joins a node. */
    public CardEnvironment joined(int tier) {
        return rig.joined(tier);
    }

    /**
     * {@code card.bind(display's id)} through OC's dispatch and argument reader, the id a Lua string, which reaches
     * Java as bytes (00:85).
     *
     * @throws IllegalStateException when bind does not answer true
     */
    public static void bind(CardEnvironment card, DisplayTile display) throws Exception {
        Object[] answer = OcCalls.call(
            card,
            "bind",
            new RecordingContext(),
            (Object) display.displayId()
                .getBytes(StandardCharsets.UTF_8));
        if (!Arrays.equals(new Object[] { Boolean.TRUE }, answer)) {
            throw new IllegalStateException("bind answered " + Arrays.toString(answer));
        }
    }

    /**
     * The card leaves the network and hears its own disconnect, as when it is removed or its case is broken: it
     * releases its device and detaches its display (P7.4; B2).
     */
    public void release(CardEnvironment card) {
        rig.nodes.leave(card.node());
        card.onDisconnect(card.node());
    }
}
