package io.github.mindbound.opengpu.fixtures;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.util.IIcon;

/**
 * An icon register for tests of the client's icon members (S12 plan P9.3), which run with no texture atlas: it records
 * each name it is asked for and answers an {@link Icon} that knows only its name. Also the PNG behind an icon name,
 * read from the classpath as a texture atlas would find it (TextureMap.java:316-319, :381).
 */
public final class FakeIcons implements IIconRegister {

    private static final byte[] PNG_SIGNATURE = { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n' };

    private final List<String> names = new ArrayList<>();

    /** The names registered so far, in order. */
    public List<String> names() {
        return names;
    }

    @Override
    public IIcon registerIcon(String name) {
        names.add(Objects.requireNonNull(name, "name"));
        return new Icon(name);
    }

    /**
     * The classpath resource an atlas loads for {@code name}, "domain:path", in the atlas whose base is
     * {@code textures/<atlas>}: {@code /assets/<domain>/textures/<atlas>/<path>.png}.
     */
    public static String resource(String name, String atlas) {
        int colon = name.indexOf(':');
        return "/assets/" + name.substring(0, colon) + "/textures/" + atlas + "/" + name.substring(colon + 1) + ".png";
    }

    /**
     * The width and height in an icon's PNG header, after its signature and IHDR chunk are checked.
     *
     * @throws IOException for a missing resource, or one that is not a PNG
     */
    public static int[] pngSize(String name, String atlas) throws IOException {
        String resource = resource(name, atlas);
        try (InputStream stream = FakeIcons.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IOException("no " + resource + " on the classpath");
            }
            DataInputStream in = new DataInputStream(stream);
            byte[] signature = new byte[PNG_SIGNATURE.length];
            in.readFully(signature);
            if (!Arrays.equals(signature, PNG_SIGNATURE)) {
                throw new IOException(resource + " has no PNG signature");
            }
            int length = in.readInt();
            byte[] kind = new byte[4];
            in.readFully(kind);
            if (length != 13 || !"IHDR".equals(new String(kind, "US-ASCII"))) {
                throw new IOException(resource + " does not start with an IHDR chunk");
            }
            return new int[] { in.readInt(), in.readInt() };
        }
    }

    /** An icon that knows its name only; its texture coordinates are 0. */
    public static final class Icon implements IIcon {

        private final String name;

        Icon(String name) {
            this.name = name;
        }

        @Override
        public int getIconWidth() {
            return 0;
        }

        @Override
        public int getIconHeight() {
            return 0;
        }

        @Override
        public float getMinU() {
            return 0;
        }

        @Override
        public float getMaxU() {
            return 0;
        }

        @Override
        public float getInterpolatedU(double u) {
            return 0;
        }

        @Override
        public float getMinV() {
            return 0;
        }

        @Override
        public float getMaxV() {
            return 0;
        }

        @Override
        public float getInterpolatedV(double v) {
            return 0;
        }

        @Override
        public String getIconName() {
            return name;
        }

        @Override
        public String toString() {
            return "Icon[" + name + "]";
        }
    }
}
