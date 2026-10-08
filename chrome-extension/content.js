(() => {
  if (window.__craftifyYtmInjected) return;
  window.__craftifyYtmInjected = true;

  const PLAYER_URL_RE = /^https:\/\/music\.youtube\.com\//;
  let lastArtConverted = "";
  let lastArtPng = "";

  const ART_CAP = 256; // HUD art is drawn small; 256px is sharp enough
  // and keeps the data URL tiny (~10-20KB as JPEG).

  async function artFromUrl(url) {
    const resp = await fetch(url, { mode: "cors" });
    if (!resp.ok) return null;
    const blob = await resp.blob();
    try {
      return await createImageBitmap(blob);
    } catch (e) {
      return null; // e.g. webp decode issue, 404 html, etc.
    }
  }

  async function convertArtToPng(videoId, barUrl) {
    // Full-res candidates first (maxres sometimes 404s on older uploads)
    const candidates = [];
    if (videoId) {
      candidates.push("https://i.ytimg.com/vi/" + videoId + "/maxresdefault.jpg");
      candidates.push("https://i.ytimg.com/vi/" + videoId + "/sddefault.jpg");
      candidates.push("https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg");
    }
    if (barUrl) candidates.push(barUrl);
    let bmp = null;
    for (const c of candidates) {
      bmp = await artFromUrl(c);
      if (bmp) break;
    }
    if (!bmp) return null;
    // center-crop to square, capped at ART_CAP so the PNG data URL stays
    // well under the websocket message limit
    const side = Math.min(bmp.width, bmp.height, ART_CAP);
    const canvas = document.createElement("canvas");
    canvas.width = side; canvas.height = side;
    const ctx = canvas.getContext("2d");
    ctx.imageSmoothingEnabled = true;
    ctx.imageSmoothingQuality = "high";
    ctx.drawImage(bmp,
        (bmp.width - Math.min(bmp.width, bmp.height)) / 2,
        (bmp.height - Math.min(bmp.width, bmp.height)) / 2,
        Math.min(bmp.width, bmp.height), Math.min(bmp.width, bmp.height),
        0, 0, side, side);
    return await new Promise((resolve) => {
      // JPEG, not PNG: photos compress ~10x smaller and the mod's stb_image
      // decodes JPEG natively (webp was the only undecodable format).
      // The wire field is still named albumArtPng for compatibility.
      canvas.toBlob((b) => {
        if (!b) return resolve(null);
        const fr = new FileReader();
        fr.onload = () => resolve(fr.result);
        fr.onerror = () => resolve(null);
        fr.readAsDataURL(b);
      }, "image/jpeg", 0.85);
    });
  }

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
    // The player's <video> gives TRUE sub-second currentTime/duration.
    // IMPORTANT: YTM has several <video> elements in the DOM (previews,
    // miniplayer stubs). The player's one lives inside <ytmusic-player>
    // (or is the only one with a real, finite, non-zero duration AND
    // currentSrc). Picking document's first <video> previously returned
    // a PREVIEW's timeline (user saw 4:41/6:47 while playing 1:33/4:07).
    const playerEl = q("ytmusic-player video") || q("video");
    let video = null;
    document.querySelectorAll("video").forEach(v => {
      const dur = parseFloat(v.duration);
      if (!v.paused && !isNaN(dur) && dur > 1 && isFinite(dur)) video = v;
    });
    if (!video && playerEl) {
      const dur = parseFloat(playerEl.duration);
      if (!isNaN(dur) && dur > 1 && isFinite(dur)) video = playerEl;
    }
    if (video) {
      const cur = parseFloat(video.currentTime);
      const dur = parseFloat(video.duration);
      if (!isNaN(cur) && cur >= 0) position = cur;
      if (!isNaN(dur) && dur > 0 && isFinite(dur)) duration = dur;
    }
    // sanity + fallback to the seconds-granular text
    if (!duration || duration > 3600 || (position && position > duration)) {
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

    // Album art URL. NOTE: the player-bar <img> is a tiny ~60px thumbnail -
    // using it made the HUD art blurry. The videoId-based ytimg thumbs are
    // full-res (maxresdefault = 1280px); the bar img is only a last resort
    // when there is no videoId.
    let albumArt = "";
    if (videoId) {
      albumArt = "https://i.ytimg.com/vi/" + videoId + "/maxresdefault.jpg";
    } else {
      try {
        const barArt = q("ytmusic-player-bar img#img") ||
                       q("ytmusic-player-bar .yt-img-shadow") ||
                       q("ytmusic-player-bar img");
        const src = barArt && (barArt.getAttribute("src") || barArt.getAttribute("href"));
        if (src && src.startsWith("http")) albumArt = src;
      } catch (e) { /* best-effort */ }
    }

    // Convert art to a PNG data URL in-page: ytimg serves Chrome webp,
    // which the mod's NativeImage (stb_image) cannot decode ("Bad PNG
    // Signature"). The canvas re-encodes to PNG the mod can always read.
    // Async - the albumArtData arrives with the NEXT state push (~1-3s).
    if (albumArt && albumArt !== lastArtConverted) {
      lastArtConverted = albumArt;
      convertArtToPng(videoId, albumArt.startsWith("https://i.ytimg.com/") ? null : albumArt)
        .then(dataUrl => {
          if (dataUrl) lastArtPng = dataUrl;
        }).catch(() => {});
    }

    return {
      type: "state",
      state: {
        playing, title, artist, album,
        duration, position,               // seconds (legacy)
        positionMs: Math.round(position * 1000),
        durationMs: Math.round(duration * 1000),
        videoId, albumArt,
        albumArtPng: lastArtPng
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
