package opengpu.v2.ocsl;

/**
 * A texture the CPU VM can sample: RGBA8, row-major, top-left origin.
 *
 * THE SAME BYTES THE CLIENT UPLOADS, deliberately. That layout is not chosen here — it is the
 * mutable-texture wire format ({@code w*h*4 bytes RGBA, row-major, top-left origin}), and reusing
 * it means there is no second representation for a texture and therefore no conversion step whose
 * rounding would have to be pinned as well.
 *
 * Holds the array by reference rather than copying. A texture is written by region deltas on a
 * different path and re-read here per evaluation; copying per bind would cost more than the whole
 * sample, and the VM never writes through it.
 *
 * THE ALIASING IS REAL AND THE CALLER OWNS IT. "The VM never writes through it" is true and not
 * sufficient, so the two things it does not cover are stated here. A concurrent write to the array
 * can TEAR A SINGLE TEXEL across channels, because {@link OcslMath#sample} re-reads the byte array
 * once per component — producing a colour that existed in neither version. And {@code final} safely
 * publishes the REFERENCE, not later writes to the array's contents: there is no happens-before
 * between the delta-application path and a sampling thread, so a sampler may see stale or partial
 * bytes indefinitely. Like {@link OcslVm}, this class is therefore NOT thread-safe: the host must
 * apply texture writes on the same thread that evaluates, or hand the VM a snapshot.
 */
public final class OcslTexture {

	public final int width;
	public final int height;
	/** {@code width * height * 4} bytes, RGBA, row-major from the top-left. */
	public final byte[] rgba;

	public OcslTexture(int width, int height, byte[] rgba) {
		if (width < 1 || height < 1) {
			throw new IllegalArgumentException("texture is " + width + "x" + height);
		}
		// LONG arithmetic. `width * height * 4` in int overflows well inside plausible dimensions:
		// 65536x65536 computes to exactly 0, so the check passed a 16-byte array for a texture the
		// sampler then indexed at -131076. The refusal message was wrong too, quoting an overflowed
		// byte count. This lesson already existed in the codebase -- Delta.java carries the same
		// note on the same product -- and was not applied here.
		long needed = (long) width * height * 4L;
		if (rgba == null || rgba.length < needed) {
			throw new IllegalArgumentException("a " + width + "x" + height + " RGBA texture needs "
					+ needed + " bytes, got "
					+ (rgba == null ? "null" : String.valueOf(rgba.length)));
		}
		this.width = width;
		this.height = height;
		this.rgba = rgba;
	}
}
