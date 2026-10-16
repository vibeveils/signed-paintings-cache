package com.signedpaintingscache.cache;

import com.nettakrim.signed_paintings.SignedPaintingsClient;
import com.nettakrim.signed_paintings.util.ImageManager;
import com.signedpaintingscache.PaintingsCache;
import com.signedpaintingscache.config.CacheConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Replaces Signed Paintings' downloadImageBuffer. Order of preference for every image:
 * fresh disk copy → (if stale) ask the server whether it changed → full download → stale disk copy as a fallback.
 */
public final class PaintingFetcher {
    private static final Logger LOGGER = LoggerFactory.getLogger("SignedPaintingsCache");
    // Same identity Signed Paintings uses, so hosts see identical traffic (just much less of it).
    private static final String USER_AGENT = "Signed Paintings mod";

    public final AtomicLong diskHits = new AtomicLong();
    public final AtomicLong downloads = new AtomicLong();
    public final AtomicLong notModified = new AtomicLong();
    public final AtomicLong staleFallbacks = new AtomicLong();
    public final AtomicLong failures = new AtomicLong();
    public final AtomicLong suppressedRetries = new AtomicLong();
    public final AtomicLong bytesDownloaded = new AtomicLong();
    public final AtomicLong bytesFromDisk = new AtomicLong();

    private final DiskCache disk;
    private final ExecutorService workers;
    private volatile ExecutorService downloaders;
    private volatile int downloaderCount;

    // Signed Paintings never follows redirects (a redirect could bypass its trusted-domain check), so neither do we.
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private final Map<String, CompletableFuture<BufferedImage>> inFlight = new ConcurrentHashMap<>();
    private final Map<String, Long> failedUntil = new ConcurrentHashMap<>();
    private final Set<String> forceRefresh = ConcurrentHashMap.newKeySet();

    public PaintingFetcher(DiskCache disk, ExecutorService workers, int downloadThreads) {
        this.disk = disk;
        this.workers = workers;
        this.downloaderCount = downloadThreads;
        this.downloaders = newDownloadPool(downloadThreads);
    }

    public CompletableFuture<BufferedImage> fetch(String url) {
        if (url == null || !ImageManager.isValid(url)) {
            return CompletableFuture.failedFuture(new IOException("Invalid URL: " + url));
        }

        Long until = failedUntil.get(url);
        if (until != null) {
            long now = System.currentTimeMillis();
            if (now < until) {
                suppressedRetries.incrementAndGet();
                return CompletableFuture.failedFuture(new IOException(
                        "Failed recently; retrying in " + ((until - now) / 1000 + 1) + "s (Signed Paintings Cache)"));
            }
            failedUntil.remove(url, until);
        }

        CompletableFuture<BufferedImage> shared = inFlight.get(url);
        if (shared == null) {
            CompletableFuture<BufferedImage> mine = new CompletableFuture<>();
            shared = inFlight.putIfAbsent(url, mine);
            if (shared == null) {
                shared = mine;
                start(url).whenComplete((image, error) -> {
                    inFlight.remove(url, mine);
                    if (error != null) mine.completeExceptionally(unwrap(error));
                    else mine.complete(image);
                });
            }
        }
        // Hand out a dependent copy: Signed Paintings calls orTimeout() on what it gets, which completes
        // that future in place. A copy keeps one caller's timeout from cancelling everyone's result.
        return shared.thenApply(image -> image);
    }

    private CompletableFuture<BufferedImage> start(String url) {
        boolean forced = forceRefresh.remove(url);
        CompletableFuture<BufferedImage> future = CompletableFuture
                .supplyAsync(() -> PaintingsCache.config().diskCacheEnabled ? disk.get(url) : null, workers)
                .thenCompose(cached -> {
                    if (cached != null && !forced && isFresh(cached.meta())) {
                        return CompletableFuture.supplyAsync(() -> decodeCached(url, cached), workers)
                                .thenCompose(image -> image != null
                                        ? CompletableFuture.completedFuture(image)
                                        : fromNetwork(url, null)); // cached file was corrupt
                    }
                    return fromNetwork(url, cached);
                });

        return future.whenComplete((image, error) -> {
            if (error != null) {
                failures.incrementAndGet();
                int minutes = PaintingsCache.config().failedRetryMinutes;
                if (minutes > 0) failedUntil.put(url, System.currentTimeMillis() + minutes * 60_000L);
                LOGGER.debug("Painting {} failed: {}", url, unwrap(error).toString());
            } else {
                failedUntil.remove(url);
            }
        });
    }

    private BufferedImage decodeCached(String url, DiskCache.Hit cached) {
        try {
            BufferedImage image = ImageScaler.prepareBase(ImageScaler.decode(cached.bytes()));
            diskHits.incrementAndGet();
            bytesFromDisk.addAndGet(cached.bytes().length);
            info("Loaded painting from disk cache: " + url);
            return image;
        } catch (Exception e) {
            LOGGER.warn("Cached copy of {} is unreadable, re-downloading: {}", url, e.toString());
            disk.remove(url);
            return null;
        }
    }

    private record NetResult(byte[] bytes, String etag, String lastModified, boolean store) {}

    private CompletableFuture<BufferedImage> fromNetwork(String url, DiskCache.Hit stale) {
        return CompletableFuture
                .supplyAsync(() -> download(url, stale), downloaders)
                .thenApplyAsync(result -> {
                    BufferedImage image;
                    try {
                        image = ImageScaler.prepareBase(ImageScaler.decode(result.bytes()));
                    } catch (IOException | RuntimeException e) {
                        throw new CompletionException(new IOException("Couldn't decode image: " + e.getMessage(), e));
                    }
                    // Only store after a successful decode, so error pages never get cached.
                    if (result.store() && PaintingsCache.config().diskCacheEnabled) {
                        disk.put(url, result.bytes(), result.etag(), result.lastModified());
                    }
                    return image;
                }, workers);
    }

    private NetResult download(String url, DiskCache.Hit stale) {
        CacheConfig config = PaintingsCache.config();
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .timeout(Duration.ofSeconds(60))
                .header("User-Agent", USER_AGENT);

        if (url.startsWith("https://i.imgur.com")) {
            request.header("Sec-Fetch-Site", "same-site");
            request.header("Referer", "https://imgur.com/");
        }

        boolean conditional = stale != null && config.revalidate
                && (stale.meta().etag != null || stale.meta().lastModified != null);
        if (conditional) {
            if (stale.meta().etag != null) request.header("If-None-Match", stale.meta().etag);
            if (stale.meta().lastModified != null) request.header("If-Modified-Since", stale.meta().lastModified);
        }

        try {
            HttpResponse<InputStream> response = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();

            if (status == 304 && stale != null) {
                response.body().close();
                notModified.incrementAndGet();
                disk.markFresh(url);
                info("Painting unchanged on server, using disk cache: " + url);
                return new NetResult(stale.bytes(), stale.meta().etag, stale.meta().lastModified, false);
            }
            if (status != 200) {
                response.body().close();
                throw new IOException("HTTP " + status);
            }

            long limit = config.maxDownloadMB * 1024L * 1024L;
            long declared = response.headers().firstValueAsLong("Content-Length").orElse(-1);
            if (declared > limit) {
                response.body().close();
                throw new IOException("Image is " + DiskCache.formatBytes(declared) + ", over the " + config.maxDownloadMB + " MB limit");
            }

            byte[] bytes = readLimited(response.body(), limit, declared);
            downloads.incrementAndGet();
            bytesDownloaded.addAndGet(bytes.length);
            info("Downloaded painting " + url + " (" + DiskCache.formatBytes(bytes.length) + ")");
            return new NetResult(bytes,
                    response.headers().firstValue("ETag").orElse(null),
                    response.headers().firstValue("Last-Modified").orElse(null),
                    true);
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            if (stale != null) {
                // Host down or offline: an old picture beats a blank sign.
                staleFallbacks.incrementAndGet();
                info("Couldn't reach server for " + url + ", using older cached copy (" + e + ")");
                return new NetResult(stale.bytes(), stale.meta().etag, stale.meta().lastModified, false);
            }
            throw new CompletionException(e instanceof IOException ? e : new IOException(e.toString(), e));
        }
    }

    private static byte[] readLimited(InputStream in, long limit, long declared) throws IOException {
        try (in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(declared > 0 ? (int) declared : 64 * 1024);
            byte[] buffer = new byte[16 * 1024];
            long total = 0;
            int n;
            while ((n = in.read(buffer)) != -1) {
                total += n;
                if (total > limit) throw new IOException("Image is over the " + (limit / (1024 * 1024)) + " MB limit");
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }

    private static boolean isFresh(DiskCache.Meta meta) {
        long maxAge = PaintingsCache.config().maxAgeMillis();
        return maxAge <= 0 || System.currentTimeMillis() - meta.fetchedAt < maxAge;
    }

    /** Called when Signed Paintings reloads images. User-requested reloads skip the "fresh" shortcut. */
    public void onReload(Collection<String> urls, boolean userRequested) {
        for (String url : urls) {
            failedUntil.remove(url);
            if (userRequested) forceRefresh.add(url);
        }
    }

    public void clearFailures() {
        failedUntil.clear();
    }

    public int failedCount() {
        return failedUntil.size();
    }

    public void setDownloadThreads(int threads) {
        if (threads == downloaderCount) return;
        ExecutorService old = downloaders;
        downloaders = newDownloadPool(threads);
        downloaderCount = threads;
        old.shutdown(); // queued and running downloads still finish
    }

    private static final AtomicInteger POOL_ID = new AtomicInteger();

    private static ExecutorService newDownloadPool(int threads) {
        int pool = POOL_ID.incrementAndGet();
        AtomicInteger n = new AtomicInteger();
        return Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "signed-paintings-cache-download-" + pool + "-" + n.incrementAndGet());
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });
    }

    private static Throwable unwrap(Throwable t) {
        while (t instanceof CompletionException && t.getCause() != null) t = t.getCause();
        return t;
    }

    private static void info(String message) {
        // Shows up when Signed Paintings' own detailed logging is on.
        SignedPaintingsClient.info("[cache] " + message, false);
    }
}
