package com.signedpaintingscache.mixin;

import com.nettakrim.signed_paintings.util.ImageManager;
import com.signedpaintingscache.PaintingsCache;
import com.signedpaintingscache.cache.ImageScaler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

@Mixin(ImageManager.class)
public abstract class ImageManagerMixin {
    @Shadow
    public abstract Set<String> getUrls();

    /**
     * The core hook: every painting image Signed Paintings loads goes through downloadImageBuffer.
     * We hand back our cached/deduplicated/rate-limited pipeline instead. Required: if Signed Paintings
     * renames this method the game should say so rather than silently caching nothing.
     */
    @Inject(method = "downloadImageBuffer", at = @At("HEAD"), cancellable = true)
    private void signedPaintingsCache$download(String url, CallbackInfoReturnable<CompletableFuture<BufferedImage>> cir) {
        cir.setReturnValue(PaintingsCache.fetcher().fetch(url));
    }

    /** Quality: smooth/bilinear/bicubic resizing instead of always nearest-neighbour. */
    @Inject(method = "scaleImage", at = @At("HEAD"), cancellable = true, require = 0)
    private static void signedPaintingsCache$scale(BufferedImage source, int width, int height, CallbackInfoReturnable<BufferedImage> cir) {
        BufferedImage scaled = ImageScaler.scale(source, width, height);
        if (scaled != null) cir.setReturnValue(scaled);
    }

    // Memory: Signed Paintings hard-codes how long unseen paintings keep their textures (1536 ticks)
    // and their decoded images (16384 ticks). Make both configurable.
    @ModifyConstant(method = "onTick", constant = @Constant(intValue = 1536), require = 0)
    private int signedPaintingsCache$vramExpiry(int original) {
        return PaintingsCache.config().vramExpiryTicks();
    }

    @ModifyConstant(method = "onTick", constant = @Constant(intValue = 16384), require = 0)
    private int signedPaintingsCache$ramExpiry(int original) {
        return PaintingsCache.config().ramExpiryTicks();
    }

    // Reloads clear our "failed recently" back-off, and user-requested reloads bypass cache freshness.
    @Inject(method = "reloadAll", at = @At("HEAD"), require = 0)
    private void signedPaintingsCache$reloadAll(CallbackInfoReturnable<Integer> cir) {
        PaintingsCache.onSignedPaintingsReload(getUrls());
        PaintingsCache.fetcher().clearFailures();
    }

    @Inject(method = "reloadUrl", at = @At("HEAD"), require = 0)
    private void signedPaintingsCache$reloadUrl(String url, CallbackInfoReturnable<Integer> cir) {
        PaintingsCache.onSignedPaintingsReload(List.of(url));
    }

    @Inject(method = "reloadDomain", at = @At("HEAD"), require = 0)
    private void signedPaintingsCache$reloadDomain(String domain, CallbackInfoReturnable<Integer> cir) {
        List<String> matching = new ArrayList<>();
        for (String url : getUrls()) {
            if (url.startsWith(domain)) matching.add(url);
        }
        PaintingsCache.onSignedPaintingsReload(matching);
    }
}
