/* Presentation only: committed state is never delayed by a piece animation. */
(() => {
  const app = document.getElementById('app');
  const reduced = matchMedia('(prefers-reduced-motion: reduce)');
  let previous = new Map(), revision = null, table = null, frame = 0;
  const flights = new Set();

  function visible(el, rect) {
    let left = Math.max(0, rect.left), top = Math.max(0, rect.top);
    let right = Math.min(innerWidth, rect.right), bottom = Math.min(innerHeight, rect.bottom);
    for (let parent = el.parentElement; parent && parent !== document.body; parent = parent.parentElement) {
      const style = getComputedStyle(parent);
      if (style.display === 'none' || style.visibility === 'hidden') return false;
      if (/(auto|scroll|hidden|clip)/.test(style.overflowX + style.overflowY)) {
        const r = parent.getBoundingClientRect();
        left = Math.max(left, r.left); right = Math.min(right, r.right);
        top = Math.max(top, r.top); bottom = Math.min(bottom, r.bottom);
      }
    }
    return right - left > 2 && bottom - top > 2;
  }

  function snapshot() {
    const pieces = new Map();
    app.querySelectorAll('[data-piece-key]').forEach(el => {
      const image = el.matches('img,image') ? el : el.querySelector('img,image');
      if (!image) return;
      const rect = image.getBoundingClientRect();
      const piece = {el, image, rect, key: el.dataset.pieceKey, type: el.dataset.pieceType,
        count: Number(el.dataset.pieceCount || 1), visible: visible(image, rect),
        src: image.getAttribute('src') || image.getAttribute('href'), filter: getComputedStyle(image).filter};
      // A farm can be tiled twice. Prefer its visible representation.
      if (!pieces.has(piece.key) || piece.visible) pieces.set(piece.key, piece);
    });
    return pieces;
  }

  function clear() {
    flights.forEach(flight => flight.cancel());
    flights.clear();
  }

  function fly(source, target, ordinal, hideDestination) {
    if (!target.visible || !target.src || !Element.prototype.animate) return;
    const r = target.rect;
    // Offscreen sources arrive from nearby; never pan or change the user's layout.
    const start = source?.visible ? source.rect : {left: r.left, top: r.top - 34, width: r.width, height: r.height};
    const ghost = document.createElement('img');
    ghost.className = 'piece-flight';
    ghost.setAttribute('aria-hidden', 'true');
    ghost.dataset.motionSource = source?.visible ? 'visible' : 'offscreen';
    ghost.src = target.src;
    Object.assign(ghost.style, {left: `${r.left}px`, top: `${r.top}px`, width: `${r.width}px`, height: `${r.height}px`, filter: target.filter});
    document.body.appendChild(ghost);
    const originalVisibility = target.image.style.visibility;
    if (hideDestination) target.image.style.visibility = 'hidden';
    const animation = ghost.animate([
      {transform: `translate(${start.left - r.left}px,${start.top - r.top}px) scale(${start.width / r.width},${start.height / r.height})`, opacity: source?.visible ? 1 : 0},
      {transform: 'translate(0,0) scale(1)', opacity: 1}
    ], {duration: 440, delay: Math.min(ordinal, 5) * 35, easing: 'cubic-bezier(.2,.75,.3,1)', fill: 'both'});
    const finish = () => {
      ghost.remove(); flights.delete(animation);
      if (hideDestination) target.image.style.visibility = originalVisibility;
    };
    animation.onfinish = finish; animation.oncancel = finish;
    flights.add(animation);
  }

  function update() {
    frame = 0;
    const game = app.querySelector('.game');
    const nextRevision = game?.dataset.motionRevision ?? game?.dataset.revision;
    const nextTable = game?.dataset.motionTable;
    const next = snapshot();
    if (game && table === nextTable && revision != null && nextRevision !== revision) {
      clear();
      if (!reduced.matches && !document.hidden) {
        const outgoing = [];
        previous.forEach(old => {
          const lost = old.count - (next.get(old.key)?.count || 0);
          for (let i = 0; i < Math.min(7, lost); i++) outgoing.push(old);
        });
        let ordinal = 0;
        next.forEach(dest => {
          const old = previous.get(dest.key);
          const gained = dest.count - (old?.count || 0);
          for (let i = 0; i < Math.min(7, gained); i++) {
            const match = outgoing.findIndex(source => source.type === dest.type);
            const source = match < 0 ? null : outgoing.splice(match, 1)[0];
            fly(source, dest, ordinal++, !old && i === 0);
          }
        });
      }
    } else if (nextTable !== table) clear();
    previous = next; revision = nextRevision; table = nextTable;
  }
  function schedule() { if (!frame) frame = requestAnimationFrame(update); }
  const observer = new MutationObserver(schedule);
  observer.observe(app, {subtree: true, childList: true, attributes: true,
    attributeFilter: ['data-revision', 'data-motion-revision', 'data-piece-count', 'data-motion-table']});
  // Cancel flights when the viewport moves, then refresh their source coordinates.
  const viewportChanged = () => { clear(); schedule(); };
  addEventListener('scroll', viewportChanged, true);
  addEventListener('resize', viewportChanged);
  reduced.addEventListener('change', viewportChanged);
  document.addEventListener('visibilitychange', viewportChanged);
  schedule();
})();
