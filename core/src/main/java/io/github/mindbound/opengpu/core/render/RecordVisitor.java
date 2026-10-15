package io.github.mindbound.opengpu.core.render;

/** One method per M0 record kind (28 §2.3), which {@link Record#accept} calls. */
public interface RecordVisitor {

    /** Kind 1, {@code FRAME}. */
    void frame(FrameRecord r);

    /** Kind 2, {@code TEX_CREATE}. */
    void texCreate(TexCreateRecord r);

    /** Kind 3, {@code TEX_UPDATE}. */
    void texUpdate(TexUpdateRecord r);

    /** Kind 4, {@code TEX_FREE}. */
    void texFree(TexFreeRecord r);

    /** Kind 9, {@code RESET}. */
    void reset(ResetRecord r);
}
