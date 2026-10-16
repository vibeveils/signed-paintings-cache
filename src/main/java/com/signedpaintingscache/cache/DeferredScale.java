package com.signedpaintingscache.cache;

import java.awt.image.BufferedImage;

/**
 * Placeholder handed back to Signed Paintings in place of a resized image, so the actual resize can
 * happen on a worker thread instead of the render thread (which is where Signed Paintings does it).
 * Only ever seen by our own redirect in ImageDataMixin.
 */
public final class DeferredScale extends BufferedImage {
    public final BufferedImage source;
    public final int targetWidth;
    public final int targetHeight;

    public DeferredScale(BufferedImage source, int targetWidth, int targetHeight) {
        super(1, 1, BufferedImage.TYPE_INT_ARGB);
        this.source = source;
        this.targetWidth = targetWidth;
        this.targetHeight = targetHeight;
    }
}
