#!/usr/bin/env python3
"""
Craftify YTM Bridge - Chrome Native Messaging Host + WebSocket Server

Chrome extension (music.youtube.com) <-> native messaging (stdio) <-> this host
                                                          <-> WebSocket (Minecraft mod)

The mod connects to ws://127.0.0.1:8765/youtube-music
Extension state updates arrive as JSON on stdin (native messaging protocol:
4-byte little-endian length prefix + UTF-8 JSON).
Mod commands (play/pause/next/prev/seek/volume) are relayed back to the
extension as {"command": ...} messages on stdout.

Run via the generated craftify_ytm_bridge.bat (registered in the Chrome
native messaging host manifest). Requires Python 3.8+ with 'websockets'.
"""
import asyncio
import json
import os
import struct
import sys
import time
from typing import Optional, Set

try:
    import websockets
except ImportError:
    print("Craftify bridge requires the 'websockets' package: pip install websockets",
          file=sys.stderr)
    sys.exit(1)

WS_HOST = "127.0.0.1"
WS_PORT = 8765
WS_PATH = "/youtube-music"


LOCK_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "bridge.lock")


def kill_stale_instance() -> None:
    """Kill any previous bridge host so this fresh instance owns port 8765."""
    try:
        if os.path.exists(LOCK_FILE):
            with open(LOCK_FILE) as f:
                try:
                    old_pid = int(f.read().strip())
                except ValueError:
                    old_pid = 0
            if old_pid and old_pid != os.getpid():
                import ctypes
                kernel32 = ctypes.windll.kernel32
                PROCESS_TERMINATE = 0x0001
                h = kernel32.OpenProcess(PROCESS_TERMINATE, False, old_pid)
                if h:
                    kernel32.TerminateProcess(h, 0)
                    kernel32.CloseHandle(h)
                    print(f"[bridge] terminated stale bridge host pid {old_pid}",
                          file=sys.stderr)
                    time.sleep(0.3)
    except Exception as e:
        print(f"[bridge] stale-instance cleanup failed: {e}", file=sys.stderr)
    try:
        with open(LOCK_FILE, "w") as f:
            f.write(str(os.getpid()))
    except Exception:
        pass


class Bridge:
    def __init__(self):
        # Minecraft mod clients
        self.mc_clients: Set = set()
        # Latest player state pushed by the extension
        self.last_state: Optional[dict] = None

    # ---------- Chrome native messaging (stdio) ----------

    @staticmethod
    def read_native_message() -> Optional[dict]:
        """Read one length-prefixed native message from Chrome on stdin."""
        raw_len = sys.stdin.buffer.read(4)
        if not raw_len or len(raw_len) < 4:
            return None
        msg_len = struct.unpack("<I", raw_len)[0]
        if msg_len == 0:
            return {}
        data = sys.stdin.buffer.read(msg_len)
        if len(data) < msg_len:
            return None
        try:
            return json.loads(data.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            return {}

    @staticmethod
    def send_native_message(msg: dict) -> None:
        """Write one length-prefixed native message to Chrome on stdout."""
        data = json.dumps(msg).encode("utf-8")
        sys.stdout.buffer.write(struct.pack("<I", len(data)))
        sys.stdout.buffer.write(data)
        sys.stdout.buffer.flush()

    # ---------- WebSocket server (Minecraft mod) ----------

    async def broadcast_state(self, state: dict) -> None:
        """Push extension state to every connected Minecraft mod client."""
        if not self.mc_clients:
            return
        payload = json.dumps(state)
        dead = []
        for ws in list(self.mc_clients):
            try:
                await ws.send(payload)
            except Exception:
                dead.append(ws)
        for ws in dead:
            self.mc_clients.discard(ws)

    async def handle_mc(self, ws) -> None:
        """Handle one Minecraft mod WebSocket connection."""
        self.mc_clients.add(ws)
        print(f"[bridge] Minecraft mod connected ({len(self.mc_clients)} client(s))",
              file=sys.stderr)
        # Send the last known state immediately so the mod has something to show
        if self.last_state:
            try:
                await ws.send(json.dumps(self.last_state))
            except Exception:
                pass
        try:
            async for message in ws:
                # Message from the mod = command for the extension
                try:
                    cmd = json.loads(message)
                except json.JSONDecodeError:
                    continue
                print(f"[bridge] mod -> extension: {cmd}", file=sys.stderr)
                if cmd.get("action") == "request-state":
                    # Mod wants fresh state NOW (e.g. just connected).
                    # Two paths: replay the last known state immediately,
                    # AND nudge the extension to push fresh state.
                    if self.last_state:
                        try:
                            await ws.send(json.dumps(self.last_state))
                        except Exception:
                            pass
                    self.send_native_message({"type": "request-state"})
                    continue
                # Forward command to Chrome via native messaging
                self.send_native_message({"type": "command", "command": cmd})
        except Exception as e:
            print(f"[bridge] Minecraft connection error: {e}", file=sys.stderr)
        finally:
            self.mc_clients.discard(ws)
            print(f"[bridge] Minecraft mod disconnected", file=sys.stderr)

    async def ws_server(self) -> None:
        # Single-instance takeover via PID lockfile: each host writes its PID
        # to bridge.lock. A NEW host (spawned by a fresh service worker, so it
        # holds the only live stdin to Chrome) kills any stale previous host
        # before binding - otherwise the mod stays connected to a zombie whose
        # native pipe died, forcing the user to refresh YTM to get state back.
        kill_stale_instance()
        async with websockets.serve(self.handle_mc, WS_HOST, WS_PORT,
                                   max_size=2 ** 22, ping_interval=20,
                                   ping_timeout=20):
            print(f"[bridge] WebSocket server listening on ws://{WS_HOST}:{WS_PORT}{WS_PATH}",
                  file=sys.stderr)
            await asyncio.Future()  # run forever


def main() -> None:
    bridge = Bridge()

    async def pump_stdin() -> None:
        """Read extension state updates from Chrome stdin in a thread."""
        loop = asyncio.get_running_loop()
        while True:
            msg = await loop.run_in_executor(None, Bridge.read_native_message)
            if msg is None:
                # stdin closed - Chrome shut the extension down
                print("[bridge] stdin closed, shutting down", file=sys.stderr)
                os._exit(0)
            if not msg:
                continue
            if msg.get("type") == "state":
                bridge.last_state = msg.get("state") or {}
                print(f"[bridge] extension -> mod: {json.dumps(bridge.last_state)[:120]}",
                      file=sys.stderr)
                await bridge.broadcast_state(bridge.last_state)
            elif msg.get("type") == "player-closed":
                # No YTM tab open: wipe the cached state and tell every mod
                # client so the HUD hides instead of showing a stale song.
                bridge.last_state = None
                print("[bridge] player closed - clearing state", file=sys.stderr)
                for ws in list(bridge.mc_clients):
                    try:
                        await ws.send(json.dumps({"type": "player-closed"}))
                    except Exception:
                        bridge.mc_clients.discard(ws)

    async def run() -> None:
        await asyncio.gather(
            bridge.ws_server(),
            pump_stdin(),
        )

    try:
        asyncio.run(run())
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
