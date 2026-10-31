// ---------- delete a server from disk ----------
async function deleteServerDialog(s) {
  let pv;
  try { pv = await api('GET', `/api/servers/${s.id}/delete-preview`); } catch (e) { toast(e.message, true); return; }
  if (pv.blocked) {
    modal('This server cannot be deleted', h('div', { style: 'display:grid;gap:8px' }, h('div', { class: 'notice' }, pv.blocked), h('div', { class: 'dim' }, 'Use Remove if you only want it out of the manager’s list.')),
      [{ label: 'Close', cls: 'primary', run: (c) => c() }]);
    return;
  }
  const mb = (n) => n >= 1024 ? (n / 1024).toFixed(1) + ' GB' : n + ' MB';
  const recycle = h('input', { type: 'radio', name: 'delmode', checked: true });
  const perm = h('input', { type: 'radio', name: 'delmode' });
  const backup = h('input', { type: 'checkbox', onchange: () => { bdir.disabled = !backup.checked; bbtn.disabled = !backup.checked; } });
  const bdir = h('input', { value: pv.defaultBackupDir, disabled: true, spellcheck: 'false', style: 'flex:1' });
  const bbtn = h('button', { class: 'btn', type: 'button', disabled: true, onclick: async () => { const p = await pickFolder({ title: 'Where should the world backup be saved?', start: bdir.value }); if (p) bdir.value = p; } }, icon('folder'), ' Browse');
  const lc = h('input', { type: 'checkbox', onchange: () => sync() });
  const typed = h('input', { placeholder: s.name, autocomplete: 'off', spellcheck: 'false', style: 'width:100%', oninput: () => sync() });
  let m;
  const sync = () => { if (m) m.btns[1].disabled = typed.value !== s.name || (pv.launcher && !lc.checked); };
  const body = h('div', { style: 'display:grid;gap:10px;width:100%;min-width:0' },
    h('div', { class: 'notice', style: 'border-color:var(--err);background:rgba(239,106,106,.1)' }, h('b', null, 'This deletes the server’s files from your PC. '), `Everything in ${pv.dir} goes: the world${pv.worldMb ? ` (${mb(pv.worldMb)})` : ''}, mods, settings and logs (${mb(pv.sizeMb)} in total).`),
    ...pv.warnings.map(w => h('div', { class: 'notice' }, w)),
    h('div', { style: 'display:grid;gap:4px' },
      h('label', null, recycle, ' Move it to the Recycle Bin (you can restore it from there)'),
      h('label', null, perm, ' Delete it permanently (cannot be undone)'),
      h('div', { class: 'dim', style: 'font-size:12.5px;margin-left:24px' }, 'If the folder is bigger than the Recycle Bin allows, Windows may refuse; then choose permanent deletion.')),
    h('div', { style: 'display:grid;gap:4px' }, h('label', null, backup, ' Zip the world to a backup first'), h('div', { class: 'row', style: 'flex-wrap:nowrap' }, bdir, bbtn),
      h('div', { class: 'dim', style: 'font-size:12.5px' }, 'Saved as a .zip in that folder, outside the server folder.')),
    pv.launcher ? h('label', null, lc, ' I understand this folder belongs to another launcher and deleting it may remove that launcher’s instance') : null,
    h('div', { style: 'display:grid;gap:4px' }, h('span', null, `To confirm, type the server name: `, h('b', null, s.name)), typed));
  m = modal(`Delete ${s.name} from disk`, body, [
    { label: 'Cancel', run: (c) => c() },
    { label: 'Delete', cls: 'danger solid', disabled: true, run: async (c) => {
      try {
        const r = await api('POST', `/api/servers/${s.id}/delete`, { confirmName: typed.value, permanent: perm.checked, backup: backup.checked, backupDir: bdir.value.trim(), confirmLauncher: lc.checked });
        c();
        openJob(r.job, `Delete ${s.name}`, s.id, { onDone: async () => {
          try { S.servers = await api('GET', '/api/servers'); } catch (e) { return; }
          S.sel = S.servers[0] && S.servers[0].id;
          renderSide(); renderMain();
        } });
      } catch (e) { toast(e.message, true); }
    } },
  ]);
  sync();
}

// ---------- Geyser tab (Bedrock players) ----------
async function buildGeyser(s, pane, tok) {
  const d = await api('GET', `/api/servers/${s.id}/geyser`);
  if (tok !== S.token) return;
  const op = lvl(s) >= 4;
  const yes = (v) => h('span', { class: v ? 'ok' : 'err' }, v ? '✓ yes' : '✗ no');
  const reload = () => { if (S.tab === 'geyser') renderPane(); };
  const act = async (fn, okMsg) => { try { await fn(); if (okMsg) toast(okMsg); } catch (e) { toast(e.message, true); } reload(); };
  const hosts = ((S.manager && S.manager.urls) || []).map(u => { try { return new URL(u).hostname; } catch (e) { return null; } }).filter(Boolean);

  // --- status
  const st = d.settings || {};
  const rows = [
    ['Geyser', yes(d.geyser)], ['Floodgate (lets Bedrock players in without a Java account)', yes(d.floodgate)],
    ['Bedrock port (TCP and UDP)', d.bedrockPort ? String(d.bedrockPort) : 'not known yet'],
    ['Connection method', d.tooOld ? 'old only (RakNet): too old for Bedrock 26.60' : d.nethernet ? 'new (NetherNet)' + (d.transport === 'both' ? ' and old (RakNet)' : '') + (d.webrtcPort ? ', NetherNet UDP port ' + d.webrtcPort : '') : d.transport ? d.transport : 'not known yet'],
    ['Geyser is listening', d.startedOnPort ? `yes, on UDP ${d.startedOnPort} (from the last start)` : 'no start seen in the recent log'],
    ['Bedrock players online', String(d.bedrockOnline)],
    ['Whitelist', d.whitelistOn ? 'on' : 'off (everyone can join)'],
  ];
  const warns = [];
  if (!d.configFound) warns.push('Geyser has not created its settings yet. Start the server once, then open this tab again.');
  if (d.configFound && st.authType && st.authType !== 'floodgate') warns.push(`Authentication is "${st.authType}", so Bedrock players would need a linked Java account. Set it to floodgate in Settings below.`);
  if (d.tooOld) warns.push('This Geyser is too old to know the new Bedrock connection method (NetherNet). Bedrock 26.60 stops using the old one, so Bedrock players will not be able to join until Geyser is updated. Open the Mods tab and update Geyser.');
  if (d.configFound && d.transport && !d.nethernet) warns.push('Geyser is set to the old connection method only. The manager switches it to the new one (and gives it its own Bedrock port) the next time the server starts.');
  if (d.bedrockClash) warns.push(`Another server (${d.bedrockClash}) is also set to use UDP port ${d.bedrockPort} for Bedrock. Two servers cannot share it, and the one that starts second will shut down. Start this server again and the manager gives Geyser a port of its own.`);
  if (d.geyser && !d.floodgate) warns.push('Floodgate is not installed, so Bedrock players can only join with a linked Java account. Install Floodgate from the Mods tab.');
  const fw = d.firewall === null ? h('span', { class: 'dim' }, 'known once Geyser has created its settings')
    : d.firewall ? yes(true) : h('span', null, yes(false), ' ', isAdmin() ? h('button', { class: 'btn sm primary', onclick: async () => {
      try { const r = await api('POST', `/api/servers/${s.id}/geyser/firewall`, {}); openJob(r.job, 'Allow Bedrock through the firewall', '', { onDone: reload }); } catch (e) { toast(e.message, true); }
    } }, 'Allow through Windows Firewall') : h('span', { class: 'dim' }, 'ask an administrator'));
  const statusBox = h('div', { class: 'box' }, h('h3', null, 'Bedrock status'),
    h('div', { class: 'props' }, ...rows.flatMap(([k, v]) => [h('div', { class: 'k' }, k), h('div', null, v)]), h('div', { class: 'k' }, `Firewall rule (${d.ruleName})`), h('div', null, fw)),
    ...warns.map(w => h('div', { class: 'notice', style: 'margin-top:8px' }, w)),
    hosts.length && d.bedrockPort ? h('div', { class: 'dim', style: 'margin-top:8px;font-size:12.5px' }, `Bedrock players add a server with address ${hosts.join(' or ')} and port ${d.bedrockPort}. Over the internet you also need to forward TCP and UDP ${d.bedrockPort}${d.webrtcPort ? ' and UDP ' + d.webrtcPort : ''} on your router.`) : null);

  // --- whitelist helper
  const tag = h('input', { placeholder: 'Xbox gamertag, e.g. Steve123', style: 'flex:1;min-width:160px', autocomplete: 'off', spellcheck: 'false' });
  const add = async () => { if (!tag.value.trim()) return; await act(async () => { const r = await api('POST', `/api/servers/${s.id}/geyser/whitelist`, { gamertag: tag.value.trim() }); toast(`${r.name} added to the whitelist`); }); };
  tag.addEventListener('keydown', (e) => { if (e.key === 'Enter') add(); });
  const wl = d.whitelist.length ? h('table', { class: 'tbl' }, h('tr', null, ['Player', 'ID', ''].map(c => h('th', null, c))),
    ...d.whitelist.map(w => h('tr', null, h('td', null, w.name), h('td', { class: 'mono dim hide-m', style: 'font-size:12px' }, w.uuid),
      h('td', null, h('button', { class: 'btn sm', onclick: async () => { if (await confirmBox('Remove from whitelist', `Remove ${w.name} from the whitelist?`, 'Remove', true)) act(() => api('POST', `/api/servers/${s.id}/geyser/whitelist/remove`, { uuid: w.uuid }), 'Removed'); } }, 'Remove'))))) : h('div', { class: 'dim' }, 'No Bedrock players are on the whitelist yet.');
  const wlBox = h('div', { class: 'box' }, h('h3', null, 'Bedrock whitelist'),
    h('div', { class: 'dim', style: 'font-size:12.5px;margin-bottom:8px' }, `Type a gamertag exactly as it appears on Xbox. The manager looks up the player's Xbox ID and adds the entry Floodgate expects (names show as ${d.prefix}Name).`),
    h('div', { class: 'row', style: 'gap:8px' }, tag, h('button', { class: 'btn primary', onclick: add }, 'Add')), h('div', { style: 'margin-top:8px;overflow:auto' }, wl));

  // --- approvals
  const mode = d.mode;
  const minutes = h('select', null, [[15, '15 minutes'], [60, '1 hour'], [240, '4 hours'], [1440, '24 hours']].map(([v, l]) => h('option', { value: v }, l)));
  const setMode = (m, mins) => act(() => api('POST', `/api/servers/${s.id}/geyser/mode`, { mode: m, minutes: mins }), 'Saved');
  const left = d.autoUntil && d.autoUntil > Date.now() ? Math.max(1, Math.round((d.autoUntil - Date.now()) / 60000)) : 0;
  const pend = d.pending.length ? h('table', { class: 'tbl' }, h('tr', null, ['Player', 'Tried', 'Last try', ''].map(c => h('th', null, c))),
    ...d.pending.map(p => h('tr', null, h('td', null, p.name), h('td', null, p.count + (p.count === 1 ? ' time' : ' times')), h('td', { class: 'dim' }, fmtWhen(p.last)),
      h('td', { style: 'white-space:nowrap' }, h('button', { class: 'btn sm primary', onclick: () => act(() => api('POST', `/api/servers/${s.id}/geyser/approve`, { name: p.name }), `${p.name} approved: ask them to try again`) }, 'Approve'), ' ',
        h('button', { class: 'btn sm', onclick: () => act(() => api('POST', `/api/servers/${s.id}/geyser/dismiss`, { name: p.name })) }, 'Dismiss'))))) : h('div', { class: 'dim' }, mode === 'off' ? 'Turned off.' : 'Nobody is waiting.');
  const modeBtn = (k, label) => h('button', { class: 'chip' + (mode === k ? ' on' : ''), disabled: !op, onclick: () => (k === 'auto' ? null : setMode(k, 0)) }, label);
  const apprBox = h('div', { class: 'box' }, h('h3', null, 'Bedrock players the whitelist turns away'),
    h('div', { class: 'dim', style: 'font-size:12.5px;margin-bottom:8px' }, 'When a Bedrock player who is not on the whitelist tries to join, the manager can list them here for you to approve, or approve everyone for a limited time.'),
    !d.whitelistOn ? h('div', { class: 'notice' }, 'The whitelist is off on this server, so nobody is turned away. Turn it on (white-list=true in Properties) for this to matter.') : null,
    h('div', { class: 'sub' }, modeBtn('off', 'Off'), modeBtn('queue', 'Approval list'),
      h('button', { class: 'chip' + (mode === 'auto' ? ' on' : ''), disabled: !op, onclick: async () => {
        if (await confirmBox('Approve every Bedrock player?', `For ${minutes.options[minutes.selectedIndex].text}, any Bedrock player who finds this server's address will be added to the whitelist automatically. That is the same as having no whitelist for Bedrock. It turns itself off afterwards.`, 'Turn on', true)) setMode('auto', +minutes.value);
      } }, 'Auto-approve for'), minutes),
    mode === 'auto' && left ? h('div', { class: 'notice', style: 'margin-top:8px' }, `Auto-approve is ON for about ${left} more minute${left === 1 ? '' : 's'}. `, h('button', { class: 'btn sm', onclick: () => setMode('queue', 0) }, 'Stop now')) : null,
    h('div', { style: 'margin-top:8px;overflow:auto' }, pend));

  // --- settings
  const f = {};
  const inp = (k, v, ph, w) => (f[k] = h('input', { value: v == null ? '' : v, placeholder: ph || '', style: w ? `width:${w}` : 'width:100%', disabled: !op || !d.configFound }));
  const chk = (k, v) => (f[k] = h('input', { type: 'checkbox', checked: !!v, disabled: !op || !d.configFound }));
  const auth = (f.authType = h('select', { disabled: !op || !d.configFound }, ['floodgate', 'online', 'offline'].map(v => h('option', { value: v, selected: v === st.authType }, v === 'floodgate' ? 'floodgate (recommended: no Java account needed)' : v === 'online' ? 'online (needs a linked Java account)' : 'offline (no checks, not recommended)'))));
  const lab = (t, el) => h('label', { class: 'dim', style: 'display:grid;gap:2px' }, t, el);
  const saveSettings = h('button', { class: 'btn primary', disabled: !op || !d.configFound, onclick: () => act(async () => {
    const b = { cloneRemotePort: f.clone.checked, authType: auth.value, syncNames: f.sync.checked, passthroughMotd: f.pt.checked, passthroughCounts: f.pc.checked };
    if (!f.sync.checked) Object.assign(b, { primaryMotd: f.pm.value, secondaryMotd: f.sm.value, serverName: f.sn.value });
    if (!f.clone.checked) b.port = +f.port.value;
    if (f.mp.value.trim()) b.maxPlayers = +f.mp.value;
    await api('POST', `/api/servers/${s.id}/geyser/settings`, b);
  }, 'Saved. Restart the server to apply.') }, 'Save');
  f.clone = chk('clone', st.cloneRemotePort);
  f.sync = chk('sync', d.syncNames);
  const syncFields = () => ['sn', 'pm', 'sm'].forEach(k => { f[k].disabled = !op || !d.configFound || f.sync.checked; });
  const setBox = h('div', { class: 'box' }, h('h3', null, 'Geyser settings'),
    !d.configFound ? h('div', { class: 'dim' }, 'Available after Geyser has created its settings (start the server once).') : null,
    h('div', { style: 'display:grid;gap:8px' },
      h('label', null, f.clone, ' Use the same port number as the Java server for Bedrock (UDP)'),
      lab('Bedrock port (used when the box above is off)', inp('port', st.port, '19132', '120px')),
      lab('Sign-in method for Bedrock players', auth),
      h('label', null, f.sync, ' Keep the Bedrock server name and server list lines the same as the Java server (its name and message of the day)'),
      lab('Server name shown to Bedrock players', inp('sn', st.serverName)),
      lab('Server list title (first line)', inp('pm', st.primaryMotd)), lab('Server list text (second line)', inp('sm', st.secondaryMotd)),
      h('label', null, chk('pt', st.passthroughMotd), ' Show the Java server’s message of the day instead'),
      h('label', null, chk('pc', st.passthroughCounts), ' Show the Java server’s player counts'),
      lab('Max players shown (when not using the Java counts)', inp('mp', st.maxPlayers, '', '120px')),
      h('div', { class: 'row', style: 'gap:10px;align-items:center' }, saveSettings, h('span', { class: 'dim', style: 'font-size:12.5px' }, op ? 'Applies after the next restart. A backup of the original is kept (.mcm-bak).' : 'You need level 4 on this server to change these.'))));

  // --- linking
  const linkBox = h('div', { class: 'box' }, h('h3', null, 'Link Java and Bedrock accounts'),
    h('div', { class: 'dim', style: 'font-size:12.5px;margin-bottom:8px' }, 'Linking lets one person keep the same inventory and world progress whether they play on Java or on Bedrock. GeyserMC runs a free global linking service; the manager does not do the linking itself.'),
    h('a', { href: 'https://link.geysermc.org/', target: '_blank', rel: 'noopener noreferrer', class: 'btn' }, 'Open GeyserMC Global Linking'),
    ' ', h('a', { href: 'https://geysermc.org/wiki/floodgate/linking/', target: '_blank', rel: 'noopener noreferrer', class: 'dim', style: 'font-size:12.5px' }, 'How linking works'));

  syncFields();
  f.sync.addEventListener('change', syncFields);
  pane.replaceChildren(statusBox, wlBox, apprBox, setBox, linkBox);
}
