package com.signedpaintingscache.gui;

import com.signedpaintingscache.PaintingsCache;
import com.signedpaintingscache.cache.DiskCache;
import com.signedpaintingscache.cache.PaintingFetcher;
import com.signedpaintingscache.config.CacheConfig;
import com.signedpaintingscache.config.ScalingFilter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Two columns of click-to-cycle buttons. Edits a copy of the config; Done applies it, Esc discards.
 * Built only from widgets Signed Paintings itself uses on 26.3, to stay compatible with the same game build.
 */
public class CacheConfigScreen extends Screen {
    private static final String K = PaintingsCache.MODID + ".";
    private static final int BUTTON_WIDTH = 170;
    private static final int GAP = 8;

    private final Screen parent;
    private final CacheConfig edit;
    private MultiLineTextWidget stats;

    public CacheConfigScreen(Screen parent) {
        this(parent, PaintingsCache.config().copy());
    }

    private CacheConfigScreen(Screen parent, CacheConfig edit) {
        super(Component.translatable(K + "title"));
        this.parent = parent;
        this.edit = edit;
    }

    @Override
    protected void init() {
        int left = width / 2 - BUTTON_WIDTH - GAP / 2;
        int right = width / 2 + GAP / 2;
        int top = 22;
        int rowHeight = Math.max(20, Math.min(24, (height - top - 76) / 9));

        addRenderableWidget(new StringWidget(0, 6, width, 12, getTitle(), font));

        // ---- Left: disk cache & network ----
        int y = top;
        addRenderableWidget(new StringWidget(left, y + 4, BUTTON_WIDTH, 12, Component.translatable(K + "section.cache"), font));
        y += rowHeight;
        toggle(left, y, "disk_cache", () -> edit.diskCacheEnabled, v -> edit.diskCacheEnabled = v);
        y += rowHeight;
        cycle(left, y, "cache_size", ints(256, 512, 1024, 2048, 4096, 0), () -> edit.maxCacheSizeMB, v -> edit.maxCacheSizeMB = v,
                v -> v == 0 ? Component.translatable(K + "value.unlimited") : Component.literal(v >= 1024 ? (v / 1024) + " GB" : v + " MB"));
        y += rowHeight;
        cycle(left, y, "max_age", ints(1, 7, 30, 90, 365, 0), () -> edit.maxAgeDays, v -> edit.maxAgeDays = v,
                v -> v == 0 ? Component.translatable(K + "value.never") : Component.translatable(K + "value.days", v));
        y += rowHeight;
        toggle(left, y, "revalidate", () -> edit.revalidate, v -> edit.revalidate = v);
        y += rowHeight;
        cycle(left, y, "retry_failed", ints(0, 1, 5, 15, 60), () -> edit.failedRetryMinutes, v -> edit.failedRetryMinutes = v,
                v -> v == 0 ? Component.translatable(K + "value.immediately") : Component.translatable(K + "value.minutes", v));
        y += rowHeight;
        cycle(left, y, "parallel_downloads", ints(1, 2, 4, 6, 8), () -> edit.maxConcurrentDownloads, v -> edit.maxConcurrentDownloads = v,
                v -> Component.literal(Integer.toString(v)));
        y += rowHeight;
        cycle(left, y, "max_download", ints(8, 16, 32, 64, 128), () -> edit.maxDownloadMB, v -> edit.maxDownloadMB = v,
                v -> Component.literal(v + " MB"));
        int leftBottom = y + rowHeight;

        // ---- Right: quality, LOD, memory ----
        y = top;
        addRenderableWidget(new StringWidget(right, y + 4, BUTTON_WIDTH, 12, Component.translatable(K + "section.quality"), font));
        y += rowHeight;
        cycle(right, y, "max_resolution", ints(256, 512, 1024, 2048, 4096, 0), () -> edit.maxImageResolution, v -> edit.maxImageResolution = v,
                v -> v == 0 ? Component.translatable(K + "value.unlimited") : Component.literal(v + " px"));
        y += rowHeight;
        cycle(right, y, "filter", Arrays.asList(ScalingFilter.values()), () -> edit.scalingFilter, v -> edit.scalingFilter = v,
                v -> Component.translatable(v.translationKey()));
        y += rowHeight;
        toggle(right, y, "hard_edges", () -> edit.preserveHardEdges, v -> edit.preserveHardEdges = v);
        y += rowHeight;
        cycle(right, y, "texture_detail", ints(100, 75, 50, 25), () -> edit.textureDetailPercent, v -> edit.textureDetailPercent = v,
                v -> Component.literal(v + "%"));
        y += rowHeight;
        cycle(right, y, "max_texture", ints(0, 2048, 1024, 512, 256, 128), () -> edit.maxTextureSize, v -> edit.maxTextureSize = v,
                v -> v == 0 ? Component.translatable(K + "value.unlimited") : Component.literal(v + " px"));
        y += rowHeight;
        toggle(right, y, "reuse_base", () -> edit.reuseBaseForUpscales, v -> edit.reuseBaseForUpscales = v);
        y += rowHeight;
        cycle(right, y, "vram_keep", ints(1, 2, 5, 10, 30, -1), () -> edit.vramKeepMinutes, v -> edit.vramKeepMinutes = v,
                v -> v < 0 ? Component.translatable(K + "value.forever") : Component.translatable(K + "value.minutes", v));
        y += rowHeight;
        cycle(right, y, "ram_keep", ints(5, 15, 30, 60, -1), () -> edit.ramKeepMinutes, v -> edit.ramKeepMinutes = v,
                v -> v < 0 ? Component.translatable(K + "value.forever") : Component.translatable(K + "value.minutes", v));
        int rightBottom = y + rowHeight;

        // ---- Footer ----
        int footerY = height - 26;
        int statsY = Math.max(Math.max(leftBottom, rightBottom) + 4, footerY - 24);
        stats = addRenderableWidget(new MultiLineTextWidget(0, statsY, statsText(), font).setCentered(true).setMaxWidth(width - 20));
        stats.setPosition((width - stats.getWidth()) / 2, statsY);

        int buttonW = 100;
        int footerLeft = width / 2 - (buttonW * 3 + GAP * 2) / 2;
        Button clear = addRenderableWidget(new Button.Builder(Component.translatable(K + "button.clear"), this::clearCache)
                .bounds(footerLeft, footerY, buttonW, 20)
                .build());
        clear.setTooltip(Tooltip.create(Component.translatable(K + "button.clear.tooltip")));
        addRenderableWidget(new Button.Builder(Component.translatable(K + "button.reset"), this::resetDefaults)
                .bounds(footerLeft + buttonW + GAP, footerY, buttonW, 20)
                .build());
        addRenderableWidget(new Button.Builder(Component.translatable(K + "button.done"), _ -> save())
                .bounds(footerLeft + (buttonW + GAP) * 2, footerY, buttonW, 20)
                .build());
    }

    private static List<Integer> ints(Integer... values) {
        return Arrays.asList(values);
    }

    private void toggle(int x, int y, String key, Supplier<Boolean> get, Consumer<Boolean> set) {
        cycle(x, y, key, List.of(true, false), get, set,
                v -> Component.translatable(v ? K + "value.on" : K + "value.off"));
    }

    private <T> void cycle(int x, int y, String key, List<T> values, Supplier<T> get, Consumer<T> set, Function<T, Component> label) {
        Component name = Component.translatable(K + "option." + key);
        Button button = new Button.Builder(Component.translatable(K + "option.format", name, label.apply(get.get())), b -> {
            int index = values.indexOf(get.get()); // -1 (hand-edited value) wraps to the first preset
            T next = values.get((index + 1) % values.size());
            set.accept(next);
            b.setMessage(Component.translatable(K + "option.format", name, label.apply(next)));
        }).bounds(x, y, BUTTON_WIDTH, 20).build();
        button.setTooltip(Tooltip.create(Component.translatable(K + "option." + key + ".tooltip")));
        addRenderableWidget(button);
    }

    private Component statsText() {
        DiskCache disk = PaintingsCache.disk();
        PaintingFetcher f = PaintingsCache.fetcher();
        return Component.translatable(K + "stats",
                disk.entryCount(), DiskCache.formatBytes(disk.sizeBytes()),
                f.diskHits.get(), f.downloads.get(), f.notModified.get(), f.failures.get());
    }

    private void clearCache(Button button) {
        button.active = false;
        PaintingsCache.workers().execute(() -> {
            PaintingsCache.disk().clear();
            Minecraft.getInstance().execute(() -> {
                button.active = true;
                if (stats != null) {
                    stats.setMessage(statsText());
                    stats.setPosition((width - stats.getWidth()) / 2, stats.getY());
                }
            });
        });
    }

    private void resetDefaults(Button button) {
        CacheConfig defaults = new CacheConfig();
        defaults.sanitize();
        Minecraft.getInstance().setScreenAndShow(new CacheConfigScreen(parent, defaults));
    }

    private void save() {
        PaintingsCache.apply(edit);
        Minecraft.getInstance().setScreenAndShow(parent);
    }

    @Override
    public void onClose() {
        // Esc = discard edits
        Minecraft.getInstance().setScreenAndShow(parent);
    }
}
