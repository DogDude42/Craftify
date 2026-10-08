#!/usr/bin/env python3
r"""
Craftify YTM Bridge - Windows installer

Registers the native messaging host manifest for Chrome AND Thorium:
  - Chrome:  HKCU\Software\Google\Chrome\NativeMessagingHosts
  - Thorium: HKCU\Software\Thorium\NativeMessagingHosts (same Chromium layout)
Also installs for Edge if present at its standard key.

Writes craftify_ytm_bridge.bat next to this script and points the native
host manifest at it. No admin rights needed (HKCU only).
"""
import json
import os
import sys
import winreg

HERE = os.path.dirname(os.path.abspath(__file__))
HOST_NAME = "com.dogdude42.craftify_ytm_bridge"
BAT_PATH = os.path.join(HERE, "craftify_ytm_bridge.bat")
MANIFEST_PATH = os.path.join(HERE, "craftify_ytm_bridge_manifest.json")

PY_EXE = r"C:\Users\dogdu\AppData\Local\Python\bin\python.exe"
HOST_SCRIPT = os.path.join(HERE, "bridge_host.py")

BROWSERS = {
    "Chrome":  r"Software\Google\Chrome\NativeMessagingHosts",
    "Thorium": r"Software\Thorium\NativeMessagingHosts",
    "Edge":    r"Software\Microsoft\Edge\NativeMessagingHosts",
}


def write_bat() -> str:
    with open(BAT_PATH, "w", newline="\r\n") as f:
        f.write(f'@echo off\r\n"{PY_EXE}" "{HOST_SCRIPT}"\r\n')
    return BAT_PATH


def write_manifest(allowed_origin: str) -> str:
    manifest = {
        "name": HOST_NAME,
        "description": "Craftify YouTube Music bridge for Chrome/Thorium",
        "path": BAT_PATH,
        "type": "stdio",
        "allowed_origins": [allowed_origin],
    }
    with open(MANIFEST_PATH, "w", newline="\n") as f:
        json.dump(manifest, f, indent=2)
    return MANIFEST_PATH


def register(browser: str, reg_key: str, manifest_path: str) -> bool:
    # Chromium native messaging: HKCU\...\NativeMessagingHosts\<host-name>
    # is a KEY whose DEFAULT value is the path to the manifest JSON.
    host_key = reg_key + "\\" + HOST_NAME
    try:
        with winreg.CreateKey(winreg.HKEY_CURRENT_USER, host_key) as key:
            winreg.SetValueEx(key, None, 0, winreg.REG_SZ, manifest_path)
        print(f"  [ok] {browser}: registered {host_key} (default = manifest path)")
        return True
    except OSError as e:
        print(f"  [!] {browser}: registration failed: {e}")
        return False


def cleanup_bad_value(reg_key: str) -> None:
    """Remove the mis-registered value (not key) from earlier versions."""
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, reg_key, 0,
                            winreg.KEY_SET_VALUE) as key:
            winreg.DeleteValue(key, HOST_NAME)
    except OSError:
        pass  # nothing to clean


def main() -> None:
    if not os.path.exists(HOST_SCRIPT):
        sys.exit(f"bridge_host.py not found next to installer: {HOST_SCRIPT}")
    if not os.path.exists(PY_EXE):
        sys.exit(f"Python not found at {PY_EXE} - edit PY_EXE in this script")

    print("[craftify] writing launcher bat...")
    write_bat()
    print(f"  [ok] {BAT_PATH}")

    # allowed_origins needs the extension ID. chrome-extension://<id>/*
    # We can't know the ID before first load; use a wildcard-style approach:
    # Chromium accepts a list - we register TWO manifests, one per extension ID
    # once known. Simplest robust path: instruct the user to paste the ID after
    # loading the unpacked extension, OR pre-register for the well-known
    # generated ID if the extension is loaded unpacked from a stable path.
    ext_id = os.environ.get("CRAFTIFY_EXT_ID", "").strip()
    if not ext_id:
        print()
        print("[craftify] Native messaging hosts require the extension ID.")
        print("  1. Load chrome-extension/ as an unpacked extension in Chrome/Thorium")
        print("  2. Copy its ID from chrome://extensions")
        print("  3. Re-run:  CRAFTIFY_EXT_ID=<id> python install.py")
        print("     (or set CRAFTIFY_EXT_ID in the environment and run install.bat)")
        print()
        sys.exit(2)

    origin = f"chrome-extension://{ext_id}/*"
    print(f"[craftify] writing manifest for origin {origin} ...")
    write_manifest(origin)

    for browser, reg_key in BROWSERS.items():
        cleanup_bad_value(reg_key)
        register(browser, reg_key, MANIFEST_PATH)

    print()
    print("[craftify] Done! Start the bridge via the extension - the native")
    print("  host is launched automatically by the browser when the extension")
    print("  connects. The Minecraft mod connects to ws://127.0.0.1:8765.")


if __name__ == "__main__":
    main()
