# checker00007 Bedrock Bridge

Werbefreie Android-App für den Beitritt zu Minecraft Java über Geyser und Floodgate auf der Xbox Series. Serverprofile bleiben lokal auf dem Smartphone. Es gibt keine Konten, Werbung, Tracker oder Abos.

**v0.2.0 ist eine Testversion.** Die automatisierten Tests simulieren LAN-Discovery, den NetherNet-Signaling-Austausch und die RakNet-UDP-Weiterleitung. Ein Beitritt mit einer echten Xbox steht aus.

## APK installieren und auf der Xbox testen

1. Lade die [Android-APK v0.2.0](https://github.com/checker00007/checker00007-Bedrock-Bridge/releases/download/v0.2.0/checker00007-Bedrock-Bridge-v0.2.0-debug.apk) direkt herunter und öffne sie auf Android 8 oder neuer. Der Download erfordert kein GitHub-Konto. Weitere Versionen findest du unter [GitHub Releases](https://github.com/checker00007/checker00007-Bedrock-Bridge/releases). Falls Android die Installation wegen einer anders signierten älteren Debug-APK ablehnt, geht beim Deinstallieren der alten App auch deren lokales Profil verloren.
2. Verbinde Smartphone und Xbox mit demselben IPv4-LAN. Die Xbox kann per Ethernet am selben Router hängen. Ein Gastnetz mit Client-Isolation kann die LAN-Erkennung blockieren.
3. Wähle **Server hinzufügen** und speichere den Geyser-Host und Bedrock-Port, meistens 19132. Wähle für Bedrock 26.60 oder neuer **NetherNet**. Die LAN-Vorgabe ist 26.60 / Protokoll 2225. Die Anzeigeparameter lassen sich im Profil ändern.
4. Tippe **Diagnose**. **HTTPS** nutzt den angegebenen Port. **HTTPS 443** nutzt Port 443. Wähle **HTTP** nur, wenn dein Endpunkt bereits für unverschlüsseltes Signaling vorgesehen ist. Das Prüfziel ist `GET /v1/join`. Ein erfolgreicher Abruf prüft die HTTP-Strecke vom Smartphone. Der eigentliche Medienpfad und der Login werden erst beim Beitritt geprüft.
5. Tippe **LAN starten**. Öffne auf der Xbox Minecraft und suche unter **Spielen → LAN-Spiele** bzw. im Freunde-/Weltenbereich nach dem Namen deines Profils. Prüfe, ob der Eintrag erscheint, ob der Beitritt gelingt und ob du dich in der Welt bewegen kannst.
6. Wenn der Test scheitert, kopiere das Protokoll aus der App und notiere Minecraft-Version, Xbox-Fehlermeldung und ob die Welt sichtbar war. Bei `NetherNet-LAN-Suche beantwortet` hat die App eine Anfrage gesehen. Bei `SDP-Antwort gesendet` hat sie die Signaling-Antwort zugestellt, ohne die Spielverbindung zu messen.

## Verbindungstechnik und Grenzen

- **NetherNet (experimentell):** Das Smartphone beantwortet Discovery auf UDP 7551 und übersetzt das LAN-Angebot in `POST /v1/join/{clientNetworkId}`. Es reicht die SDP-Antwort an die Konsole durch. Die App terminiert kein WebRTC und verändert weder Identitätsnachweise noch DTLS-Fingerprints. Die Xbox muss die angebotenen ICE-Kandidaten des Zielservers erreichen. Je nach Geyser-Build kann die Xbox-Anmeldung mit einem LAN-Angebot und HTTP-Signaling scheitern. Die App implementiert keinen ICE-Relay, kein Login-Workaround und keine Protokollübersetzung zwischen Client-Versionen. Die optionale öffentliche Nonce darf nur vom tatsächlichen Zielserver stammen.
- **RakNet (ältere Clients):** Die App übernimmt die Protokoll- und Versionsdaten aus dem Geyser-Pong und kündigt das Profil auf UDP 19132 an. Sie leitet eine gleichzeitige Client-Sitzung an den RakNet-Endpunkt weiter. Android muss während der Sitzung als Bridge erreichbar bleiben. Bedrock 26.60 und neuere Xbox-Clients benötigen NetherNet.
- **Unterwegs:** Smartphone und Xbox können gemeinsam in einem fremden LAN sein und einen entfernten Geyser-Server erreichen. Liegen Smartphone und Xbox in unterschiedlichen LANs, fehlt der Xbox das lokale Discovery-Signal. Ein zukünftiger Ansatz wäre ein Agent im Xbox-LAN mit Fernsteuerung vom Smartphone. Diese Version enthält keinen Agenten.
- Die App arbeitet ohne Änderungen an DNS, Xbox-Freundesliste, Geyser, Floodgate, Proxmox oder Docker. Ein vorhandener RakNet-Endpunkt allein reicht für den experimentellen NetherNet-Pfad nicht.

## Quellcode, Referenzen und Lizenzen

| Projekt | Lizenz | Bezug |
| --- | --- | --- |
| [Pugmatt/BedrockConnect](https://github.com/Pugmatt/BedrockConnect) | GPL-3.0 | Standalone-Server und Transfertechnik untersucht. Kein Code übernommen. |
| [paddedhelmets/bedrockproxy](https://github.com/paddedhelmets/bedrockproxy/tree/d3fa0ec) | MIT | Referenz für transparentes RakNet-Relay und LAN-zu-HTTP-Signaling. Die Android-App nutzt eine eigene Kotlin-Implementierung. |
| [df-mc/go-nethernet v1.0.26](https://github.com/df-mc/go-nethernet/tree/v1.0.26/discovery) | MIT | Vorlage für das binäre LAN-Format und dessen Kodierung. |
| [Geyser Xbox-LAN-Bericht](https://github.com/GeyserMC/Geyser/pull/6896) | Geyser-Projekt | Dokumentiert Xbox-spezifische Discovery- und Login-Unterschiede. Kein Code übernommen. |

Die App steht unter [Apache-2.0](LICENSE). MIT-Hinweise liegen in [notices](notices).

## Build

JDK 17, Android SDK 35, Build Tools 35.0.0 und Gradle 8.11.1.

```sh
gradle :app:testDebugUnitTest :app:assembleDebug
```

Der Workflow prüft die APK-Signatur vor dem Upload. Der Build liegt lokal unter `app/build/outputs/apk/debug/app-debug.apk`.
