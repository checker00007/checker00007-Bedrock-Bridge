# checker00007 Bedrock Bridge

Open-source, ad-free Android project for managing a GeyserMC Bedrock server connection for Xbox Series consoles.

**Status: v0.1 scaffold only.** Stores a single server profile locally. LAN discovery, NetherNet signaling, forwarding and Xbox connectivity are **not implemented**. No Minecraft server or router settings are changed.

## Build

Requires JDK 17, Android SDK 35 and Gradle 8.11.1. Run `gradle :app:assembleDebug`. GitHub Actions builds a debug APK.

## Network limitation

Classic LAN discovery requires Android and Xbox on the same discoverable LAN. The Geyser endpoint may be remote, provided it is reachable from the Xbox. A phone on a separate network cannot announce a LAN game to the Xbox without another mechanism.

Apache-2.0 license.