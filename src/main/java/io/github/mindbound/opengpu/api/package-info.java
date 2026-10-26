/**
 * OpenGPU's public API, published as the -api jar: component interfaces, caps and status constants, and the
 * command-format version and opcodes (docs/research/24-architecture-gl-first.md §3.1).
 * <p>
 * The API invariants (26 G2, 26 §1.3; 24 §3.3, 24:133; RM:38, :257). Every callback, signal and op of this API keeps
 * them, in M0 and in every later version; they are text from M0 on, and the API dump lints them at the M4 freeze.
 * <ol>
 * <li>No callback waits on, or returns data from, the host GPU, except state the server already holds: the confirmed
 * pixels and the cached {@code hostRenderer}. Any future host-derived value is a signal, never a return value.</li>
 * <li>No {@code barrier}, {@code sync} or {@code fence} in graphics or compute. Ordering is per-card submission order
 * plus binding access flags, and v2's {@code barrier()} is workgroup-internal only.</li>
 * <li>Compute reaches graphics only through ops in the same device tail.</li>
 * </ol>
 */
package io.github.mindbound.opengpu.api;
