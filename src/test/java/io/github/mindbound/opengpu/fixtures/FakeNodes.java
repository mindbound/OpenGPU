package io.github.mindbound.opengpu.fixtures;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.nbt.NBTTagCompound;

import io.github.mindbound.opengpu.card.NodeFactory;
import li.cil.oc.api.network.Component;
import li.cil.oc.api.network.Environment;
import li.cil.oc.api.network.Message;
import li.cil.oc.api.network.Network;
import li.cil.oc.api.network.Node;
import li.cil.oc.api.network.Visibility;

/**
 * OC's nodes for tests in a JVM where OC does not run (S11 plan P15.2; amendments B4): a {@link NodeFactory} whose
 * components are {@link Proxy} fakes, with one fake network that a test puts nodes in and takes them out of, and
 * neighbour pairs the test declares. OC's real nodes cannot be built here, since their builders reach
 * {@code SideTracker} and so FML (S11 spike §4). Nothing here connects or disconnects by itself: a test drives an
 * environment's {@code onConnect}, {@code onDisconnect} and {@code onMessage} in the order OC would
 * (Network.scala:31-35, :250-260, :349-373).
 * <p>
 * A fake answers {@code address}, {@code host}, {@code reachability}, {@code name}, {@code visibility},
 * {@code network} (the fake network while it is in it, else null), {@code isNeighborOf}, {@code canBeReachedFrom}
 * and {@code canBeSeenFrom} (true for the same network), {@code load} (nothing), {@code save} (its address, as OC's
 * node saves it), {@code remove} (it leaves the network) and {@code sendToReachable}, which {@link #sent()} records
 * while the node is in the network and drops otherwise, as OC's node does (Node.scala:102-103; S11 plan U5.9); every
 * other member throws, so a test sees what it did not model. The network answers {@code node(address)} and
 * {@code nodes()}. Addresses are "&lt;name&gt;-&lt;n&gt;", n from 1 per factory, and "machine-&lt;n&gt;" for
 * {@link #machine()}, until {@link #readdress} changes one. Not thread-safe: the server thread's.
 */
public final class FakeNodes implements NodeFactory {

    private final Map<String, Node> network = new LinkedHashMap<>();
    private final Set<String> neighbours = new HashSet<>();
    private final List<String> requests = new ArrayList<>();
    private final List<Sent> sent = new ArrayList<>();
    private final Network networkProxy = proxy(Network.class, this::networkMethod);
    /** Each fake's address, in a holder that {@link #readdress} changes. */
    private final Map<Node, String[]> addresses = new IdentityHashMap<>();
    private int made;

    /** Records the request as "&lt;host's simple class name&gt; &lt;reachability&gt; &lt;name&gt;". */
    @Override
    public Component component(Environment host, Visibility reachability, String name) {
        requests.add(
            host.getClass()
                .getSimpleName() + " "
                + reachability
                + " "
                + name);
        return fake(Component.class, host, reachability, name, name + "-" + ++made);
    }

    /** A plain node with no host, a machine's, to be the source of {@code computer.started} and the like. */
    public Node machine() {
        return fake(Node.class, null, Visibility.Network, null, "machine-" + ++made);
    }

    /** Puts the nodes in the network. */
    public void join(Node... nodes) {
        for (Node node : nodes) {
            network.put(node.address(), node);
        }
    }

    /** Takes the node out of the network. */
    public void leave(Node node) {
        network.remove(node.address(), node);
    }

    /**
     * Gives the fake a new address, as OC re-addresses a node that joins a network holding its address already
     * (Network.scala:226-229, :276-300): OC does it while the node is out of the network, so take it out first.
     *
     * @throws IllegalArgumentException for a node this factory did not make
     */
    public void readdress(Node node, String address) {
        String[] holder = addresses.get(node);
        if (holder == null) {
            throw new IllegalArgumentException("not a fake of this factory: " + node);
        }
        holder[0] = address;
    }

    /** Makes a and b each other's neighbours. */
    public void neighbours(Node a, Node b) {
        neighbours.add(pair(a, b));
    }

    /** The factory's requests so far, in order. */
    public List<String> requests() {
        return new ArrayList<>(requests);
    }

    /** The messages the fakes sent to every node they reach, in order. */
    public List<Sent> sent() {
        return new ArrayList<>(sent);
    }

    /** One {@code sendToReachable(name, data...)} of a fake in the network. */
    public static final class Sent {

        /** The sending node. */
        public final Node source;
        /** The message's name. */
        public final String name;
        /** The message's values, in order; nulls kept. */
        public final List<Object> data;

        Sent(Node source, String name, Object[] data) {
            this.source = source;
            this.name = name;
            this.data = Collections.unmodifiableList(new ArrayList<>(Arrays.asList(data)));
        }

        @Override
        public String toString() {
            return "Sent[" + source + " " + name + " " + data + "]";
        }
    }

    /** OC's message {@code name} from {@code source}, with no data. */
    public static Message message(String name, Node source) {
        return proxy(Message.class, (self, method, args) -> {
            switch (method.getName()) {
                case "name":
                    return name;
                case "source":
                    return source;
                case "data":
                    return new Object[0];
                case "cancel":
                    return null;
                default:
                    return objectMethod(self, method.getName(), args, "FakeMessage[" + name + "]");
            }
        });
    }

    private <T extends Node> T fake(Class<T> type, Environment host, Visibility reachability, String name,
        String first) {
        String[] at = { first };
        T fake = proxy(type, (self, method, args) -> {
            String address = at[0];
            switch (method.getName()) {
                case "address":
                    return address;
                case "host":
                    return host;
                case "reachability":
                    return reachability;
                case "name":
                    return name;
                case "visibility":
                    return reachability;
                case "network":
                    return network.get(address) == self ? networkProxy : null;
                case "isNeighborOf":
                    return neighbours.contains(pair((Node) self, (Node) args[0]));
                case "canBeReachedFrom":
                case "canBeSeenFrom":
                    return network.get(address) == self && network.containsValue(args[0]);
                case "load":
                    return null;
                case "save":
                    ((NBTTagCompound) args[0]).setString("address", address);
                    return null;
                case "remove":
                    network.remove(address, self);
                    return null;
                case "sendToReachable":
                    if (network.get(address) == self) {
                        sent.add(new Sent((Node) self, (String) args[0], (Object[]) args[1]));
                    }
                    return null;
                default:
                    return objectMethod(
                        self,
                        method.getName(),
                        args,
                        "Fake" + type.getSimpleName() + "[" + address + "]");
            }
        });
        addresses.put(fake, at);
        return fake;
    }

    private Object networkMethod(Object self, java.lang.reflect.Method method, Object[] args) {
        switch (method.getName()) {
            case "node":
                return network.get((String) args[0]);
            case "nodes":
                return Collections.unmodifiableCollection(new ArrayList<>(network.values()));
            default:
                return objectMethod(self, method.getName(), args, "FakeNetwork" + network.keySet());
        }
    }

    private static String pair(Node a, Node b) {
        String x = a.address();
        String y = b.address();
        return x.compareTo(y) < 0 ? x + "|" + y : y + "|" + x;
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(FakeNodes.class.getClassLoader(), new Class<?>[] { type }, handler);
    }

    private static Object objectMethod(Object self, String name, Object[] args, String description) {
        switch (name) {
            case "toString":
                return description;
            case "hashCode":
                return System.identityHashCode(self);
            case "equals":
                return self == args[0];
            default:
                throw new UnsupportedOperationException(description + "." + name);
        }
    }
}
