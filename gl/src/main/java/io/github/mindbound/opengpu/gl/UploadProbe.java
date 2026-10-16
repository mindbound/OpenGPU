package io.github.mindbound.opengpu.gl;

/**
 * G13b's hook (28 §6.7; H43): one call per texture upload, a create's or an update's, on the GL thread right after the
 * call. M0's measurements (RM:43) record the table from it; {@link #NONE} records nothing.
 */
public interface UploadProbe {

    /**
     * @param handle     the texture's full handle
     * @param bytes      the payload's bytes, w·h·bpp
     * @param stageNanos the staging copy's render-thread time: from the host's {@code staging(bytes)} to the upload,
     *                   when the upload's texels are that staging buffer, else 0
     * @param callNanos  the render-thread time of the {@code glTexImage2D} or {@code glTexSubImage2D} call
     */
    void upload(long session, int deviceId, int handle, int bytes, long stageNanos, long callNanos);

    /** Records nothing. */
    UploadProbe NONE = new UploadProbe() {

        @Override
        public void upload(long session, int deviceId, int handle, int bytes, long stageNanos, long callNanos) {}

        @Override
        public String toString() {
            return "UploadProbe.NONE";
        }
    };
}
