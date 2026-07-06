# MetaStream

Dateien im lokalen WLAN zwischen Handy und **Meta Quest 3S** übertragen — Fotos,
Videos und beliebige Dateien, schnell, ohne Kabel und ohne Cloud.

Das Repo enthält **zwei Projekte**:

| Ordner | Was | Deployment |
|--------|-----|------------|
| [`quest-app/`](quest-app) | Android-APK für die Meta Quest. Startet einen eingebetteten HTTP-Server und liefert eine Upload-/Download-Oberfläche aus. | GitHub Actions → Release (`.apk`) |
| [`web/`](web) | Landing-Page + „Mit Quest verbinden" für das Handy. | Vercel (statische Site) |

## So funktioniert's

```
 Handy (Browser)  ⇄  WLAN  ⇄  Meta Quest 3S (MetaStream-App = HTTP-Server)
```

1. **APK** auf die Quest sideloaden (SideQuest oder `adb install`).
2. App im Headset öffnen — sie zeigt einen **QR-Code** und die lokale Adresse
   (z. B. `http://192.168.1.42:8080`).
3. Am Handy den QR scannen (oder auf der Website die IP eingeben) → die
   Upload-Oberfläche öffnet sich. Dateien lassen sich in **beide Richtungen**
   übertragen.

Empfangene Dateien liegen unter
`Android/data/com.metastream.quest/files/MetaStream/`.

## Warum es schnell ist

- Uploads werden als **roher Stream** (`PUT`) direkt auf die Disk geschrieben —
  ein einziger Schreibvorgang mit 64-KB-Puffern.
- Downloads unterstützen **HTTP-Range**, d. h. Videos lassen sich direkt im
  Browser abspielen und weiterspulen.
- Der Foreground-Service hält einen **High-Performance-WLAN-Lock**.

## Build

Die APK wird von GitHub Actions gebaut ([`.github/workflows/android.yml`](.github/workflows/android.yml)):

- Bei jedem Push auf `main` (oder einem `v*`-Tag) entsteht automatisch ein
  **GitHub Release** mit angehängter `.apk`.
- Bei Feature-Branches wird die APK als **Artifact** hochgeladen.

Lokal (mit installiertem Android SDK):

```bash
cd quest-app
./gradlew assembleDebug
# -> app/build/outputs/apk/debug/app-debug.apk
```

## Website (Vercel)

Der Ordner `web/` ist eine statische Site (`vercel.json` inklusive) und kann als
eigenes Vercel-Projekt deployt werden — Root Directory auf `web` setzen.

## Technik

- **Quest-App:** Kotlin, [NanoHTTPD](https://github.com/NanoHttpd/nanohttpd),
  ZXing (QR-Code), minSdk 29 / targetSdk 34.
- **Web:** eine `index.html`, keine Build-Tools nötig.
