package com.signedpaintingscache.cache;

import com.google.gson.Gson;
import com.signedpaintingscache.PaintingsCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Stores the original downloaded bytes of each painting image, keyed by SHA-256 of its URL.
 * Each entry is two files: {@code <hash>.bin} (the image) and {@code <hash>.json} (URL, ETag, timestamps).
 * The .bin file's modified time doubles as "last used", which drives least-recently-used eviction.
 * Raw bytes are stored (not processed pixels) so changing quality options never needs a re-download.
 */
public final class DiskCache {
    private static final Logger LOGGER = LoggerFactory.getLogger("SignedPaintingsCache");
    private static final Gson GSON = new Gson();

    public static final class Meta {
        public String url;
        public String etag;
        public String lastModified;
        public long fetchedAt;
        public long size;
    }

    public record Hit(byte[] bytes, Meta meta) {}

    private final Path dir;
    private final Map<String, Meta> index = new ConcurrentHashMap<>();
    private final AtomicLong totalBytes = new AtomicLong();
    private final CompletableFuture<Void> ready;
    private final Object evictLock = new Object();

    public DiskCache(Path dir, Executor executor) {
        this.dir = dir;
        this.ready = CompletableFuture.runAsync(this::scan, executor);
    }

    public Path directory() {
        return dir;
    }

    private void scan() {
        long start = System.nanoTime();
        try {
            Files.createDirectories(dir);
            List<Path> bins = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
                for (Path p : stream) {
                    String name = p.getFileName().toString();
                    if (name.endsWith(".tmp")) {
                        Files.deleteIfExists(p);
                    } else if (name.endsWith(".bin")) {
                        bins.add(p);
                    }
                }
            }
            for (Path bin : bins) {
                String key = stripExt(bin.getFileName().toString());
                Path json = dir.resolve(key + ".json");
                Meta meta = readMeta(json);
                if (meta == null || meta.url == null) {
                    Files.deleteIfExists(bin);
                    Files.deleteIfExists(json);
                    continue;
                }
                meta.size = Files.size(bin);
                index.put(key, meta);
                totalBytes.addAndGet(meta.size);
            }
            // json files whose image is missing
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.json")) {
                for (Path p : stream) {
                    if (!index.containsKey(stripExt(p.getFileName().toString()))) Files.deleteIfExists(p);
                }
            }
        } catch (IOException e) {
            LOGGER.warn("Couldn't scan painting cache at {}: {}", dir, e.toString());
        }
        LOGGER.info("Painting cache ready: {} images, {} ({} ms)", index.size(), formatBytes(totalBytes.get()),
                (System.nanoTime() - start) / 1_000_000);
        evictIfNeeded();
    }

    private void awaitReady() {
        try {
            ready.join();
        } catch (Exception ignored) {
        }
    }

    /** Returns the cached bytes for {@code url}, or null. Marks the entry as recently used. */
    public Hit get(String url) {
        awaitReady();
        String key = key(url);
        Meta meta = index.get(key);
        if (meta == null || !url.equals(meta.url)) return null;
        Path bin = dir.resolve(key + ".bin");
        try {
            byte[] bytes = Files.readAllBytes(bin);
            touch(bin);
            return new Hit(bytes, meta);
        } catch (IOException e) {
            removeKey(key);
            return null;
        }
    }

    public void put(String url, byte[] bytes, String etag, String lastModified) {
        awaitReady();
        String key = key(url);
        Meta meta = new Meta();
        meta.url = url;
        meta.etag = etag;
        meta.lastModified = lastModified;
        meta.fetchedAt = System.currentTimeMillis();
        meta.size = bytes.length;
        try {
            Files.createDirectories(dir);
            writeAtomic(dir.resolve(key + ".bin"), bytes, key);
            writeAtomic(dir.resolve(key + ".json"), GSON.toJson(meta).getBytes(StandardCharsets.UTF_8), key);
            Meta old = index.put(key, meta);
            totalBytes.addAndGet(meta.size - (old != null ? old.size : 0));
        } catch (IOException e) {
            LOGGER.warn("Couldn't write cached painting {}: {}", url, e.toString());
            return;
        }
        evictIfNeeded();
    }

    /** Server said the image hasn't changed: reset its age so it isn't re-checked again soon. */
    public void markFresh(String url) {
        String key = key(url);
        Meta meta = index.get(key);
        if (meta == null) return;
        meta.fetchedAt = System.currentTimeMillis();
        try {
            writeAtomic(dir.resolve(key + ".json"), GSON.toJson(meta).getBytes(StandardCharsets.UTF_8), key);
        } catch (IOException ignored) {
        }
    }

    public void remove(String url) {
        awaitReady();
        removeKey(key(url));
    }

    public int clear() {
        awaitReady();
        int count = 0;
        for (String key : new ArrayList<>(index.keySet())) {
            if (removeKey(key)) count++;
        }
        return count;
    }

    public int entryCount() {
        return index.size();
    }

    public long sizeBytes() {
        return totalBytes.get();
    }

    public void evictIfNeeded() {
        long limit = PaintingsCache.config().maxCacheSizeMB * 1024L * 1024L;
        if (limit <= 0 || totalBytes.get() <= limit) return;

        synchronized (evictLock) {
            if (totalBytes.get() <= limit) return;
            record Candidate(String key, long lastUsed) {}
            List<Candidate> candidates = new ArrayList<>();
            for (Map.Entry<String, Meta> e : index.entrySet()) {
                long used;
                try {
                    used = Files.getLastModifiedTime(dir.resolve(e.getKey() + ".bin")).toMillis();
                } catch (IOException ex) {
                    used = e.getValue().fetchedAt;
                }
                candidates.add(new Candidate(e.getKey(), used));
            }
            candidates.sort((a, b) -> Long.compare(a.lastUsed(), b.lastUsed()));

            long target = (long) (limit * 0.9);
            int removed = 0;
            for (Candidate c : candidates) {
                if (totalBytes.get() <= target) break;
                if (removeKey(c.key())) removed++;
            }
            if (removed > 0) {
                LOGGER.info("Painting cache over its {} limit; removed {} least-recently-used images", formatBytes(limit), removed);
            }
        }
    }

    private boolean removeKey(String key) {
        Meta meta = index.remove(key);
        if (meta != null) totalBytes.addAndGet(-meta.size);
        try {
            Files.deleteIfExists(dir.resolve(key + ".bin"));
            Files.deleteIfExists(dir.resolve(key + ".json"));
        } catch (IOException ignored) {
        }
        return meta != null;
    }

    private void writeAtomic(Path target, byte[] bytes, String key) throws IOException {
        Path tmp = Files.createTempFile(dir, key, ".tmp");
        try {
            Files.write(tmp, bytes);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static void touch(Path p) {
        try {
            Files.setLastModifiedTime(p, FileTime.fromMillis(System.currentTimeMillis()));
        } catch (IOException ignored) {
        }
    }

    private static Meta readMeta(Path json) {
        if (!Files.exists(json)) return null;
        try (Reader r = Files.newBufferedReader(json, StandardCharsets.UTF_8)) {
            return GSON.fromJson(r, Meta.class);
        } catch (Exception e) {
            return null;
        }
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    static String key(String url) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(url.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(java.util.Locale.ROOT, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024));
        return String.format(java.util.Locale.ROOT, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
