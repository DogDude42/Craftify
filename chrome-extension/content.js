(() => {
  if (window.__craftifyYtmInjected) return;
  window.__craftifyYtmInjected = true;

  const PLAYER_URL_RE = /^https:\/\/music\.youtube\.com\//;
  let lastArtConverted = "";
  let lastArtPng = "";

  // ---- stable video binding ----
  let activeVideo = null;

  function findActiveVideo() {
    // the playing one, else the one inside ytmusic-player with sane duration
    let playing = null;
    let player = q("ytmusic-player video");
    document.querySelectorAll("video").forEach(v => {
      const dur = parseFloat(v.duration);
      if (!v.paused && !isNaN(dur) && dur > 1 && isFinite(dur)) playing = v;
    });
    if (playing) return playing;
    if (player) {
      const dur = parseFloat(player.duration);
      if (!isNaN(dur) && dur > 1 && isFinite(dur)) return player;
    }
    return null;
  }

  function getActiveVideo() {
    if (activeVideo && activeVideo.isConnected &&
        !isNaN(parseFloat(activeVideo.duration))) {
      return activeVideo;
    }
    activeVideo = findActiveVideo();
    return activeVideo;
  }

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
      // PNG is REQUIRED: verified against the actual 26.1.2 NativeImage
      // (stb pipeline) - it decodes PNG only; JPEG bytes throw
      // "Bad PNG Signature" (tested with real files via NativeImageTest).
      // 256px PNG is ~60-150KB; the mod's WebSocket frame buffering
      // handles multi-frame messages fine now.
      canvas.toBlob((b) => {
        if (!b) return resolve(null);
        const fr = new FileReader();
        fr.onload = () => resolve(fr.result);
        fr.onerror = () => resolve(null);
        fr.readAsDataURL(b);
      }, "image/png");
    });
  }

  function q(sel) { return document.querySelector(sel); }

  function readState() {
    const playerPage = q("ytmusic-player-page");
    const playButton = q("ytmusic-player-bar .play-pause-button") ||
                      q("#play-pause-button");
    // Playing when the button's aria-label offers "Pause" (YTM i18n:
    // "Pause" shown while playing, "Play" while paused).
    let playing = false;
    if (playButton) {
      const label = (playButton.getAttribute("aria-label") || "").toLowerCase();
      playing = label.includes("pause");
    }
    // Cross-check with the bound video element (authoritative during
    // transitions when the label lags)
    const pVideo = getActiveVideo();
    if (pVideo) {
      const vPlaying = !pVideo.paused && !pVideo.ended;
      // trust the element when it disagrees hard with the label
      if (vPlaying !== playing) playing = vPlaying;
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
    // ATOMICITY: the title/artist come from the player-bar DOM, which
    // updates instantly on a song change - but the cached <video> element
    // still holds the OLD song's timeline during the transition. Reporting
    // "new title + old position" made the HUD clock hard-sync to the old
    // playhead at every song boundary. YTM's own time-info TEXT is rendered
    // in the same UI pass as the title, so it is atomic with it. Strategy:
    // trust the element's sub-second numbers ONLY while they agree with the
    // text; on disagreement the element is stale -> use the text and force
    // a rebind.
    const video = getActiveVideo();
    let vPos = -1, vDur = -1, videoUsable = false;
    if (video) {
      const cur = parseFloat(video.currentTime);
      const dur = parseFloat(video.duration);
      if (!isNaN(cur) && cur >= 0) vPos = cur;
      if (!isNaN(dur) && dur > 0 && isFinite(dur)) vDur = dur;
      videoUsable = video.readyState >= 2; // HAVE_CURRENT_DATA
    }
    let textPos = -1, textDur = -1;
    const timeInfo = q("ytmusic-player-bar .time-info");
    if (timeInfo) {
      const m = timeInfo.textContent.match(/(\d+:\d+(?::\d+)?)/g);
      if (m) {
        textPos = parseTime(m[0]);
        if (m[1]) textDur = parseTime(m[1]);
      }
    }
    // element-vs-text agreement check (3s tolerance for UI refresh lag)
    if (videoUsable && textPos >= 0 &&
        (Math.abs(vPos - textPos) > 3 ||
         (textDur > 0 && vDur > 0 && Math.abs(vDur - textDur) > 3))) {
      videoUsable = false;
      activeVideo = null; // stale element: force rescan + rebind next tick
    }
    if (videoUsable) {
      position = vPos;
      duration = vDur > 0 ? vDur : Math.max(0, textDur);
    } else {
      position = Math.max(0, textPos);
      duration = Math.max(0, textDur);
    }
    // final sanity
    if (duration > 0 && position > duration) position = duration;
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

    // Repeat + shuffle states. NOTE: these toggle buttons do NOT expose
    // aria-pressed. Verified against the live DOM + YTM's own i18n table
    // (REPEAT_OFF "Repeat off" / REPEAT_ALL "Repeat all" / REPEAT_ONE
    // "Repeat one" / REPEAT_DISABLED "Repeat disabled" / SHUFFLE "Shuffle"):
    // the state lives in the button's aria-LABEL text, and the buttons sit
    // in .right-controls-buttons as .repeat and .shuffle.
    // Repeat is THREE-state: NONE -> ALL -> ONE (string "repeat").
    let loop = "NONE"; // "NONE" | "ALL" | "ONE"
    let shuffle = false;
    try {
      const repeatBtn = q("ytmusic-player-bar .repeat button") ||
                        q("ytmusic-player-bar .repeat");
      if (repeatBtn) {
        const label = (repeatBtn.getAttribute("aria-label") || "").toLowerCase();
        if (label.includes("one")) loop = "ONE";
        else if (label.includes("all")) loop = "ALL";
        else if (label.includes("off") || label.includes("disabled")) loop = "NONE";
      }
      const shuffleBtn = q("ytmusic-player-bar .shuffle button") ||
                         q("ytmusic-player-bar .shuffle");
      if (shuffleBtn) {
        const label = (shuffleBtn.getAttribute("aria-label") || "").toLowerCase();
        // when ON, the label is just "Shuffle"; when OFF it appends something
        // like "off" / a state suffix - check the button's aria-pressed OR
        // the yt-icon's aria-hidden pattern. Most robust: aria-pressed if
        // present, else label heuristics.
        const pressed = shuffleBtn.getAttribute("aria-pressed");
        if (pressed !== null) {
          shuffle = pressed === "true";
        } else {
          shuffle = label === "shuffle" || label.includes("on");
        }
      }
    } catch (e) { /* best-effort */ }

    return {
      type: "state",
      state: {
        playing, title, artist, album,
        duration, position,               // seconds (legacy)
        positionMs: Math.round(position * 1000),
        durationMs: Math.round(duration * 1000),
        videoId, albumArt,
        albumArtPng: lastArtPng,
        loopMode: loop,          // "NONE" | "ALL" | "ONE"
        shuffle
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
    loop()   {
      const btn = q("ytmusic-player-bar .repeat button") ||
                  q("ytmusic-player-bar .repeat");
      if (btn) { btn.click(); setTimeout(sendState, 150); return true; }
      return false;
    },
    shuffle() {
      const btn = q("ytmusic-player-bar .shuffle button") ||
                  q("ytmusic-player-bar .shuffle");
      if (btn) { btn.click(); setTimeout(sendState, 150); return true; }
      return false;
    },
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

  // Fresh state straight from the player element, 4x/sec while playing:
  // guarantees sub-second-fresh positions even if the SW poll stalls.
  function attachVideoListeners() {
    const v = getActiveVideo();
    if (!v || v.__craftifyBound) return;
    v.__craftifyBound = true;
    v.addEventListener("timeupdate", throttledSend);
    v.addEventListener("play", throttledSend);
    v.addEventListener("pause", throttledSend);
    v.addEventListener("ended", throttledSend);
    v.addEventListener("loadedmetadata", () => {
      // new element got metadata - rebind listeners
      activeVideo = null;
      attachVideoListeners();
      throttledSend();
    });
  }
  attachVideoListeners();
  // rebind occasionally as YTM swaps elements between songs
  setInterval(attachVideoListeners, 2000);


  sendState();
})();
