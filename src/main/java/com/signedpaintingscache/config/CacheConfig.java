package com.signedpaintingscache.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * All user-facing options. Stored as JSON in config/signed_paintings_cache.json.
 * Every field is read live, so most changes apply without a restart.
 */
public final class CacheConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("SignedPaintingsCache");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("signed_paintings_cache.json");

    /** Bumped when a default changes in a way existing config files should pick up. */
    public int configVersion = 0;
    private static final int CURRENT_VERSION = 2;

    // ---- Disk cache ----
    /** Keep downloaded images on disk between sessions. */
    public boolean diskCacheEnabled = true;
    /** Total size cap for the cache folder, in MB. 0 = unlimited. Oldest-used images are removed first. */
    public int maxCacheSizeMB = 1024;
    /** After this many days a cached image is re-checked with its server. 0 = never re-check. */
    public int maxAgeDays = 30;
    /** When re-checking, ask the server "has this changed?" instead of downloading it again. */
    public boolean revalidate = true;

    // ---- Network ----
    /** Minutes to wait before retrying an image that failed to download. 0 = retry immediately (Signed Paintings' default). */
    public int failedRetryMinutes = 5;
    /** How many images can download at once. Lower = smoother joins on slow connections. */
    public int maxConcurrentDownloads = 4;
    /** Images larger than this many MB are refused. */
    public int maxDownloadMB = 32;

    // ---- Quality ----
    /**
     * Longest side, in pixels, an image is kept at after decoding. 0 = Original (16384 hard safety cap).
     * Defaults to Original so paintings are pixel-identical to what their placer sees.
     */
    public int maxImageResolution = 0;
    /** How images over Max resolution are shrunk. Never used for Signed Paintings' own pixels-per-block resizing. */
    public ScalingFilter scalingFilter = ScalingFilter.AUTO;
    /** If an image has only fully-opaque/fully-clear pixels, keep it that way after smooth resizing. */
    public boolean preserveHardEdges = true;

    // ---- LOD ----
    /** Scales every painting texture Signed Paintings asks for. 100 = unchanged. */
    public int textureDetailPercent = 100;
    /** Caps the longest side of each per-painting texture (from pixels-per-block). 0 = unlimited. */
    public int maxTextureSize = 0;
    /** Don't make enlarged copies of small images; draw the original instead (looks the same, uses far less VRAM). */
    public boolean reuseBaseForUpscales = true;

    // ---- Memory ----
    /** Minutes an unseen painting's texture stays in VRAM. -1 = forever. Signed Paintings default is about 1. */
    public int vramKeepMinutes = 2;
    /** Minutes an unseen painting's image stays in RAM before it's dropped (it reloads from the disk cache). -1 = forever. */
    public int ramKeepMinutes = 15;

    public static CacheConfig load() {
        CacheConfig config = null;
        if (Files.exists(FILE)) {
            try (Reader reader = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
                config = GSON.fromJson(reader, CacheConfig.class);
            } catch (Exception e) {
                LOGGER.warn("Couldn't read {}, using defaults: {}", FILE, e.toString());
            }
        }
        if (config == null) config = new CacheConfig();
        if (config.configVersion < CURRENT_VERSION) {
            // v1 shipped with a 2048px cap on by default, which could soften deliberately pixelated paintings.
            if (config.maxImageResolution == 2048) config.maxImageResolution = 0;
            config.configVersion = CURRENT_VERSION;
        }
        config.sanitize();
        config.save();
        return config;
    }

    public void save() {
        try {
            Files.createDirectories(FILE.getParent());
            Path tmp = FILE.resolveSibling(FILE.getFileName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(this, writer);
            }
            Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOGGER.warn("Couldn't save {}: {}", FILE, e.toString());
        }
    }

    public void sanitize() {
        maxCacheSizeMB = Math.max(0, maxCacheSizeMB);
        maxAgeDays = Math.max(0, maxAgeDays);
        failedRetryMinutes = Math.max(0, failedRetryMinutes);
        maxConcurrentDownloads = clamp(maxConcurrentDownloads, 1, 16);
        maxDownloadMB = clamp(maxDownloadMB, 1, 512);
        maxImageResolution = maxImageResolution <= 0 ? 0 : clamp(maxImageResolution, 16, 16384);
        if (scalingFilter == null) scalingFilter = ScalingFilter.AUTO;
        textureDetailPercent = clamp(textureDetailPercent, 5, 100);
        maxTextureSize = maxTextureSize <= 0 ? 0 : clamp(maxTextureSize, 16, 16384);
        vramKeepMinutes = vramKeepMinutes < 0 ? -1 : clamp(vramKeepMinutes, 1, 24 * 60);
        ramKeepMinutes = ramKeepMinutes < 0 ? -1 : clamp(ramKeepMinutes, 1, 24 * 60);
    }

    /** Anything that changes how loaded paintings look; if this changes, loaded paintings are rebuilt. */
    public String visualKey() {
        return maxImageResolution + "|" + scalingFilter + "|" + preserveHardEdges + "|"
                + textureDetailPercent + "|" + maxTextureSize + "|" + reuseBaseForUpscales;
    }

    // Signed Paintings counts its expiry timer in client ticks (20 per second).
    public int vramExpiryTicks() {
        return vramKeepMinutes < 0 ? Integer.MAX_VALUE / 2 : vramKeepMinutes * 1200;
    }

    public int ramExpiryTicks() {
        return ramKeepMinutes < 0 ? Integer.MAX_VALUE / 2 : ramKeepMinutes * 1200;
    }

    public long maxAgeMillis() {
        return maxAgeDays * 86_400_000L;
    }

    public CacheConfig copy() {
        CacheConfig c = GSON.fromJson(GSON.toJson(this), CacheConfig.class);
        c.sanitize();
        return c;
    }

    public void copyFrom(CacheConfig other) {
        diskCacheEnabled = other.diskCacheEnabled;
        maxCacheSizeMB = other.maxCacheSizeMB;
        maxAgeDays = other.maxAgeDays;
        revalidate = other.revalidate;
        failedRetryMinutes = other.failedRetryMinutes;
        maxConcurrentDownloads = other.maxConcurrentDownloads;
        maxDownloadMB = other.maxDownloadMB;
        maxImageResolution = other.maxImageResolution;
        scalingFilter = other.scalingFilter;
        preserveHardEdges = other.preserveHardEdges;
        textureDetailPercent = other.textureDetailPercent;
        maxTextureSize = other.maxTextureSize;
        reuseBaseForUpscales = other.reuseBaseForUpscales;
        vramKeepMinutes = other.vramKeepMinutes;
        ramKeepMinutes = other.ramKeepMinutes;
        sanitize();
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
