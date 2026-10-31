// ---------- account, accounts dashboard, invites, activity log ----------
const LEVEL_HELP = [
  'Viewer: watch the console and status, no commands.',
  'Moderator (1): basic commands such as list, msg and help.',
  'Gamemaster (2): gameplay commands such as gamemode, give, tp, time and weather.',
  'Admin (3): player management (kick, ban, op, whitelist) and read the server settings.',
  'Operator (4): start, stop and restart the server and edit its settings; can invite people up to level 3.',
];

function fmtWhen(t) {
  if (!t) return '';
  const d = new Date(t);
  return d.toLocaleDateString(undefined, { month: 'short', day: 'numeric' }) + ' ' + d.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' });
}

function copyText(text, input) {
  const done = () => toast('Copied');
  if (navigator.clipboard && window.isSecureContext) { navigator.clipboard.writeText(text).then(done, () => { input.select(); document.execCommand('copy'); done(); }); return; }
  input.select();
  try { document.execCommand('copy'); done(); } catch (e) { toast('Press Ctrl+C to copy', true); }
}

function accountMenu() {
  const me = S.me || {};
  const cur = h('input', { type: 'password', placeholder: 'Current password', autocomplete: 'current-password' });
  const np = h('input', { type: 'password', placeholder: 'New password (8+ characters)', autocomplete: 'new-password' });
  const mc = h('input', { placeholder: 'Your Minecraft username (optional)', value: me.mcName || '' });
  const msg = h('div', { class: 'err' });
  const access = me.admin ? 'Manager administrator (OP5): full control of the manager and every server.'
    : Object.keys(me.grants || {}).map(id => { const s = S.servers.find(x => x.id === id); return `${s ? s.name : id}: level ${me.grants[id]} (${LEVEL_NAME[me.grants[id]] || ''})`; }).join('\n') || 'No server access yet.';
  modal('Account', h('div', { class: 'dlg', style: 'padding:0;display:grid;gap:8px' },
    h('div', null, h('b', null, me.username || ''), h('div', { class: 'dim', style: 'white-space:pre-line;font-size:13px' }, access)),
    h('div', { class: 'dim' }, 'Minecraft username'), h('div', { class: 'row' }, mc, h('button', { class: 'btn sm', onclick: async () => {
      try { await api('POST', '/api/users/mcname', { user: me.id, mcName: mc.value.trim() }); S.me.mcName = mc.value.trim(); toast('Saved'); } catch (e) { msg.textContent = e.message; }
    } }, 'Save')),
    h('div', { class: 'dim' }, 'Change password'), cur, np, msg, twoStepSection()), [
    { label: 'Sign out', run: async (c) => { await api('POST', '/api/auth/logout', {}); c(); S.me = null; showLogin(); } },
    { label: 'Close', run: (c) => c() },
    { label: 'Change password', cls: 'primary', run: async (c) => {
      try { await api('POST', '/api/auth/password', { current: cur.value, password: np.value }); toast('Password changed'); c(); } catch (e) { msg.textContent = e.message; }
    } },
  ]);
}

/** Two-step sign-in with an authenticator app (Google/Microsoft Authenticator, Aegis, 1Password, ...). */
function twoStepSection() {
  const wrap = h('div', { style: 'display:grid;gap:6px;margin-top:6px;border-top:1px solid var(--line);padding-top:10px' });
  const draw = () => {
    const on = !!(S.me && S.me.twoStep);
    wrap.replaceChildren(h('div', { class: 'dim' }, 'Two-step sign-in'),
      h('div', { class: 'dim', style: 'font-size:12.5px' }, on ? 'On: signing in needs a code from your authenticator app as well as your password.' : 'Off. Turn it on to require a code from your phone when signing in.'),
      on ? h('button', { class: 'btn sm', onclick: turnOff }, 'Turn off') : h('button', { class: 'btn sm', onclick: setup }, 'Set up'));
  };
  const group = (k) => (k.match(/.{1,4}/g) || []).join(' ');
  async function setup() {
    let info;
    try { info = await api('POST', '/api/auth/2fa/begin', {}); } catch (e) { toast(e.message, true); return; }
    const key = h('input', { value: group(info.secret), readonly: true, style: 'width:100%;font-family:var(--mono)', onclick: (e) => e.target.select() });
    const code = h('input', { placeholder: '6-digit code', inputmode: 'numeric', autocomplete: 'off', maxlength: 7, style: 'width:140px' });
    const msg = h('div', { class: 'err' });
    const qr = (typeof QR !== 'undefined') ? QR.svg(info.uri, 224) : null;
    modal('Set up two-step sign-in', h('div', { style: 'display:grid;gap:8px;width:100%;min-width:0' },
      qr ? h('div', null, 'Open your authenticator app, choose "add account" and "scan QR code", and scan this:') : null,
      qr ? h('div', { style: 'justify-self:center;line-height:0;background:#fff;border-radius:8px;padding:2px' }, qr) : null,
      h('div', null, qr ? 'Can\u2019t scan it (for example you are using this page on the same phone)? Choose "enter a setup key" instead and type this key (time-based):' : 'In your authenticator app choose "add account" and "enter a setup key", then type this key (time-based):'),
      h('div', { class: 'row' }, key, h('button', { class: 'btn sm', onclick: () => copyText(info.secret, key) }, 'Copy')),
      h('div', { class: 'dim', style: 'font-size:12.5px' }, 'On a phone you can also open this link, which most apps accept: '),
      h('a', { href: info.uri, style: 'word-break:break-all;font-size:12px' }, info.uri),
      h('div', null, 'Then enter the 6-digit code the app shows:'), code, msg), [
      { label: 'Cancel', run: (c) => c() },
      { label: 'Turn on', cls: 'primary', run: async (c) => {
        try {
          const r = await api('POST', '/api/auth/2fa/enable', { code: code.value.trim() });
          c();
          S.me.twoStep = true;
          showRecovery(r.codes);
          draw();
        } catch (e) { msg.textContent = e.message; }
      } }]);
  }
  function showRecovery(codes) {
    const box = h('textarea', { readonly: true, rows: 8, style: 'width:100%;font-family:var(--mono)' }, codes.join('\n'));
    modal('Save your recovery codes', h('div', { style: 'display:grid;gap:8px;width:100%;min-width:0' },
      h('div', { class: 'notice' }, 'If you lose your phone, each of these codes lets you sign in once instead of the app code. They are shown only now: store them somewhere safe.'), box,
      h('button', { class: 'btn sm', style: 'justify-self:start', onclick: () => copyText(codes.join('\n'), box) }, 'Copy')), [{ label: 'I saved them', cls: 'primary', run: (c) => c() }]);
  }
  async function turnOff() {
    const pw = h('input', { type: 'password', placeholder: 'Your password', autocomplete: 'current-password', style: 'width:100%' });
    const msg = h('div', { class: 'err' });
    modal('Turn off two-step sign-in', h('div', { style: 'display:grid;gap:8px' }, h('div', null, 'Enter your password to confirm.'), pw, msg), [
      { label: 'Cancel', run: (c) => c() },
      { label: 'Turn off', cls: 'danger solid', run: async (c) => {
        try { await api('POST', '/api/auth/2fa/disable', { password: pw.value }); c(); S.me.twoStep = false; toast('Two-step sign-in is off'); draw(); } catch (e) { msg.textContent = e.message; }
      } }]);
  }
  draw();
  return wrap;
}

async function usersDialog() {
  let tab = 'people', data = null;
  const body = h('div', { style: 'display:grid;gap:10px;width:100%;min-width:0;max-height:68vh;overflow:auto;align-content:start' });
  const tabBar = h('div', { class: 'sub' });
  const serverName = (id) => { const s = (data && data.servers.find(x => x.id === id)) || S.servers.find(x => x.id === id); return s ? s.name : id; };

  const drawTabs = () => tabBar.replaceChildren(...[['people', 'People'], ['invites', 'Invites'], ['activity', 'Activity']].map(([k, l]) =>
    h('button', { class: 'chip' + (tab === k ? ' on' : ''), onclick: () => { tab = k; show(); } }, l)));

  const act = async (path, payload, ok) => {
    try { await api('POST', path, payload); if (ok) toast(ok); await show(); } catch (e) { toast(e.message, true); await show(); }
  };

  // ----- people -----
  const people = async () => {
    data = await api('GET', '/api/users');
    const lv = (u, sid) => (u.grants[sid] === undefined ? -1 : u.grants[sid]);
    const cards = data.users.map(u => {
      const self = u.id === S.me.id;
      const badges = [u.admin ? h('span', { class: 'badge' }, 'Administrator (OP5)') : null, u.disabled ? h('span', { class: 'badge be' }, 'Disabled') : null, self ? h('span', { class: 'badge' }, 'You') : null];
      const rows = u.admin ? [h('div', { class: 'dim' }, 'Full access to every server and to the manager.')] : data.servers.map(s => {
        const cur = lv(u, s.id);
        const locked = !isAdmin() && (cur >= 4 || self);
        const opts = [[-1, 'No access'], [0, '0 Viewer'], [1, '1 Moderator'], [2, '2 Gamemaster'], [3, '3 Admin']].concat(isAdmin() ? [[4, '4 Operator']] : (cur >= 4 ? [[4, '4 Operator']] : []));
        const sel = h('select', { disabled: locked, title: locked ? (self ? 'You cannot change your own access' : 'Only the manager administrator can change a level 4 operator') : '',
          onchange: () => act('/api/users/grant', { user: u.id, server: s.id, level: +sel.value }, 'Saved') }, opts.map(([v, l]) => h('option', { value: v, selected: v === cur }, l)));
        return h('div', { class: 'row', style: 'justify-content:space-between;gap:8px' }, h('span', null, s.name), sel);
      });
      const btns = [];
      if (isAdmin()) {
        if (u.admin && self) btns.push(h('button', { class: 'btn sm', onclick: async () => { if (await confirmBox('Step down', 'Give up your administrator rights? You will keep any server access you are given afterwards, but you will no longer control the manager.', 'Step down', true)) act('/api/users/admin', { user: u.id, admin: false }, 'Done'); } }, 'Step down as administrator'));
        btns.push(h('button', { class: 'btn sm', onclick: async () => { const v = await inputBox('Minecraft username', `Minecraft username for ${u.username} (leave empty to clear)`, u.mcName || '', 'e.g. Steve'); if (v !== null) act('/api/users/mcname', { user: u.id, mcName: v.trim() }, 'Saved'); } }, 'Minecraft name'));
        if (!u.admin) {
          btns.push(h('button', { class: 'btn sm', onclick: () => act('/api/users/disable', { user: u.id, disabled: !u.disabled }, u.disabled ? 'Enabled' : 'Disabled') }, u.disabled ? 'Enable' : 'Disable'));
          btns.push(h('button', { class: 'btn sm', onclick: () => act('/api/users/signout', { user: u.id }, 'Signed out everywhere') }, 'Sign out everywhere'));
          if (u.twoStep) btns.push(h('button', { class: 'btn sm', title: 'Use when they lost their phone', onclick: async () => { if (await confirmBox('Reset two-step sign-in', `Turn off two-step sign-in for ${u.username}? They can set it up again themselves.`, 'Reset', true)) act('/api/users/reset2fa', { user: u.id }, 'Reset'); } }, 'Reset two-step'));
          btns.push(h('button', { class: 'btn sm', onclick: async () => { if (await confirmBox('Make administrator', `Make ${u.username} a manager administrator (OP5)? They get full control of the manager, and only they (or the PC owner) can ever remove that.`, 'Make administrator', true)) act('/api/users/admin', { user: u.id, admin: true }, 'Done'); } }, 'Make administrator'));
          btns.push(h('button', { class: 'btn sm danger', onclick: async () => { if (await confirmBox('Delete account', `Delete ${u.username}? They lose all access immediately.`, 'Delete', true)) act('/api/users/delete', { user: u.id }, 'Deleted'); } }, 'Delete'));
        } else if (!self) btns.push(h('span', { class: 'dim', style: 'font-size:12.5px' }, 'Only they can step down, or the PC owner with Owner.bat.'));
      }
      return h('div', { class: 'box ucard' }, h('div', { class: 'row', style: 'justify-content:space-between' }, h('b', null, u.username, ' ', ...badges.filter(Boolean)),
        h('span', { class: 'dim', style: 'font-size:12.5px' }, `${u.mcName ? 'Minecraft: ' + u.mcName + ' · ' : ''}added ${fmtWhen(u.created)}${u.createdBy ? ' by ' + u.createdBy : ''}`)),
        h('div', { style: 'display:grid;gap:6px;margin-top:8px' }, ...rows), btns.length ? h('div', { class: 'row', style: 'margin-top:8px;gap:6px;flex-wrap:wrap' }, ...btns) : null);
    });
    body.replaceChildren(h('div', { class: 'dim', style: 'font-size:12.5px' }, isAdmin() ? 'Administrators can change anyone. Use the Invites tab to add people.' : 'You manage the servers where you are level 4. You can set levels up to 3 and cannot change other operators.'), ...cards);
  };

  // ----- invites -----
  const invites = async () => {
    data = await api('GET', '/api/users');
    const list = (await api('GET', '/api/users/invites')).invites;
    const note = h('input', { placeholder: 'Who is it for? (optional note, e.g. "Sam - friend")', style: 'width:100%' });
    const mcI = h('input', { placeholder: 'Their Minecraft username (optional)', style: 'width:100%', autocomplete: 'off', spellcheck: 'false' });
    const lvlOpts = [['0', '0 Viewer'], ['1', '1 Moderator'], ['2', '2 Gamemaster'], ['3', '3 Admin']].concat(isAdmin() ? [['4', '4 Operator'], ['admin', 'Manager administrator (OP5)']] : []);
    const lvlSel = h('select', null, lvlOpts.map(([v, l]) => h('option', { value: v, selected: v === '1' }, l)));
    const help = h('div', { class: 'dim', style: 'font-size:12.5px' });
    const hours = h('select', null, [[1, '1 hour'], [24, '1 day'], [168, '7 days'], [720, '30 days']].map(([v, l]) => h('option', { value: v, selected: v === 24 }, l)));
    const checks = data.servers.map(s => ({ s, c: h('input', { type: 'checkbox' }) }));
    if (checks.length === 1) checks[0].c.checked = true;
    const sync = () => {
      const isAdm = lvlSel.value === 'admin';
      help.textContent = isAdm ? 'Full control of the manager: servers, settings, startup and all accounts. Only an administrator can create one.' : LEVEL_HELP[+lvlSel.value];
      checks.forEach(x => { x.c.disabled = isAdm; });
    };
    lvlSel.addEventListener('change', sync);
    sync();
    const mint = h('button', { class: 'btn primary', onclick: async () => {
      const adm = lvlSel.value === 'admin';
      const servers = checks.filter(x => x.c.checked).map(x => x.s.id);
      try {
        const r = await api('POST', '/api/users/invite', { admin: adm, level: adm ? 0 : +lvlSel.value, servers, hours: +hours.value, note: note.value.trim(), mcName: mcI.value.trim() });
        showInvite(r.token, r.expires, adm ? 'a manager administrator' : `level ${lvlSel.value} on ${servers.map(serverName).join(', ')}`);
        await show();
      } catch (e) { toast(e.message, true); }
    } }, 'Create invite');
    const form = h('div', { class: 'box', style: 'display:grid;gap:8px' }, h('h3', null, 'Invite someone'),
      h('div', { class: 'dim', style: 'font-size:12.5px' }, 'An invite is a one-time code. The person opens the link, chooses their own username and password, and gets exactly the access you pick here. It works once and expires.'),
      note, mcI, h('div', { class: 'row', style: 'gap:10px;flex-wrap:wrap' }, h('label', null, 'Access ', lvlSel), h('label', null, 'Expires in ', hours)), help,
      h('div', { style: 'display:grid;gap:4px' }, h('span', { class: 'dim' }, 'On these servers'), ...checks.map(x => h('label', null, x.c, ' ', x.s.name))), mint);
    const rows = list.length ? list.map(i => h('tr', null,
      h('td', null, i.admin ? 'Administrator' : `Level ${i.level}`), h('td', null, i.admin ? '' : (i.servers || []).join(', ')), h('td', { class: 'dim' }, i.note || ''),
      h('td', { class: 'dim hide-m' }, `${i.createdByName}, ${fmtWhen(i.created)}`),
      h('td', null, i.status === 'used' ? `Used by ${i.usedBy}` : i.status === 'expired' ? 'Expired' : 'Waiting, until ' + fmtWhen(i.expires)),
      h('td', null, i.status === 'active' ? h('button', { class: 'btn sm', onclick: () => act('/api/users/invite/revoke', { id: i.id }, 'Cancelled') }, 'Cancel') : null))) : [];
    body.replaceChildren(form, h('div', { class: 'box' }, h('h3', null, 'Invites'), list.length ? h('table', { class: 'tbl' }, h('tr', null, ['Access', 'Servers', 'Note', h('span', { class: 'hide-m' }, 'Created'), 'Status', ''].map(c => h('th', null, c))), ...rows) : h('div', { class: 'dim' }, 'No invites yet.')));
  };

  // ----- activity -----
  const activity = async () => {
    data = await api('GET', '/api/users');
    const srvSel = h('select', null, h('option', { value: '' }, 'All servers'), data.servers.map(s => h('option', { value: s.id }, s.name)));
    const userI = h('input', { placeholder: 'Username (optional)', style: 'width:160px' });
    const out = h('div');
    const load = async () => {
      const q = new URLSearchParams({ limit: '300' });
      if (srvSel.value) q.set('server', srvSel.value);
      if (userI.value.trim()) q.set('user', userI.value.trim());
      const r = await api('GET', '/api/audit?' + q.toString());
      const integrity = h('div', { class: r.intact ? 'dim' : 'err', style: 'font-size:12.5px' }, r.intact ? `Log integrity: every entry checks out (${r.total} entries in total).` : 'WARNING: the activity log has been edited or damaged. Entries may have been altered or removed.');
      out.replaceChildren(integrity, r.entries.length ? h('table', { class: 'tbl' }, h('tr', null, ['When', 'Who', 'What', 'Server', 'Details'].map(c => h('th', null, c))),
        ...r.entries.map(e => h('tr', null, h('td', { class: 'dim' }, fmtWhen(e.t)), h('td', null, e.user), h('td', null, e.action), h('td', null, e.server ? serverName(e.server) : ''), h('td', { class: 'dim', style: 'word-break:break-word' }, e.detail || ''))))
        : h('div', { class: 'dim' }, 'Nothing recorded yet.'));
    };
    srvSel.addEventListener('change', load);
    userI.addEventListener('change', load);
    body.replaceChildren(h('div', { class: 'row', style: 'gap:8px;flex-wrap:wrap' }, srvSel, userI, h('button', { class: 'btn sm', onclick: load }, 'Refresh')), out);
    await load();
  };

  const show = async () => {
    drawTabs();
    try { await ({ people, invites, activity }[tab])(); } catch (e) { body.replaceChildren(h('div', { class: 'err' }, e.message)); }
  };

  modal('Accounts and access', h('div', { style: 'display:grid;gap:10px;min-width:0' }, tabBar, body), [{ label: 'Close', run: (c) => c() }], { wide: true });
  show();
}

function showInvite(token, expires, what) {
  const base = (S.manager && S.manager.urls && S.manager.urls[0]) || location.origin;
  const link = base + '/#invite=' + token;
  const li = h('input', { value: link, readonly: true, style: 'width:100%;font-family:var(--mono);font-size:12px', onclick: (e) => e.target.select() });
  const ci = h('input', { value: token, readonly: true, style: 'width:100%;font-family:var(--mono);font-size:12px', onclick: (e) => e.target.select() });
  modal('Invite created', h('div', { style: 'display:grid;gap:8px;width:100%;min-width:0' },
    h('div', null, `This invite gives ${what}. It can be used once and expires ${fmtWhen(expires)}.`),
    h('div', { class: 'notice' }, 'Copy it now: this is the only time the code is shown. If you lose it, cancel it and create a new one.'),
    h('div', { class: 'dim' }, 'Link (send this to them)'), h('div', { class: 'row' }, li, h('button', { class: 'btn sm', onclick: () => copyText(link, li) }, 'Copy')),
    h('div', { class: 'dim' }, 'Or just the code'), h('div', { class: 'row' }, ci, h('button', { class: 'btn sm', onclick: () => copyText(token, ci) }, 'Copy')),
    h('div', { class: 'dim', style: 'font-size:12.5px' }, 'The link only works for people who can reach this PC (same network, or your VPN).')), [{ label: 'Done', cls: 'primary', run: (c) => c() }]);
}
