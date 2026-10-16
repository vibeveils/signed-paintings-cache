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

/** Decoding and resizing. Everything here runs on the mod's worker threads, never the render thread. */
public final class ImageScaler {
    /** Even with "unlimited" resolution, never keep more than this (largest texture most GPUs accept). */
    public static final int HARD_LIMIT = 16384;

    private ImageScaler() {}

    /**
     * Decodes image bytes. Huge images are subsampled while decoding, so a 12000px photo destined for
     * a 1024px painting never needs ~576 MB of RAM just to be shrunk.
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
                // Leave 2x headroom so the final resize still has detail to filter.
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

    /** Applies the resolution cap and converts to the RGBA layout Signed Paintings uploads from. */
    public static BufferedImage prepareBase(BufferedImage decoded) {
        int cap = cap(PaintingsCache.config());
        int w = decoded.getWidth();
        int h = decoded.getHeight();
        int longest = Math.max(w, h);
        if (longest > cap) {
            double f = (double) cap / longest;
            w = Math.max(1, (int) Math.round(w * f));
            h = Math.max(1, (int) Math.round(h * f));
        }
        // Goes through ImageManager.scaleImage, which our mixin routes back into scale() below.
        return ImageManager.scaleImage(decoded, w, h);
    }

    private static int cap(CacheConfig c) {
        return c.maxImageResolution > 0 ? Math.min(c.maxImageResolution, HARD_LIMIT) : HARD_LIMIT;
    }

    /**
     * Replacement for ImageManager.scaleImage. Returns null to let Signed Paintings' own
     * nearest-neighbour resize run unchanged.
     */
    public static BufferedImage scale(BufferedImage src, int width, int height) {
        width = Math.max(width, 1);
        height = Math.max(height, 1);
        int sw = src.getWidth();
        int sh = src.getHeight();
        if (width == sw && height == sh) return null;

        CacheConfig config = PaintingsCache.config();
        boolean shrinking = width < sw || height < sh;
        ScalingFilter filter = config.scalingFilter;
        if (filter == ScalingFilter.AUTO) filter = shrinking ? ScalingFilter.SMOOTH : ScalingFilter.NEAREST;
        if (filter == ScalingFilter.NEAREST) return null;

        boolean hardEdges = config.preserveHardEdges && !hasPartialAlpha(src);

        // Filter in premultiplied space so transparent pixels don't bleed dark fringes into edges.
        BufferedImage current = copy(src, sw, sh, BufferedImage.TYPE_INT_ARGB_PRE, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);

        if (filter == ScalingFilter.SMOOTH && shrinking) {
            // Halve repeatedly while still at least 2x the target; a single big bilinear jump skips pixels.
            int cw = sw, ch = sh;
            while (true) {
                int nw = cw / 2 >= width ? cw / 2 : cw;
                int nh = ch / 2 >= height ? ch / 2 : ch;
                if (nw == cw && nh == ch) break;
                current = copy(current, nw, nh, BufferedImage.TYPE_INT_ARGB_PRE, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
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

    private static BufferedImage copy(BufferedImage src, int w, int h, int type, Object interpolation) {
        BufferedImage dst = new BufferedImage(w, h, type);
        Graphics2D g = dst.createGraphics();
        g.setComposite(AlphaComposite.Src);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interpolation);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return dst;
    }

    /**
     * Signed Paintings switches a painting to the (more expensive, sorting-sensitive) translucent render type
     * if any pixel is partly transparent. Smooth resizing creates such pixels at edges, so for images that
     * started with only hard edges we snap alpha back to 0/255.
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
}
