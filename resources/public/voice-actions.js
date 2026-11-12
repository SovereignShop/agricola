/* The assistant selects native, enabled controls, never arbitrary selectors or code. */
(() => {
  const ids = new WeakMap(); let nextId = 0, interaction = 0, current = new Map();
  const selector = 'button,summary,[role="button"],input:not([type="hidden"]):not([type="file"]):not([type="password"]),select,textarea';
  const clean = value => (value || '').replace(/\s+/g, ' ').trim();
  const visible = node => node.isConnected && node.getClientRects().length > 0 &&
    !node.closest('[hidden],[inert],[aria-hidden="true"],.voice-controls,.chat-panel') &&
    getComputedStyle(node).visibility !== 'hidden' && getComputedStyle(node).display !== 'none';
  const enabled = node => !node.disabled && !node.readOnly && node.getAttribute('aria-disabled') !== 'true';
  const label = node => clean(node.getAttribute('aria-label') || node.getAttribute('title') ||
    Array.from(node.labels || []).map(n => n.textContent).join(' ') ||
    node.textContent || node.getAttribute('placeholder') || node.getAttribute('name'));
  const hash = value => { let h = 2166136261; for (const c of value) h = Math.imul(h ^ c.charCodeAt(0), 16777619); return (h >>> 0).toString(36); };
  function snapshot() {
    const game = document.querySelector('.game');
    if (!game) { current = new Map(); return {version:'none', scope:'No game', actions:[]}; }
    const dialogs = Array.from(document.querySelectorAll('[role="dialog"]')).filter(visible);
    const scope = dialogs.at(-1) || game;
    const actions = [], mapping = new Map();
    for (const node of scope.querySelectorAll(selector)) {
      if (!visible(node) || !enabled(node) || node.matches('.tt-handle')) continue;
      if (node.matches('input[type="email"],input[type="url"]')) continue;
      if (!ids.has(node)) ids.set(node, 'ui-' + (++nextId));
      const space = ids.get(node);
      const groups = []; let parent = node.parentElement;
      while (parent && parent !== scope && groups.length < 3) {
        const name = parent.getAttribute('aria-label');
        if (name && !groups.includes(name)) groups.push(name);
        parent = parent.parentElement;
      }
      const field = node.closest('label,.form-field,.form-row,.quantity-control,.resource-control');
      const name = clean([label(node), node.getAttribute('data-voice-position'), ...groups].filter(Boolean).join(' · ')).slice(0, 240);
      if (!name) continue;
      const input = node.matches('input:not([type="checkbox"]):not([type="radio"]),textarea,select');
      const action = {space, name, mode:input ? 'input' : 'click',
        selected: node.getAttribute('aria-pressed') || (node.matches('summary') ? String(node.parentElement.open) :
          node.matches('input[type="checkbox"],input[type="radio"]') ? String(node.checked) : null)};
      if (field) action.detail = clean(field.textContent).slice(0, 240);
      if (input) {
        action.value = node.value; action.input_type = node.type || node.tagName.toLowerCase();
        if (node.min) action.min = node.min; if (node.max) action.max = node.max;
        if (node.step) action.step = node.step;
        if (node.tagName === 'SELECT') action.options = Array.from(node.options).filter(o => !o.disabled).map(o => ({value:o.value, name:o.text}));
      }
      if (node.dataset.action) action.board_space = node.dataset.action;
      actions.push(action); mapping.set(space, {node, action});
    }
    const title = scope === game ? 'Game board' : label(scope);
    // Only the top dialog's rendered information, including a player's own shown cards.
    const text = scope === game ? '' : clean(scope.innerText || scope.textContent).slice(0, 16000);
    const version = hash(JSON.stringify([interaction, title, text, actions]));
    current = mapping;
    return {version, scope:title, text, actions};
  }
  function execute(space, value, version) {
    const now = snapshot();
    if (now.version !== version) return {ok:false, error:'The open controls changed. Please say that again.'};
    const entry = current.get(space);
    if (!entry || !visible(entry.node) || !enabled(entry.node)) return {ok:false, error:'That control is no longer available.'};
    const {node, action} = entry;
    if (action.mode === 'input') {
      if (typeof value !== 'string' || value.length > 300) return {ok:false, error:'A value is required for this control.'};
      if (node.type === 'number' || node.type === 'range') {
        const n = Number(value);
        if (!value.trim() || !Number.isFinite(n) || (node.min !== '' && n < Number(node.min)) ||
            (node.max !== '' && n > Number(node.max))) return {ok:false, error:'That quantity is outside the available range.'};
      }
      if (node.tagName === 'SELECT' && !Array.from(node.options).some(o => !o.disabled && o.value === value))
        return {ok:false, error:'That choice is unavailable.'};
      const prototype = node.tagName === 'SELECT' ? HTMLSelectElement.prototype :
        node.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
      Object.getOwnPropertyDescriptor(prototype, 'value').set.call(node, value);
      node.dispatchEvent(new Event('input', {bubbles:true}));
      node.dispatchEvent(new Event('change', {bubbles:true}));
    } else {
      if (value != null && value !== '') return {ok:false, error:'This control does not accept a value.'};
      if (typeof node.click === 'function') node.click();
      else node.dispatchEvent(new MouseEvent('click', {bubbles:true, cancelable:true, view:window}));
    }
    return {ok:true};
  }
  window.EurozoneActions = {snapshot, execute};
  let queued = false, last = '';
  function changed() {
    if (queued) return;
    queued = true;
    requestAnimationFrame(() => {
      queued = false;
      const version = snapshot().version;
      if (version !== last) {
        last = version;
        window.dispatchEvent(new CustomEvent('eurozone-controls-changed', {detail:version}));
      }
    });
  }
  // Invalidate synchronously, before React renders a manually changed menu/draft.
  for (const type of ['pointerdown', 'click', 'input', 'change', 'keydown']) {
    document.addEventListener(type, event => {
      const node = event.target;
      if (!node.closest?.('.game,[role="dialog"]') || node.closest('.voice-controls,.chat-panel')) return;
      if (type === 'keydown' && !['Escape', 'Enter', ' ', 'ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight'].includes(event.key)) return;
      interaction++; changed();
    }, true);
  }
  new MutationObserver(records => {
    if (!records.every(r => r.target.nodeType === 1 && r.target.closest?.('.voice-controls,.chat-panel'))) changed();
  }).observe(document.body, {subtree:true, childList:true, attributes:true, characterData:true});
})();
