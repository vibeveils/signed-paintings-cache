package com.signedpaintingscache.mixin;

import com.nettakrim.signed_paintings.util.ImageData;
import com.nettakrim.signed_paintings.util.ImageManager;
import com.signedpaintingscache.PaintingsCache;
import com.signedpaintingscache.cache.DeferredScale;
import com.signedpaintingscache.config.CacheConfig;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.awt.image.BufferedImage;
import java.util.concurrent.CompletableFuture;

@Mixin(ImageData.class)
public abstract class ImageDataMixin {
    private static final ThreadLocal<Boolean> SIGNED_PAINTINGS_CACHE$ADJUSTING = ThreadLocal.withInitial(() -> false);

    /**
     * LOD: Signed Paintings asks for a texture of (painting size × pixels-per-block). Shrink that by the
     * texture detail setting and max texture size, and skip enlarged copies of small images.
     * The sign-editing preview ("working" texture) is left exact.
     */
    @Inject(method = "getIdentifier", at = @At("HEAD"), cancellable = true, require = 0)
    private void signedPaintingsCache$lod(int width, int height, boolean working, CallbackInfoReturnable<Identifier> cir) {
        if (working || SIGNED_PAINTINGS_CACHE$ADJUSTING.get()) return;
        ImageData self = (ImageData) (Object) this;
        if (!self.ready || self.width <= 0 || self.height <= 0) return;

        CacheConfig config = PaintingsCache.config();
        int w = width;
        int h = height;

        if (config.reuseBaseForUpscales && w >= self.width && h >= self.height) {
            w = self.width;
            h = self.height;
        }

        if (config.textureDetailPercent < 100) {
            w = Math.max(1, Math.round(w * config.textureDetailPercent / 100f));
            h = Math.max(1, Math.round(h * config.textureDetailPercent / 100f));
        }

        if (config.maxTextureSize > 0) {
            int longest = Math.max(w, h);
            if (longest > config.maxTextureSize) {
                float f = (float) config.maxTextureSize / longest;
                w = Math.max(1, Math.round(w * f));
                h = Math.max(1, Math.round(h * f));
            }
        }

        if (w == width && h == height) return;

        SIGNED_PAINTINGS_CACHE$ADJUSTING.set(true);
        try {
            cir.setReturnValue(self.getIdentifier(w, h, false));
        } finally {
            SIGNED_PAINTINGS_CACHE$ADJUSTING.set(false);
        }
    }

    /**
     * Lag spikes: Signed Paintings resizes new painting variants on the render thread. Swap in a placeholder
     * here (2nd scaleImage call in getIdentifier = the non-preview branch)...
     */
    @Redirect(method = "getIdentifier",
            at = @At(value = "INVOKE",
                    target = "Lcom/nettakrim/signed_paintings/util/ImageManager;scaleImage(Ljava/awt/image/BufferedImage;II)Ljava/awt/image/BufferedImage;",
                    ordinal = 1),
            require = 0)
    private BufferedImage signedPaintingsCache$deferScale(BufferedImage source, int width, int height) {
        return new DeferredScale(source, width, height);
    }

    /** ...and do the real resize on a worker thread, right before the (already async) texture upload. */
    @Redirect(method = "getIdentifier",
            at = @At(value = "INVOKE",
                    target = "Lcom/nettakrim/signed_paintings/util/ImageManager;saveBufferedImageAsIdentifierAsync(Ljava/awt/image/BufferedImage;Lnet/minecraft/resources/Identifier;)Ljava/util/concurrent/CompletableFuture;"),
            require = 0)
    private CompletableFuture<Void> signedPaintingsCache$scaleOffThread(BufferedImage image, Identifier identifier) {
        if (!(image instanceof DeferredScale deferred)) {
            return ImageManager.saveBufferedImageAsIdentifierAsync(image, identifier);
        }
        return CompletableFuture.runAsync(() -> ImageManager.saveBufferedImageAsIdentifier(
                ImageManager.scaleImage(deferred.source, deferred.targetWidth, deferred.targetHeight), identifier),
                PaintingsCache.workers());
    }
}
