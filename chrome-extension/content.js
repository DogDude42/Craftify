(() => {
  if (window.__craftifyYtmInjected) return;
  window.__craftifyYtmInjected = true;

  const PLAYER_URL_RE = /^https:\/\/music\.youtube\.com\//;

  function q(sel) { return document.querySelector(sel); }

  function readState() {
    const playerPage = q("ytmusic-player-page");
    const playButton = q("ytmusic-player-bar .play-pause-button") ||
                      q("#play-pause-button");
    // Playing when the button's aria-label says "Pause" (i.e. a pause action offered)
    let playing = false;
    if (playButton) {
      const label = (playButton.getAttribute("aria-label") || "").toLowerCase();
      playing = label.includes("pause");
    }
    const title = playerPage?.getAttribute("title") ||
                  q("ytmusic-player-bar .title")?.getAttribute("title") ||
                  q(".ytmusic-player-bar .title")?.textContent?.trim() ||
                  "";
    const artist = q("ytmusic-player-bar .subtitle")?.textContent?.trim() ||
                   playerPage?.getAttribute("author") || "";
    let album = "";
    const albumEl = playerPage?.querySelector(".album-title");
    if (albumEl) album = albumEl.textContent.trim();

    let duration = 0, position = 0;
    // The HTML5 <video> element gives TRUE sub-second currentTime/duration -
    // the .time-info text only has seconds, which made the HUD clock skip.
    const video = q("video");
    if (video) {
      const cur = parseFloat(video.currentTime);
      const dur = parseFloat(video.duration);
      if (!isNaN(cur) && cur > 0) position = cur;
      if (!isNaN(dur) && dur > 0 && isFinite(dur)) duration = dur;
    }
    if (!duration || !position) {
      const timeInfo = q("ytmusic-player-bar .time-info");
      if (timeInfo) {
        const m = timeInfo.textContent.match(/(\d+:\d+(?::\d+)?)/g);
        if (m) {
          position = parseTime(m[0]);
          if (m[1]) duration = parseTime(m[1]);
        }
      }
    }
    let videoId = "";
    try {
      const playerPage = q("ytmusic-player-page");
      const pr = playerPage && (playerPage.data || playerPage.__data);
      videoId = (pr && pr.playerResponse && pr.playerResponse.videoDetails
                 && pr.playerResponse.videoDetails.videoId) || "";
    } catch (e) { /* best-effort */ }
    if (!videoId) {
      videoId = new URL(location.href).searchParams.get("v") ||
                q("ytmusic-player-page")?.getAttribute("video-id") || "";
    }

    // Album art: try the player bar's actual <img> first (most reliable -
    // it's the real art YTM displays), then videoId-based thumbnail.
    let albumArt = "";
    try {
      const barArt = q("ytmusic-player-bar img#img") ||
                     q("ytmusic-player-bar .yt-img-shadow") ||
                     q("ytmusic-player-bar image[src]") ||
                     q("ytmusic-player-bar img");
      const src = barArt && (barArt.getAttribute("src") || barArt.getAttribute("href"));
      if (src && src.startsWith("http")) albumArt = src;
    } catch (e) { /* best-effort */ }
    if (!albumArt && videoId) {
      albumArt = "https://i.ytimg.com/vi/" + videoId + "/maxresdefault.jpg";
    }

    return {
      type: "state",
      state: {
        playing, title, artist, album,
        duration, position,               // seconds (legacy)
        positionMs: Math.round(position * 1000),
        durationMs: Math.round(duration * 1000),
        videoId, albumArt
      }
    };
  }

  function parseTime(t) {
    const parts = t.split(":").map(Number);
    if (parts.some(isNaN)) return 0;
    return parts.reduce((acc, p) => acc * 60 + p, 0);
  }

  function sendState() {
    try {
      chrome.runtime.sendMessage(readState(), () => void chrome.runtime.lastError);
    } catch (e) { /* extension context gone */ }
  }

  // Click-based command execution
  function clickButton(selector) {
    const el = q(selector);
    if (el) { el.click(); return true; }
    return false;
  }

  const COMMANDS = {
    play()   { return clickButton("ytmusic-player-bar .play-pause-button, #play-pause-button"); },
    pause()  { return clickButton("ytmusic-player-bar .play-pause-button, #play-pause-button"); },
    next()   { return clickButton("ytmusic-player-bar .next-button, .next-button"); },
    prev()   { return clickButton("ytmusic-player-bar .previous-button, .previous-button"); },
    // YTM has no user-facing seek-by-N UI, so skip unsupported actions gracefully
    seek()   { return false; },
    volume() { return false; }
  };

  chrome.runtime.onMessage.addListener((msg, _sender, sendResponse) => {
    if (msg && msg.type === "craftify-ytm-get-state") {
      sendResponse({ ok: true });
      sendState();
      return;
    }
    if (msg && msg.type === "craftify-ytm-command") {
      const action = msg.command && msg.command.action;
      const fn = COMMANDS[action];
      if (fn) {
        setTimeout(() => { fn(); sendState(); }, 0);
        sendResponse({ ok: true });
      } else {
        sendResponse({ ok: false, error: "unsupported: " + action });
      }
    }
    return false; // sync response
  });

  // Push state: on load, on player-page mutations (throttled), on tab events
  let lastPush = 0;
  const THROTTLE_MS = 500;

  function throttledSend() {
    const now = Date.now();
    if (now - lastPush < THROTTLE_MS) return;
    lastPush = now;
    sendState();
  }

  const observer = new MutationObserver(throttledSend);
  observer.observe(document.body, { childList: true, subtree: true });

  document.addEventListener("yt-navigate-finish", throttledSend);
  window.addEventListener("focus", throttledSend);


  sendState();
})();
