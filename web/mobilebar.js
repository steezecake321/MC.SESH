// ---------- compact server picker and actions menu (shown on phones and narrow screens) ----------
let mmOpen = null;

function closeMenu() {
  if (!mmOpen) return;
  mmOpen.back.remove();
  mmOpen.menu.remove();
  document.removeEventListener('keydown', mmOpen.onKey);
  mmOpen = null;
}

/** Drops a menu down from under the bar. Tapping outside it, or pressing Escape, closes it. */
function openMenu(bar, children) {
  closeMenu();
  const back = h('div', { class: 'mback', onclick: closeMenu });
  const menu = h('div', { class: 'mmenu', role: 'menu' }, children);
  const top = bar.getBoundingClientRect().bottom + 6;
  menu.style.top = top + 'px';
  menu.style.setProperty('--mm-top', top + 'px');
  document.body.append(back, menu);
  const onKey = (e) => { if (e.key === 'Escape') closeMenu(); };
  document.addEventListener('keydown', onKey);
  mmOpen = { back, menu, onKey };
}

const mmItem = (label, fn, opts = {}) => h('button', {
  class: 'mitem' + (opts.cls ? ' ' + opts.cls : ''), role: 'menuitem', disabled: !!opts.disabled, title: opts.title || '',
  onclick: () => { closeMenu(); if (fn) fn(); },
}, opts.lead || null, h('span', { style: 'flex:1;min-width:0' }, label, opts.sub ? h('div', { class: 'dim', style: 'font-size:12px;font-weight:400' }, opts.sub) : null));

/** Administrator menu for every server at once: start, restart or stop them all politely, or force-kill them all. */
function allServersItems() {
  const running = S.servers.filter(x => x.state === 'running');
  const live = S.servers.filter(x => x.state !== 'stopped');
  const startable = S.servers.filter(x => x.state === 'stopped' && !x.portHeldBy);
  return [
    mmItem('Start all servers', startAll, { disabled: !startable.length, sub: startable.length ? `${startable.length} stopped` : 'Every server is already running' }),
    mmItem('Restart all servers', restartAllDialog, { disabled: !running.length, sub: running.length ? `${running.length} running. Worlds are saved first.` : 'No servers are running' }),
    mmItem('Stop all servers', stopAllDialog, { disabled: !running.length, sub: running.length ? `${running.length} running. Worlds are saved first.` : 'No servers are running' }),
    mmItem('Force kill all servers', killAllDialog, { cls: 'danger', disabled: !live.length, sub: 'Last resort: ends every server process now' }),
  ];
}

/** The one-line summary shown under the server's name. */
function serverInfoLine(s) {
  if (s.portHeldBy) return 'Port conflict - open the menu';
  if (s.state === 'starting') return 'Starting...';
  if (s.state === 'stopping') return 'Stopping...';
  if (s.state === 'running') return [`${s.players.length}/${s.maxPlayers} players`, 'Port ' + s.port, fmtMem(s.memMb)].join(' · ');
  return ['Stopped', 'Port ' + s.port].join(' · ');
}

function serverFacts(s) {
  const KIND = { vanilla: 'Vanilla', fabric: 'Fabric ' + (s.loader || ''), forge: 'Forge', neoforge: 'NeoForge' };
  return [`Port ${s.port}`, s.mc ? `Minecraft ${s.mc}` : null, KIND[s.kind] || 'Vanilla'].filter(Boolean).join(' · ');
}

/** What the Actions menu offers for this server (same rules as the buttons in the desktop header). */
function actionItems(s) {
  const items = [];
  if (s.job) items.push(mmItem('Show progress: ' + s.jobTitle, () => openJob(s.job, s.jobTitle, s.id), { lead: h('span', { class: 'dot starting' }) }));
  if (lvl(s) >= 4 && s.state === 'stopped' && !s.portHeldBy) items.push(mmItem('Start', () => doStart(s), { cls: 'good' }));
  if (lvl(s) >= 4 && s.state === 'running') {
    items.push(mmItem('Stop', () => doStop(s, false)));
    items.push(mmItem('Restart', () => doStop(s, true)));
  }
  if (lvl(s) >= 4) {
    items.push(mmItem('Backup', () => backupDialog(s), { sub: 'Save a copy of the world or the whole server' }));
    items.push(mmItem('Restore', () => restoreDialog(s), { sub: 'Put a saved backup back (server must be stopped)' }));
  }
  const danger = [];
  if (isAdmin() && s.state === 'stopped') {
    danger.push(mmItem('Remove from manager', () => removeFromManager(s), { sub: 'Stop managing it; files are kept' }));
    danger.push(mmItem('Delete from disk...', () => deleteServerDialog(s), { cls: 'danger' }));
  }
  if (isAdmin() && s.state !== 'stopped') danger.push(mmItem('Force kill', () => doKill(s), { cls: 'danger' }));
  if (danger.length) { if (items.length) items.push(h('div', { class: 'msep' })); items.push(...danger); }
  return items;
}

function renderMobileBar() {
  const bar = document.getElementById('mbar');
  if (!bar) return;
  const s = sv();
  if (!s) { closeMenu(); bar.replaceChildren(); return; }
  const chev = icon('chev');
  const serverBtn = h('button', { class: 'btn msel', 'aria-haspopup': 'menu', onclick: () => {
    const cur = sv();
    openMenu(bar, [
      h('div', { class: 'mhead' }, h('div', { class: 'dim', style: 'font-size:12.5px' }, serverFacts(cur)),
        isAdmin() ? null : h('div', { class: 'dim', style: 'font-size:12.5px' }, 'Your level: ' + lvl(cur) + ' (' + (LEVEL_NAME[lvl(cur)] || '') + ')')),
      ...S.servers.map(x => mmItem(x.name, () => selectServer(x.id), {
        cls: x.id === cur.id ? 'sel' : '', lead: h('span', { class: 'dot ' + x.state }),
        sub: x.state === 'running' ? `${x.players.length}/${x.maxPlayers} players` : x.state,
      })),
      ...(isAdmin() ? [h('div', { class: 'msep' }), mmItem('+ Add server', addMenu), h('div', { class: 'msep' }), ...allServersItems()] : []),
    ]);
  } },
  h('span', { class: 'dot ' + s.state }),
  h('span', { class: 'mt' }, h('span', { class: 'mn' }, s.name), h('span', { class: 'mi' }, serverInfoLine(s))),
  chev);
  const acts = actionItems(s);
  const actBtn = acts.length ? h('button', { class: 'btn mact', 'aria-haspopup': 'menu', onclick: () => openMenu(bar, actionItems(sv())) },
    s.job ? h('span', { class: 'dot starting' }) : null, s.job ? 'Busy' : 'Actions', chev.cloneNode(true)) : null;
  bar.replaceChildren(...[serverBtn, actBtn].filter(Boolean));
}
