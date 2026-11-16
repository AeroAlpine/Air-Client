<h1 align="center">Air Client</h1>

<p align="center">
  <img src="https://github.com/user-attachments/assets/89dae206-827e-4909-95d3-ef8a4218c574" alt="Air Client – Hypixel SkyBlock mod and Fabric client logo" width="720" />
</p>

<p align="center"><strong>Hypixel SkyBlock macro, Bazaar flipper and AH flipper in one free Fabric client.</strong></p>

<p align="center">
  <a href="https://github.com/AeroAlpine/Air-Client/releases">
    <img src="https://img.shields.io/github/v/release/AeroAlpine/Air-Client?style=flat-square&amp;color=38bdf8" alt="Latest Air Client release" />
  </a>
  <a href="#installation">
    <img src="https://img.shields.io/badge/Fabric-Minecraft_26.2-38bdf8?style=flat-square" alt="Fabric for Minecraft 26.2" />
  </a>
  <a href="https://github.com/AeroAlpine/Air-Client/releases">
    <img src="https://img.shields.io/github/downloads/AeroAlpine/Air-Client/total?style=flat-square&amp;color=38bdf8" alt="Downloads" />
  </a>
  <a href="LICENSE">
    <img src="https://img.shields.io/badge/License-AGPL--3.0-38bdf8?style=flat-square" alt="AGPL-3.0 license" />
  </a>
</p>

<p align="center">
  <a href="https://github.com/AeroAlpine/Air-Client/releases"><strong>Download</strong></a>
  · <a href="#features">Features</a>
  · <a href="#installation">Installation</a>
  · <a href="#hotkeys">Hotkeys</a>
  · <a href="#faq">FAQ</a>
</p>

**Air Client** is an open-source Minecraft Fabric client for **Hypixel SkyBlock**. It brings a
**Bazaar flipper**, an **Auction House (AH) flipper**, auto-bidding, lowballing, a combat macro
with pathfinding, an Experiment Table solver and investment tools together with a full
client toolkit: ESP, Xray, KillAura, a liquid-glass HUD and built-in voice chat.

<p align="center">
  <img src="https://github.com/user-attachments/assets/6491ab15-b6e2-4656-8b89-96bc15a3435a" alt="Air Client settings menu with SkyBlock flipper and macro controls" width="960" />
</p>

## Features

### SkyBlock trading and flipping

| Module | What it does |
| --- | --- |
| **Bazaar Flipper** | Places buy orders and sell offers, always 0.1 coins better than the best price. Limits for total investment, per session and per item, Bazaar daily limit tracking, item mode for picking your own items, and filters for coins/hour, margin, weekly volume and price swings. |
| **Bazaar Flips** | Live list of the best Bazaar flips right now, sorted by coins per hour, using the same filters as the bot. |
| **Auction Flipper (AH flipper)** | Finds underpriced BIN auctions, buys them and lists them again for profit. Minimum profit after tax, bankroll limit, lowest-BIN checks and an item blocklist (press **K** on an item). |
| **Auto-Bid** | Bids on normal auctions that end soon and are worth more than the bid, and lists won items automatically. |
| **Lowballing** | Walks the hub, asks players for items and makes low offers. Smart chat replies, profit and bankroll limits, looping rounds. |
| **AH Investments** | Finds auction items that are cheap now and expected to rise over the next days. |
| **Fire Sale Skins** | Rates Fire Sale skins by expected return after the sale ends, with trend, confidence and a backtest. |
| **Item Value** | Adds the estimated market value to item tooltips, optionally broken down into enchants, stars and gems. |
| **Profit tracking** | Profit HUD for SkyBlock overall and per module, plus on-screen panels for every bot. |

### SkyBlock macros and helpers

| Module | What it does |
| --- | --- |
| **Combat macro** | Walks to the chosen mobs with the pathfinder, fights them and moves on. Mob picker, one-hit mode, target queue, adjustable CPS and calm camera movement. |
| **Pathfinder** | How the bots move: jumps, parkour, safety distances, AOTV assist, stuck detection and learning from mistakes. |
| **Experiment Table solver** | Solves Chronomatron, Ultrasequencer and Superpairs with adjustable click delay. |
| **Slayer Highlight** | Outlines your slayer boss and its minibosses. |
| **Etherwarp helper** | Shows where an Etherwarp would land before you use it. |
| **Visuals** | Hide particles and mob names, glass mob health bars, Bazaar order colors (outbid / best price), player outlines. |
| **Sessions and breaks** | Session length and automatic breaks for the bots, server restart handling, and bots keep running while the game window is in the background. |

### Client features

- **Render:** Mob ESP, Storage ESP, Xray, Fullbright, Hitboxes, projectile preview, Zoom, shoulder cam.
- **Combat and automation:** KillAura (players and mobs), Auto Tools, Fast Place, hand swap, Force Sneak.
- **HUD:** drag-and-drop HUD editor, FPS, ping, CPS, speed, keystrokes, coordinates, arraylist, scoreboard, chat, custom crosshair, docked hotbar, Spotify widget.
- **Liquid glass UI:** glass menus and HUD, custom tint, damage glass effect and weather glass (rain and frost).
- **Voice chat:** LabyMod voice chat with push-to-talk, noise suppression and per-player volume.
- **Profiles and updates:** settings profiles and one-click updates from the main menu.

## Installation

1. Install **Fabric Loader 0.19.5+** for **Minecraft 26.2** and use **Java 25+**.
2. [Download the latest Air Client release](https://github.com/AeroAlpine/Air-Client/releases) and put the JAR together with **Fabric API** into your `mods` folder.
3. Start the Fabric profile and press **Right Shift** to open the settings.

When a newer release is out, the main menu shows an **Update** button. Download it there and restart Minecraft. See the [updater notes](docs/AUTO_UPDATER.md).

**Build from source:** with Java 25, run `./gradlew build` (`gradlew.bat build` on Windows). The JAR is written to `build/libs/`.

## Hotkeys

| Key | Action |
| --- | --- |
| **Right Shift** | Settings (Controller Center) |
| **G** | HUD editor |
| **V** | Voice chat menu |
| **Caps Lock / M** | Push to talk / mute |
| **F7** | Bazaar Flipper on/off |
| **F9** | Auction Flipper on/off |
| **F6** | Lowballing on/off |
| **F10** / **F8** | AH Investments / Fire Sale screen |
| **K** | Block the hovered item for the AH flipper |
| **Left Alt** | Release the mouse while a bot runs |
| **Z** / **C** / **R** | KillAura / Zoom / shoulder cam |

All keys can be changed in the Minecraft controls menu. While a bot is running, **Escape** releases the mouse without opening the pause menu. Use the bot's toggle key or **All off** to stop everything.

## FAQ

**Which Minecraft version does Air Client support?**
Minecraft 26.2 with Fabric Loader 0.19.5 or newer and Java 25.

**Is Air Client free?**
Yes. It is open source under the AGPL-3.0 license.

**Does it have a Bazaar flipper and an AH flipper?**
Yes. Both are built in, together with Auto-Bid, lowballing, AH investments and Fire Sale skin ratings.

**Is it a hack client?**
It contains macros and client features like ESP, Xray and KillAura. Read the disclaimer below before you use them anywhere.

## License

Licensed under the [GNU Affero General Public License v3.0 (AGPL-3.0-only)](LICENSE).
See [NOTICE](NOTICE) for copyright and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)
for bundled components. The Air Client name and logo are not covered by the AGPL.

## Usage disclaimer

Use this client at your own risk. Follow the rules and terms of every server you connect to.

Do not use automation or cheating features on the Hypixel network or on any server that prohibits them. Use the SkyBlock automation features only on private testing instances or independent servers that are unaffiliated with Hypixel and whose operators explicitly permit automation and the specific features you enable.

Air Client is not affiliated with or endorsed by Hypixel. This notice does not override server rules, applicable terms, or third-party rights.
