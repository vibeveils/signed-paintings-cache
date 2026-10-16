package com.signedpaintingscache.gui;

import com.mojang.brigadier.context.CommandContext;
import com.signedpaintingscache.PaintingsCache;
import com.signedpaintingscache.cache.DiskCache;
import com.signedpaintingscache.cache.PaintingFetcher;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * /paintingscache          open the options screen
 * /paintingscache stats    cache size and this session's hit/download counts
 * /paintingscache clear    delete everything in the disk cache
 * /paintingscache refresh  re-check every loaded painting with its server
 */
public final class CacheCommand {
    private static final String K = PaintingsCache.MODID + ".command.";

    private CacheCommand() {}

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                ClientCommands.literal("paintingscache")
                        .executes(CacheCommand::open)
                        .then(ClientCommands.literal("stats").executes(CacheCommand::stats))
                        .then(ClientCommands.literal("clear").executes(CacheCommand::clear))
                        .then(ClientCommands.literal("refresh").executes(CacheCommand::refresh))
        ));
    }

    private static int open(CommandContext<FabricClientCommandSource> context) {
        // The chat screen closes after a command runs; open ours on the next frame (same trick Signed Paintings uses).
        Minecraft client = Minecraft.getInstance();
        client.schedule(() -> client.setScreenAndShow(new CacheConfigScreen(null)));
        return 1;
    }

    private static int stats(CommandContext<FabricClientCommandSource> context) {
        DiskCache disk = PaintingsCache.disk();
        PaintingFetcher f = PaintingsCache.fetcher();
        context.getSource().sendFeedback(Component.translatable(K + "stats.disk",
                disk.entryCount(), DiskCache.formatBytes(disk.sizeBytes())));
        context.getSource().sendFeedback(Component.translatable(K + "stats.session",
                f.diskHits.get(), DiskCache.formatBytes(f.bytesFromDisk.get()),
                f.downloads.get(), DiskCache.formatBytes(f.bytesDownloaded.get()),
                f.notModified.get(), f.staleFallbacks.get()));
        context.getSource().sendFeedback(Component.translatable(K + "stats.failures",
                f.failures.get(), f.failedCount(), f.suppressedRetries.get()));
        return 1;
    }

    private static int clear(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        PaintingsCache.workers().execute(() -> {
            int removed = PaintingsCache.disk().clear();
            Minecraft.getInstance().execute(() -> source.sendFeedback(Component.translatable(K + "cleared", removed)));
        });
        return 1;
    }

    private static int refresh(CommandContext<FabricClientCommandSource> context) {
        int count = PaintingsCache.refreshAll();
        context.getSource().sendFeedback(Component.translatable(K + "refreshed", count));
        return 1;
    }
}
