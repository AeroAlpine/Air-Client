<h1 align="center">Air-Client</h1>

<p align="center">
  <img src="https://github.com/user-attachments/assets/89dae206-827e-4909-95d3-ef8a4218c574" alt="Air-Client logo" width="720" />
</p>

<p align="center"><strong>Automation, trading tools, voice chat, and a customizable Minecraft experience.</strong></p>

<p align="center">
  <a href="https://github.com/AeroAlpine/Air-Client/releases">
    <img src="https://img.shields.io/github/v/release/AeroAlpine/Air-Client?style=flat-square&amp;color=38bdf8" alt="Latest Air-Client release" />
  </a>
  <a href="#how-to-use">
    <img src="https://img.shields.io/badge/Mod_loader-Fabric-38bdf8?style=flat-square" alt="Fabric mod loader" />
  </a>
</p>

<p align="center">
  <a href="https://github.com/AeroAlpine/Air-Client/releases"><strong>Download Air-Client</strong></a>
  · <a href="#how-to-use">Installation guide</a>
  · <a href="https://github.com/AeroAlpine/Air-Client">Source code</a>
</p>

A Minecraft Fabric client combining SkyBlock market tools, automation, LabyMod voice chat, and a glass-style interface.

<p align="center">
  <img src="https://github.com/user-attachments/assets/6491ab15-b6e2-4656-8b89-96bc15a3435a" alt="Air-Client demo showing the settings interface and feature controls" width="960" />
</p>

## Features

- **Market tools:** Auction and Bazaar flippers, automated lowballing, investment tracking, and profit displays.
- **Your interface:** Customizable HUD elements and glass-style settings to make the client your own.
- **Voice chat:** LabyMod voice chat integration with dedicated in-game controls.
- **Updates:** Release detection and downloads from the main menu.

## How to use

Build it yourself with **Java 25** using `./gradlew build` (`gradlew.bat build` on Windows), or [download the latest build here](https://github.com/AeroAlpine/Air-Client/releases).

1. Install **Fabric Loader 0.19.5+** for **Minecraft 26.2** with **Java 25+**.
2. Put the client JAR and **Fabric API** in your Minecraft instance's `mods` folder. For local builds, use `build/libs/Air-Client-0.3.1.jar`.
3. Launch the Fabric profile. Press **Right Shift** for client settings, **G** to edit the HUD, or **V** for voice chat settings.

The main menu shows the installed version and an **Update** button when a newer GitHub release is available. Download it there, then quit and relaunch Minecraft to apply it. See [updater and release instructions](docs/AUTO_UPDATER.md).

While the Bazaar flipper, AH flipper, bid macro, or lowballer is active, switching windows keeps it running. Escape releases the mouse without opening a blocking pause menu. Stop the bots with their toggle keys or **All off** to use the normal pause menu again.

## License

Licensed under the [GNU Affero General Public License v3.0 (AGPL-3.0-only)](LICENSE).
See [NOTICE](NOTICE) for copyright and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)
for bundled components. The Air Client name and logo are not covered by the AGPL.

## Usage disclaimer

Use this client at your own risk. Follow the rules and terms of every server you connect to.

Do not use automation or cheating features on the Hypixel network or on any server that prohibits them. Use the SkyBlock automation features only on private testing instances or independent servers that are unaffiliated with Hypixel and whose operators explicitly permit automation and the specific features you enable.

Air Client is not affiliated with or endorsed by Hypixel. This notice does not override server rules, applicable terms, or third-party rights.
