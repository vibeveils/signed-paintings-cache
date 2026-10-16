package com.signedpaintingscache.config;

public enum ScalingFilter {
    /** Nearest-neighbour when enlarging (keeps pixel art crisp), smooth when shrinking (no shimmering/jaggies). */
    AUTO,
    /** Signed Paintings' original behaviour: blocky everywhere. */
    NEAREST,
    BILINEAR,
    BICUBIC,
    /** Multi-step bilinear downscale: the cleanest result for big photos shrunk onto small paintings. */
    SMOOTH;

    public String translationKey() {
        return "signed_paintings_cache.filter." + name().toLowerCase(java.util.Locale.ROOT);
    }
}
