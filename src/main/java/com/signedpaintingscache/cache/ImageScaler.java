package com.signedpaintingscache.cache;

import com.nettakrim.signed_paintings.util.ImageManager;
import com.signedpaintingscache.PaintingsCache;
import com.signedpaintingscache.config.CacheConfig;
import com.signedpaintingscache.config.ScalingFilter;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;

/**
 * Decoding and resizing. Everything here runs on the mod's worker threads, never the render thread.
 *
 * <p>Fidelity rule: every resize Signed Paintings itself requests (pixels-per-block variants, the sign editor
 * preview, LOD variants) runs Signed Paintings' own nearest-neighbour code untouched, so a deliberately pixelated
 * painting looks exactly as its placer saw it. Our filters only ever apply to one thing: shrinking an image that
 * is over the "Max resolution" cap, which is off by default.
 */
public final class ImageScaler {
    /** Even with "Original" resolution, never keep more than this (largest texture most GPUs accept). */
    public static final int HARD_LIMIT = 16384;

    /** Filter for the resize currently being done by our own code on this thread; null = Signed Paintings' resize. */
    private static final ThreadLocal<ScalingFilter> ACTIVE = new ThreadLocal<>();

    private ImageScaler() {}

    /**
     * Decodes image bytes. Only when an image is far over the cap (4x+) is it subsampled while decoding,
     * so a 12000px photo destined for 1024px never needs ~576 MB of RAM just to be shrunk.
     */
    public static BufferedImage decode(byte[] bytes) throws IOException {
        int cap = cap(PaintingsCache.config());
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (in == null) throw new IOException("Unreadable image data");
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) throw new IOException("Not a supported image format");
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                int longest = Math.max(reader.getWidth(0), reader.getHeight(0));
                ImageReadParam param = reader.getDefaultReadParam();
                int step = longest / (cap * 2);
                if (step > 1) param.setSourceSubsampling(step, step, 0, 0);
                BufferedImage image = reader.read(0, param);
                if (image == null) throw new IOException("Image decoded to nothing");
                return image;
            } finally {
                reader.dispose();
            }
        }
    }

    /** Applies the resolution cap (if any) and converts to the RGBA layout Signed Paintings uploads from. */
    public static BufferedImage prepareBase(BufferedImage decoded) {
        CacheConfig config = PaintingsCache.config();
        int cap = cap(config);
        int w = decoded.getWidth();
        int h = decoded.getHeight();

        if (Math.max(w, h) <= cap) {
            // Under the cap: exactly what Signed Paintings does (1:1 copy into RGBA).
            return ImageManager.scaleImage(decoded, w, h);
        }

        ScalingFilter filter = config.scalingFilter;
        Blockiness blocks = Blockiness.measure(decoded);
        if (filter == ScalingFilter.AUTO) filter = blocks.blocky() ? ScalingFilter.NEAREST : ScalingFilter.SMOOTH;

        int[] size = fitWithin(w, h, cap);
        if (filter == ScalingFilter.NEAREST && blocks.blockSize() > 1) {
            // Pixel art / pre-pixelated image: shrink to a whole multiple of its real pixel grid, so every
            // art pixel stays the same size instead of some becoming 5px and others 6px.
            int artW = w / blocks.blockSize();
            int artH = h / blocks.blockSize();
            int k = cap / Math.max(artW, artH);
            if (k >= 1) size = new int[]{artW * k, artH * k};
        }

        ACTIVE.set(filter);
        try {
            return ImageManager.scaleImage(decoded, size[0], size[1]);
        } finally {
            ACTIVE.remove();
        }
    }

    private static int[] fitWithin(int w, int h, int cap) {
        double f = (double) cap / Math.max(w, h);
        return new int[]{Math.max(1, (int) Math.round(w * f)), Math.max(1, (int) Math.round(h * f))};
    }

    private static int cap(CacheConfig c) {
        return c.maxImageResolution > 0 ? Math.min(c.maxImageResolution, HARD_LIMIT) : HARD_LIMIT;
    }

    /**
     * Hook for ImageManager.scaleImage. Returns null (= run Signed Paintings' own nearest-neighbour code) for
     * every call except our own resolution-cap shrink with a non-nearest filter.
     */
    public static BufferedImage scale(BufferedImage src, int width, int height) {
        ScalingFilter filter = ACTIVE.get();
        if (filter == null || filter == ScalingFilter.NEAREST || filter == ScalingFilter.AUTO) return null;

        width = Math.max(width, 1);
        height = Math.max(height, 1);
        int sw = src.getWidth();
        int sh = src.getHeight();
        if (width == sw && height == sh) return null;

        boolean hardEdges = PaintingsCache.config().preserveHardEdges && !hasPartialAlpha(src);

        // Filter in premultiplied space so transparent pixels don't bleed dark fringes into edges.
        BufferedImage current = copy(src, sw, sh, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);

        if (filter == ScalingFilter.SMOOTH) {
            // Halve repeatedly while still at least 2x the target; a single big bilinear jump skips pixels.
            int cw = sw, ch = sh;
            while (true) {
                int nw = cw / 2 >= width ? cw / 2 : cw;
                int nh = ch / 2 >= height ? ch / 2 : ch;
                if (nw == cw && nh == ch) break;
                current = copy(current, nw, nh, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                cw = nw;
                ch = nh;
            }
        }

        Object hint = filter == ScalingFilter.BICUBIC
                ? RenderingHints.VALUE_INTERPOLATION_BICUBIC
                : RenderingHints.VALUE_INTERPOLATION_BILINEAR;

        BufferedImage out = ImageManager.createRGBAImage(width, height);
        Graphics2D g = out.createGraphics();
        g.setComposite(AlphaComposite.Src);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, hint);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(current, 0, 0, width, height, null);
        g.dispose();

        if (hardEdges) thresholdAlpha(out);
        return out;
    }

    private static BufferedImage copy(BufferedImage src, int w, int h, Object interpolation) {
        BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB_PRE);
        Graphics2D g = dst.createGraphics();
        g.setComposite(AlphaComposite.Src);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interpolation);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return dst;
    }

    /**
     * Signed Paintings switches a painting to the translucent render type if any pixel is partly transparent.
     * Smooth resizing creates such pixels at edges, so images that started with only hard edges get their
     * alpha snapped back to 0/255.
     */
    private static boolean hasPartialAlpha(BufferedImage img) {
        if (!img.getColorModel().hasAlpha()) return false;
        int w = img.getWidth();
        int h = img.getHeight();
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            img.getRGB(0, y, w, 1, row, 0, w);
            for (int argb : row) {
                int a = argb >>> 24;
                if (a != 0 && a != 255) return true;
            }
        }
        return false;
    }

    private static void thresholdAlpha(BufferedImage rgba) {
        byte[] data = ((DataBufferByte) rgba.getRaster().getDataBuffer()).getData();
        for (int i = 3; i < data.length; i += 4) {
            data[i] = (data[i] & 0xFF) >= 128 ? (byte) 0xFF : 0;
        }
    }

    /**
     * Detects pixel art and pre-pixelated images by where their strong edges fall. Only colour jumps too big to
     * be compression noise or a gradient count as edges. In pixelated images (PNG or JPEG) 95%+ of those land
     * on a regular grid of block boundaries; in photos at most ~45% line up with any grid of 4px or more.
     * Blocks of 2-3px are not looked for: photos can show that pattern too, and such fine blocks survive
     * smooth shrinking anyway.
     */
    record Blockiness(boolean blocky, int blockSize) {
        private static final int SAMPLES = 64;
        private static final int MIN_BLOCK = 4;
        private static final int MAX_BLOCK = 64;
        /** Summed RGBA difference that counts as a real edge. */
        private static final int STRONG_EDGE = 96;
        /** Fewer edges than this on an axis says nothing either way (flat colour, soft image). */
        private static final int MIN_EDGES = 64;
        /** Share of strong edges that must sit on the grid. */
        private static final double THRESHOLD = 0.8;

        static Blockiness measure(BufferedImage img) {
            int w = img.getWidth();
            int h = img.getHeight();
            if (w < MIN_BLOCK * 4 || h < MIN_BLOCK * 4) return new Blockiness(false, 1);

            int[] gx = grid(edgeProfile(img, true));
            int[] gy = grid(edgeProfile(img, false));
            if (gx == null && gy == null) return new Blockiness(false, 1);
            boolean blocky = (gx == null || gx[0] >= MIN_BLOCK) && (gy == null || gy[0] >= MIN_BLOCK);
            if (!blocky) return new Blockiness(false, 1);

            // An exact block size is only usable if both axes agree, the grid starts at 0, and it divides the image.
            int size = 1;
            if (gx != null && gy != null && gx[0] == gy[0] && gx[1] == 0 && gy[1] == 0
                    && w % gx[0] == 0 && h % gy[0] == 0) {
                size = gx[0];
            }
            return new Blockiness(true, size);
        }

        /** edges[i] = how many sampled rows (or columns) have a strong edge between pixel i-1 and i. */
        private static int[] edgeProfile(BufferedImage img, boolean horizontal) {
            int len = horizontal ? img.getWidth() : img.getHeight();
            int other = horizontal ? img.getHeight() : img.getWidth();
            int[] edges = new int[len];
            int[] line = new int[len];
            int lines = Math.min(SAMPLES, other);
            for (int i = 0; i < lines; i++) {
                int at = (int) ((i + 0.5) * other / lines);
                if (horizontal) img.getRGB(0, at, len, 1, line, 0, len);
                else img.getRGB(at, 0, 1, len, line, 0, 1);
                for (int p = 1; p < len; p++) {
                    if (distance(line[p - 1], line[p]) > STRONG_EDGE) edges[p]++;
                }
            }
            return edges;
        }

        /**
         * Largest block size b whose grid holds at least THRESHOLD of the strong edges. Divisors of the true
         * size also pass (every 10px boundary is a 5px one too) and multiples don't, so the largest passing b
         * is the real one. Returns {b, phase}, {1, 0} if no grid fits, or null if there are too few edges to tell.
         */
        private static int[] grid(int[] edges) {
            long total = 0;
            for (int e : edges) total += e;
            if (total < MIN_EDGES) return null;
            int[] best = {1, 0};
            int maxB = Math.min(MAX_BLOCK, edges.length / 4);
            for (int b = MIN_BLOCK; b <= maxB; b++) {
                long[] byPhase = new long[b];
                for (int i = 1; i < edges.length; i++) byPhase[i % b] += edges[i];
                int phase = 0;
                for (int p = 1; p < b; p++) if (byPhase[p] > byPhase[phase]) phase = p;
                if (byPhase[phase] >= total * THRESHOLD) best = new int[]{b, phase};
            }
            return best;
        }

        private static int distance(int a, int b) {
            int d = 0;
            for (int shift = 0; shift < 32; shift += 8) d += Math.abs(((a >>> shift) & 0xFF) - ((b >>> shift) & 0xFF));
            return d;
        }
    }
}
