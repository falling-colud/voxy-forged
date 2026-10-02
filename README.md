# Voxy NeoForge 1.21.1

> **Unofficial NeoForge port** of the Voxy mod

![Distant voxel LOD terrain rendered smoothly to the horizon](screenshots/withfixcurve.png)

## Special Thanks

**All credit for Voxy goes to [MCRcortex](https://github.com/MCRcortex)**, the original author and creator of this incredible LOD rendering mod.

- **Original Repository:** [MCRcortex/voxy](https://github.com/MCRcortex/voxy)
- **Original Author:** [MCRcortex](https://github.com/MCRcortex)
- **Fog and Iris fixes** ported from [NHblock-Johnsnow/neo-voxy](https://github.com/NHblock-Johnsnow/neo-voxy), a NeoForge Voxy fork built on this one.

This repository is a community port to NeoForge 1.21.1, created because the original author has indicated they will not be backporting to this version. We are deeply grateful for MCRcortex's work on Voxy.

## License Notice

The original Voxy mod is licensed under **All Rights Reserved** by MCRcortex. This port is provided for personal use. Please respect the original author's licensing terms.

---

## About

**Voxy** is a Level-of-Detail (LOD) rendering mod for Minecraft that extends your view distance far beyond vanilla limits by rendering distant terrain at lower detail levels.

## Screenshots

This port includes rendering fixes on top of the base NeoForge port. Each is toggleable in-game for comparison.

### LOD colour matching (`/voxy colorfix`)

Distant LOD terrain is tuned to match the brightness of the vanilla chunks it borders, instead of reading too dark or too light at the seam.

| Without fix | With fix |
|:---:|:---:|
| ![LOD without the colour fix](screenshots/wihtoutfixcolor.png) | ![LOD with the colour fix](screenshots/withfixcolor.png) |

### Seamless world curvature (`/voxy curvefix`)

Optional planet curvature now begins exactly at the vanilla render-distance edge and continues the flat terrain smoothly — no hard cut into the near chunks, and no snapping as you move.

| Without fix | With fix |
|:---:|:---:|
| ![World curve without the fix, cutting into flat terrain](screenshots/withoutfixcurve.png) | ![World curve with the fix, seamless](screenshots/withfixcurve.png) |

## Why This Port?

You might wonder: "Why not just use the Fabric version with [Sinytra Connector](https://github.com/Sinytra/Connector)?"

| Aspect | Native NeoForge Port (this repo) | Sinytra Connector |
|--------|----------------------------------|-------------------|
| **Performance** | No translation overhead | Runtime translation layer |
| **Mod Integration** | Native NeoForge API calls | Fabric API emulation via FFAPI |
| **Maintenance** | Must track upstream Voxy changes | Just drop in Fabric jar |
| **Stability** | Tested against NeoForge directly | May have edge cases from translation |
| **Dependencies** | Forgified Fabric API | Connector + Forgified Fabric API |

**Bottom line:** For a performance-critical LOD mod like Voxy, eliminating the translation layer overhead is worthwhile. If you prioritize simplicity and don't mind potential overhead, Sinytra Connector is a valid alternative.

## Status

**1.1.0** - Stable. Built and tested against the modern Sodium 0.8 backport for 1.21.1. See [CHANGELOG.md](CHANGELOG.md).

### Working Features
- LOD terrain rendering beyond vanilla render distance
- Smooth transitions between LOD and vanilla chunks (no see-through gap when chunks hand off to LOD)
- LOD colour/brightness matching, tunable live with `/voxy colorfix`
- Optional seamless world curvature, tunable live with `/voxy curvefix`
- Consistent water surface height across LOD levels
- LOD fog that follows the game's real fog (and Better Fog, when installed)
- Background distant generation on singleplayer/LAN worlds (`/voxy distantgen status|pause|resume|reset`)
- Iris shaderpacks that ship Voxy support (`voxy.json`), e.g. Complementary, BSL, Photon
- Block model baking for all render types (solid, cutout, cutout_mipped, translucent)
- Delayed chunk unloading to prevent pop-out effects

### Current Limitations
- Requires Sodium 0.8.12-beta.2 (NeoForge version) — the modern Sodium 0.8 backport for MC 1.21.1
- Some optional integrations not yet ported (Nvidium, Vivecraft)
- The in-game Voxy page inside Sodium's video settings is not available (Sodium 0.8 rewrote that API); configure Voxy via the Mods config menu instead
- Debug screen integration disabled (MC 1.21.1 API changes)

## Requirements

### Required Dependencies

| Dependency | Version | Link |
|------------|---------|------|
| Minecraft | 1.21.1 | - |
| NeoForge | 21.1.219+ | [NeoForge](https://neoforged.net/) |
| Sodium | mc1.21.1-0.8.12-beta.2-neoforge | [Modrinth](https://modrinth.com/mod/sodium/version/mc1.21.1-0.8.12-beta.2-neoforge) |
| Forgified Fabric API | 0.116.7+2.2.0+1.21.1 | [Modrinth](https://modrinth.com/mod/forgified-fabric-api/version/0.116.7+2.2.0+1.21.1) |

### Recommended Dependencies

| Dependency | Purpose | Link |
|------------|---------|------|
| Reese's Sodium Options | Better settings UI for Sodium + Voxy config access | [Modrinth](https://modrinth.com/mod/reeses-sodium-options) |
| Lithium | General performance improvements | [Modrinth](https://modrinth.com/mod/lithium) |

## Installation

> **Note:** Due to Voxy's ARR (All Rights Reserved) license, compiled JARs are not distributed. You must build from source.

1. Install NeoForge for Minecraft 1.21.1
2. Install required dependencies (see above)
3. Build Voxy from source (see below)
4. Place the built JAR in your `mods` folder

## Building from Source

You need **JDK 21** (for example [Temurin 21](https://adoptium.net/temurin/releases/?version=21)) and an internet connection. Nothing else: the Gradle wrapper downloads Gradle itself, and Python is optional (it only runs extra validation scripts, which are skipped without it).

```bash
git clone https://github.com/falling-colud/voxy-forged.git
cd voxy-forged
./gradlew build
```

On Windows, use `gradlew.bat build` in Command Prompt, or `.\gradlew.bat build` in PowerShell.

The built JAR is `build/libs/voxy-<version>.jar`. The first build takes a few minutes while NeoForge sets up Minecraft; later builds take seconds.

## Contributing

For development guidelines, see [CLAUDE.md](CLAUDE.md).

### Validation Scripts

The `scripts/` directory contains build validation tools used in CI.

## Links

- **Original Voxy:** [github.com/MCRcortex/voxy](https://github.com/MCRcortex/voxy)
- **This Port:** [github.com/falling-colud/voxy-forged](https://github.com/falling-colud/voxy-forged)
- **NeoForge base port:** [github.com/j-shelfwood/voxy-neoforge](https://github.com/j-shelfwood/voxy-neoforge)
- **Sinytra Connector (alternative):** [github.com/Sinytra/Connector](https://github.com/Sinytra/Connector)
