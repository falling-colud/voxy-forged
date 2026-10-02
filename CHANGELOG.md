# Changelog

## 1.1.0 — 2026-10-02

### Fixes
- **Shaders: distant terrain is lit and shadowed correctly again.** Ported upstream's LOD depth fix that belongs with the `VOXY=2` define: after the opaque LOD pass, the LOD depth buffer is reset to "far" wherever vanilla terrain was drawn, so a pack's own LOD shadow/ambient/fog path sees LODs only where LODs are. A pack that does not want it can set `"skipShaderDepthHackFix": true` in its `voxy.json`.
- **Settings no longer reset on every launch.** The config writer also stored one of the mod's constants in `voxy-config.json`, which cannot be read back, so the file was thrown away and rewritten with defaults on each start after the first save.
- **Fixed the game hanging on "Saving worlds" when leaving a world while distant generation was running.** Vanilla's chunk unload loop retries a chunk that is still claimed by a generation task without ever yielding; on world close that loop has no time limit, and the claims could only be released by the thread stuck in it. Each pass over the unload queue now handles the entries it started with and no more.
- **Distant generation no longer stalls itself.** The same loop burned the rest of every server tick while generation was in flight, which pushed MSPT to the configured limit and paused new chunks. Measured on the same hardware: 3523 chunks in a minute instead of 2775, at a lower MSPT.

### Distant generation (new)
- **Voxy now generates the terrain beyond your render distance by itself** on the integrated server (singleplayer/LAN host). Chunks are generated in expanding, distance-sorted rings around the player and ingested straight into the LOD store, so distant LODs fill in without ever having visited the area or importing region files.
- Smart/fast by design:
  - Chunks are requested at the `LIGHT` chunk status (blocks + biomes + light) and never loaded as ticking chunks, then released, so they unload right after ingestion. Generation runs on the vanilla chunk-system worker pool via async futures (`getChunkFutureMainThread`), never blocking the server thread; each chunk is kept alive during generation by a dedicated no-timeout ticket.
  - Progress is remembered per world/dimension in a compact bitmap (1 bit per chunk, `<world>/voxy/distantgen/`), so already-ingested chunks are skipped across sessions and re-centers.
  - Backpressure-aware: new chunks only start while server MSPT is below a configurable limit, the voxel ingest queue is shallow, and the save queue isn't backed up.
- New config options (Sodium video settings page "Distant Generation" + `voxy-client.toml`): enable/disable, radius (default 96 chunks), concurrent chunk budget, MSPT limit.
- New commands: `/voxy distantgen status|pause|resume|reset`.
- When the standalone **Groundwork** pregeneration mod is installed, Voxy's built-in distant generation steps aside automatically - Groundwork takes over generation and feeds Voxy through its event bridge.

### Fog (reworked)
- **LOD fog now follows the game's real fog.** The final fog of each frame (including changes other mods make) is reproduced on LOD terrain with the same smoothstep curve Sodium uses, so there is no seam where near chunks hand off to LODs. Ported from [neo-voxy](https://github.com/NHblock-Johnsnow/neo-voxy).
- Water, lava, blindness and darkness fog always apply to LODs; only plain distance fog is pushed out to the LOD distance.
- Better Fog is detected and left in charge of the fog; the LODs get a matching second fog line that starts at Better Fog's opacity at the vanilla render distance and reaches full opacity at the LOD fog edge.
- New options: sky fog distance, fog intensity, fog density, fog start/end distance, cave fog (fades in when fully underground, off by default).
- Clouds can extend with the LOD render distance, or use a fixed distance.

### Shader (Iris) fixes
- Non-mipped depth samplers, `VOXY=2` versioned define, dynamic lightmap sampler, and a pack-marker strip fix (ported from neo-voxy).

### Chunk ingest fixes
- Chunks are ingested as soon as they have block data and light, so the outermost loaded ring no longer shows a void at the render-distance edge on servers.
- Chunks are always captured right before they unload (previously only with Bobby installed).
- Fixed the client chunk ring buffer occasionally handing back the wrong chunk, which re-ingested an unrelated chunk and skipped the requested one.

## 1.0.0 — 2026-06-26

First stable release of the NeoForge 1.21.1 port, updated to the modern **Sodium 0.8** backport.

### Sodium 0.6 → 0.8 compatibility
- Updated the Sodium dependency to `mc1.21.1-0.8.12-beta.2-neoforge`.
- Reworked the build to compile against Sodium 0.8: its real classes ship inside a JiJ'd nested jar, which is now extracted onto the compile classpath, and Sodium is provided on the runtime classpath so the dev environment loads it like a normal install.
- Updated `ShaderLoader` for Sodium 0.8's `ShaderParser.parseShader()` now returning a `ParsedShader`.
- Updated the chunk-render mixins for Sodium 0.8 signature changes:
  - `RenderSectionManager` constructor gained a `SortBehavior` parameter.
  - `DefaultChunkRenderer.render()` gained a trailing `boolean` parameter.
  - The chunk fade-in suppression hook (`RenderRegionManager`) is now optional — Sodium 0.8 removed the chunk fade-in entirely, so there is nothing to suppress.
- Dropped the Sodium video-settings options page (Sodium 0.8 rewrote that GUI API). Voxy settings remain available via the Mods config menu.

### Fixes
- **Fixed chunks briefly disappearing (see-through gap) when they hand off from full detail to LOD.** Sodium 0.8 reports `RenderSection.setInfo() == false` during section disposal, which previously skipped clearing Voxy's LOD-occlusion mask, leaving the area masked-but-empty until something else cleared it. The mask is now cleared immediately when Sodium disposes a section, while transient rebuilds keep the existing smoothing delay.

### Requirements
- Minecraft 1.21.1
- NeoForge 21.1.219+ (required by Forgified Fabric API)
- Sodium `mc1.21.1-0.8.12-beta.2-neoforge` (or newer 0.8.x)
- Forgified Fabric API `0.116.7+2.2.0+1.21.1`
