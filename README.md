# ServerStyler

**Make your multiplayer server list look the way you want.**

ServerStyler lets you give every server in your list its own name style — real RGB colours, smooth multi-colour gradients, transparency, bold/italic/small-caps and left/centre/right alignment. You edit it right inside Minecraft's normal *Add Server* / *Edit Server* screen, and you see the result live while you type.

Client-side only. It works on every server, including vanilla ones, and nobody else has to install anything.

---

## What you can do

- **Any colour you want** — a hue slider, a hex field and a live preview, not a fixed palette.
- **Gradients with up to 5 stops** — drag the handles to place each colour exactly where you want it along the name.
- **Transparency** — fade a name out with an opacity slider, with a checkerboard preview so you can see what you are doing.
- **Bold, italic and small caps** — small caps also handles Polish letters (ą → ᴀ, ł → ʟ, and so on).
- **Alignment** — left, centre or right, so names line up the way you like in the list.
- **Live preview while typing** — the name box shows the finished look, never any markup.
- **Recent colours** — the last eight colours you used sit beside the hex field; one click re-applies one.
- **Undo and redo** — every change is reversible, up to twenty steps back.
- **Reset** — one button clears the style back to the plain default.
- **Per-server styles** — each server keeps its own look, matched by its address.

---

## Installation

1. Install **Fabric Loader** for your Minecraft version.
2. Drop **[Fabric API](https://modrinth.com/mod/fabric-api)** into your `mods` folder.
3. Drop the ServerStyler jar that matches your Minecraft version into `mods`.
4. Start the game.

> **Pick the right jar.** Each jar covers a range of Minecraft versions — see the table below. Installing the wrong one will make Fabric refuse to load it.

### Which jar do I need?

| Your Minecraft version | Jar to download |
| --- | --- |
| 1.21.4 – 1.21.5 | `serverstyler-1.21.4-…` |
| 1.21.6 – 1.21.8 | `serverstyler-1.21.6-…` |
| 1.21.9 – 1.21.11 | `serverstyler-1.21.9-…` |
| 26.1 – 26.3 | `serverstyler-26.1-…` |

Java 21 is required up to 1.21.11; Minecraft 26.1 and newer need Java 25.

---

## How to use it

1. Open **Multiplayer** and click **Add Server** (or select a server and click **Edit**).
2. Click the small **ServerStyler button** next to the server name field. A panel opens beside it.
3. Style the name:
   - **Mode** switches between a single solid colour and a gradient.
   - Click a handle on the colour bar to choose which stop you are editing, then drag it to move it.
   - **+** and **–** add and remove gradient stops (up to 5).
   - The **hue slider**, **hex field** and **opacity slider** change the selected stop.
   - **B**, **I** and **Aa** toggle bold, italic and small caps.
   - **L**, **C** and **R** set the alignment.
   - **On/Off** temporarily disables the style for that server without deleting it.
   - **<** and **>** undo and redo the last change; **Reset** clears the style back to the default.
   - The eight small squares beside the hex field are your recent colours — click one to reuse it.
4. Click **Done**. The name in your server list updates immediately.

The panel always opens next to the name field and flips to the other side when there is not enough room, so it stays on screen at any GUI scale. At GUI scale 3 and above it automatically switches to a more compact layout so it never covers the whole screen.

### Presets

Next to or below the editor there are two small windows: ready-made styles and your own saved ones.

- **Built-in presets** — twelve ready styles (Sunset, Ocean, Neon, Fire, Ice, Candy, Forest, Gold, Blood, Ghost, Rainbow, Royal). One click applies the whole look: colours, gradient, formatting and alignment.
- **Save current** — type a name and press **Save** to store the style you are editing. Saving the same name again overwrites it.
- **Your presets** — three per page, with **<** and **>** to move between pages, so the list never grows off the bottom of the screen. Click a name to apply it, **R** to rename it to the name typed above, **D** to save a copy under that name, or the **x** button to delete it.

Presets are shared across every server: save a look once, then apply it to any other server with one click.

---

## Where your styles are stored

Everything lives in two small files inside your Minecraft folder:

```
config/serverstyler/servers.json   your per-server styles
config/serverstyler/presets.json   your saved presets
```

You normally never need to touch them. If you do edit one by hand, keep a copy first — the mod validates what it reads and will fall back to a backup rather than crash if something is wrong.

Styles are tied to the server **address**, so if you rename a server in the list the style follows it. If you change the address, the style stays with the old address.

---

## Troubleshooting

**The button does not appear next to the name field.**
Make sure you are on the *Add Server* / *Edit Server* screen, not the server list itself. If it is still missing, check that Fabric API is installed and that the jar matches your Minecraft version.

**My style disappeared after an update.**
Your config is upgraded automatically and the previous file is kept next to it as `servers.json.bak`. If a style looks wrong after a big update, that backup is your safety net.

**The preset window is not visible.**
It opens together with the editor panel and sits to its right, or directly below it when there is no room on the right. It never covers the name field. At GUI scale 3 and above both windows switch to a compact layout so they still fit on screen.

**The name is cut off in the list.**
Long names are shortened with `...` so they never spill over the player count. Shorten the name or use a smaller style.

**Undo does nothing, or the recent colours are empty.**
Both are per-session conveniences: the undo history and the recent-colour palette live in memory only and start empty each time you open the screen. They are never written to your config files.

**A name is hard to read.**
Very transparent names blend into the list background. Keep opacity above roughly 40% for readability.

**Does this work on servers?**
Yes. It only changes what *you* see in your own server list. Servers never receive anything from this mod.

---

## Building from source

Only needed if you want to compile it yourself.

```bash
./gradlew build
```

The jars appear in `versions/<minecraft version>/build/libs/`. You need JDK 21 for the 1.21.x targets and JDK 25 for the 26.x targets.

---

## Credits

- **zelekdev** — idea, design and testing
- **DeepSeek** — a large share of the implementation

Licensed under the **MIT License** — see [LICENSE](LICENSE).

Found a bug or have an idea? [Open an issue](https://github.com/zelekdev/ServerStyler/issues).
