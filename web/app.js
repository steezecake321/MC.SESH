'use strict';

// ---------- helpers ----------
const $ = (s, r = document) => r.querySelector(s);

function h(tag, attrs, ...kids) {
  const e = document.createElement(tag);
  if (attrs) {
    for (const [k, v] of Object.entries(attrs)) {
      if (v == null || v === false) continue;
      if (k === 'class') e.className = v;
      else if (k.startsWith('on')) e.addEventListener(k.slice(2), v);
      else if (k === 'value' || k === 'checked' || k === 'disabled' || k === 'selected' || k === 'hidden') e[k] = v;
      else e.setAttribute(k, v === true ? '' : v);
    }
  }
  const add = (k) => {
    if (k == null || k === false) return;
    if (Array.isArray(k)) k.forEach(add);
    else e.append(k instanceof Node ? k : document.createTextNode(String(k)));
  };
  kids.forEach(add);
  return e;
}

const ICONS = {
  console: 'M4 5h16v14H4z M7.5 9.5l3 2.5-3 2.5 M12.5 15h4',
  players: 'M12 11a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7z M5 20v-1a5 5 0 0 1 5-5h4a5 5 0 0 1 5 5v1',
  mods: 'M12 3l8 4.5v9L12 21l-8-4.5v-9z M4 7.5l8 4.5 8-4.5 M12 12v9',
  props: 'M4 7h9 M17 7h3 M4 12h3 M11 12h9 M4 17h11 M19 17h1 M15 5v4 M9 10v4 M17 15v4',
  files: 'M6 3h9l4 4v14H6z M14 3v5h5 M9 13h6 M9 17h6',
  gear: 'M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z',
  user: 'M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8z M4 21a8 8 0 0 1 16 0',
  plus: 'M12 5v14 M5 12h14',
  folder: 'M3 6a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z',
  up: 'M12 19V5 M6 11l6-6 6 6',
  chev: 'M6 9l6 6 6-6',
  search: 'M11 4a7 7 0 1 0 0 14 7 7 0 0 0 0-14z M21 21l-5-5',
  bolt: 'M13 3L5 14h6l-1 7 8-11h-6z',
  refresh: 'M20 11a8 8 0 1 0-2.3 5.7 M20 4v7h-7',
  archive: 'M3 5h18v4H3z M5 9v10h14V9 M10 13h4',
  users: 'M9 11a3 3 0 1 0 0-6 3 3 0 0 0 0 6z M3 20v-1a4 4 0 0 1 4-4h4a4 4 0 0 1 4 4v1 M16 5.2a3 3 0 0 1 0 5.6 M21 20v-1a4 4 0 0 0-3-3.8',
  map: 'M12 21s-7-6.2-7-11a7 7 0 0 1 14 0c0 4.8-7 11-7 11z M12 12.5a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5z',
  speaker: 'M4 9v6h4l5 4V5L8 9z M16.5 8.5a5 5 0 0 1 0 7 M19 6a8.5 8.5 0 0 1 0 12',
  speakeroff: 'M4 9v6h4l5 4V5L8 9z M17 9.5l5 5 M22 9.5l-5 5',
  geyser: 'M4.5 10a11 11 0 0 1 15 0 M7.5 13.2a6.8 6.8 0 0 1 9 0 M10.4 16.3a2.6 2.6 0 0 1 3.2 0 M12 19.5v.01',
};
function icon(name) {
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('viewBox', '0 0 24 24');
  svg.setAttribute('class', 'ico');
  svg.setAttribute('aria-hidden', 'true');
  const p = document.createElementNS('http://www.w3.org/2000/svg', 'path');
  p.setAttribute('d', ICONS[name]);
  svg.append(p);
  return svg;
}
const isMobile = () => window.matchMedia('(max-width: 860px)').matches;

const S = {
  servers: [], sel: null, tab: 'console', manager: null, pdata: {}, ac: {}, cons: {}, view: null, lastCheck: {}, hist: {}, token: 0,
};

function toast(msg, err) {
  const t = h('div', { class: 'toast' + (err ? ' err' : '') }, msg);
  $('#toasts').append(t);
  setTimeout(() => t.remove(), err ? 7000 : 3500);
}

async function api(method, path, body) {
  const r = await fetch(path, {
    method, credentials: 'same-origin',
    headers: Object.assign({ 'X-Requested-With': 'mgr' }, body !== undefined ? { 'Content-Type': 'application/json' } : {}),
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
  let data = null;
  try { data = await r.json(); } catch (e) { /* not json */ }
  if (r.status === 401 && !path.startsWith('/api/auth/')) { showLogin(); throw new Error('Signed out'); }
  if (!r.ok) throw new Error((data && data.error) || r.statusText || 'Request failed');
  return data;
}

function modal(title, body, buttons, opts = {}) {
  const dlg = h('dialog');
  if (opts.wide) dlg.style.maxWidth = 'min(940px, 96vw)';
  const close = () => { try { dlg.close(); } catch (e) { /* closed */ } dlg.remove(); if (opts.onClose) opts.onClose(); };
  const btns = h('div', { class: 'btns' }, (buttons || []).map(b => h('button', {
    class: 'btn ' + (b.cls || ''), disabled: b.disabled, onclick: () => b.run ? b.run(close, btnEls) : close(),
  }, b.label)));
  const btnEls = [...btns.children];
  dlg.append(h('div', { class: 'dlg' }, h('h3', null, title), body, btns));
  dlg.addEventListener('cancel', () => { setTimeout(close, 0); });
  document.body.append(dlg);
  dlg.showModal();
  const first = dlg.querySelector('input,select,textarea');
  if (first) first.focus();
  return { close, el: dlg, btns: btnEls };
}

function confirmBox(title, msg, okLabel, danger) {
  return new Promise(res => {
    let done = false;
    const fin = (v) => { if (!done) { done = true; res(v); } };
    modal(title, h('div', null, msg), [
      { label: 'Cancel', run: (c) => { fin(false); c(); } },
      { label: okLabel || 'OK', cls: danger ? 'danger solid' : 'primary', run: (c) => { fin(true); c(); } },
    ], { onClose: () => fin(false) });
  });
}

function inputBox(title, label, def, placeholder) {
  return new Promise(res => {
    let done = false;
    const fin = (v) => { if (!done) { done = true; res(v); } };
    const inp = h('input', { value: def || '', placeholder: placeholder || '', style: 'width:100%' });
    const m = modal(title, h('label', { class: 'dim' }, label, h('div', null, inp)), [
      { label: 'Cancel', run: (c) => { fin(null); c(); } },
      { label: 'OK', cls: 'primary', run: (c) => { fin(inp.value); c(); } },
    ], { onClose: () => fin(null) });
    inp.addEventListener('keydown', e => { if (e.key === 'Enter') { fin(inp.value); m.close(); } });
  });
}

function fmtUp(s) {
  if (!s) return '-';
  const d = Math.floor(s / 86400), hh = Math.floor(s % 86400 / 3600), m = Math.floor(s % 3600 / 60);
  return d ? `${d}d ${hh}h` : hh ? `${hh}h ${m}m` : `${m}m`;
}
/** Copies text to the clipboard (works on plain-http addresses too) and shows a toast. */
function copyValue(text, msg) {
  const done = () => toast(msg || 'Copied');
  if (navigator.clipboard && window.isSecureContext) { navigator.clipboard.writeText(text).then(done, () => legacyCopy(text, done)); return; }
  legacyCopy(text, done);
}
/** The LAN address(es) in the header; clicking one copies it. */
function showRemote() {
  const urls = (S.manager && S.manager.urls) || [];
  $('#remote').replaceChildren('LAN: ', ...(urls.length ? urls.flatMap((u, i) => [i ? '  ' : '', h('span', { class: 'copyurl', title: 'Click to copy', onclick: () => copyValue(u, 'Address copied: ' + u) }, u)]) : ['not available']));
}
function legacyCopy(text, done) {
  const ta = h('textarea', { readonly: true, style: 'position:fixed;left:-9999px;top:0;opacity:0' });
  ta.value = text;
  document.body.append(ta);
  ta.select();
  ta.setSelectionRange(0, text.length);
  let ok = false;
  try { ok = document.execCommand('copy'); } catch (e) { ok = false; }
  ta.remove();
  if (ok) done(); else toast('Could not copy. Press and hold the text to copy it.', true);
}
const fmtMem = (mb) => mb >= 1024 ? (mb / 1024).toFixed(1) + ' GB' : mb + ' MB';
const sv = () => S.servers.find(s => s.id === S.sel);
const isAdmin = () => !!(S.me && S.me.admin);
const lvl = (s) => (isAdmin() ? 5 : (s && typeof s.level === 'number' ? s.level : 0));
const LEVEL_NAME = ['Viewer', 'Moderator', 'Gamemaster', 'Admin', 'Operator'];

// ---------- auth ----------
async function init() {
  let st;
  try { st = await (await fetch('/api/auth/state')).json(); } catch (e) { document.body.textContent = 'Cannot reach the manager.'; return; }
  if (st.loggedIn && !inviteFromUrl()) startApp(); else showLogin(st);
}

function inviteFromUrl() {
  try { return new URLSearchParams(location.hash.replace(/^#/, '')).get('invite') || ''; } catch (e) { return ''; }
}

async function showLogin(st) {
  if (S.es) { S.es.close(); S.es = null; }
  $('#app').classList.add('hidden');
  const box = $('#login');
  box.classList.remove('hidden');
  if (!st) st = await (await fetch('/api/auth/state')).json();
  const inv = inviteFromUrl();
  if (inv) { showRegister(box, inv); return; }
  const err = h('div', { class: 'err' });
  const user = h('input', { placeholder: 'Username', autocomplete: 'username', autocapitalize: 'none', spellcheck: 'false' });
  const pw = h('input', { type: 'password', placeholder: 'Password', autocomplete: st.configured ? 'current-password' : 'new-password' });
  const pw2 = h('input', { type: 'password', placeholder: 'Repeat password', autocomplete: 'new-password' });
  const code = h('input', { class: 'hidden', placeholder: '6-digit code or recovery code', autocomplete: 'one-time-code', inputmode: 'numeric', spellcheck: 'false' });
  const plain = location.protocol === 'http:' && !/^(localhost|127\.0\.0\.1|\[::1\])$/.test(location.hostname)
    ? h('div', { class: 'dim', style: 'font-size:12px' }, 'This page is not encrypted (http), so your password travels in the clear. Only use it on a network you trust, never on public Wi-Fi or over the internet.') : null;
  const go = async (e) => {
    e.preventDefault();
    err.textContent = '';
    try {
      if (!st.configured) {
        if (pw.value !== pw2.value) throw new Error('Passwords do not match');
        await api('POST', '/api/auth/setup', { username: user.value.trim(), password: pw.value });
      } else {
        const r = await api('POST', '/api/auth/login', { username: user.value.trim(), password: pw.value, code: code.value.trim() });
        if (r && r.need2fa) {
          code.classList.remove('hidden');
          err.textContent = 'Enter the 6-digit code from your authenticator app (or one of your recovery codes).';
          err.className = 'dim';
          code.focus();
          return;
        }
      }
      box.classList.add('hidden');
      startApp();
    } catch (ex) {
      // This page was loaded before the first account existed (or after it was removed): reload the right form.
      if (/already exists/i.test(ex.message) || /No account has been created/i.test(ex.message)) {
        const fresh = await (await fetch('/api/auth/state')).json();
        await showLogin(fresh);
        const e2 = $('#login .err');
        if (e2) e2.textContent = fresh.configured ? 'An account already exists. Please sign in.' : 'No account exists yet. Please create the first one.';
        return;
      }
      err.className = 'err';
      err.textContent = ex.message;
    }
  };
  let body;
  if (!st.configured && !st.canSetup) {
    body = [h('img', { src: 'banner.png', class: 'login-banner', alt: 'MC.SESH - Minecraft Server Easy Self Hosting' }), h('p', null, 'No account has been created yet. Open this page in a browser on the server PC (http://localhost:' + location.port + ') to finish first-time setup.')];
  } else if (!st.configured) {
    body = h('form', { class: 'card', onsubmit: go }, h('img', { src: 'banner.png', class: 'login-banner', alt: 'MC.SESH - Minecraft Server Easy Self Hosting' }), h('h1', null, 'Welcome - create the owner account'),
      h('div', { class: 'dim' }, 'This first account is the manager administrator (OP5): it controls the manager itself. Later you can invite other people with their own logins and access levels. Password: 8+ characters.'),
      user, pw, pw2, err, h('button', { class: 'btn primary' }, 'Create account and continue'));
  } else {
    body = h('form', { class: 'card', onsubmit: go }, h('img', { src: 'banner.png', class: 'login-banner', alt: 'MC.SESH - Minecraft Server Easy Self Hosting' }), st.hint ? h('div', { class: 'notice' }, st.hint) : null, user, pw, code, err, h('button', { class: 'btn primary' }, 'Sign in'), plain,
      h('a', { href: '#', class: 'dim', style: 'text-align:center;font-size:13px', onclick: (e) => { e.preventDefault(); showRegister(box, ''); } }, 'I have an invite'));
  }
  box.replaceChildren(body instanceof Node ? body : h('div', { class: 'card' }, body));
  user.focus();
}

/** Create an account from an invite token (typed in, or taken from the #invite=... link). */
function showRegister(box, token) {
  const err = h('div', { class: 'err' });
  const info = h('div', { class: 'dim' });
  const tok = h('input', { placeholder: 'Invite code', value: token || '', autocomplete: 'off', spellcheck: 'false', style: 'font-family:var(--mono);font-size:13px' });
  const user = h('input', { placeholder: 'Choose a username', autocomplete: 'username', autocapitalize: 'none', spellcheck: 'false' });
  const pw = h('input', { type: 'password', placeholder: 'Choose a password (8+ characters)', autocomplete: 'new-password' });
  const pw2 = h('input', { type: 'password', placeholder: 'Repeat password', autocomplete: 'new-password' });
  const LV = ['view-only access', 'moderator access (level 1)', 'gamemaster access (level 2)', 'admin access (level 3)', 'operator access (level 4)'];
  const check = async () => {
    info.textContent = '';
    if (tok.value.trim().length < 20) return;
    try {
      const r = await api('POST', '/api/auth/invite', { token: tok.value.trim() });
      info.textContent = r.admin ? `${r.createdBy} invited you to be a manager administrator (OP5).` : `${r.createdBy} invited you with ${LV[r.level] || 'access'} to: ${r.servers.join(', ')}.`;
    } catch (e) { info.textContent = ''; err.textContent = e.message; }
  };
  tok.addEventListener('change', check);
  const go = async (e) => {
    e.preventDefault();
    err.textContent = '';
    if (pw.value !== pw2.value) { err.textContent = 'Passwords do not match'; return; }
    try {
      await api('POST', '/api/auth/register', { token: tok.value.trim(), username: user.value.trim(), password: pw.value });
      history.replaceState(null, '', location.pathname);
      box.classList.add('hidden');
      startApp();
    } catch (ex) { err.textContent = ex.message; }
  };
  box.replaceChildren(h('form', { class: 'card', onsubmit: go }, h('img', { src: 'banner.png', class: 'login-banner', alt: 'MC.SESH - Minecraft Server Easy Self Hosting' }), h('h1', null, 'Join MC.SESH'), h('div', { class: 'dim' }, 'Paste the invite code you were given, then choose your own username and password.'),
    tok, info, user, pw, pw2, err, h('button', { class: 'btn primary' }, 'Create my account'),
    h('a', { href: '#', class: 'dim', style: 'text-align:center;font-size:13px', onclick: (e) => { e.preventDefault(); history.replaceState(null, '', location.pathname); showLogin(); } }, 'Back to sign in')));
  if (token) check();
  (token ? user : tok).focus();
}
// ---------- app shell ----------
async function startApp() {
  $('#login').classList.add('hidden');
  $('#app').classList.remove('hidden');
  $('#acct').onclick = accountMenu;
  $('#acct').replaceChildren(icon('user'));
  $('#settingsBtn').replaceChildren(icon('gear'));
  $('#settingsBtn').onclick = () => settingsDialog();
  $('#refreshBtn').replaceChildren(icon('refresh'));
  $('#refreshBtn').onclick = () => refreshAll();
  $('#bkMgrBtn').replaceChildren(icon('archive'));
  $('#bkMgrBtn').onclick = () => backupManager();
  $('#updAllBtn').replaceChildren(icon('up'));
  $('#updAllBtn').onclick = () => updateAllDialog();
  try { S.me = await api('GET', '/api/me'); } catch (e) { return; }
  $('#settingsBtn').classList.toggle('hidden', !S.me.admin);
  $('#updAllBtn').classList.toggle('hidden', !S.me.admin);
  api('GET', '/api/settings').then((s) => { if (s && s.version) { $('#verTag').textContent = 'v' + s.version; document.title = 'MC.SESH ' + s.version; } }).catch(() => {});
  const ub = $('#usersBtn');
  ub.replaceChildren(icon('users'));
  ub.onclick = () => usersDialog();
  ub.classList.toggle('hidden', !S.me.manage);
  try {
    S.manager = await api('GET', '/api/manager');
    showRemote();
  } catch (e) { /* ignore */ }
  S.servers = await api('GET', '/api/servers');
  const saved = localStorage.getItem('mcm.sel');
  S.sel = S.servers.some(s => s.id === saved) ? saved : (S.servers[0] && S.servers[0].id);
  renderSide();
  renderMain();
  connect();
  if (typeof initNotify === 'function') initNotify();
  if (S.me.admin && typeof firstRunCheck === 'function') firstRunCheck();
}

function connect() {
  if (S.es) S.es.close();
  const es = new EventSource('/api/stream');
  S.es = es;
  es.addEventListener('status', e => { S.servers = JSON.parse(e.data); onStatus(); });
  es.addEventListener('log', e => onLogEvent(JSON.parse(e.data)));
  es.addEventListener('notify', e => { if (typeof onNotify === 'function') onNotify(JSON.parse(e.data)); });
  es.addEventListener('reset', e => { const d = JSON.parse(e.data); const c = S.cons[d.server]; if (c) { c.entries = []; c.seq = 0; c.loaded = false; if (S.view && S.view.id === d.server) loadLog(d.server); } });
  es.onopen = () => { $('#banner').classList.add('hidden'); if (S.view && S.view.id) { const c = S.cons[S.view.id]; if (c) { c.loaded = false; loadLog(S.view.id); } } };
  es.onerror = async () => {
    $('#banner').textContent = 'Connection to the manager lost - reconnecting...';
    $('#banner').classList.remove('hidden');
    try { const st = await (await fetch('/api/auth/state')).json(); if (!st.loggedIn) showLogin(st); } catch (e) { /* offline */ }
  };
}

/** Re-reads every server from the machine (is it really up, who is online) and reloads what this page remembers about them. */
let refreshing = false;
async function refreshAll() {
  if (refreshing) return;
  refreshing = true;
  const b = $('#refreshBtn');
  if (b) b.classList.add('spin');
  try {
    const r = await api('POST', '/api/refresh', {});
    S.servers = r.servers;
    S.ac = {}; S.pdata = {}; S.live = {};
    for (const c of Object.values(S.cons)) { c.loaded = false; c.entries = []; c.seq = 0; }
    try { S.manager = await api('GET', '/api/manager'); showRemote(); } catch (e) { /* ignore */ }
    if (S.sel && !sv()) S.sel = S.servers[0] && S.servers[0].id;
    connect();
    renderSide();
    renderMain();
    const up = S.servers.filter(s => s.state === 'running').length;
    toast(`Refreshed: ${up} of ${S.servers.length} server${S.servers.length === 1 ? '' : 's'} running`);
  } catch (e) { toast(e.message, true); }
  finally { refreshing = false; if (b) b.classList.remove('spin'); }
}

/** Restarts the manager program itself (administrators). Minecraft servers keep running. */
async function restartManagerFlow() {
  if (!await confirmBox('Restart the manager', 'Restart the manager program? Your Minecraft servers keep running and nobody is kicked. This page reconnects by itself, usually within 15 seconds.', 'Restart', true)) return;
  try { await api('POST', '/api/manager/restart', {}); } catch (e) { toast(e.message, true); return; }
  const banner = $('#banner');
  banner.textContent = 'Restarting the manager... this page reloads by itself.';
  banner.classList.remove('hidden');
  if (S.es) S.es.close();
  const t0 = Date.now();
  let down = false;
  const poll = async () => {
    let ok = false;
    try { ok = (await fetch('/api/auth/state', { cache: 'no-store' })).ok; } catch (e) { ok = false; }
    if (!ok) down = true;
    if (ok && (down || Date.now() - t0 > 12000)) { location.reload(); return; }
    if (Date.now() - t0 > 120000) { banner.textContent = 'The manager has not come back yet. Wait a little longer, then reload this page. If it never returns, start it with Start.bat.'; return; }
    setTimeout(poll, 2000);
  };
  setTimeout(poll, 2500);
}

function onStatus() {
  if (S.sel && !sv()) {
    S.sel = S.servers[0] && S.servers[0].id;
    renderSide();
    renderMain();
    return;
  }
  renderSide();
  renderHead();
  if (S.view && S.view.onStatus) S.view.onStatus();
}

async function removeFromManager(s) {
  if (!await confirmBox('Remove from manager', `Stop managing ${s.name}? Its folder, world and mods are NOT deleted; it just disappears from this list. You can add it again any time with Add server > Add an existing server.`, 'Remove', true)) return;
  try {
    await api('POST', `/api/servers/${s.id}/remove`, {});
    S.servers = await api('GET', '/api/servers');
    S.sel = S.servers[0] && S.servers[0].id;
    renderSide(); renderMain(); toast('Removed from the manager');
  } catch (ex) { toast(ex.message, true); }
}

function renderSide() {
  renderMobileBar();
  const side = $('#side');
  side.replaceChildren(...(isAdmin() && S.servers.length ? [h('button', { class: 'btn', onclick: (e) => openMenu(e.currentTarget, allServersItems()) }, 'All servers ', icon('chev'))] : []),
    ...S.servers.map(s => h('button', { class: 'scard' + (s.id === S.sel ? ' sel' : ''), onclick: () => selectServer(s.id) },
    h('div', { class: 'n' }, h('span', { class: 'dot ' + s.state }), s.name),
    h('div', { class: 's' }, s.state === 'running' ? `${s.players.length}/${s.maxPlayers} players \u00b7 ${fmtMem(s.memMb)}` : s.state))),
    ...(isAdmin() ? [h('button', { class: 'btn', onclick: addMenu }, icon('plus'), ' Add', h('span', { class: 'hide-s' }, ' server'))] : []));
}

async function addServerWizard(opts = {}) {
  let defs, det = null;
  try { defs = await api('GET', '/api/install/defaults'); } catch (e) { toast(e.message, true); return; }
  if (opts.world) {
    try { det = await api('POST', '/api/worlds/details', { kind: opts.world.kind, path: opts.world.path, root: opts.world.root || '' }); } catch (e) { toast(e.message, true); return; }
  }
  const pk = opts.pack || null;
  const P = (!det && !pk && defs.prefs) || {};
  const LOADER_NAME = { fabric: 'Fabric', forge: 'Forge', neoforge: 'NeoForge' };
  const prefLoader = ['fabric', 'forge', 'neoforge'].includes(P.modLoader) ? P.modLoader : 'fabric';
  const geyserLoader = prefLoader === 'forge' ? 'neoforge' : prefLoader; // Geyser has no Forge build
  const ws = det ? worldSection(det) : null;
  const safeName = (t) => (t || 'New Server').replace(/[^A-Za-z0-9 _.-]/g, '_').trim().slice(0, 40) || 'New Server';
  const safeWorld = (t) => (t || 'world').replace(/[\\/:*?"<>|\u0000-\u001f]/g, '_').trim().slice(0, 64) || 'world';
  let preferMc = det && det.mc ? det.mc : (pk ? pk.info.mc : null), snapTried = false;
  const TYPES = [['vanilla', 'Vanilla'], ['fabric', 'Fabric'], ['forge', 'Forge'], ['neoforge', 'NeoForge']];
  const BLURB = { vanilla: 'Official Mojang server, no mods.', fabric: 'Lightweight mod loader. Keeps mods up to date automatically.', forge: 'Classic mod loader.', neoforge: 'Modern Forge successor.' };
  let type = pk ? pk.info.loader : det && ['fabric', 'forge', 'neoforge'].includes(det.loader) ? det.loader : (det && det.loader === 'quilt' ? 'fabric' : (['fabric', 'forge', 'neoforge'].includes(P.type) ? P.type : (P.bedrock ? (P.modLoader === 'forge' ? 'neoforge' : (['fabric', 'neoforge'].includes(P.modLoader) ? P.modLoader : 'fabric')) : P.journeymap ? (['fabric', 'forge', 'neoforge'].includes(P.modLoader) ? P.modLoader : 'fabric') : 'vanilla'))), seq = 0, javaBlock = false;
  const typeRow = h('div', { class: 'sub' });
  const blurb = h('div', { class: 'dim' });
  const mcSel = h('select');
  const snap = h('input', { type: 'checkbox', onchange: () => loadVersions() });
  const loaderSel = h('select');
  const loaderRow = h('label', { class: 'dim' }, 'Loader version', loaderSel);
  const note = h('div', { class: 'dim' });
  const name = h('input', { value: pk ? safeName(pk.info.name) : det ? safeName(det.name) : 'New Server', maxlength: 40 });
  let parent = defs.root;
  const finalPath = h('div', { class: 'dim mono', style: 'font-size:12px;word-break:break-all' });
  const updFinal = () => { finalPath.textContent = 'Will be created in: ' + parent.replace(/[\\/]+$/, '') + '\\' + (name.value.trim() || '...'); };
  const locPath = h('input', { value: parent, spellcheck: 'false', style: 'flex:1', oninput: () => { parent = locPath.value.trim(); updFinal(); } });
  const locBtn = h('button', { class: 'btn', type: 'button', onclick: async () => {
    const p = await pickFolder({ title: 'Where should the new server be installed?', start: locPath.value });
    if (p) { locPath.value = p; parent = p; updFinal(); }
  } }, icon('folder'), ' Browse');
  name.addEventListener('input', updFinal);
  updFinal();
  const port = h('input', { type: 'number', value: defs.port, min: 1024, max: 65535 });
  const gb = (mb) => h('option', { value: mb }, (mb / 1024) + ' GB');
  const minMb = h('select', null, [512, 1024, 2048, 4096].map(gb));
  minMb.value = 1024;
  const maxMb = h('select', null, [2048, 4096, 6144, 8192, 12288, 16384].map(gb));
  maxMb.value = pk ? (pk.info.files >= 150 ? 8192 : pk.info.files >= 60 ? 6144 : 4096) : 4096;
  if (P.maxMb) { maxMb.value = P.maxMb; if (+maxMb.value !== +P.maxMb) maxMb.value = 4096; }
  const players = h('input', { type: 'number', value: P.maxPlayers || 20, min: 1, max: 500 });
  const motd = h('input', { value: '', maxlength: 120, placeholder: 'Defaults to the server name' });
  const worldName = h('input', { value: det ? safeWorld(det.folder) : 'world', maxlength: 64, spellcheck: 'false' });
  const seedI = h('input', { value: '', placeholder: 'Random', maxlength: 64, spellcheck: 'false' });
  const sel = (opts, def) => { const e = h('select', null, opts.map(([v, l]) => h('option', { value: v }, l))); e.value = def; return e; };
  const levelType = sel([['normal', 'Normal'], ['flat', 'Superflat'], ['large_biomes', 'Large biomes'], ['amplified', 'Amplified'], ['single_biome', 'Single biome']], 'normal');
  const biomeSel = h('select');
  const biomeOther = h('input', { class: 'hidden', placeholder: 'namespace:biome, for example terralith:volcanic_peaks', spellcheck: 'false', maxlength: 100 });
  const biomeMsg = h('div', { class: 'dim', style: 'font-size:12.5px' });
  const lab0 = (t, el) => h('label', { class: 'dim', style: 'display:grid;gap:2px' }, t, el);
  const biomeRow = h('div', { class: 'hidden', style: 'display:grid;gap:6px' }, lab0('Biome', biomeSel), biomeOther, biomeMsg);
  let biomeSeq = 0;
  const refreshBiomes = async () => {
    const on = levelType.value === 'single_biome';
    biomeRow.classList.toggle('hidden', !on);
    if (!on || !mcSel.value || mcSel.value === 'Loading...') return;
    const my = ++biomeSeq;
    try {
      const r = await api('GET', '/api/install/biomes?mc=' + encodeURIComponent(mcSel.value));
      if (my !== biomeSeq) return;
      const keep = biomeSel.value || 'minecraft:plains';
      const groups = [];
      for (const b of (r.supported ? r.biomes : [])) {
        let g = groups.find(x => x.name === b.group);
        if (!g) groups.push(g = { name: b.group, items: [] });
        g.items.push(b);
      }
      const nodes = groups.map(g => h('optgroup', { label: g.name }, g.items.map(b => h('option', { value: b.id }, b.name))));
      if (r.supported && type !== 'vanilla') nodes.push(h('optgroup', { label: 'Mods' }, h('option', { value: 'other' }, 'Another biome from a mod...')));
      biomeSel.replaceChildren(...nodes);
      biomeSel.value = [...biomeSel.options].some(o => o.value === keep) ? keep : 'minecraft:plains';
      biomeOther.classList.toggle('hidden', biomeSel.value !== 'other');
      biomeMsg.textContent = r.supported
        ? 'The whole overworld uses this one biome. Terrain still has hills, rivers and caves; the biome sets the trees, colours, weather and mobs. The Nether and End dimensions are normal. Cave, Nether, End and Void biomes are allowed too, but make strange worlds.'
        : `A single-biome world needs Minecraft ${r.from} or newer. Pick another world type or a newer version.`;
    } catch (e) { /* the server will say if it is not allowed */ }
  };
  levelType.addEventListener('change', refreshBiomes);
  biomeSel.addEventListener('change', () => biomeOther.classList.toggle('hidden', biomeSel.value !== 'other'));
  const difficulty = sel([['peaceful', 'Peaceful'], ['easy', 'Easy'], ['normal', 'Normal'], ['hard', 'Hard']], det ? det.difficulty : (P.difficulty || 'easy'));
  const gamemode = sel([['survival', 'Survival'], ['creative', 'Creative'], ['adventure', 'Adventure'], ['spectator', 'Spectator']], det ? det.gamemode : (P.gamemode || 'survival'));
  const spawnProt = h('input', { type: 'number', value: 0, min: 0, max: 256 });
  const whitelistOn = h('input', { type: 'checkbox', checked: P.whitelist === true });
  const planMods = h('input', { type: 'checkbox', checked: !det && P.planMods !== false, onchange: () => syncStart() });
  const planModsRow = h('label', null, planMods, ' I plan to add mods (opens a mod picker as soon as the server is created)');
  let wantGeyser = false;
  const geyser = h('input', { type: 'checkbox', onchange: () => {
    if (geyser.checked && type === 'vanilla') { wantGeyser = true; type = geyserLoader; drawTypes(); loadVersions(); toast(LOADER_NAME[geyserLoader] + ' selected: Geyser needs a mod loader' + (prefLoader === 'forge' ? ' and has no Forge build' : '')); }
    syncStart();
  } });
  const geyserRow = h('div', { class: 'hidden', style: 'display:grid;gap:2px' }, h('label', null, geyser, ' Let Bedrock players join (installs Geyser and Floodgate)'),
    h('div', { class: 'dim', style: 'font-size:12.5px;margin-left:24px' }, 'Phones, consoles and Windows Bedrock players connect to this Java server. Uses a separate UDP port; the Geyser tab then helps with the firewall and the whitelist.'));
  const geyserNote = h('div', { class: 'dim hidden', style: 'font-size:12.5px' });
  let wantJm = false;
  const jm = h('input', { type: 'checkbox', onchange: () => {
    if (jm.checked && type === 'vanilla') { wantJm = true; type = prefLoader; drawTypes(); loadVersions(); toast(LOADER_NAME[prefLoader] + ' selected: JourneyMap needs a mod loader'); }
  } });
  const jmRow = h('div', { class: 'hidden', style: 'display:grid;gap:2px' }, h('label', null, jm, ' Install JourneyMap (live map, waypoints and player radar)'),
    h('div', { class: 'dim', style: 'font-size:12.5px;margin-left:24px' }, 'Players add JourneyMap on their own computer to see the map. A JourneyMap tab here then lets administrators manage waypoints and map settings.'));
  const jmNote = h('div', { class: 'dim hidden', style: 'font-size:12.5px' });
  const autostart = h('input', { type: 'checkbox', checked: P.autostart === true });
  const lanBox = h('input', { type: 'checkbox', checked: P.lanBroadcast !== false });
  const startAfter = h('input', { type: 'checkbox', checked: !det });
  const startRow = h('label', null, startAfter, h('span', null, ' Start the server when installation finishes'));
  const pin = h('input', { type: 'checkbox' });
  const pinRow = h('label', { class: 'hidden' }, pin, ' Keep this Minecraft version (the updater will still update Fabric and mods, but never upgrade Minecraft)');
  let latestMc = '';
  const syncStart = () => {
    const hold = (planMods.checked && type !== 'vanilla') || (geyser.checked && !geyserRow.classList.contains('hidden'));
    if (hold) startAfter.checked = false;
    startAfter.disabled = hold;
    startRow.querySelector('span').textContent = hold ? ' Start the server when installation finishes (off: you will start it after adding mods)' : ' Start the server when installation finishes';
  };
  const checkGeyser = async (my) => {
    geyserRow.classList.add('hidden');
    geyserNote.classList.add('hidden');
    geyser.checked = false;
    if (type === 'vanilla' && !pk && !det) { geyserNote.textContent = 'Ticking this switches the server type to ' + LOADER_NAME[geyserLoader] + ', because Geyser needs a mod loader' + (prefLoader === 'forge' ? ' and has no Forge build' : '') + '. You can pick another loader above afterwards.'; geyserNote.classList.remove('hidden'); geyserRow.classList.remove('hidden'); syncStart(); return; }
    if (pk || type === 'vanilla' || !mcSel.value || (loaderSel.value === '' && type !== 'vanilla')) { syncStart(); return; }
    try {
      const g = await api('GET', `/api/install/geyser?type=${type}&mc=${encodeURIComponent(mcSel.value)}`);
      if (my !== undefined && my !== seq) return;
      if (g.available) { geyserRow.classList.remove('hidden'); if (P.bedrock || wantGeyser) geyser.checked = true; }
      else { geyserNote.textContent = g.reason; geyserNote.classList.remove('hidden'); }
    } catch (e) { /* optional */ }
    wantGeyser = false;
    syncStart();
  };
  const checkJm = async (my) => {
    jmRow.classList.add('hidden');
    jmNote.classList.add('hidden');
    jm.checked = false;
    if (type === 'vanilla' && !pk && !det) { jmNote.textContent = 'Ticking this switches the server type to ' + LOADER_NAME[prefLoader] + ', because JourneyMap is a mod. You can pick another loader above afterwards.'; jmNote.classList.remove('hidden'); jmRow.classList.remove('hidden'); wantJm = false; return; }
    if (pk || type === 'vanilla' || !mcSel.value || loaderSel.value === '') return;
    try {
      const g = await api('GET', `/api/install/journeymap?type=${type}&mc=${encodeURIComponent(mcSel.value)}`);
      if (my !== undefined && my !== seq) return;
      if (g.available) { jmRow.classList.remove('hidden'); if (P.journeymap || wantJm) jm.checked = true; }
      else { jmNote.textContent = g.reason; jmNote.classList.remove('hidden'); }
    } catch (e) { /* optional */ }
    wantJm = false;
  };
  const eula = h('input', { type: 'checkbox', onchange: () => sync() });
  const lab =(t, el) => h('label', { class: 'dim', style: 'display:grid;gap:2px' }, t, el);
  let m;
  const sync = () => { if (m) m.btns[1].disabled = !eula.checked || javaBlock; };

  const drawTypes = () => {
    typeRow.replaceChildren(...TYPES.map(([k, label]) => h('button', { class: 'chip' + (k === type ? ' on' : ''), type: 'button', disabled: !!pk && k !== type, onclick: () => { type = k; drawTypes(); loadVersions(); } }, label)));
    blurb.textContent = BLURB[type];
    loaderRow.classList.toggle('hidden', type === 'vanilla');
    pinRow.classList.toggle('hidden', type !== 'fabric' || !!pk);
    planModsRow.classList.toggle('hidden', type === 'vanilla' || !!det || !!pk);
    if (ws) ws.refresh(type);
    syncStart();
  };
  const loadLoaders = async (my) => {
    refreshBiomes();
    note.textContent = '';
    javaBlock = false;
    sync();
    if (type !== 'vanilla') {
      loaderSel.replaceChildren(h('option', null, 'Loading...'));
      try {
        const r = await api('GET', `/api/install/loaders?type=${type}&mc=${encodeURIComponent(mcSel.value)}`);
        if (my !== seq) return;
        loaderSel.replaceChildren(...r.loaders.map(l => h('option', { value: l.id, selected: l.recommended }, l.label)));
        if (pk && [...loaderSel.options].some(o => o.value === pk.info.loaderVersion)) loaderSel.value = pk.info.loaderVersion;
        if (!r.loaders.length) { note.textContent = `No ${type} build for this Minecraft version.`; javaBlock = true; }
      } catch (e) { if (my === seq) { note.textContent = e.message; javaBlock = true; } }
    }
    try {
      const j = await api('GET', `/api/install/java?mc=${encodeURIComponent(mcSel.value)}&type=${type}`);
      if (my !== seq) return;
      if (!j.hasServer) { note.textContent = 'Mojang published no server download for this version.'; javaBlock = true; }
      else if (j.serverJavaFound === false) { note.textContent = j.message; javaBlock = true; }
      else if (j.required > j.installed) { note.textContent = `This version needs Java ${j.required}; this PC only has Java ${j.installed}.`; javaBlock = true; }
      else if (j.required < j.installed) note.textContent = j.serverJavaMajor && j.serverJavaMajor < j.installed
        ? `Built for Java ${j.required}. The manager found a Java ${j.serverJavaMajor} on this PC and will run this server on it.`
        : `Built for Java ${j.required}; this PC runs Java ${j.installed}. Older versions can have problems on newer Java.`;
    } catch (e) { /* non-blocking */ }
    if ((det || pk) && type !== 'vanilla' && preferMc && mcSel.value && mcSel.value !== preferMc && !javaBlock)
      note.textContent = `This ${pk ? 'modpack' : 'world'} was made for Minecraft ${preferMc}. Its mods only exist for that version, so a different version starts without them` + (det ? ' and can damage modded blocks and items in the copy (your original is not touched).' : '.');
    sync();
    checkGeyser(my);
    checkJm(my);
  };
  const loadVersions = async () => {
    const my = ++seq;
    mcSel.replaceChildren(h('option', null, 'Loading...'));
    loaderSel.replaceChildren();
    try {
      const r = await api('GET', `/api/install/versions?type=${type}&snapshots=${snap.checked ? 1 : 0}`);
      if (my !== seq) return;
      latestMc = r.latest;
      mcSel.replaceChildren(...r.versions.map(v => h('option', { value: v.id, selected: v.id === r.latest }, v.id + (v.id === r.latest ? ' (latest)' : v.type === 'snapshot' ? ' (snapshot)' : ''))));
      if (preferMc) {
        if ([...mcSel.options].some(o => o.value === preferMc)) { mcSel.value = preferMc; pin.checked = preferMc !== latestMc; }
        else if (!snap.checked && !snapTried) { snapTried = true; snap.checked = true; return loadVersions(); }
        else note.textContent = `Minecraft ${preferMc} (the version this world was saved in) is not available for ${type}. A server needs the same version or newer.`;
      }
      await loadLoaders(my);
    } catch (e) { if (my === seq) { mcSel.replaceChildren(); note.textContent = e.message; } }
  };
  mcSel.addEventListener('change', () => { pin.checked = mcSel.value !== latestMc; loadLoaders(++seq); });

  const body = h('div', { style: 'display:grid;gap:10px;max-height:70vh;overflow:auto' },
    typeRow, blurb,
    h('div', { class: 'row' }, lab('Minecraft version', mcSel), h('label', { class: 'dim' }, snap, ' show snapshots'), loaderRow),
    note,
    lab('Server name (also its folder name)', name),
    h('div', { class: 'dim', style: 'display:grid;gap:4px' }, 'Install location (parent folder)', h('div', { class: 'row', style: 'flex-wrap:nowrap' }, locPath, locBtn), finalPath),
    h('div', { class: 'row' }, lab('Port', port), lab('Min RAM', minMb), lab('Max RAM', maxMb)),
    ws ? ws.el : null,
    h('div', { class: 'dim', style: 'font-weight:600;margin-top:4px' }, det ? 'Server settings (written before the first start)' : 'World and gameplay (written before the first start)'),
    h('div', { class: 'row' }, lab(det ? 'World folder name' : 'World name', worldName), det ? null : lab('Seed', seedI), det ? null : lab('World type', levelType)),
    det ? null : biomeRow,
    h('div', { class: 'row' }, lab('Difficulty', difficulty), lab('Game mode', gamemode), lab('Max players', players), lab('Spawn protection (blocks, 0 = off)', spawnProt)),
    lab('Message of the day', motd),
    h('label', null, whitelistOn, ' Whitelist: only listed players can join (off by default)'),
    pinRow,
    planModsRow, geyserRow, geyserNote, jmRow, jmNote,
    h('label', null, autostart, ' Start automatically when the manager starts'),
    h('label', null, lanBox, ' Show this server under LAN worlds in Minecraft (same network only; some Wi-Fi blocks it)'),
    startRow,
    h('label', null, eula, ' I have read and accept the ', h('a', { href: 'https://aka.ms/MinecraftEULA', target: '_blank', rel: 'noopener' }, 'Minecraft EULA')));
  if (pk) {
    body.prepend(h('div', { class: 'box', style: 'display:grid;gap:4px' }, h('b', null, 'Modpack: ' + pk.info.name + (pk.info.version ? ' ' + pk.info.version : '')),
      h('div', { class: 'dim', style: 'font-size:12.5px' }, 'Minecraft ' + pk.info.mc + ' with ' + WD_CAP(pk.info.loader) + ' ' + pk.info.loaderVersion + ' (set by the pack) · ' + pk.info.files + ' listed files'),
      h('div', { class: 'dim', style: 'font-size:12.5px' }, 'The manager downloads the mods that run on a server (client-only ones are skipped), copies the pack’s config files, and turns off automatic updates so the pack stays as its author tested it. Big packs need several GB of memory and a few minutes for the first start.'),
      !pk.info.loaderAvailable ? h('div', { class: 'notice' }, 'The exact loader version the pack asks for is not available any more; the closest recommended one is used.') : null));
    mcSel.disabled = true;
    snap.disabled = true;
  }
  m = modal(pk ? 'Create a server from a modpack' : det ? 'Create a server from a world' : 'Add server', body, [
    { label: 'Cancel', run: (c) => c() },
    { label: 'Create server', cls: 'primary', disabled: true, run: async (c) => {
      try {
        const r = await api('POST', det ? '/api/install/create-from-world' : pk ? '/api/install/create-from-pack' : '/api/install/create', {
          world: det ? ws.collect() : undefined, pack: pk ? pk.ref : undefined,
          name: name.value.trim(), parent, type, mc: mcSel.value, loader: type === 'vanilla' ? '' : loaderSel.value, port: +port.value, minMb: +minMb.value, maxMb: +maxMb.value,
          maxPlayers: +players.value, motd: motd.value, eula: eula.checked, autostart: autostart.checked, lanBroadcast: lanBox.checked, startAfter: startAfter.checked, pinMc: type === 'fabric' && pin.checked,
          worldName: worldName.value.trim(), seed: seedI.value.trim(), levelType: levelType.value, biome: biomeSel.value === 'other' ? biomeOther.value.trim() : biomeSel.value, difficulty: difficulty.value, gamemode: gamemode.value, spawnProtection: +spawnProt.value, whitelist: whitelistOn.checked,
          geyser: geyser.checked && !geyserRow.classList.contains('hidden'),
          journeymap: jm.checked && !jmRow.classList.contains('hidden'),
          autoRestart: P.autoRestart || undefined,
        });
        const wantMods = planMods.checked && type !== 'vanilla' && !det;
        const showClients = !!det && type !== 'vanilla' && ws.collect().mods.length > 0;
        c();
        openJob(r.job, 'Create server ' + name.value.trim(), '', { onDone: async (state) => {
          try { S.servers = await api('GET', '/api/servers'); } catch (e) { return; }
          if (S.servers.some(s => s.id === r.id)) {
            selectServer(r.id);
            if (state === 'done' && wantMods) {
              // close the progress window and open the mod picker on the new server
              document.querySelectorAll('dialog').forEach(d => { try { d.close(); } catch (e) { /* closed */ } d.remove(); });
              addModsWindow(S.servers.find(s => s.id === r.id));
            } else if (state === 'done' && showClients) {
              document.querySelectorAll('dialog').forEach(d => { try { d.close(); } catch (e) { /* closed */ } d.remove(); });
              clientPackDialog(S.servers.find(s => s.id === r.id));
            }
          } else renderSide();
        } });
      } catch (e) { toast(e.message, true); }
    } },
  ]);
  drawTypes();
  loadVersions();
}

function selectServer(id) {
  S.sel = id;
  localStorage.setItem('mcm.sel', id);
  renderSide();
  renderMain();
}

const ALL_TABS = [['console', 'Console'], ['players', 'Players'], ['props', 'Properties'], ['mods', 'Mods & Updates'], ['geyser', 'Bedrock (Geyser)'], ['journeymap', 'JourneyMap'], ['files', 'Log files']];
const tabsFor = (s) => ALL_TABS.filter(([k]) => (k !== 'props' || lvl(s) >= 3) && (k !== 'players' || lvl(s) >= 1) && (k !== 'geyser' || (s && s.geyser && lvl(s) >= 3)) && (k !== 'journeymap' || (s && s.journeymap && lvl(s) >= 3)));

function renderMain() {
  renderMobileBar();
  const main = $('#main');
  if (!sv() && !isAdmin()) {
    main.replaceChildren(h('div', { class: 'box', style: 'max-width:520px;width:100%;margin:0 auto;align-self:start' }, h('h3', null, 'No servers shared with you yet'),
      h('div', { class: 'dim' }, 'Ask a manager administrator or a server operator for an invite or for access to a server.')));
    $('#bottomnav').replaceChildren();
    return;
  }
  if (!sv()) {
    const choice = (title, text, fn) => h('button', { onclick: fn }, h('b', null, title), h('span', { class: 'dim' }, text));
    main.replaceChildren(h('div', { class: 'box', style: 'max-width:520px;width:100%;margin:0 auto;align-self:start' }, h('h3', null, 'No servers yet'),
      h('div', { class: 'dim' }, 'Create a new one, or let the manager find servers you already have.'),
      h('div', { class: 'bigchoice', style: 'margin-top:12px' },
        choice('Create a new server', 'Vanilla, Fabric, Forge or NeoForge, downloaded for you.', addServerWizard),
        choice('Search this PC for servers', 'Finds CurseForge, ATLauncher, FTB and plain server folders.', () => scanDialog()),
        choice('Add an existing server', 'Pick a server folder yourself.', addExistingFlow))));
    $('#bottomnav').replaceChildren();
    return;
  }
  S.token++;
  S.view = null;
  main.replaceChildren(h('div', { id: 'head' }), h('div', { class: 'tabs', id: 'tabs' }), h('div', { class: 'pane', id: 'pane' }));
  renderHead();
  renderTabs();
  renderPane();
}

const TAB_ICON = { console: 'console', players: 'players', mods: 'mods', geyser: 'geyser', journeymap: 'map', props: 'props', files: 'files' };
const TAB_SHORT = { console: 'Console', players: 'Players', mods: 'Mods', geyser: 'Bedrock', journeymap: 'Map', props: 'Settings', files: 'Logs' };

function renderTabs() {
  const TABS = tabsFor(sv());
  if (!TABS.some(([k]) => k === S.tab)) S.tab = 'console';
  const go = (k) => { S.tab = k; renderTabs(); renderPane(); };
  $('#tabs').replaceChildren(...TABS.map(([k, label]) => h('button', { class: 'tab' + (S.tab === k ? ' sel' : ''), onclick: () => go(k) }, label)));
  $('#bottomnav').replaceChildren(...TABS.map(([k]) => h('button', { class: S.tab === k ? 'sel' : '', onclick: () => go(k), 'aria-label': TAB_SHORT[k] }, icon(TAB_ICON[k]), h('span', null, TAB_SHORT[k]))));
}

function renderPane() {
  const pane = $('#pane');
  const tok = ++S.token;
  S.view = null;
  pane.replaceChildren();
  const s = sv();
  const fn = { console: buildConsole, players: buildPlayers, mods: buildMods, geyser: buildGeyser, journeymap: buildJourneyMap, props: buildProps, files: buildFiles }[S.tab];
  Promise.resolve(fn(s, pane, tok)).catch(e => { if (tok === S.token) pane.replaceChildren(h('p', { class: 'err' }, e.message)); });
}

function renderHead() {
  renderMobileBar();
  const head = $('#head');
  const s = sv();
  if (!head || !s) return;
  const busy = s.state === 'starting' || s.state === 'stopping';
  const KIND = { vanilla: 'Vanilla', fabric: 'Fabric ' + (s.loader || ''), forge: 'Forge', neoforge: 'NeoForge' };
  const facts = [`Port ${s.port}`, s.mc ? `Minecraft ${s.mc}` : null, KIND[s.kind] || 'Vanilla'];
  if (s.state === 'running') facts.push(`${s.players.length}/${s.maxPlayers} players`, 'Up ' + fmtUp(s.uptime), fmtMem(s.memMb) + ' RAM');
  head.replaceChildren(...[
    h('div', { class: 'shead' },
      h('h2', null, s.name), h('span', { class: 'pill ' + s.state }, s.state), isAdmin() ? null : h('span', { class: 'pill', title: 'Your access level on this server' }, 'Your level: ' + lvl(s) + ' (' + (LEVEL_NAME[lvl(s)] || '') + ')'),
      h('div', { class: 'facts' }, facts.filter(Boolean).map(f => h('span', null, f))),
      h('div', { class: 'actions' },
        lvl(s) >= 4 && s.state === 'stopped' && !s.portHeldBy ? h('button', { class: 'btn primary', onclick: () => doStart(s) }, 'Start') : null,
        lvl(s) >= 4 && s.state === 'running' ? h('button', { class: 'btn', onclick: () => doStop(s, false) }, 'Stop') : null,
        lvl(s) >= 4 && s.state === 'running' ? h('button', { class: 'btn', onclick: () => doStop(s, true) }, 'Restart') : null,
        lvl(s) >= 4 ? h('button', { class: 'btn', title: 'Save a copy of the world or the whole server', onclick: () => backupDialog(s) }, 'Backup') : null,
        lvl(s) >= 4 ? h('button', { class: 'btn', title: 'Put a saved backup back (the server must be stopped)', onclick: () => restoreDialog(s) }, 'Restore') : null,
        isAdmin() && s.state !== 'stopped' ? h('button', { class: 'btn danger', disabled: false, onclick: () => doKill(s) }, 'Force kill') : null,
        isAdmin() && s.state === 'stopped' ? h('button', { class: 'btn', title: 'Stop managing this server (files are kept)', onclick: () => removeFromManager(s) }, 'Remove') : null,
        isAdmin() && s.state === 'stopped' ? h('button', { class: 'btn danger', title: 'Delete this server\u2019s files from the PC', onclick: () => deleteServerDialog(s) }, 'Delete\u2026') : null)),
    s.job ? h('div', { class: 'activity', onclick: () => openJob(s.job, s.jobTitle, s.id) }, h('span', { class: 'dot starting' }), s.jobTitle + ' in progress - click for details') : null,
    s.portHeldBy ? h('div', { class: 'notice' }, `Port ${s.port} is in use by ${s.portHeldBy}, which has the same game port as this server (a copy?). Change this server's port on the Properties tab before starting it.`) : null,
    busy && !s.job ? h('div', { class: 'dim', style: 'margin-top:4px' }, s.state === 'starting' ? 'Starting (this includes the update check)...' : 'Stopping...') : null].filter(Boolean));
}

// ---------- jobs / lifecycle ----------
function openJob(id, title, serverId, opts = {}) {
  const log = h('div', { class: 'joblog' });
  const status = h('div', { class: 'dim' }, 'Working...');
  let from = 0, timer = null, finished = false;
  const lines = [];
  const m = modal(title, h('div', { style: 'display:grid;gap:8px' }, status, log), [
    { label: 'Cancel action', cls: 'danger', run: async () => { try { await api('POST', `/api/jobs/${id}/cancel`, {}); } catch (e) { toast(e.message, true); } } },
    { label: 'Close', run: (c) => c() },
  ], { onClose: () => clearInterval(timer) });
  const tick = async () => {
    try {
      const j = await api('GET', `/api/jobs/${id}?from=${from}`);
      for (const l of j.lines) { lines.push(l); log.append(h('div', null, l)); }
      if (j.lines.length) log.scrollTop = log.scrollHeight;
      from = j.total;
      if (j.state !== 'running' && !finished) {
        finished = true;
        clearInterval(timer);
        status.textContent = j.state === 'done' ? 'Finished.' : j.state === 'cancelled' ? 'Cancelled.' : 'Failed.';
        status.className = j.state === 'error' ? 'err' : 'dim';
        m.btns[0].disabled = true;
        if (opts.onDone) opts.onDone(j.state, lines);
        if (j.state === 'done' && opts.autoClose) setTimeout(() => m.close(), 1200);
      }
    } catch (e) { clearInterval(timer); status.textContent = e.message; status.className = 'err'; }
  };
  timer = setInterval(tick, 1000);
  tick();
}

async function startJob(s, endpoint, body, title, opts) {
  try {
    const r = await api('POST', `/api/servers/${s.id}/${endpoint}`, body || {});
    openJob(r.job, title, s.id, opts || {});
  } catch (e) { toast(e.message, true); }
}

const doStart = (s) => startJob(s, 'start', {}, `Start ${s.name}`, { autoClose: true });

async function doStop(s, restart) {
  const sel = h('select', null, [[0, 'Now'], [30, 'In 30 seconds'], [60, 'In 1 minute'], [120, 'In 2 minutes'], [300, 'In 5 minutes']].map(([v, l]) => h('option', { value: v }, l)));
  const online = s.players.length;
  const body = h('div', { style: 'display:grid;gap:8px' },
    h('div', null, online ? `${online} player(s) online: ${s.players.map(p => p.name).join(', ')}. They will be warned in chat if you pick a delay.` : 'Nobody is online.'),
    h('label', { class: 'dim' }, 'When: ', sel));
  modal(restart ? `Restart ${s.name}` : `Stop ${s.name}`, body, [
    { label: 'Cancel', run: (c) => c() },
    { label: restart ? 'Restart' : 'Stop', cls: 'primary', run: (c) => { c(); startJob(s, restart ? 'restart' : 'stop', { delay: +sel.value }, restart ? `Restart ${s.name}` : `Stop ${s.name}`, { autoClose: true }); } },
  ]);
}

async function doKill(s) {
  if (await confirmBox('Force kill', `Kill the ${s.name} process immediately? Unsaved progress since the last autosave is lost and the world could be damaged. Use Stop unless the server is stuck.`, 'Force kill', true))
    startJob(s, 'kill', {}, `Force kill ${s.name}`);
}

/** Sends the same action to several servers at once and reports any that refused (for example one that is busy with another job). */
async function bulkAction(list, endpoint, body, verb) {
  const res = await Promise.allSettled(list.map(x => api('POST', `/api/servers/${x.id}/${endpoint}`, body)));
  const bad = res.map((r, i) => r.status === 'rejected' ? `${list[i].name}: ${r.reason.message}` : null).filter(Boolean);
  toast(bad.length ? `${verb} ${list.length - bad.length} of ${list.length}. Problems: ${bad.join('; ')}` : `${verb} ${list.length} server${list.length === 1 ? '' : 's'}...`, bad.length > 0);
}

function startAll() {
  const list = S.servers.filter(x => x.state === 'stopped' && !x.portHeldBy);
  if (!list.length) { toast('Every server is already running'); return; }
  bulkAction(list, 'start', {}, 'Starting');
}

/** Stop or restart every running server, with the same "when" choice and chat warning as the single-server dialog. */
function stopAllDialog(restart) {
  const run = S.servers.filter(x => x.state === 'running');
  if (!run.length) { toast('No servers are running'); return; }
  const verb = restart ? 'Restart' : 'Stop';
  const sel = h('select', null, [[0, 'Now'], [30, 'In 30 seconds'], [60, 'In 1 minute'], [120, 'In 2 minutes'], [300, 'In 5 minutes']].map(([v, l]) => h('option', { value: v }, l)));
  const body = h('div', { style: 'display:grid;gap:8px' },
    h('div', null, `${verb} ${run.length} running server${run.length === 1 ? '' : 's'}? Each world is saved first.`),
    h('ul', { style: 'margin:0;padding-left:20px' }, run.map(x => h('li', null, x.name + (x.players.length ? ` (${x.players.length} online)` : '')))),
    h('label', { class: 'dim' }, 'When: ', sel),
    h('div', { class: 'dim' }, 'Players are warned in chat if you pick a delay.'));
  modal(`${verb} all servers`, body, [
    { label: 'Cancel', run: (c) => c() },
    { label: `${verb} all`, cls: 'primary', run: (c) => { c(); bulkAction(run, restart ? 'restart' : 'stop', { delay: +sel.value }, restart ? 'Restarting' : 'Stopping'); } },
  ]);
}
const restartAllDialog = () => stopAllDialog(true);

async function killAllDialog() {
  const live = S.servers.filter(x => x.state !== 'stopped');
  if (!live.length) { toast('No servers are running'); return; }
  if (await confirmBox('Force kill all servers', `Kill ${live.length} server process${live.length === 1 ? '' : 'es'} immediately (${live.map(x => x.name).join(', ')})? Unsaved progress since the last autosave is lost and worlds could be damaged. Use Stop all unless servers are stuck.`, 'Force kill all', true))
    bulkAction(live, 'kill', {}, 'Force killing');
}

// ---------- console ----------
const LINE = /^\[(\d\d:\d\d:\d\d)\] \[([^\]]*?)\/(\w+)\]: ?(.*)$/;

function cstate(id) {
  return S.cons[id] || (S.cons[id] = { entries: [], seq: 0, loaded: false, q: '', lv: { INFO: true, WARN: true, ERROR: true }, last: 'INFO' });
}

function parseEntry(raw, st) {
  const m = LINE.exec(raw);
  if (!m) return { raw, t: '', lvl: st.last, msg: raw, cls: 'cont' };
  st.last = m[3] === 'FATAL' ? 'ERROR' : m[3];
  const msg = m[4];
  let cls = m[3];
  if (/^<[^>]+> /.test(msg)) cls += ' chat';
  else if (/ joined the game$/.test(msg)) cls += ' join';
  else if (/ left the game$/.test(msg)) cls += ' leave';
  return { raw, t: m[1], lvl: st.last, msg, cls };
}

function entryVisible(e, st) {
  if (e.lvl in st.lv && !st.lv[e.lvl]) return false;
  return !st.q || (e.t + ' ' + e.msg).toLowerCase().includes(st.q);
}

async function loadLog(id) {
  const st = cstate(id);
  const d = await api('GET', `/api/servers/${id}/log?lines=1200`);
  st.entries = [];
  st.last = 'INFO';
  for (const l of d.lines) st.entries.push(parseEntry(l, st));
  st.seq = d.seq;
  st.loaded = true;
  if (S.view && S.view.id === id && S.view.redraw) S.view.redraw();
}

function onLogEvent(ev) {
  const st = S.cons[ev.server];
  if (!st || !st.loaded) return;
  const fresh = [];
  ev.lines.forEach((l, i) => { if (ev.first + i > st.seq) fresh.push(parseEntry(l, st)); });
  st.seq = Math.max(st.seq, ev.first + ev.lines.length - 1);
  if (S.view && S.view.id === ev.server && S.view.add) S.view.add(fresh); else st.entries.push(...fresh);
  if (st.entries.length > 3500) st.entries.splice(0, 500);
}

function ensureEngine(s) {
  if (S.ac[s.id]) return S.ac[s.id];
  const p = api('GET', `/api/servers/${s.id}/autocomplete`).then(data => {
    const pd = () => S.pdata[s.id] || {};
    const nm = (a) => (a || []).map(x => x.name).filter(Boolean);
    const eng = Complete.create(data, {
      players: () => { const x = S.servers.find(v => v.id === s.id); return x ? x.players.map(p => p.name) : []; },
      known: () => nm(pd().known), banned: () => nm(pd().bans), ops: () => nm(pd().ops), whitelist: () => nm(pd().whitelist),
      objectives: () => liveLists(s.id).objectives, teams: () => liveLists(s.id).teams,
    });
    eng.regs = data.registries || {};
    return eng;
  });
  p.catch(() => { delete S.ac[s.id]; });
  S.ac[s.id] = p;
  return p;
}

// Scoreboard objectives and teams only exist in the running server, so they are read from it (quietly) for the console's suggestions.
function liveLists(id) { const m = S.live || (S.live = {}); return m[id] || (m[id] = { objectives: [], teams: [] }); }
async function loadLiveLists(s) {
  const x = S.servers.find(v => v.id === s.id);
  if (!x || x.state !== 'running' || lvl(x) < 2) return;
  const ask = async (cmd) => {
    try {
      const o = ((await api('POST', `/api/servers/${s.id}/command`, { command: cmd })).output || '').trim();
      const i = o.indexOf(':');
      return o.startsWith('There are') && i > 0 ? [...o.slice(i + 1).matchAll(/\[([^\]]+)\]/g)].map(m => m[1]) : [];
    } catch (e) { return null; }
  };
  const l = liveLists(s.id);
  const o = await ask('scoreboard objectives list'); if (o) l.objectives = o;
  const t = await ask('team list'); if (t) l.teams = t;
}

async function sendCommand(s, cmd) {
  const st = cstate(s.id);
  const push = (text, cls) => {
    const e = { raw: text, t: '', lvl: 'INFO', msg: text, cls };
    if (S.view && S.view.id === s.id && S.view.add) S.view.add([e]); else st.entries.push(e);
  };
  push('> ' + cmd, 'echo');
  try {
    // /tpwp <player> <waypoint name>: JourneyMap waypoint teleport (the player's own waypoints first, then the global ones)
    const tp = /^\/?tpwp\s+(\S+)\s+(.+)$/i.exec(cmd.trim());
    const r = tp ? await api('POST', `/api/servers/${s.id}/journeymap/tpwp`, { player: tp[1], waypoint: tp[2].trim() })
      : await api('POST', `/api/servers/${s.id}/command`, { command: cmd });
    const out = tp ? ('Teleported ' + tp[1] + ' to the waypoint "' + r.waypoint + '". ' + (r.output || '')).trim() : (r.output || '').trim();
    if (out) out.split('\n').forEach(l => push(l, 'out'));
    return out;
  } catch (e) { push(e.message, 'err'); throw e; }
}

async function buildConsole(s, pane, tok) {
  const st = cstate(s.id);
  const box = h('div', { class: 'logbox' });
  const jump = h('button', { class: 'btn sm jump hidden', onclick: () => { box.scrollTop = box.scrollHeight; } }, '\u2193 Latest');
  const nodes = new WeakMap();
  const atBottom = () => box.scrollHeight - box.scrollTop - box.clientHeight < 40;
  const mk = (e) => {
    const n = h('div', { class: 'ln ' + e.cls }, h('span', { class: 't' }, e.t), h('span', { class: 'm' }, e.msg));
    n.hidden = !entryVisible(e, st);
    nodes.set(e, n);
    return n;
  };
  const view = {
    id: s.id,
    add(list) {
      const stick = atBottom();
      for (const e of list) { st.entries.push(e); box.insertBefore(mk(e), jump); }
      while (box.children.length > 3001) { box.removeChild(box.firstChild); }
      if (st.entries.length > 3500) st.entries.splice(0, 500);
      if (stick) box.scrollTop = box.scrollHeight; else jump.classList.remove('hidden');
    },
    redraw() {
      box.replaceChildren(...st.entries.map(mk), jump);
      box.scrollTop = box.scrollHeight;
    },
    refilter() { for (const e of st.entries) { const n = nodes.get(e); if (n) n.hidden = !entryVisible(e, st); } },
    onStatus() { updateRunState(); },
  };
  S.view = view;
  box.addEventListener('scroll', () => { if (atBottom()) jump.classList.add('hidden'); });

  // toolbar
  const chip = (lv, label) => {
    const c = h('span', { class: 'chip' + (st.lv[lv] ? ' on' : ''), onclick: () => { st.lv[lv] = !st.lv[lv]; c.classList.toggle('on', st.lv[lv]); view.refilter(); } }, label);
    return c;
  };
  const search = h('input', { class: 'csearch', placeholder: 'Filter log...', value: st.q, style: 'width:160px', oninput: (e) => { st.q = e.target.value.toLowerCase(); view.refilter(); } });
  const quickBtn = h('button', { class: 'btn sm cq', onclick: () => quick.classList.toggle('hidden') }, 'Quick commands');
  const tool = h('div', { class: 'ctool' }, chip('INFO', 'Info'), chip('WARN', 'Warnings'), chip('ERROR', 'Errors'), search,
    h('button', { class: 'btn sm cclear', onclick: () => { st.entries = []; view.redraw(); } }, 'Clear'), quickBtn);
  quickBtn.replaceChildren(icon('bolt'), ' Quick');

  // command bar
  const input = h('input', { placeholder: 'Type a command, e.g. gamemode creative ...  (Tab completes)', autocomplete: 'off', spellcheck: 'false' });
  const usage = h('div', { class: 'usage' });
  const sug = h('div', { class: 'sug hidden' });
  const send = h('button', { class: 'btn primary' }, 'Send');
  const bar = h('div', { class: 'cmdbar' }, sug, h('div', { class: 'cmdrow' }, input, send), usage);

  let engine = null, cur = null, idx = 0, nav = false;
  const hist = S.hist[s.id] || (S.hist[s.id] = []);
  let hpos = -1;

  const updateRunState = () => {
    const x = sv();
    const up = x && x.state === 'running';
    const can = lvl(x) >= 1;
    input.disabled = !up || !can;
    send.disabled = !up || !can;
    input.placeholder = !can ? 'View only: your account cannot run commands' : up ? 'Type a command, e.g. gamemode creative ...  (Tab completes)' : 'Server is not running';
    qdis();
  };

  const refresh = () => {
    if (!engine) { usage.textContent = ''; sug.classList.add('hidden'); return; }
    const caret = input.selectionStart == null ? input.value.length : input.selectionStart;
    cur = engine.analyze(input.value.slice(0, caret));
    idx = 0; nav = false;
    cur.caret = caret;
    usage.className = 'usage' + (cur.error ? ' bad' : '');
    usage.textContent = cur.error || ((cur.usage ? cur.usage : '') + (cur.hint ? '   ' + cur.hint : '') + (cur.executable ? '   \u21b5 run' : ''));
    drawSug();
  };
  const drawSug = () => {
    if (!cur || !cur.items.length || !input.value) { sug.classList.add('hidden'); return; }
    sug.classList.remove('hidden');
    sug.replaceChildren(...cur.items.slice(0, 300).map((it, i) => h('div', { class: i === idx ? 'sel' : '', onmousedown: (e) => { e.preventDefault(); accept(i); } },
      h('span', null, it.text), h('span', { class: 'k' }, it.kind === 'literal' ? (it.run ? 'run' : '') : it.kind))));
    const sel = sug.children[idx];
    if (sel) sel.scrollIntoView({ block: 'nearest' });
  };
  const accept = (i) => {
    const it = cur && cur.items[i];
    if (!it) return;
    const v = input.value;
    const rest = v.slice(cur.caret);
    const text = v.slice(0, cur.from) + it.text + (it.more ? ' ' : '') + rest;
    input.value = text;
    const pos = cur.from + it.text.length + (it.more ? 1 : 0);
    input.setSelectionRange(pos, pos);
    refresh();
  };
  const submit = async () => {
    const cmd = input.value.trim();
    if (!cmd) return;
    if (hist[hist.length - 1] !== cmd) hist.push(cmd);
    if (hist.length > 100) hist.shift();
    hpos = -1;
    input.value = '';
    refresh();
    const first = cmd.replace(/^\//, '').split(/\s+/)[0].toLowerCase();
    if (first === 'stop' && !(await confirmBox('Stop server', 'Stop the server now? Use the Stop button for a delayed stop with a player warning.', 'Stop', true))) return;
    try { await sendCommand(s, cmd); loadPlayersSoft(s); if (first === 'scoreboard' || first === 'team') loadLiveLists(s); } catch (e) { toast(e.message, true); }
  };
  input.addEventListener('input', () => { hpos = -1; refresh(); });
  input.addEventListener('click', refresh);
  input.addEventListener('keydown', (e) => {
    const open = !sug.classList.contains('hidden');
    if (e.key === 'Tab') { if (cur && cur.items.length) { e.preventDefault(); accept(idx); } else e.preventDefault(); }
    else if (e.key === 'ArrowDown' && open) { e.preventDefault(); idx = Math.min(idx + 1, Math.min(cur.items.length, 300) - 1); nav = true; drawSug(); }
    else if (e.key === 'ArrowUp' && open) { e.preventDefault(); idx = Math.max(idx - 1, 0); nav = true; drawSug(); }
    else if (e.key === 'ArrowUp') { if (hist.length) { e.preventDefault(); hpos = hpos < 0 ? hist.length - 1 : Math.max(0, hpos - 1); input.value = hist[hpos]; refresh(); sug.classList.add('hidden'); } }
    else if (e.key === 'ArrowDown') { if (hpos >= 0) { e.preventDefault(); hpos = hpos + 1; input.value = hpos >= hist.length ? '' : hist[hpos]; if (hpos >= hist.length) hpos = -1; refresh(); sug.classList.add('hidden'); } }
    else if (e.key === 'Escape') { sug.classList.add('hidden'); }
    else if (e.key === 'Enter') { e.preventDefault(); if (open && nav) accept(idx); else submit(); }
  });
  input.addEventListener('blur', () => setTimeout(() => sug.classList.add('hidden'), 120));
  send.onclick = submit;

  // quick commands drawer
  const { quick, qdis } = buildQuick(s);
  box.addEventListener('click', () => { if (isMobile()) quick.classList.add('hidden'); });
  quick.classList.toggle('hidden', isMobile() || localStorage.getItem('mcm.quick') === '0');
  quickBtn.addEventListener('click', () => { if (!isMobile()) localStorage.setItem('mcm.quick', quick.classList.contains('hidden') ? '0' : '1'); });

  pane.replaceChildren(h('div', { class: 'console' }, h('div', { class: 'cmain' }, tool, box, bar), quick));
  updateRunState();
  if (!st.loaded) await loadLog(s.id); else view.redraw();
  if (tok !== S.token) return;
  loadPlayersSoft(s);
  loadLiveLists(s);
  usage.textContent = 'Loading command help...';
  try {
    engine = await ensureEngine(s);
    if (tok !== S.token) return;
    fillDatalists(engine.regs);
    usage.textContent = '';
    if (document.activeElement === input) refresh();
  } catch (e) { usage.textContent = 'Autocomplete unavailable: ' + e.message; }
  S.engineRef = engine;
}

function fillDatalists(regs) {
  const mk = (id, list) => {
    let dl = document.getElementById(id);
    if (!dl) { dl = h('datalist', { id }); document.body.append(dl); }
    dl.replaceChildren(...(list || []).map(v => h('option', { value: v })));
  };
  mk('dl-items', regs.item);
  mk('dl-effects', regs.mob_effect);
  mk('dl-entities', regs.entity_type);
  mk('dl-rules', regs.game_rule);
}

async function loadPlayersSoft(s) {
  if (lvl(s) < 1) return;
  try { S.pdata[s.id] = await api('GET', `/api/servers/${s.id}/players`); updatePlayerList(s.id); } catch (e) { /* ignore */ }
}

function updatePlayerList(id) {
  let dl = document.getElementById('dl-players');
  if (!dl) { dl = h('datalist', { id: 'dl-players' }); document.body.append(dl); }
  const x = S.servers.find(v => v.id === id);
  const names = new Set([...(x ? x.players.map(p => p.name) : []), ...(((S.pdata[id] || {}).known || []).map(k => k.name)), '@a', '@p', '@r', '@s']);
  dl.replaceChildren(...[...names].filter(Boolean).map(v => h('option', { value: v })));
}

function buildQuick(s) {
  const quick = h('div', { class: 'quick' });
  quick.append(h('div', { class: 'qhead' }, h('b', null, 'Quick commands'), h('button', { class: 'btn sm', onclick: () => quick.classList.add('hidden') }, 'Close')));
  const disables = [];
  for (const g of QuickCmds.groups) {
    const det = h('details', { open: g.name === 'Players' }, h('summary', null, g.name));
    for (const it of g.items) {
      const form = h('div', { class: 'qform hidden' });
      const item = h('div', { class: 'qitem' }, h('button', { onclick: () => { form.classList.toggle('hidden'); updatePlayerList(s.id); } }, it.label), form);
      const vals = {};
      const preview = h('pre');
      const run = h('button', { class: 'btn primary sm' }, 'Run');
      const toCons = h('button', { class: 'btn sm' }, 'Edit in console');
      const upd = () => {
        const missing = it.fields.some(f => !/optional|^At player/.test(f.label) && String(vals[f.k] ?? '').trim() === '');
        let cmd = '';
        try { cmd = it.cmd(vals); } catch (e) { cmd = ''; }
        preview.textContent = '/' + cmd;
        run.dataset.missing = missing ? '1' : '';
        run.disabled = missing || !(sv() && sv().state === 'running');
        toCons.disabled = missing;
        form._cmd = cmd;
      };
      for (const f of it.fields) {
        vals[f.k] = f.def ?? '';
        let el;
        if (f.t === 'enum') {
          el = h('select', { onchange: (e) => { vals[f.k] = e.target.value; upd(); } }, f.opts.map(o => h('option', { value: o }, o)));
          vals[f.k] = f.opts[0];
        } else {
          const list = { player: 'dl-players', item: 'dl-items', effect: 'dl-effects', entity: 'dl-entities', rule: 'dl-rules' }[f.t];
          el = h('input', { type: f.t === 'int' ? 'number' : 'text', value: f.def ?? '', min: f.min, max: f.max, list, autocomplete: 'off', oninput: (e) => { vals[f.k] = e.target.value; upd(); } });
        }
        form.append(h('label', null, f.label, el));
      }
      run.onclick = async () => {
        if (it.danger && !(await confirmBox(it.label, 'Run /' + form._cmd + ' ?', 'Run', true))) return;
        try {
          const out = await sendCommand(s, form._cmd);
          toast(out ? out.slice(0, 160) : 'Done: /' + form._cmd);
          loadPlayersSoft(s);
        } catch (e) { toast(e.message, true); }
      };
      toCons.onclick = () => { const inp = $('.cmdrow input'); if (inp && !inp.disabled) { inp.value = form._cmd; inp.focus(); inp.dispatchEvent(new Event('input')); } else if (inp) { inp.value = form._cmd; } };
      form.append(preview, h('div', { class: 'row' }, run, toCons));
      upd();
      disables.push(upd);
      det.append(item);
    }
    quick.append(det);
  }
  return { quick, qdis: () => disables.forEach(f => f()) };
}

/** Quick commands that act on one player, with that player's name already filled in; the result is shown as a toast. */
function playerQuickPanel(s, name, only) {
  const panel = h('div', { class: 'pquick' });
  const up = () => sv() && sv().state === 'running';
  for (const g of QuickCmds.groups) {
    const items = g.items.filter(it => it.fields.some(f => f.k === 'p' && f.t === 'player') && (!only || only.has(it.id)));
    if (!items.length) continue;
    const det = h('details', { open: g.name === 'Players' }, h('summary', null, g.name));
    for (const it of items) {
      const vals = { p: name };
      const others = it.fields.filter(f => f.k !== 'p');
      const form = h('div', { class: 'qform hidden' });
      const preview = h('pre');
      const run = h('button', { class: 'btn primary sm' }, 'Run');
      let cmd = '';
      const upd = () => {
        const missing = others.some(f => !/optional/.test(f.label) && String(vals[f.k] ?? '').trim() === '');
        try { cmd = it.cmd(vals); } catch (e) { cmd = ''; }
        preview.textContent = '/' + cmd;
        run.disabled = missing || !up();
      };
      const go = async () => {
        if (it.danger && !(await confirmBox(it.label, 'Run /' + cmd + ' ?', 'Run', true))) return;
        try {
          const out = await sendCommand(s, cmd);
          toast(out ? out.slice(0, 200) : 'Done: /' + cmd);
          loadPlayersSoft(s);
        } catch (e) { toast(e.message, true); }
      };
      for (const f of others) {
        vals[f.k] = f.def ?? '';
        let el;
        if (f.t === 'enum') {
          el = h('select', { onchange: (e) => { vals[f.k] = e.target.value; upd(); } }, f.opts.map(o => h('option', { value: o }, o)));
          vals[f.k] = f.opts[0];
        } else {
          const list = { player: 'dl-players', item: 'dl-items', effect: 'dl-effects', entity: 'dl-entities', rule: 'dl-rules' }[f.t];
          el = h('input', { type: f.t === 'int' ? 'number' : 'text', value: f.def ?? '', min: f.min, max: f.max, list, autocomplete: 'off', oninput: (e) => { vals[f.k] = e.target.value; upd(); } });
        }
        form.append(h('label', null, f.label, el));
      }
      run.onclick = go;
      form.append(preview, h('div', { class: 'row' }, run));
      upd();
      // nothing else to fill in: the button itself runs the command
      const btn = h('button', { class: it.danger ? 'danger' : '', onclick: () => { if (!others.length) { upd(); if (up()) go(); else toast('The server is not running', true); } else { form.classList.toggle('hidden'); upd(); } } }, it.label);
      det.append(h('div', { class: 'qitem' }, btn, others.length ? form : null));
    }
    panel.append(det);
  }
  return panel;
}

// ---------- players ----------
async function buildPlayers(s, pane, tok) {
  const wrap = h('div', { style: 'display:grid;gap:12px;grid-template-columns:minmax(0,1fr)' });
  pane.replaceChildren(wrap);
  let listTab = localStorage.getItem('mcm.plist') || 'whitelist';

  const run = async (cmd, okMsg) => {
    try { const out = await sendCommand(s, cmd); toast(out ? out.slice(0, 200) : (okMsg || 'Done')); } catch (e) { toast(e.message, true); }
    await draw();
  };

  const draw = async () => {
    const d = await api('GET', `/api/servers/${s.id}/players`);
    if (tok !== S.token) return;
    S.pdata[s.id] = d;
    updatePlayerList(s.id);
    const cs = sv();
    const up = cs.state === 'running';
    const live = cs.players;
    const qOpen = S.qOpen || (S.qOpen = new Set());
    const qBits = new Map(live.map(p => {
      const key = s.id + ':' + p.name;
      const panel = playerQuickPanel(s, p.name);
      panel.classList.toggle('hidden', !qOpen.has(key));
      const btn = h('button', { class: 'btn sm', onclick: () => {
        panel.classList.toggle('hidden');
        if (panel.classList.contains('hidden')) qOpen.delete(key); else { qOpen.add(key); updatePlayerList(s.id); }
      } }, 'Quick commands');
      return [p.name, { panel, btn }];
    }));
    const online = h('div', { class: 'box' }, h('h3', null, `Online now (${live.length}/${cs.maxPlayers})`),
      !up ? h('div', { class: 'dim' }, 'The server is not running. You can still remove list entries below; adding needs the server to be up.') :
        live.length ? h('div', null, live.map(p => h('div', { class: 'pcard' },
          h('div', { class: 'pn' }, nameCell(p.name), p.op ? h('span', { class: 'badge op' }, 'OP') : null, p.bedrock ? h('span', { class: 'badge be' }, 'Bedrock') : null),
          h('div', null, h('div', { class: 'row' },
            h('select', { onchange: (e) => { if (e.target.value) run(`gamemode ${e.target.value} ${p.name}`); e.target.value = ''; } }, h('option', { value: '' }, 'Gamemode'), ['survival', 'creative', 'adventure', 'spectator'].map(m => h('option', { value: m }, m))),
            h('button', { class: 'btn sm', onclick: async () => { const to = await inputBox('Teleport ' + p.name, 'To which player (or x y z)?', '', 'player name or 0 80 0'); if (to) run(`tp ${p.name} ${to}`); } }, 'Teleport'),
            h('button', { class: 'btn sm', onclick: async () => { const m = await inputBox('Message ' + p.name, 'Private message:'); if (m) run(`tell ${p.name} ${m}`); } }, 'Message'),
            h('button', { class: 'btn sm', onclick: () => run(p.op ? `deop ${p.name}` : `op ${p.name}`) }, p.op ? 'Deop' : 'Op'),
            h('button', { class: 'btn sm', onclick: async () => { const r = await inputBox('Kick ' + p.name, 'Reason (optional):'); if (r !== null) run(`kick ${p.name} ${r}`.trim()); } }, 'Kick'),
            h('button', { class: 'btn sm danger', onclick: async () => { const r = await inputBox('Ban ' + p.name, 'Reason (optional):'); if (r !== null) run(`ban ${p.name} ${r}`.trim()); } }, 'Ban'), qBits.get(p.name).btn), qBits.get(p.name).panel)))) : h('div', { class: 'dim' }, 'Nobody is online.'));

    const addForm = (placeholder, build, label) => {
      const inp = h('input', { placeholder, list: 'dl-players', autocomplete: 'off' });
      const go = () => { if (inp.value.trim()) { run(build(inp.value.trim())); inp.value = ''; } };
      inp.addEventListener('keydown', e => { if (e.key === 'Enter') go(); });
      return h('div', { class: 'row', style: 'margin:6px 0' }, inp, h('button', { class: 'btn sm', disabled: !up, onclick: go, title: up ? '' : 'Server must be running' }, label));
    };
    const remove = async (list, value, cmd) => {
      if (up) return run(cmd);
      try { await api('POST', `/api/servers/${s.id}/players/remove`, { list, value }); toast('Removed'); } catch (e) { toast(e.message, true); }
      draw();
    };
    const date = (v) => v ? String(v).slice(0, 10) : '';
    const L = {
      whitelist: () => [
        h('div', { class: 'row' }, h('span', null, 'Whitelist is ', h('b', null, d.whitelistOn ? 'ON' : 'OFF')),
          h('button', { class: 'btn sm', disabled: !up, onclick: () => run(d.whitelistOn ? 'whitelist off' : 'whitelist on') }, d.whitelistOn ? 'Turn off' : 'Turn on')),
        addForm('Player name to whitelist', n => `whitelist add ${n}`, 'Add'),
        table(['Name', 'UUID'], d.whitelist.map(w => [nameCell(w.name), uuidCell(w.uuid)]), d.whitelist.map(w => () => remove('whitelist', w.name, `whitelist remove ${w.name}`)), 'Remove', false, 'actmid', d.whitelist.map(w => w.name))],
      ops: () => [addForm('Player name to make operator', n => `op ${n}`, 'Op'),
        table(['Name', 'Level'], d.ops.map(w => [nameCell(w.name), String(w.level)]), d.ops.map(w => () => remove('ops', w.name, `deop ${w.name}`)), 'Deop', false, '', d.ops.map(w => w.name))],
      bans: () => [addForm('Player name to ban', n => `ban ${n}`, 'Ban'),
        table(['Name', 'Reason', 'By', 'Date'], d.bans.map(w => [nameCell(w.name), w.reason || '', w.source || '', date(w.created)]), d.bans.map(w => () => remove('bans', w.name, `pardon ${w.name}`)), 'Pardon', false, '', d.bans.map(w => w.name))],
      banIps: () => [addForm('IP address to ban', n => `ban-ip ${n}`, 'Ban IP'),
        table(['IP', 'Reason', 'Date'], d.banIps.map(w => [w.ip, w.reason || '', date(w.created)]), d.banIps.map(w => () => remove('banIps', w.ip, `pardon-ip ${w.ip}`)), 'Pardon')],
      known: () => [h('div', { class: 'dim' }, 'Players who have joined recently (from usercache.json).'),
        table(['Name', 'UUID'], d.known.map(w => [nameCell(w.name), uuidCell(w.uuid)]), d.known.map(w => () => run(`whitelist add ${w.name}`)), 'Whitelist', !up, 'actmid', d.known.map(w => w.name))],
    };
    const tabs = [['whitelist', `Whitelist (${d.whitelist.length})`], ['ops', `Operators (${d.ops.length})`], ['bans', `Banned players (${d.bans.length})`], ['banIps', `Banned IPs (${d.banIps.length})`], ['known', `Known players (${d.known.length})`]];
    const lists = h('div', { class: 'box' }, h('div', { class: 'sub' }, tabs.map(([k, label]) => h('button', { class: 'chip' + (k === listTab ? ' on' : ''), onclick: () => { listTab = k; localStorage.setItem('mcm.plist', k); draw(); } }, label))), L[listTab]());
    wrap.replaceChildren(online, lists);
  };

  /** A player name that copies itself to the clipboard when clicked or tapped. */
  function nameCell(name) {
    return h('span', { class: 'copyurl', title: 'Click to copy', onclick: () => copyValue(name, 'Name copied: ' + name) }, name);
  }

  /** A UUID that copies itself to the clipboard when clicked or tapped. */
  function uuidCell(uuid) {
    return h('span', { class: 'mono dim uuid', title: 'Click to copy', onclick: () => copyValue(uuid, 'UUID copied') }, uuid);
  }

  /** names (optional): the player in each row; those rows get a "Quick commands" button with the commands that also work on offline players. */
  const OFFLINE_OK = new Set(['op', 'deop', 'ban', 'pardon', 'wla', 'wlr']);
  function table(cols, rows, acts, actLabel, disabled, cls, names) {
    if (!rows.length) return h('div', { class: 'dim' }, 'Empty.');
    const qOpen = S.qOpen || (S.qOpen = new Set());
    return h('table', { class: 'tbl' + (cls ? ' ' + cls : '') }, h('tr', null, cols.map(c => h('th', null, c)), h('th')),
      rows.flatMap((r, i) => {
        const nm = names && names[i];
        const key = 'L:' + s.id + ':' + nm;
        const panelRow = nm ? h('tr', { class: qOpen.has(key) ? '' : 'hidden' }, h('td', { colspan: cols.length + 1 }, playerQuickPanel(s, nm, OFFLINE_OK))) : null;
        const qb = nm ? h('button', { class: 'btn sm', onclick: () => { panelRow.classList.toggle('hidden'); if (panelRow.classList.contains('hidden')) qOpen.delete(key); else { qOpen.add(key); updatePlayerList(s.id); } } }, 'Quick commands') : null;
        return [h('tr', null, r.map(c => h('td', null, c)), h('td', { class: 'act' }, qb, h('button', { class: 'btn sm', disabled, onclick: acts[i] }, actLabel))), panelRow];
      }));
  }

  S.view = { id: s.id, onStatus: () => { const key = JSON.stringify(sv().players) + sv().state; if (key !== S.view.key) { S.view.key = key; draw().catch(() => { }); } } };
  S.view.key = JSON.stringify(sv().players) + sv().state;
  await draw();
}

// ---------- mods & updates ----------
async function buildMods(s, pane, tok) {
  const wrap = h('div', { style: 'display:grid;gap:12px;grid-template-columns:minmax(0,1fr)' });
  pane.replaceChildren(wrap);
  const mr = isAdmin() && s.kind !== 'vanilla' ? modrinthBox(s) : null;
  const cf = isAdmin() && s.kind !== 'vanilla' && typeof curseBox === 'function' ? curseBox(s) : null;
  if (mr) mr.el.addEventListener('installed', () => draw());
  const draw = async () => {
    const [m, info] = await Promise.all([api('GET', `/api/servers/${s.id}/mods`), api('GET', `/api/servers/${s.id}/info`)]);
    if (tok !== S.token) return;
    const up = sv().state !== 'stopped';
    const last = S.lastCheck[s.id];
    const head = h('div', { class: 'box' }, h('h3', null, 'Versions'),
      h('div', { class: 'row' }, h('span', null, `Minecraft ${info.mc || '?'}`), m.fabric ? h('span', null, `\u00b7 Fabric loader ${info.loader || '?'}`) : h('span', { class: 'dim' }, '\u00b7 ' + (sv().kind === 'vanilla' ? 'Vanilla' : sv().kind) + ' - automatic updates are only available for Fabric servers'),
        h('span', { class: 'dim' }, `\u00b7 World "${info.world}" ${info.worldSizeMb} MB`)),
      m.fabric && isAdmin() ? h('div', { class: 'row', style: 'margin-top:8px' },
        h('button', { class: 'btn', onclick: () => startJob(s, 'updates/check', {}, 'Check for updates', { onDone: (st, lines) => { S.lastCheck[s.id] = { at: new Date(), lines }; if (S.tab === 'mods' && tok === S.token) draw(); } }) }, 'Check for updates'),
        h('button', { class: 'btn primary', onclick: () => applyUpdates(s, draw) }, 'Update everything now'),
        h('span', { class: 'dim' }, 'Updates Fabric, mods and (when every mod supports it) Minecraft. A running server is restarted.')) : null,
      last ? h('div', { style: 'margin-top:8px' }, h('div', { class: 'dim' }, 'Last check ' + last.at.toLocaleTimeString()), h('div', { class: 'joblog', style: 'max-height:180px' }, last.lines.join('\n'))) : null);
    const mods = h('div', { class: 'box' }, h('h3', null, `Mods (${m.mods.length})`),
      h('div', { style: 'max-height:42vh;overflow:auto' }, h('table', { class: 'tbl' }, h('tr', null, ['On', 'Name', 'Version', 'File', 'Size', ''].map(c => h('th', { class: c === 'File' || c === 'Size' ? 'hide-m' : '' }, c))),
        m.mods.map(x => h('tr', null,
          h('td', null, h('input', { type: 'checkbox', checked: x.enabled, disabled: up || !isAdmin(), title: !isAdmin() ? 'Only the manager administrator can change mods' : up ? 'Stop the server to enable/disable mods' : '', onchange: async (e) => {
            try { await api('POST', `/api/servers/${s.id}/mods/toggle`, { file: x.file, enabled: e.target.checked }); toast('Saved. Takes effect on next start.'); } catch (ex) { toast(ex.message, true); }
            draw();
          } })),
          h('td', null, x.name || ''), h('td', { class: 'mono' }, x.version || ''), h('td', { class: 'dim mono hide-m' }, x.file), h('td', { class: 'dim hide-m' }, x.sizeKb + ' KB'),
          h('td', null, isAdmin() && !up ? h('button', { class: 'btn sm', title: 'Moves the file to mods-removed (not deleted)', onclick: async () => {
            if (!await confirmBox('Remove mod', `Remove ${x.name || x.file} from ${s.name}? The file is moved to the mods-removed folder, not deleted.`, 'Remove', true)) return;
            try { await api('POST', `/api/servers/${s.id}/mods/remove`, { file: x.file }); toast('Removed'); if (mr) mr.rerun(); } catch (ex) { toast(ex.message, true); }
            draw();
          } }, 'Remove') : null))))));
    const hint = isAdmin() && s.kind === 'vanilla' ? h('div', { class: 'box' }, h('h3', null, 'Add mods'), h('div', { class: 'dim', style: 'margin-bottom:8px' }, 'A vanilla server cannot load mods. Add Fabric, Forge or NeoForge to it and keep its world, settings, whitelist and ops. A backup is offered first.'),
      h('button', { class: 'btn primary', disabled: sv().state !== 'stopped', title: sv().state !== 'stopped' ? 'Stop the server first' : '', onclick: () => convertDialog(s) }, 'Add a mod loader...')) : null;
    const players = s.kind !== 'vanilla' ? h('div', { class: 'box' }, h('h3', null, 'Players joining this server'),
      h('div', { class: 'dim', style: 'margin-bottom:8px' }, 'Server-only mods need nothing on a player’s side, but some mods must also be installed on each player’s own game. See exactly what your players need, copy it as a message, or make a one-file modpack they can import.'),
      h('button', { class: 'btn', onclick: () => clientPackDialog(s) }, 'What players need to join...')) : null;
    wrap.replaceChildren(...[head, mods, players, mr ? mr.el : hint, cf ? cf.el : null].filter(Boolean));
  };
  await draw();
}

async function applyUpdates(s, refresh) {
  const cur = sv();
  const sel = h('select', null, [[0, 'Immediately'], [60, 'After a 1 minute warning'], [180, 'After a 3 minute warning']].map(([v, l]) => h('option', { value: v }, l)));
  const running = cur.state === 'running';
  const bk = h('input', { type: 'checkbox', checked: true });
  modal('Update everything', h('div', { style: 'display:grid;gap:8px' },
    h('div', null, running ? `${s.name} is running${cur.players.length ? ' with ' + cur.players.length + ' player(s) online' : ''}. It will be stopped, updated and started again.` : `${s.name} is stopped; updates will be installed and it stays stopped.`),
    running ? h('label', { class: 'dim' }, 'Restart: ', sel) : null,
    h('label', null, bk, ' Back up the whole server first (recommended)'),
    h('div', { class: 'dim' }, 'Replaced files are kept in updater-backups. The world is also backed up before any Minecraft version change.')), [
    { label: 'Cancel', run: (c) => c() },
    { label: 'Update', cls: 'primary', run: (c) => { c(); startJob(s, 'updates/apply', { delay: +sel.value, backup: bk.checked }, 'Update ' + s.name, { onDone: () => { if (S.tab === 'mods') refresh().catch(() => { }); } }); } },
  ]);
}

/** Stops, backs up, updates and restarts every Fabric server in turn (administrators). */
function updateAllDialog() {
  const fab = S.servers.filter(s => s.fabric);
  if (!fab.length) { toast('There are no Fabric servers to update.'); return; }
  const sel = h('select', null, [[0, 'Immediately'], [60, 'After a 1 minute warning'], [180, 'After a 3 minute warning']].map(([v, l]) => h('option', { value: v }, l)));
  const bk = h('input', { type: 'checkbox', checked: true });
  const skip = h('input', { type: 'checkbox' });
  const up = fab.filter(s => s.state === 'running');
  const online = up.reduce((n, s) => n + s.players.length, 0);
  modal('Update all servers', h('div', { style: 'display:grid;gap:8px' },
    h('div', null, `This goes through ${fab.length} Fabric server${fab.length === 1 ? '' : 's'} one at a time (${fab.map(s => s.name).join(', ')}). A server with updates is stopped, backed up, updated and started again; one without updates is left alone; a stopped one stays stopped.`),
    online ? h('div', { class: 'notice' }, `${online} player${online === 1 ? ' is' : 's are'} online right now and will be kicked when their server restarts.`) : null,
    h('label', { class: 'dim' }, 'Restart: ', sel),
    h('label', null, bk, ' Back up each whole server first (recommended)'),
    h('label', null, skip, ' Skip servers that have players online'),
    h('div', { class: 'dim' }, 'Servers are done one after another, so total time is the sum of each restart. Progress shows next, and a notification summarises the result.')), [
    { label: 'Cancel', run: (c) => c() },
    { label: 'Update all', cls: 'primary', run: async (c) => {
      c();
      try { const r = await api('POST', '/api/updates/all', { delay: +sel.value, backup: bk.checked, skipOccupied: skip.checked }); openJob(r.job, 'Update all servers', '', { onDone: () => { refreshAll(); } }); } catch (e) { toast(e.message, true); }
    } },
  ]);
}

// ---------- properties ----------
const PROP_HELP = {
  'motd': 'Message shown in the server list', 'max-players': 'Player limit', 'difficulty': 'Game difficulty', 'gamemode': 'Default gamemode for new players',
  'pvp': 'Players can hurt each other', 'white-list': 'Only whitelisted players may join', 'enforce-whitelist': 'Kick non-whitelisted players when the whitelist turns on',
  'online-mode': 'Verify accounts with Mojang (keep on)', 'view-distance': 'Chunks sent to players', 'simulation-distance': 'Chunks that are ticked',
  'spawn-protection': 'Radius around spawn only ops can build in', 'allow-flight': 'Allow flying (needed by some mods)', 'allow-nether': 'Nether enabled',
  'hardcore': 'Hardcore mode', 'force-gamemode': 'Force the default gamemode on every join', 'level-seed': 'World seed (only used when a world is created)',
  'level-name': 'World folder (start.bat --world overrides this)', 'server-port': 'Java edition port (restart required)', 'enable-rcon': 'Remote console - the manager needs this',
  'broadcast-rcon-to-ops': 'Show manager-issued commands to online ops in chat', 'spawn-monsters': 'Hostile mobs spawn', 'spawn-animals': 'Animals spawn',
  'spawn-npcs': 'Villagers spawn', 'enable-command-block': 'Command blocks work', 'player-idle-timeout': 'Minutes before idle players are kicked (0 = never)',
};
const COMMON = ['motd', 'max-players', 'difficulty', 'gamemode', 'pvp', 'white-list', 'enforce-whitelist', 'online-mode', 'view-distance', 'simulation-distance', 'spawn-protection', 'allow-flight', 'allow-nether', 'hardcore', 'force-gamemode', 'level-seed'];
const PROP_ENUM = { difficulty: ['peaceful', 'easy', 'normal', 'hard'], gamemode: ['survival', 'creative', 'adventure', 'spectator'], 'region-file-compression': ['deflate', 'lz4', 'none'] };
const PROP_NUM = new Set(['max-players', 'view-distance', 'simulation-distance', 'spawn-protection', 'server-port', 'rcon.port', 'query.port', 'max-tick-time', 'op-permission-level', 'player-idle-timeout', 'entity-broadcast-range-percentage', 'network-compression-threshold', 'max-world-size', 'rate-limit', 'function-permission-level']);

async function buildProps(s, pane, tok) {
  const d = await api('GET', `/api/servers/${s.id}/properties`);
  if (tok !== S.token) return;
  const changes = {};
  const save = h('button', { class: 'btn primary', disabled: true }, 'Save changes');
  const note = h('span', { class: 'dim' }, d.running ? 'Server is running - changes apply after a restart.' : '');
  const search = h('input', { placeholder: 'Search settings...' });
  const rows = [];
  const mkField = (p) => {
    let el;
    const mark = () => { const orig = p.value; if (el.value === orig) delete changes[p.key]; else changes[p.key] = el.value; row.classList.toggle('changed', p.key in changes); save.disabled = !Object.keys(changes).length; };
    if (p.secret) el = h('input', { type: 'password', placeholder: p.set ? '(set - type to replace)' : '(not set)', autocomplete: 'new-password', oninput: mark });
    else if (p.value === 'true' || p.value === 'false') el = h('select', { onchange: mark }, ['true', 'false'].map(v => h('option', { value: v, selected: v === p.value }, v)));
    else if (PROP_ENUM[p.key]) el = h('select', { onchange: mark }, (PROP_ENUM[p.key].includes(p.value) ? PROP_ENUM[p.key] : [p.value, ...PROP_ENUM[p.key]]).map(v => h('option', { value: v, selected: v === p.value }, v)));
    else el = h('input', { type: PROP_NUM.has(p.key) ? 'number' : 'text', value: p.value, oninput: mark });
    const row = h('div', { style: 'display:contents' }, h('div', { class: 'k' }, p.key, PROP_HELP[p.key] ? h('small', null, PROP_HELP[p.key]) : null), el);
    row.dataset.key = p.key;
    return row;
  };
  const common = d.properties.filter(p => COMMON.includes(p.key)).sort((a, b) => COMMON.indexOf(a.key) - COMMON.indexOf(b.key));
  const rest = d.properties.filter(p => !COMMON.includes(p.key));
  const sect = (title, list) => { const g = h('div', { class: 'props' }); list.forEach(p => { const r = mkField(p); rows.push([p.key, r]); g.append(r); }); return h('div', { class: 'box' }, h('h3', null, title), g); };
  search.addEventListener('input', () => { const q = search.value.toLowerCase(); rows.forEach(([k, r]) => { r.style.display = !q || k.includes(q) || (PROP_HELP[k] || '').toLowerCase().includes(q) ? 'contents' : 'none'; }); });
  save.onclick = async () => {
    try { const r = await api('PUT', `/api/servers/${s.id}/properties`, { changes }); toast(r.restartNeeded ? 'Saved. Restart the server to apply.' : 'Saved.'); S.view = null; renderPane(); } catch (e) { toast(e.message, true); }
  };
  const rconOk = d.properties.some(p => p.key === 'enable-rcon' && p.value === 'true') && d.properties.some(p => p.key === 'rcon.password' && p.set);
  const rcon = !rconOk ? h('div', { class: 'box' }, h('b', null, 'Remote console (RCON) is not enabled.'), h('div', { class: 'dim' }, 'The manager needs it to run commands and stop the server cleanly.'),
    h('button', { class: 'btn primary', style: 'margin-top:6px', onclick: async () => { try { const r = await api('POST', `/api/servers/${s.id}/rcon/enable`, {}); toast(r.message); renderPane(); } catch (e) { toast(e.message, true); } } }, 'Enable RCON')) : null;
  const auto = h('input', { type: 'checkbox', checked: !!s.autostart, onchange: async (e) => {
    try { await api('POST', `/api/servers/${s.id}/settings`, { autostart: e.target.checked }); toast('Saved'); } catch (ex) { toast(ex.message, true); e.target.checked = !e.target.checked; }
  } });
  const pinBox = s.fabric ? h('input', { type: 'checkbox', checked: !!s.pinned, onchange: async (e) => {
    try { await api('POST', `/api/servers/${s.id}/settings`, { pinned: e.target.checked }); toast('Saved'); } catch (ex) { toast(ex.message, true); e.target.checked = !e.target.checked; }
  } }) : null;
  let rts = [];
  if (isAdmin()) try { rts = (await api('GET', '/api/java')).runtimes; } catch (e) { /* optional */ }
  if (s.java && !rts.some(r => r.path === s.java)) rts = [{ path: s.java, label: 'Custom: ' + s.java }, ...rts];
  const javaSel = h('select', { style: 'max-width:100%', onchange: async (e) => {
    try { await api('POST', `/api/servers/${s.id}/settings`, { java: e.target.value }); toast('Saved. Applies the next time the server starts.'); } catch (ex) { toast(ex.message, true); }
  } }, h('option', { value: '' }, 'Automatic (best match for this Minecraft version)'), rts.map(r => h('option', { value: r.path, selected: r.path === s.java }, r.label)));
  const opsBox = h('input', { type: 'checkbox', checked: !!s.opsSync, onchange: async (e) => {
    try { await api('POST', `/api/servers/${s.id}/settings`, { opsSync: e.target.checked }); s.opsSync = e.target.checked; toast(e.target.checked ? 'Saved. Operators are updated whenever the server is stopped or starts.' : 'Saved'); } catch (ex) { toast(ex.message, true); e.target.checked = !e.target.checked; }
  } });
  const flag = (key, label, help, msg) => h('div', { style: 'margin-bottom:8px' }, h('label', null, h('input', { type: 'checkbox', checked: !!s[key], onchange: async (e) => {
    try { await api('POST', `/api/servers/${s.id}/settings`, { [key]: e.target.checked }); s[key] = e.target.checked; toast(msg || 'Saved'); } catch (ex) { toast(ex.message, true); e.target.checked = !e.target.checked; }
  } }), ' ' + label), help ? h('div', { class: 'dim', style: 'font-size:12.5px;margin-left:24px' }, help) : null);
  const crashRow = flag('crashRestart', 'Restart this server automatically if it crashes', 'If it goes down without anyone asking it to, the manager starts it again after 10 seconds. After 3 crashes in 15 minutes it stops trying and tells you. Stopping it from the manager or with /stop is never treated as a crash.');
  const lanRow = flag('lanBroadcast', 'Show this server under LAN worlds in Minecraft (Java Edition)', 'While the server is running, the manager announces it on your network, so players on the same network find it in Multiplayer without typing an address. Some Wi-Fi networks block this.');
  const autoUpd = s.fabric ? h('input', { type: 'checkbox', checked: !!s.autoUpdate, onchange: async (e) => {
    try { await api('POST', `/api/servers/${s.id}/settings`, { autoUpdate: e.target.checked }); toast('Saved'); } catch (ex) { toast(ex.message, true); e.target.checked = !e.target.checked; }
  } }) : null;
  const mgr = h('div', { class: 'box' }, h('h3', null, 'Manager settings'),
    h('div', { style: 'margin-bottom:8px;display:grid;gap:4px' }, h('span', { class: 'dim' }, 'Java runtime'), javaSel),
    autoUpd ? h('div', { style: 'margin-bottom:8px' }, h('label', null, autoUpd, ' Check for Fabric and mod updates every time the manager starts this server')) : null,
    crashRow, lanRow,
    pinBox ? h('div', { style: 'margin-bottom:8px' }, h('label', null, pinBox, ' Keep this Minecraft version (updates still apply to Fabric and mods)')) : null,
    h('div', { style: 'margin-bottom:8px' }, h('label', null, opsBox, ' Keep in-game operators (ops.json) in step with manager accounts that have a Minecraft username'),
      h('div', { class: 'dim', style: 'font-size:12.5px;margin-left:24px' }, 'Level 1-4 on this server becomes the same operator level in the game (administrators get 4). Only entries made by this feature are changed. A running server picks changes up at its next start.')),
    h('div', { class: 'row' }, h('label', null, auto, ' Start this server automatically when the manager starts'),
      h('button', { class: 'btn sm danger', onclick: () => removeFromManager(s) }, 'Remove from manager')));
  let launchBox = null;
  if (isAdmin()) try { launchBox = launchSection(s, await api('GET', `/api/servers/${s.id}/launch`)); } catch (e) { /* optional */ }
  const editable = lvl(s) >= 4;
  const arBox = isAdmin() && typeof autoRestartBox === 'function' ? await autoRestartBox(s) : null;
  pane.replaceChildren(...[h('div', { class: 'row' }, search, save, note), editable ? rcon : null, editable ? null : h('div', { class: 'notice' }, 'View only: you need level 4 on this server to change these settings.'),
    sect('Common settings', common), sect('All other settings', rest), launchBox, isAdmin() ? mgr : null, arBox].filter(Boolean));
  if (!editable) {
    pane.querySelectorAll('.props input, .props select').forEach(el => { el.disabled = true; });
    save.disabled = true;
  }
}

// ---------- log files ----------
async function buildFiles(s, pane, tok) {
  const d = await api('GET', `/api/servers/${s.id}/logs`);
  if (tok !== S.token) return;
  const viewer = h('div', { class: 'joblog viewer' }, 'Pick a file to view it.');
  const q = h('input', { placeholder: 'Search in file...' });
  const tail = h('select', null, [500, 1000, 5000].map(n => h('option', { value: n, selected: n === 1000 }, 'Last ' + n + ' lines')));
  let cur = null;
  const load = async () => {
    if (!cur) return;
    viewer.textContent = 'Loading...';
    try {
      const r = await api('GET', `/api/servers/${s.id}/logs/read?kind=${cur.kind}&name=${encodeURIComponent(cur.name)}&tail=${tail.value}&q=${encodeURIComponent(q.value)}`);
      viewer.textContent = r.lines.join('\n') || '(no matching lines)';
      viewer.scrollTop = viewer.scrollHeight;
    } catch (e) { viewer.textContent = e.message; }
  };
  q.addEventListener('keydown', e => { if (e.key === 'Enter') load(); });
  tail.addEventListener('change', load);
  const list = h('div', { class: 'box', style: 'max-height:220px;overflow:auto' }, h('table', { class: 'tbl' }, d.files.map(f => h('tr', null,
    h('td', null, h('a', { href: '#', onclick: (e) => { e.preventDefault(); cur = f; load(); } }, f.name)),
    h('td', { class: 'dim' }, f.kind === 'crash' ? 'crash report' : 'log'), h('td', { class: 'dim' }, new Date(f.modified).toLocaleString()), h('td', { class: 'dim' }, Math.max(1, Math.round(f.size / 1024)) + ' KB')))));
  pane.replaceChildren(list, h('div', { class: 'row' }, q, tail, h('button', { class: 'btn sm', onclick: load }, 'Search / reload')), viewer);
}

init();

// ---------- memory / JVM options (start script) ----------
function launchSection(s, d) {
  const box = h('div', { class: 'box' }, h('h3', null, 'Memory and Java options'));
  if (!d.editable) {
    box.append(h('div', { class: 'dim' }, d.reason || 'These settings cannot be edited for this server.'));
    return box;
  }
  const gb = (mb) => mb ? String(Math.round(mb / 1024 * 100) / 100) : '';
  const toMb = (v) => Math.round((parseFloat(v) || 0) * 1024);
  const running = s.state !== 'stopped';
  const maxI = h('input', { type: 'number', min: '0.25', step: '0.5', value: gb(d.maxMb), style: 'width:90px' });
  const minI = h('input', { type: 'number', min: '0', step: '0.5', value: gb(d.minMb), style: 'width:90px', placeholder: 'auto' });
  const GCS = [['keep', 'Keep as it is now'], ['g1', 'G1 (balanced, recommended)'], ['zgc', 'ZGC (very short pauses, wants more RAM)'], ['parallel', 'Parallel (throughput)'], ['serial', 'Serial (tiny servers)'], ['none', 'Java default']];
  const gcSel = h('select', null, GCS.map(([v, l]) => h('option', { value: v, selected: v === 'keep' }, v === 'keep' && d.gc && d.gc !== 'none' && d.gc !== 'other' ? `Keep current (${d.gc.toUpperCase()})` : l)));
  const extraI = h('input', { value: d.extra || '', placeholder: 'e.g. -XX:+UseStringDeduplication', style: 'width:100%' });
  const warn = h('div', { class: 'notice', style: 'display:none' });
  const prev = h('div', { class: 'mono dim', style: 'font-size:12px;word-break:break-all;display:grid;gap:4px' }, (d.preview || []).map(l => h('div', null, l)));

  const TG = [
    ['aikar', "Aikar's tuned G1 flags", 'A widely used set that smooths out lag spikes on busy servers. Needs G1 and the same minimum and maximum memory; best from about 4 GB up.'],
    ['reserve', 'Reserve all memory at start', 'Sets minimum = maximum and claims the memory up front. Slower start, steadier running.'],
    ['compact', 'Compact object headers', 'Uses less memory for the same world (Java 25 or newer). If a mod misbehaves, turn it off.'],
    ['oom', 'Exit if the server runs out of memory', 'A server that has run out of memory stops instead of hanging half-alive.'],
    ['heapdump', 'Save a heap dump when out of memory', 'Writes a file to heap-dumps/ for diagnosis (can be large).'],
    ['gclog', 'Log garbage collection', 'Writes logs/gc.log (5 rotating files) so you can see whether memory is really the problem.'],
    ['utf8', 'Force UTF-8 text encoding', 'Avoids garbled player and world names with unusual characters.']];
  const tg = {};
  const tgRows = TG.map(([id, label, help]) => {
    const c = h('input', { type: 'checkbox', checked: !!(d.toggles && d.toggles[id]) });
    tg[id] = c;
    const dis = id === 'compact' && d.javaMajor < 25;
    if (dis) { c.disabled = true; c.checked = false; }
    c.addEventListener('change', () => sync(id));
    return h('div', { style: 'margin-bottom:6px' }, h('label', null, c, ' ', h('b', null, label)), h('div', { class: 'dim', style: 'font-size:12.5px;margin-left:24px' }, help + (dis ? ` Not available: this server runs Java ${d.javaMajor || 'unknown'}.` : '')));
  });

  const sync = (changed) => {
    if (changed === 'aikar' && tg.aikar.checked) { gcSel.value = 'g1'; tg.reserve.checked = true; }
    if (tg.aikar.checked) { tg.reserve.checked = true; tg.reserve.disabled = true; } else tg.reserve.disabled = false;
    if (tg.reserve.checked && maxI.value) minI.value = maxI.value;
    check();
  };
  const PRESETS = [
    ['Balanced (G1)', () => { gcSel.value = 'g1'; tg.aikar.checked = false; sync(); }],
    ["Aikar's tuned", () => { gcSel.value = 'g1'; tg.aikar.checked = true; sync('aikar'); }],
    ['Low-pause (ZGC)', () => { gcSel.value = 'zgc'; tg.aikar.checked = false; sync(); }]];
  const presetRow = h('div', { class: 'sub' }, h('span', { class: 'dim' }, 'Presets: '), ...PRESETS.map(([l, fn]) => h('button', { class: 'chip', onclick: fn }, l)));

  const check = () => {
    const mx = toMb(maxI.value), mn = toMb(minI.value), msgs = [];
    if (d.systemMb) {
      if (mx > d.systemMb * 0.85) msgs.push(`${gb(mx)} GB is most of this PC's ${gb(d.systemMb)} GB; Windows and other programs will struggle.`);
      else if (mx + d.othersMaxMb > d.systemMb) msgs.push(`With the other servers' maximums (${gb(d.othersMaxMb)} GB) this adds up to more than the ${gb(d.systemMb)} GB installed. Fine if they are not all running at once.`);
    }
    if (mx > 31 * 1024) msgs.push('Above about 31 GB Java loses its compact pointers, so a 32 GB heap holds less than a 31 GB one. 31 GB or less is usually better.');
    if (mn && mn > mx) msgs.push('The minimum is larger than the maximum.');
    if (gcSel.value === 'zgc' && mx && mx < 4096) msgs.push('ZGC works best with 4 GB or more. G1 is a better fit for a small heap.');
    if (tg.aikar.checked && mx && mx < 4096) msgs.push("Aikar's flags are tuned for 4 GB or more; with less, G1 default is usually better.");
    if (tg.aikar.checked && gcSel.value !== 'g1') msgs.push("Aikar's flags need the G1 collector.");
    warn.textContent = msgs.join(' ');
    warn.style.display = msgs.length ? '' : 'none';
  };
  maxI.addEventListener('input', () => { if (tg.reserve.checked) minI.value = maxI.value; check(); });
  minI.addEventListener('input', check);
  gcSel.addEventListener('change', check);

  const save = h('button', { class: 'btn primary', disabled: running, title: running ? 'Stop the server first' : '', onclick: async () => {
    const toggles = {};
    for (const [id] of TG) toggles[id] = tg[id].checked;
    try {
      const r = await api('POST', `/api/servers/${s.id}/launch`, { minMb: toMb(minI.value), maxMb: toMb(maxI.value), gc: gcSel.value, extra: extraI.value, toggles });
      toast('Saved. Applies the next time the server starts.');
      prev.replaceChildren(...(r.preview || []).map(l => h('div', null, l)));
      d = r;
      maxI.value = gb(r.maxMb); minI.value = gb(r.minMb);
      check();
    } catch (e) { toast(e.message, true); }
  } }, 'Save');

  const chips = [2, 4, 6, 8, 12, 16].filter(g => !d.systemMb || g * 1024 <= d.systemMb).map(g => h('button', { class: 'chip', onclick: () => { maxI.value = g; if (tg.reserve.checked) minI.value = g; check(); } }, g + ' GB'));
  const use = running && s.memMb && d.maxMb ? h('div', { class: 'dim', style: 'margin-bottom:8px' }, `Right now this server is using ${gb(s.memMb)} GB of RAM (its maximum is ${gb(d.maxMb)} GB). Java's total includes some memory outside the heap, so staying a little under the maximum is normal.`) : null;
  const sugg = d.suggestMb ? h('div', { class: 'dim', style: 'font-size:12.5px;margin-top:6px' }, `Suggested: about ${gb(d.suggestMb)} GB`, ` (${d.modCount || 0} mod${d.modCount === 1 ? '' : 's'}, based on mods and player slots). `,
    h('button', { class: 'btn sm', onclick: () => { maxI.value = gb(d.suggestMb); if (tg.reserve.checked) minI.value = maxI.value; check(); } }, 'Use it')) : null;

  const PRI = [['Normal', 'Normal'], ['AboveNormal', 'Above normal'], ['High', 'High (favours the server over other programs)']];
  const priSel = h('select', null, PRI.map(([v, l]) => h('option', { value: v, selected: v === (d.priority || 'Normal') }, l)));
  const priBtn = h('button', { class: 'btn sm', onclick: async () => {
    try {
      const r = await api('POST', `/api/servers/${s.id}/priority`, { priority: priSel.value });
      toast(r.running ? (r.applied ? 'CPU priority applied.' : 'Saved, but Windows would not let the manager change the running process (it has higher rights). It will be applied on the next start.') : 'Saved. Applied when the server starts.');
    } catch (e) { toast(e.message, true); }
  } }, 'Apply');

  box.append(...[
    h('div', { class: 'dim', style: 'font-size:12.5px;margin-bottom:8px' }, `Edits the java line in ${d.script}. ` + (d.systemMb ? `This PC has ${gb(d.systemMb)} GB of RAM` + (d.othersMaxMb ? `; your other servers allow up to ${gb(d.othersMaxMb)} GB.` : '.') : '')),
    use, presetRow,
    h('div', { class: 'row', style: 'flex-wrap:wrap;gap:12px;align-items:center;margin-top:8px' }, h('label', null, 'Max memory (GB) ', maxI), h('label', null, 'Min memory (GB) ', minI), h('div', { class: 'sub' }, ...chips)),
    sugg,
    h('div', { style: 'display:grid;gap:4px;margin-top:8px' }, h('span', { class: 'dim' }, 'Garbage collector'), gcSel),
    h('div', { style: 'margin-top:10px' }, ...tgRows),
    h('div', { style: 'display:grid;gap:4px;margin-top:4px' }, h('span', { class: 'dim' }, 'Other Java flags (optional)'), extraI),
    warn,
    d.mixed ? h('div', { class: 'dim', style: 'margin-top:6px;font-size:12.5px' }, 'This script has several java lines with different settings; saving applies the same values to all of them.') : null,
    h('div', { class: 'row', style: 'margin-top:10px;gap:10px;align-items:center' }, save, h('span', { class: 'dim', style: 'font-size:12.5px' }, running ? 'Stop the server to change these (they apply on the next start).' : 'Applies the next time the server starts. A backup of the original script is kept (.mcm-bak).')),
    h('div', { style: 'margin-top:12px;display:grid;gap:4px' }, h('span', { class: 'dim' }, 'CPU priority (can be changed while the server runs)'), h('div', { class: 'row', style: 'gap:8px' }, priSel, priBtn)),
    h('details', { style: 'margin-top:8px' }, h('summary', { class: 'dim' }, 'Current command'), prev)].filter(Boolean));
  sync();
  return box;
}