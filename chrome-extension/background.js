// Craftify YTM Bridge - service worker
// Manages the native messaging port and routes state/command messages
// between the content script (music.youtube.com) and the bridge host.

let nativePort = null;

function connectNative() {
  if (nativePort) return;
  try {
    nativePort = chrome.runtime.connectNative("com.dogdude42.craftify_ytm_bridge");
    nativePort.onMessage.addListener((msg) => {
      // Message from the bridge (i.e. Minecraft mod command)
      if (msg && msg.type === "command") {
        forwardCommandToYtm(msg.command);
      }
    });
    nativePort.onDisconnect.addListener(() => {
      console.warn("[craftify] native host disconnected:",
                   chrome.runtime.lastError && chrome.runtime.lastError.message);
      nativePort = null;
      // Retry after a delay; the host may just not be running yet
      setTimeout(connectNative, 5000);
    });
  } catch (e) {
    console.error("[craftify] connectNative failed:", e);
  }
}

async function forwardCommandToYtm(command) {
  const tabs = await chrome.tabs.query({ url: "*://music.youtube.com/*" });
  if (!tabs.length) return;
  for (const tab of tabs) {
    try {
      await chrome.tabs.sendMessage(tab.id, {
        type: "craftify-ytm-command", command: command
      }, () => void chrome.runtime.lastError);
    } catch (e) { /* tab may be gone */ }
  }
}

chrome.runtime.onMessage.addListener((msg, sender, sendResponse) => {
  // State update from the content script -> relay to native host
  if (msg && msg.type === "state") {
    connectNative();
    if (nativePort) {
      nativePort.postMessage(msg);
      sendResponse({ ok: true });
    } else {
      sendResponse({ ok: false, error: "native host not connected" });
    }
  }
  return false;
});

// Keep the SW alive while the port is connected (MV3 SW lifetime workaround)
if (chrome.runtime.onConnect) {
  chrome.runtime.onConnect.addListener(() => {});
}

connectNative();
