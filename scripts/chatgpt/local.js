// Pairing capabilities stay in this local browser tab and are never URL queries.
(() => {
  const input = document.getElementById('pair-link');
  if (!input) return;
  function readPairingLink() {
    if (location.hash) {
      try { sessionStorage.setItem('eurozone-game-pair', decodeURIComponent(location.hash.slice(1))); } catch (_) {}
      history.replaceState(null, '', location.pathname);
    }
    input.value = sessionStorage.getItem('eurozone-game-pair') || '';
  }
  readPairingLink();
  window.addEventListener('hashchange', readPairingLink);
  input.addEventListener('input', () => sessionStorage.setItem('eurozone-game-pair', input.value));
  document.querySelectorAll('form[action="/pair-game"]').forEach(form => {
    form.addEventListener('submit', event => {
      if (!input.value.trim()) { event.preventDefault(); input.focus(); input.reportValidity(); return; }
      form.querySelector('[name="link"]').value = input.value.trim();
    });
  });
})();
