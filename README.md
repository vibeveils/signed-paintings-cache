# Signed Paintings Cache

A client-side Fabric companion for [Signed Paintings](https://github.com/Nettakrim/Signed-Paintings)
(Minecraft **26.3**, built against Signed Paintings **1.2.17-mc26.3**).

Signed Paintings downloads every painting image again each time you launch the game, and again whenever an
image hasn't been looked at for ~15 minutes, or after you disconnect from a server. This mod keeps the
images on disk, so after the first download they load from your drive instead of the web.

## What it does

- **Persistent disk cache** – images are saved to `.minecraft/signed_paintings_cache/` and reused across sessions,
  servers, disconnects and Signed Paintings' own memory expiry. Raw image bytes are stored, so changing quality
  options never re-downloads anything.
- **Smart re-checking** – after a configurable age, the mod asks the image host "has this changed?" (ETag /
  Last-Modified). Most hosts reply with a tiny *304 Not Modified*. If the host is down or you're offline, the
  older cached copy is shown instead of a blank sign.
- **Fewer, calmer requests** – duplicate requests for the same image share one download, failed/dead links are
  not re-requested every frame (configurable back-off), downloads are limited to N at a time, and oversized files
  are refused.
- **Fewer lag spikes** – decoding and every resize run on low-priority background threads. Signed Paintings normally
  resizes per-painting textures on the render thread; this moves that work off it. Very large images are subsampled
  while decoding, so a 12000px photo never needs half a gigabyte of RAM.
- **Quality, LOD and memory options** (below).

`/paintings:reload` still works as expected: it re-checks the image with its server rather than trusting the cache.
Domain trust, blocking and everything else in Signed Paintings is untouched; this mod only replaces the step that
fetches the bytes, using the same User-Agent and the same "no redirects" rule.

## Options

Open with **`/paintingscache`**, or the config button in Mod Menu if installed. Every option has a tooltip.
Stored in `config/signed_paintings_cache.json`.

| Option | Default | What it does |
|---|---|---|
| Disk cache | On | Keep images on disk between sessions |
| Cache size | 1 GB | Disk limit; least recently seen images are removed first |
| Re-check after | 30 days | Age before asking the host for a newer version (Never = cache forever) |
| Smart re-check | On | Use conditional requests (304) instead of re-downloading |
| Retry failed | 5 min | Back-off before retrying a broken/unreachable image |
| Parallel downloads | 4 | Simultaneous downloads |
| Max image size | 32 MB | Larger downloads are refused |
| Max resolution | Original | Shrink images bigger than this on load to save memory. Original = pixel-identical to Signed Paintings |
| Shrink filter | Auto | Only used for Max resolution. Auto keeps pixel art and pixelated images on an exact pixel grid and shrinks photos smoothly. Also Nearest, Bilinear, Bicubic, Smooth |
| Keep hard edges | On | Cutout images stay opaque/clear after a smooth shrink, keeping the cheaper render path |
| Texture detail | 100% | LOD: scales every painting texture down to save VRAM |
| Max texture | Unlimited | LOD: caps each painting's texture, including high pixels-per-block ones |
| Reuse for upscales | On | LOD: draw the original instead of making enlarged copies of small images (identical look, far less VRAM) |
| Unload textures after | 2 min | How long an unseen painting keeps its GPU texture |
| Forget images after | 15 min | How long an unseen image stays in RAM (then reloads from disk, not the web) |

Changing a quality/LOD option rebuilds loaded paintings from the disk cache straight away.

**Pixel fidelity:** the pixels-per-block setting on signs (and the sign editor preview) always runs Signed Paintings'
own nearest-neighbour resizing, untouched, so deliberately pixelated paintings look exactly as their placer saw them.
With default settings every painting is byte-identical to Signed Paintings without this mod.

## Commands

- `/paintingscache` – options screen
- `/paintingscache stats` – cache size, and this session's disk loads, downloads, 304s, failures
- `/paintingscache clear` – delete the disk cache
- `/paintingscache refresh` – re-check every loaded painting with its server

Turning on Signed Paintings' detailed logging (`/paintings:toggle`) also logs every cache hit/download.

## Building

Requires JDK 25.

```
./gradlew build
```

The jar is in `build/libs/signed_paintings_cache-1.0.1+mc26.3.jar`. Put it in `mods/` next to Signed Paintings
and Fabric API. `./gradlew runClient` starts a dev client with Signed Paintings loaded.

`src/main/java/com/terraformersmc/` contains two compile-only copies of Mod Menu's API interfaces so the optional
Mod Menu button builds without pinning a Mod Menu version; they are excluded from the jar.

## Compatibility notes

The mod hooks Signed Paintings internals (`ImageManager.downloadImageBuffer`, `scaleImage`, `onTick`, the reload
methods, `ImageData.getIdentifier`). The download hook is required: if a future Signed Paintings renames it, the game
reports a mixin error rather than silently caching nothing. All other hooks are optional and simply switch off if
their target changes. Version bumps should normally only need `signed_paintings_version` in `gradle.properties`.

The cache folder holds plain image files from the URLs you've seen on signs; `/paintingscache clear` or deleting the
folder removes them.
