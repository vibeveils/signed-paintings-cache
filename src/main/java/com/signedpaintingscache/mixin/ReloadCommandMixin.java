package com.signedpaintingscache.mixin;

import com.mojang.brigadier.context.CommandContext;
import com.nettakrim.signed_paintings.commands.ReloadCommand;
import com.signedpaintingscache.PaintingsCache;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * /paintings:reload means "fetch this again", so while it runs, reloads skip the disk cache's freshness
 * check. Other reloads (disconnecting, our own option changes) still load straight from disk.
 */
@Mixin(ReloadCommand.class)
public abstract class ReloadCommandMixin {
    @Inject(method = {"reloadAll", "reload"}, at = @At("HEAD"), require = 0)
    private static void signedPaintingsCache$begin(CommandContext<FabricClientCommandSource> context, CallbackInfoReturnable<Integer> cir) {
        PaintingsCache.setUserReloadInProgress(true);
    }

    @Inject(method = {"reloadAll", "reload"}, at = @At("RETURN"), require = 0)
    private static void signedPaintingsCache$end(CommandContext<FabricClientCommandSource> context, CallbackInfoReturnable<Integer> cir) {
        PaintingsCache.setUserReloadInProgress(false);
    }
}
