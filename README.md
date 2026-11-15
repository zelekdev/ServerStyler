# ServerStyler

Client-side Fabric mod that lets you customise multiplayer server names with
full **RGB colours**, **multi-stop linear gradients**, **opacity** (ARGB alpha)
and **formatting flags** (bold/italic). Styling is edited from inside Minecraft
native *Add Server* / *Edit Server* screen and rendered live on the server list.

## Features

- Colour picker: **vertical hue slider**, live **hex field**, live **opacity**
  (0-100%) field.
- **Solid** and **multi-stop gradient** (up to 3 stops) modes.
- **Alpha / opacity** with a checkered transparency preview and a 10% minimum
  preview floor so text always stays legible.
- Bold / Italic flags.
- **WYSIWYG** preview rendered straight inside the server-name text box (the
  field never shows markup).
- A 20x20 vanilla-styled trigger button next to the name field toggles a
  floating side panel.
- Styles are matched to servers by address and stored in
  `config/serverstyler/servers.json`.

## Supported versions

| Node | Toolchain | Status |
| ---- | --------- | ------ |
| **1.21.4** | obfuscated, remapping Loom, Java 21 | Fully implemented and verified (`./gradlew :1.21.4:build`) |
| **26.2** | unobfuscated, non-remapping Loom, Java 25 | Fully implemented and verified (`./gradlew :26.2:build`) |

Minecraft 26.1 removed obfuscation and replaced the GUI rendering + input model,
so the mod ships **two client implementations** that share one core:

- the version-agnostic core (config model, colour maths, gradient engine,
  styling engine) lives in `src/`,
- the version-specific GUI + mixins live in `versions/<mc_version>/src/client/`
  (classic `GuiGraphics` for 1.21.4, `GuiGraphicsExtractor` + extract/render-state
  and `MouseButtonEvent` input for 26.2).

## Multi-version build (Stonecutter)

The project uses [Stonecutter](https://stonecutter.kikugie.dev) plus
`dev.kikugie.loom-back-compat` to build one shared source tree for several
Minecraft versions. Nodes are declared in `settings.gradle`:

```groovy
stonecutter {
    create(getRootProject()) {
        versions '1.21.4', '26.2'
        vcsVersion = '1.21.4'
    }
}
```

`loom-back-compat` automatically selects the correct Fabric Loom plugin per
node: the remapping Loom (`net.fabricmc.fabric-loom-remap`) for obfuscated
versions (< 26.1) and the new non-remapping Loom (`net.fabricmc.fabric-loom`)
for the unobfuscated game (>= 26.1). It also aliases `modImplementation` ->
`implementation` and makes `loomx.applyMojangMappings()` a no-op where mappings do
not exist.

Per-node versions live in `versions/<mc_version>/gradle.properties`:

```properties
# versions/1.21.4/gradle.properties
minecraft_version=1.21.4
java_version=21
fabric_api_version=0.119.4+1.21.4
```

### Requirements

- JDK 21 to build the `1.21.4` node.
- JDK 25 (Gradle JVM) to build the `26.2` node, because Minecraft 26.1+ requires
  Java 25.
- Internet access on first run (Stonecutter + Loom download their toolchains).

### Build

```bash
# Windows
set JAVA_HOME=C:\path\to\jdk
./gradlew :1.21.4:build
./gradlew :26.2:build

# Linux / macOS
export JAVA_HOME=/path/to/jdk
./gradlew :1.21.4:build
./gradlew :26.2:build
```

Jars land in `versions/<mc_version>/build/libs/`.

### Run the client in a dev environment

```bash
./gradlew :1.21.4:runClient
```

## Configuration

The config file is written **asynchronously** and **atomically** (a `.tmp` file
is written, then moved over the real file) to:

```
config/serverstyler/servers.json
```

Schema (v1):

```json
{
  "schemaVersion": 1,
  "servers": {
    "play.example.com": {
      "enabled": true,
      "mode": "GRADIENT",
      "colors": ["#FF5555", "#55FFFF"],
      "opacity": 0.85,
      "bold": true,
      "italic": false
    }
  }
}
```

If the file is ever malformed it is moved aside to `servers.json.bak` and a
fresh default config is regenerated, so a bad file can never crash the game.

## How it works

**1.21.4 node** (`versions/1.21.4/src/client/`):

- `EditServerScreenMixin` injects a trigger button + the floating
  `StyleEditorPanel` next to the name field, installs a custom `EditBox`
  formatter that renders the styled name live (WYSIWYG) and persists the style on
  close.
- `ServerSelectionListMixin` redirects the single
  `GuiGraphics.drawString(Font, String, ...)` call in
  `OnlineServerEntry.render` (the server name) and renders the per-character
  gradient component instead; ping icons, player counts, MOTD and tooltips are
  left untouched.

**26.2 node** (`versions/26.2/src/client/`) mirrors the above against the 26.1+
API:

- `ManageServerScreenMixin` targets `ManageServerScreen` (the replacement for
  `EditServerScreen`), uses `EditBox.addFormatter(...)`, `GuiGraphicsExtractor`
  and `extractRenderState`.
- `ServerSelectionListMixin` redirects
  `GuiGraphicsExtractor.text(Font, String, ...)` inside
  `OnlineServerEntry.extractContent`.
- The sliders honour the new `MouseButtonEvent` / `MouseButtonInfo` input API and
  `extractWidgetRenderState` rendering.

Shared by both nodes: `TextStylingEngine` builds per-character `Component`s from
a `ServerStyle`, with a revision-keyed cache (`RenderCache`) so a static screen
costs nothing.

## Project layout

```
src/main/java          version-agnostic core (config model, colour maths, styling engine)
src/main/resources     fabric.mod.json + assets (shared)
src/client/java        version-agnostic client entrypoint (ServerStylerClient)
versions/1.21.4/       gradle.properties + src/client (classic GuiGraphics API)
versions/26.2/         gradle.properties + src/client (extract/render-state API)
```

## License

MIT.