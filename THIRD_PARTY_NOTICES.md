# Third-party notices

Duel Dojo's own code and artwork are original and licensed under MIT (see `LICENSE`).
The textures in `src/main/resources/assets/dueldojo/` are drawn by `tools/generate-art.ps1`
and contain no Minecraft or third-party assets. No Minecraft files are included in the mod. The
sword icon in tier tags is the game's own diamond sword texture, which `font/icons.json` only points to;
it is loaded from your game (or resource pack) at runtime.

The mod is built with, or depends at runtime on, the following projects. None of their code is
copied into Duel Dojo; they are used as libraries or build tools under their own licenses.

| Project | Used for | License |
|---|---|---|
| [Fabric Loader](https://github.com/FabricMC/fabric-loader) | Mod loader (runtime dependency) | Apache-2.0 |
| [Fabric API](https://github.com/FabricMC/fabric) | Events, commands, entity registration, renderers (runtime dependency) | Apache-2.0 |
| [Fabric Loom](https://github.com/FabricMC/fabric-loom) | Gradle build plugin (build time only) | MIT |
| [fabric-example-mod](https://github.com/FabricMC/fabric-example-mod) | Project skeleton (Gradle files) this project started from | CC0-1.0 |
| [Gradle wrapper](https://gradle.org) | Build tool (build time only) | Apache-2.0 |
| Mojang official mappings | Readable names at build time only; not redistributed | Mojang mappings license |
| Gson, SLF4J, Brigadier | Bundled with Minecraft itself; used via the game | Apache-2.0 / MIT / MIT |

Minecraft is a trademark of Mojang Studios / Microsoft. Duel Dojo is not an official Minecraft
product and is not approved by or associated with Mojang or Microsoft.
