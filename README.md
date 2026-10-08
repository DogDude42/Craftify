# Craftify - Chrome/Thorium YTM

Control YouTube Music in Chrome/Thorium from Minecraft (Fabric, MC 26.1.2).
Fork of [ThatGravyBoat/Craftify](https://github.com/ThatGravyBoat/Craftify) with the
YTMD desktop-app integration replaced by a browser bridge for the YouTube Music
**website**.

## Architecture

```
music.youtube.com  <--content.js-->  service worker  <--native messaging (stdio)-->
bridge_host.py  <--WebSocket ws://127.0.0.1:8765-->  Craftify mod (Minecraft)
```

- **Mod** (`src/main/java/net/gravtech/`): pure-Java Fabric mod, connects to the
  bridge over WebSocket, exposes player state + controls (play/pause/next/prev).
- **Bridge** (`bridge/bridge_host.py`): Python native messaging host; relays
  extension state updates to the mod and mod commands back to the browser.
- **Extension** (`chrome-extension/`): MV3 extension; reads the YTM player state
  and executes play/pause/next/prev by driving the real page buttons.

## Setup

1. **Mod**: drop `build/libs/craftify-ytm-web-1.0.0+26.1.2.jar` into `mods/`
   (Fabric Loader >= 0.19.5, MC 26.1.x, Java 25).
2. **Extension**: Chrome/Thorium -> `chrome://extensions` -> Developer mode ->
   "Load unpacked" -> select `chrome-extension/`. Copy the generated extension ID.
3. **Native host**: run `bridge/install.bat` (paste the extension ID when asked).
   It writes `craftify_ytm_bridge.bat` + the host manifest and registers it under
   HKCU for Chrome, Thorium and Edge. No admin rights required.
4. Open https://music.youtube.com and play something. The service worker spawns
   the native host automatically; the mod picks it up on its next reconnect
   (it retries with backoff, so restarting Minecraft is never needed).

## Build

Requires Java 25 (Gradle JVM). `./gradlew build` produces the mod jar.

## State protocol

Extension -> mod (JSON over WS):
```json
{"playing": true, "title": "...", "artist": "...", "album": "",
 "duration": 213, "position": 42, "videoId": "dQw4w9WgXcQ"}
```

Mod -> extension:
```json
{"action": "play" | "pause" | "next" | "prev" | "seek" | "volume", ...}
```
