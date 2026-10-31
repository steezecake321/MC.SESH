// ---------- notifications: the bell, its list, and the pop-ups ----------
const NT = { notes: [], unread: 0, open: false, panel: null, seenTimer: null };

ICONS.bell = 'M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9 M10.3 21a1.9 1.9 0 0 0 3.4 0';

function ntAgo(ms) {
  const s = Math.max(0, Math.round((Date.now() - ms) / 1000));
  if (s < 60) return 'just now';
  if (s < 3600) return Math.round(s / 60) + ' min ago';
  if (s < 86400) return Math.round(s / 3600) + ' h ago';
  return new Date(ms).toLocaleDateString();
}

function ntServerName(id) {
  const s = (S.servers || []).find(x => x.id === id);
  return s ? s.name : '';
}

/** Runs the button a notification carries. */
function ntAct(a) {
  if (!a) return;
  if (a.type === 'open' && a.server) {
    if (!(S.servers || []).some(x => x.id === a.server)) return;
    S.tab = a.tab || 'console';
    ntClose();
    selectServer(a.server);
  } else if (typeof ntExtraAct === 'function') ntExtraAct(a);
}

function ntBadge() {
  const b = $('#bellBtn');
  if (!b) return;
  let dot = b.querySelector('.nbadge');
  if (!NT.unread) { if (dot) dot.remove(); return; }
  if (!dot) { dot = h('span', { class: 'nbadge' }); b.append(dot); }
  dot.textContent = NT.unread > 9 ? '9+' : String(NT.unread);
}

async function ntLoad() {
  try {
    const r = await api('GET', '/api/notifications');
    NT.notes = r.notes;
    NT.unread = r.unread;
    ntBadge();
    if (NT.open) ntDraw();
  } catch (e) { /* offline */ }
}

function ntClose() {
  NT.open = false;
  if (NT.panel) { NT.panel.remove(); NT.panel = null; }
  document.removeEventListener('mousedown', ntOutside, true);
}

function ntOutside(e) {
  if (NT.panel && !NT.panel.contains(e.target) && !e.target.closest('#bellBtn')) ntClose();
}

function ntDraw() {
  if (!NT.panel) return;
  const list = NT.notes.length ? NT.notes.map(n => h('div', { class: 'nitem ' + (n.severity || 'info') + (n.unread ? ' unread' : '') },
    h('div', { class: 'nhead' }, h('b', null, n.title), h('span', { class: 'dim ntime' }, ntAgo(n.at))),
    n.text ? h('div', { class: 'ntext' }, n.text) : null,
    h('div', { class: 'nfoot' }, n.server && ntServerName(n.server) ? h('span', { class: 'badge', style: 'margin-left:0' }, ntServerName(n.server)) : null,
      n.action && n.action.label ? h('button', { class: 'btn sm', onclick: () => ntAct(n.action) }, n.action.label) : null))) :
    [h('div', { class: 'dim', style: 'padding:14px' }, 'Nothing yet. Crashes, restarts, failed backups and updates show up here.')];
  NT.panel.replaceChildren(
    h('div', { class: 'nbar' }, h('b', null, 'Notifications'),
      h('span', { style: 'flex:1' }),
      h('button', { class: 'btn sm ghost', onclick: async () => { try { await api('POST', '/api/notifications/clear', {}); NT.notes = []; NT.unread = 0; ntBadge(); ntDraw(); } catch (e) { toast(e.message, true); } } }, 'Clear all'),
      typeof ntSettingsButton === 'function' ? ntSettingsButton() : null),
    h('div', { class: 'nlist' }, list));
}

function ntToggle() {
  if (NT.open) { ntClose(); return; }
  NT.open = true;
  NT.panel = h('div', { class: 'npanel', role: 'dialog', 'aria-label': 'Notifications' });
  document.body.append(NT.panel);
  document.addEventListener('mousedown', ntOutside, true);
  ntDraw();
  ntLoad().then(() => {
    clearTimeout(NT.seenTimer);
    NT.seenTimer = setTimeout(async () => {
      if (!NT.open) return;
      try { await api('POST', '/api/notifications/read', {}); NT.unread = 0; ntBadge(); NT.notes.forEach(n => { n.unread = false; }); } catch (e) { /* ignore */ }
    }, 1500);
  });
}

/** A notification arrived while the page is open. */
function onNotify(n) {
  NT.notes.unshift(Object.assign({ unread: true }, n));
  NT.notes = NT.notes.slice(0, 100);
  NT.unread++;
  ntBadge();
  if (NT.open) ntDraw();
  if (!(typeof ntWants === 'function' ? ntWants(n) : true)) { if (typeof ntDesktop === 'function') ntDesktop(n); return; }
  const t = h('div', { class: 'toast ntoast ' + (n.severity || 'info'), role: 'status', onclick: () => { t.remove(); if (!NT.open) ntToggle(); } },
    h('b', null, n.title), n.text ? h('div', { class: 'ntext' }, n.text.length > 160 ? n.text.slice(0, 160) + '...' : n.text) : null);
  $('#toasts').append(t);
  setTimeout(() => t.remove(), 12000);
  if (typeof ntDesktop === 'function') ntDesktop(n);
}

function initNotify() {
  const b = $('#bellBtn');
  if (!b) return;
  b.replaceChildren(icon('bell'));
  b.classList.toggle('hidden', !(S.me.admin || S.me.manage));
  b.onclick = ntToggle;
  if (S.me.admin || S.me.manage) ntLoad();
}

// ---------- what to show, desktop alerts, the webhook ----------
const NT_CATS = [['update', 'Updates and new versions'], ['health', 'Server health (crashes, restarts)'], ['backup', 'Backups'], ['system', 'Drives and system'], ['security', 'Security (blocked sign-ins)'], ['player', 'Players waiting for approval']];

function ntPrefs() {
  try { return JSON.parse(localStorage.getItem('mcm.nt') || '{}'); } catch (e) { return {}; }
}
function ntSavePrefs(p) {
  try { localStorage.setItem('mcm.nt', JSON.stringify(p)); } catch (e) { /* private mode */ }
}
/** Pop-ups and desktop alerts honour the kinds this person switched off here (the list in the bell always shows everything). */
function ntWants(n) {
  const p = ntPrefs();
  return !(p.off || []).includes(n.category);
}

/** A desktop notification, only when the page is not in front (the pop-up covers that case). */
function ntDesktop(n) {
  const p = ntPrefs();
  if (!p.desktop || typeof Notification === 'undefined' || Notification.permission !== 'granted' || !ntWants(n)) return;
  if (document.visibilityState === 'visible' && document.hasFocus()) return;
  try {
    const d = new Notification(n.title, { body: n.text || '', tag: 'mcm-' + n.id, icon: 'icon-192.png' });
    d.onclick = () => { window.focus(); if (!NT.open) ntToggle(); d.close(); };
  } catch (e) { /* not allowed here */ }
}

function ntSettingsButton() {
  return h('button', { class: 'btn sm ghost', title: 'Notification settings', onclick: () => ntSettingsDialog() }, icon('gear'));
}

async function ntSettingsDialog() {
  ntClose();
  const p = ntPrefs();
  const supported = typeof Notification !== 'undefined' && window.isSecureContext;
  const perm = supported ? Notification.permission : 'unsupported';
  const deskNote = h('div', { class: 'dim', style: 'font-size:12.5px' });
  const desk = h('input', { type: 'checkbox', checked: !!p.desktop && perm === 'granted', disabled: !supported });
  const askBtn = h('button', { class: 'btn sm', type: 'button', onclick: async () => {
    const r = await Notification.requestPermission();
    deskNote.textContent = ntDeskText(r);
    if (r === 'granted') { desk.checked = true; desk.disabled = false; }
  } }, 'Allow notifications');
  function ntDeskText(r) {
    if (!supported) return 'Desktop notifications need the page to be opened as http://localhost:' + location.port + ' on the server PC, or over HTTPS. Other addresses on your network cannot use them (a browser rule). The pop-ups in the page still work.';
    if (r === 'granted') return 'Allowed. A notification appears on your desktop when something happens while this page is not in front.';
    if (r === 'denied') return 'Blocked in the browser. Allow notifications for this site in the browser\'s site settings, then come back here.';
    return 'The browser will ask for your permission.';
  }
  deskNote.textContent = ntDeskText(perm);
  askBtn.classList.toggle('hidden', !supported || perm === 'granted');
  const cats = NT_CATS.map(([k, label]) => ({ k, c: h('input', { type: 'checkbox', checked: !(p.off || []).includes(k) }), label }));
  const nodes = [
    h('div', { class: 'box', style: 'display:grid;gap:8px' }, h('h3', null, 'On this device'),
      h('label', null, desk, ' Desktop notifications when this page is not in front'), deskNote, h('div', null, askBtn),
      h('div', { style: 'font-weight:600;margin-top:4px' }, 'Show pop-ups and desktop notifications for'),
      h('div', { style: 'display:grid;gap:3px' }, cats.map(x => h('label', null, x.c, ' ' + x.label))),
      h('div', { class: 'dim', style: 'font-size:12.5px' }, 'The list behind the bell always shows everything you are allowed to see.')),
  ];
  let wh = null;
  if (isAdmin()) {
    let w;
    try { w = await api('GET', '/api/notifications/settings'); } catch (e) { toast(e.message, true); return; }
    const url = h('input', { value: w.webhook.url, placeholder: 'https://discord.com/api/webhooks/...', spellcheck: 'false', style: 'width:100%', autocomplete: 'off' });
    const fmt = h('select', null, [['discord', 'Discord (also works with Discord-compatible apps)'], ['json', 'Plain JSON for your own program']].map(([v, l]) => h('option', { value: v, selected: v === w.webhook.format }, l)));
    const sev = h('select', null, [['info', 'Everything'], ['warn', 'Warnings and problems'], ['bad', 'Problems only']].map(([v, l]) => h('option', { value: v, selected: v === w.webhook.minSeverity }, l)));
    const wc = NT_CATS.map(([k, label]) => ({ k, c: h('input', { type: 'checkbox', checked: w.webhook.categories.includes(k) }), label }));
    const collect = () => ({ url: url.value.trim(), format: fmt.value, minSeverity: sev.value, categories: wc.filter(x => x.c.checked).map(x => x.k) });
    wh = h('div', { class: 'box', style: 'display:grid;gap:8px' }, h('h3', null, 'Webhook (administrators)'),
      h('div', { class: 'dim', style: 'font-size:12.5px' }, 'Sends each notification to a chat channel or your own program. In Discord: channel settings > Integrations > Webhooks > New webhook > Copy webhook URL. Anyone with the address can post to that channel, so keep it private.'),
      h('label', { class: 'dim', style: 'display:grid;gap:2px' }, 'Webhook address', url),
      h('label', { class: 'dim', style: 'display:grid;gap:2px' }, 'Format', fmt),
      h('label', { class: 'dim', style: 'display:grid;gap:2px' }, 'Send', sev),
      h('div', { style: 'display:grid;gap:3px' }, wc.map(x => h('label', null, x.c, ' ' + x.label))),
      w.webhook.lastError ? h('div', { class: 'notice' }, 'The last delivery failed: ' + w.webhook.lastError) : null,
      h('div', { class: 'row', style: 'gap:8px' },
        h('button', { class: 'btn primary', type: 'button', onclick: async () => { try { await api('POST', '/api/notifications/settings', collect()); toast('Webhook saved'); } catch (e) { toast(e.message, true); } } }, 'Save webhook'),
        h('button', { class: 'btn', type: 'button', onclick: async () => {
          try { await api('POST', '/api/notifications/settings', collect()); const r = await api('POST', '/api/notifications/test', {}); toast(r.ok ? 'Test message sent' : 'Failed: ' + r.error, !r.ok); } catch (e) { toast(e.message, true); }
        } }, 'Save and send a test')));
    nodes.push(wh);
    nodes.push(h('div', { class: 'box', style: 'display:grid;gap:6px' }, h('h3', null, 'Update check'),
      h('div', { class: 'dim', style: 'font-size:12.5px' }, 'The manager looks for new Minecraft versions, mod updates and servers that can update about every 6 hours. Press the button to look now.'),
      h('div', null, h('button', { class: 'btn', type: 'button', onclick: async () => { try { const r = await api('POST', '/api/notifications/check', {}); toast(r.started ? 'Checking now. Results appear under the bell.' : 'A check is already running.'); } catch (e) { toast(e.message, true); } } }, 'Check for updates now'))));
  }
  modal('Notification settings', h('div', { style: 'display:grid;gap:12px;max-height:72vh;overflow:auto;width:100%;min-width:0' }, nodes), [
    { label: 'Close', run: (c) => c() },
    { label: 'Save', cls: 'primary', run: async (c) => {
      const np = { desktop: !!desk.checked && supported && Notification.permission === 'granted', off: cats.filter(x => !x.c.checked).map(x => x.k) };
      ntSavePrefs(np);
      toast('Saved');
      c();
    } },
  ], { wide: true });
}

/** The "Update now" button on an update notification. */
async function ntExtraAct(a) {
  if (a.type !== 'update') return;
  const s = (S.servers || []).find(x => x.id === a.server);
  if (!s) return;
  ntClose();
  if (!isAdmin()) { S.tab = 'mods'; selectServer(s.id); toast('Ask an administrator to update ' + s.name + '.'); return; }
  const bk = h('input', { type: 'checkbox', checked: true });
  const msg = h('div', { style: 'display:grid;gap:8px' },
    h('div', null, `Update ${s.name} now? ${s.state === 'running' ? 'It is stopped (players get a 30 second warning), updated and started again.' : 'It is updated and left stopped.'}`),
    h('label', null, bk, ' Back up the whole server first (recommended)'),
    h('div', { class: 'dim', style: 'font-size:12.5px' }, 'With a backup, Restore can put everything back if an update does not suit you. A world saved by a newer Minecraft cannot go back to an older one without a backup.'));
  if (!await confirmDlg(`Update ${s.name}`, msg, 'Update now')) return;
  try {
    const r = await api('POST', `/api/servers/${s.id}/updates/apply`, { delay: 30, backup: bk.checked });
    selectServer(s.id);
    openJob(r.job, 'Update ' + s.name, s.id);
  } catch (e) { toast(e.message, true); }
}