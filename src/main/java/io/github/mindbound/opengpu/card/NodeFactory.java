package io.github.mindbound.opengpu.card;

import li.cil.oc.api.Network;
import li.cil.oc.api.detail.Builder;
import li.cil.oc.api.network.Component;
import li.cil.oc.api.network.Environment;
import li.cil.oc.api.network.Visibility;

/**
 * Where OpenGPU's environments get their OC node (S11 plan P7.1, P15.2; amendments B4): OC's node builder in game, and
 * a test's fake in plain JUnit, where OC's network API is not set and {@code Network.newNode} returns null
 * (api/Network.java:183-185). The card asks for a {@link Visibility#Neighbors} component named {@code opengpu}, so only
 * the machine it sits in sees it (24:29), and the display for a {@link Visibility#Network} one named
 * {@code opengpu_screen} (09:52). Neither has a connector before M1's energy (24:135).
 */
public interface NodeFactory {

    /**
     * OC's builder: {@code Network.newNode(host, reachability).withComponent(name).create()}, a component visible as
     * far as it reaches, as OC's GPU builds its node without the connector (GraphicsCard.scala:32-36). Null while OC's
     * network API is not set, in plain tests (B4), and on a client, where OC's builders make no node.
     */
    NodeFactory OC = new NodeFactory() {

        @Override
        public Component component(Environment host, Visibility reachability, String name) {
            Builder.NodeBuilder node = Network.newNode(host, reachability);
            return node == null ? null
                : node.withComponent(name)
                    .create();
        }

        @Override
        public String toString() {
            return "NodeFactory.OC";
        }
    };

    /**
     * A new component node for {@code host}, reaching and visible as {@code reachability}, named {@code name}; null
     * when no node can be made.
     */
    Component component(Environment host, Visibility reachability, String name);
}
