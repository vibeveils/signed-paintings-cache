package com.signedpaintingscache;

import com.nettakrim.signed_paintings.SignedPaintingsClient;
import com.signedpaintingscache.cache.DiskCache;
import com.signedpaintingscache.cache.PaintingFetcher;
import com.signedpaintingscache.config.CacheConfig;
import com.signedpaintingscache.gui.CacheCommand;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class PaintingsCache implements ClientModInitializer {
    public static final String MODID = "signed_paintings_cache";

    private static CacheConfig config;
    private static DiskCache disk;
    private static PaintingFetcher fetcher;
    private static ExecutorService workers;

    /** True while one of Signed Paintings' /paintings:reload commands runs: those should really re-download. */
    private static boolean userReloadInProgress;

    @Override
    public void onInitializeClient() {
        ensureStarted();
        CacheCommand.register();
        // Safety net in case a reload command threw before our RETURN hook could clear the flag.
        ClientTickEvents.START_CLIENT_TICK.register(_ -> userReloadInProgress = false);
    }

    /**
     * Lazily starts everything. Signed Paintings may build its ImageManager (and so hit our mixins) before or
     * after our entrypoint runs, depending on load order, so every accessor goes through here.
     */
    private static synchronized void ensureStarted() {
        if (fetcher != null) return;
        config = CacheConfig.load();
        AtomicInteger n = new AtomicInteger();
        int threads = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
        workers = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "signed-paintings-cache-worker-" + n.incrementAndGet());
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1); // decoding shouldn't compete with the render thread
            return t;
        });
        disk = new DiskCache(FabricLoader.getInstance().getGameDir().resolve("signed_paintings_cache"), workers);
        fetcher = new PaintingFetcher(disk, workers, config.maxConcurrentDownloads);
    }

    public static CacheConfig config() {
        if (config == null) ensureStarted();
        return config;
    }

    public static DiskCache disk() {
        if (disk == null) ensureStarted();
        return disk;
    }

    public static PaintingFetcher fetcher() {
        if (fetcher == null) ensureStarted();
        return fetcher;
    }

    public static ExecutorService workers() {
        if (workers == null) ensureStarted();
        return workers;
    }

    public static void setUserReloadInProgress(boolean value) {
        userReloadInProgress = value;
    }

    /** Called from our hooks on ImageManager.reloadAll / reloadUrl / reloadDomain. */
    public static void onSignedPaintingsReload(Collection<String> urls) {
        fetcher().onReload(List.copyOf(urls), userReloadInProgress);
    }

    /** Re-download every loaded painting, ignoring cache freshness (servers can still answer "unchanged"). */
    public static int refreshAll() {
        userReloadInProgress = true;
        try {
            return SignedPaintingsClient.imageManager.reloadAll();
        } finally {
            userReloadInProgress = false;
        }
    }

    /** Applies edited options. Rebuilds loaded paintings (from the disk cache, not the web) if their look changed. */
    public static void apply(CacheConfig edited) {
        String oldVisual = config().visualKey();
        config.copyFrom(edited);
        config.save();
        fetcher().setDownloadThreads(config.maxConcurrentDownloads);
        workers().execute(disk()::evictIfNeeded);
        if (!oldVisual.equals(config.visualKey()) && SignedPaintingsClient.imageManager != null) {
            SignedPaintingsClient.imageManager.reloadAll();
        }
    }
}
