
// Injects into music.youtube.com and relays player state
const observer = new MutationObserver(mutations => {
  const playing = document.querySelector('.play-pause-button')?.ariaLabel?.includes('Pause');
  const title = document.querySelector('ytmusic-player-bar')?.title || 'Unknown';
  chrome.runtime.sendNativeMessage('craftify_ytm_bridge', {cmd:'state', playing, title}, resp => console.log('Bridge response:', resp));
});
observer.observe(document.body, {subtree: true, childList: true});
