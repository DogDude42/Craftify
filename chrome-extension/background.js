// Craftify YTM Bridge - service worker
// Routes state/command messages between music.youtube.com and the native host.
// Verbose logging on purpose: the console should show the full chain.

const NATIVE_NAME = "com.dogdude42.craftify_ytm_bridge";
const LOG = (...a) => console.log("[craftify]", ...a);
const WARN = (...a) => console.warn("[craftify]", ...a);

let nativePort = null;
let connectAttempts = 0;

function connectNative() {
  if (nativePort) return;
  connectAttempts++;
  LOG(`connecting to native host "${NATIVE_NAME}" (attempt ${connectAttempts})...`);
  try {
    nativePort = chrome.runtime.connectNative(NATIVE_NAME);
  } catch (e) {
    WARN("connectNative threw:", e);
    nativePort = null;
    setTimeout(connectNative, 5000);
    return;
  }

  nativePort.onMessage.addListener((msg) => {
    LOG("native -> extension:", msg);
    if (msg && msg.type === "command") {
      forwardCommandToYtm(msg.command);
    }
  });

  nativePort.onDisconnect.addListener(() => {
    const err = chrome.runtime.lastError;
    WARN("native host DISCONNECTED:", err && err.message);
    nativePort = null;
    setTimeout(connectNative, 5000);
  });

  LOG("native port opened OK - bridge should now be running");
  // Ask the YTM tab(s) for fresh state so the bridge/mod start up-to-date
  requestStateFromAllTabs();
}

async function requestStateFromAllTabs() {
  try {
    const tabs = await chrome.tabs.query({ url: "*://music.youtube.com/*" });
    LOG(`requesting state from ${tabs.length} YTM tab(s)`);
    for (const tab of tabs) {
      try {
        await chrome.tabs.sendMessage(tab.id, { type: "craftify-ytm-get-state" },
                                      () => void chrome.runtime.lastError);
      } catch (e) {
        WARN("state request to tab", tab.id, "failed:", e);
      }
    }
  } catch (e) {
    WARN("tabs query failed:", e);
  }
}

async function forwardCommandToYtm(command) {
  const tabs = await chrome.tabs.query({ url: "*://music.youtube.com/*" });
  if (!tabs.length) { WARN("command dropped - no YTM tab open:", command); return; }
  LOG("forwarding command to YTM tab(s):", command);
  for (const tab of tabs) {
    try {
      await chrome.tabs.sendMessage(tab.id,
        { type: "craftify-ytm-command", command }, () => void chrome.runtime.lastError);
    } catch (e) { /* tab may be gone */ }
  }
}

chrome.runtime.onMessage.addListener((msg, sender, sendResponse) => {
  if (msg && msg.type === "state") {
    LOG("content -> native:", msg.state);
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

// Reconnect when a YTM tab finishes loading / is opened
chrome.tabs.onUpdated.addListener((tabId, info, tab) => {
  if (tab && tab.url && tab.url.startsWith("https://music.youtube.com/") &&
      info.status === "complete") {
    LOG("YTM tab finished loading:", tabId);
    connectNative();
  }
});

chrome.runtime.onStartup.addListener(() => LOG("extension starting up"));
chrome.runtime.onInstalled.addListener(() => LOG("extension installed/updated"));

connectNative();
