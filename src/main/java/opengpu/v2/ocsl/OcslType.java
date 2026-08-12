package opengpu.v2.ocsl;

/**
 * The IR's value types. Ruthlessly small by design: {@code float}, {@code vec2/3/4}, and
 * {@code bool} for conditions only.
 *
 * There is no integer type and there never was one at this level — the IR "exposes no
 * integer-typed value to any op", and the loop counter reaches float arithmetic only through an
 * explicit charged {@code ITOF}. Keeping that true is what stops a future real integer type
 * inheriting an implicit coercion rule forever.
 */
public enum OcslType {
	FLOAT(1),
	VEC2(2),
	VEC3(3),
	VEC4(4),
	/** Conditions only. Produced by the comparison and boolean ops, consumed by SELECT. */
	BOOL(1);

	/** Components this type occupies in the VM's flat float[] frame. */
	public final int width;

	OcslType(int width) {
		this.width = width;
	}

	public boolean isVector() {
		return this == VEC2 || this == VEC3 || this == VEC4;
	}

	public boolean isNumeric() {
		return this != BOOL;
	}

	/** The vector type of the given width, or FLOAT at width 1. Null when there is none. */
	public static OcslType ofWidth(int w) {
		switch (w) {
			case 1: return FLOAT;
			case 2: return VEC2;
			case 3: return VEC3;
			case 4: return VEC4;
			default: return null;
		}
	}

	public String display() {
		return name().toLowerCase();
	}
}
