
chrome.runtime.onMessageExternal.addListener((msg, sender, reply) => {
  console.log('Craftify message:', msg);
  reply({status: 'ok'});
});
