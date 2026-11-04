'use strict';
/* Folder picking, adding existing servers, searching for servers, settings and first-run setup. */

function pickFolder(opts = {}) {
  return new Promise(res => {
    let done = false, cur = '', data = null, picked = null;
    const fin = (v) => { if (!done) { done = true; res(v); } };
    const pathIn = h('input', { value: opts.start || '', spellcheck: 'false', placeholder: 'Type a path or pick below', style: 'flex:1', autocomplete: 'off' });
    const list = h('div', { class: 'flist' });
    const chips = h('div', { class: 'sub' });
    const info = h('div', { class: 'dim', style: 'word-break:break-all' });
    const up = h('button', { class: 'btn', type: 'button', 'aria-label': 'Up one level', onclick: () => load(data && data.parent !== undefined ? data.parent : '') }, icon('up'));
    const newBtn = h('button', { class: 'btn', type: 'button', onclick: async () => {
      const n = await inputBox('New folder', 'Folder name:');
      if (!n) return;
      try { const r = await api('POST', '/api/fs/mkdir', { parent: cur, name: n }); load(r.path); } catch (e) { toast(e.message, true); }
    } }, icon('plus'), ' New folder');
    let m;
    const load = async (p) => {
      try { data = await api('GET', '/api/fs/list?path=' + encodeURIComponent(p || '') + (opts.files ? '&files=' + encodeURIComponent(opts.files) : '')); } catch (e) { toast(e.message, true); return; }
      cur = data.path;
      picked = null;
      pathIn.value = cur;
      up.disabled = !cur;
      newBtn.disabled = !cur;
      chips.replaceChildren(...data.shortcuts.map(s => h('button', { class: 'chip', type: 'button', onclick: () => load(s.path) }, s.name)));
      const dirRows = data.dirs.map(d => h('div', { class: 'frow', onclick: () => load(d.path) }, icon('folder'), h('span', { style: 'flex:1;word-break:break-all' }, d.name), d.server ? h('span', { class: 'badge ok' }, 'server') : null));
      const fileRows = (data.files || []).map(f => h('div', { class: 'frow', onclick: () => choose(f.path) }, icon('files'), h('span', { style: 'flex:1;word-break:break-all' }, f.name)));
      list.replaceChildren(...(dirRows.length || fileRows.length ? [...dirRows, ...fileRows] : [h('div', { class: 'dim', style: 'padding:12px' }, cur ? (opts.files ? 'No sub-folders or .' + opts.files + ' files here.' : 'No sub-folders here.') : 'No drives found.')]));
      info.textContent = cur ? (opts.files ? 'Open folders and click a .' + opts.files + ' file to choose it.' : data.writable ? 'Selected: ' + cur : 'Selected: ' + cur + '  (read-only)') : 'Choose a drive to start.';
      if (m) m.btns[1].disabled = opts.files ? true : !cur || (opts.writable && !data.writable);
    };
    const choose = (p) => { picked = p; pathIn.value = p; info.textContent = 'Selected: ' + p; if (m) m.btns[1].disabled = false; };
    const go = () => { const v = pathIn.value.trim(); if (opts.files && v.toLowerCase().endsWith('.' + opts.files)) choose(v); else load(v); };
    pathIn.addEventListener('keydown', e => { if (e.key === 'Enter') { e.preventDefault(); go(); } });
    const body = h('div', { class: 'fpick' }, h('div', { class: 'row', style: 'flex-wrap:nowrap' }, up, pathIn, h('button', { class: 'btn', type: 'button', onclick: go }, 'Go')),
      chips, list, h('div', { class: 'row' }, newBtn), info);
    m = modal(opts.title || 'Choose a folder', body, [
      { label: 'Cancel', run: (c) => { fin(null); c(); } },
      { label: opts.button || 'Use this folder', cls: 'primary', disabled: true, run: (c) => { fin(picked || cur); c(); } },
    ], { onClose: () => fin(null) });
    load(opts.start || '');
  });
}

function addMenu() {
  let m;
  const choice = (title, text, fn) => h('button', { onclick: () => { m.close(); fn(); } }, h('b', null, title), h('span', { class: 'dim' }, text));
  m = modal('Add a server', h('div', { class: 'bigchoice' },
    choice('Create a new server', 'Download Vanilla, Fabric, Forge or NeoForge and set it up for you.', addServerWizard),
    choice('Create a server from a world', 'Turn a singleplayer world into a server. Finds worlds on this PC, or pick a folder or zip from anywhere.', worldImportDialog),
    choice('Start from a modpack', 'Build a server from a CurseForge or Modrinth modpack (or a pack file): the right Minecraft, loader, server mods and configs.', packDialog),
    choice('Add an existing server', 'Point the manager at a server folder you already have.', addExistingFlow),
    choice('Search this PC for servers', 'Finds servers made by CurseForge, ATLauncher, FTB, plain jars and more.', () => scanDialog())),
  [{ label: 'Close', run: (c) => c() }]);
}

async function addExistingFlow() {
  const dir = await pickFolder({ title: "Pick the server's folder", button: 'Use this server folder' });
  if (!dir) return;
  try { importDialog(await api('POST', '/api/import/inspect', { dir })); } catch (e) { toast(e.message, true); }
}

async function importDialog(info, onAdded) {
  let rts = [];
  try { rts = (await api('GET', '/api/java')).runtimes; } catch (e) { /* optional */ }
  const lab = (t, el) => h('label', { class: 'dim', style: 'display:grid;gap:2px' }, t, el);
  const name = h('input', { value: info.name, maxlength: 60 });
  const scriptSel = h('select', { onchange: () => sync() }, info.scripts.map(s => h('option', { value: s }, s)), h('option', { value: '__gen' }, 'Let the manager create a start script'));
  if (!info.scripts.length) scriptSel.value = '__gen';
  const jarSel = h('select', null, info.jars.map(j => h('option', { value: j, selected: j === 'server.jar' }, j)));
  const gb = (mb) => h('option', { value: mb }, (mb / 1024) + ' GB');
  const minMb = h('select', null, [512, 1024, 2048, 4096].map(gb));
  minMb.value = 1024;
  const maxMb = h('select', null, [2048, 4096, 6144, 8192, 12288, 16384].map(gb));
  maxMb.value = 4096;
  const jarRow = h('div', { class: 'row' }, info.kind === 'forge' || info.kind === 'neoforge' ? null : lab('Server jar', jarSel), lab('Min RAM', minMb), lab('Max RAM', maxMb));
  const javaSel = h('select', { style: 'max-width:100%' }, h('option', { value: '' }, 'Automatic (best match for this Minecraft version)'), rts.map(r => h('option', { value: r.path }, r.label)));
  const chk = (checked, text) => { const i = h('input', { type: 'checkbox', checked }); return [i, h('label', null, i, ' ', text)]; };
  const [rconI, rconL] = chk(!info.rcon, 'Enable the remote console (RCON) so the manager can run commands and stop it cleanly');
  const [eulaI, eulaL] = chk(false, h('span', null, 'Accept the ', h('a', { href: 'https://aka.ms/MinecraftEULA', target: '_blank', rel: 'noopener' }, 'Minecraft EULA'), ' for this server'));
  const [autoI, autoL] = chk(false, 'Start automatically when the manager starts');
  const [updI, updL] = chk(false, 'Check for Fabric and mod updates each time the manager starts it');
  const conflict = S.servers.find(x => x.port === info.port);
  const rconClash = S.servers.find(x => x.rconPort === info.rconPort || x.port === info.rconPort);
  const sync = () => jarRow.classList.toggle('hidden', scriptSel.value !== '__gen');
  const kindLabel = { vanilla: 'Vanilla', fabric: 'Fabric', forge: 'Forge', neoforge: 'NeoForge', quilt: 'Quilt', paper: 'Paper', spigot: 'Spigot', purpur: 'Purpur', folia: 'Folia', unknown: 'Unknown type' }[info.kind] || info.kind;
  const body = h('div', { style: 'display:grid;gap:10px' },
    h('div', { class: 'mono dim', style: 'font-size:12px;word-break:break-all' }, info.dir),
    h('div', null, h('b', null, kindLabel), info.mc ? ' ' + info.mc : '', ` · port ${info.port} · ${info.mods} mods`, info.hint ? h('span', { class: 'badge' }, info.hint) : null),
    conflict ? h('div', { class: 'err' }, `Port ${info.port} is already used by "${conflict.name}". Change one of them in server.properties before running both.`) : null,
    rconClash && info.rcon ? h('div', { class: 'err' }, `Its remote-console port ${info.rconPort} clashes with "${rconClash.name}". After adding, open Settings > Ports and click "Assign free RCON ports".`) : null,
    rconClash && !info.rcon ? h('div', { class: 'dim', style: 'font-size:12.5px' }, `The default remote-console port ${info.rconPort} is taken by "${rconClash.name}", so a free one will be chosen automatically if you enable RCON.`) : null,
    info.bedrockPort ? h('div', { class: 'dim', style: 'font-size:12.5px' }, `Bedrock (Geyser) port: ${info.bedrockPort}. Check Settings > Ports for clashes with your other servers.`) : null,
    info.firstRun ? h('div', { class: 'dim' }, 'This server has not been started yet (no server.properties). It may run an installer on first start; check the console afterwards.') : null,
    lab('Name in the manager', name),
    lab('Start script', scriptSel), jarRow,
    lab('Java runtime', javaSel),
    info.rcon ? h('div', { class: 'dim' }, 'Remote console (RCON) is already enabled.') : rconL,
    info.eula === 'accepted' ? null : eulaL,
    autoL,
    info.kind === 'fabric' ? updL : null);
  sync();
  const m = modal('Add existing server', body, [
    { label: 'Cancel', run: (c) => c() },
    { label: 'Add server', cls: 'primary', run: async (c) => {
      const gen = scriptSel.value === '__gen';
      try {
        const r = await api('POST', '/api/import/add', {
          dir: info.dir, name: name.value.trim(), script: gen ? null : scriptSel.value, generate: gen, jar: jarSel.value, minMb: +minMb.value, maxMb: +maxMb.value,
          enableRcon: rconI.checked && !info.rcon, acceptEula: eulaI.checked, autostart: autoI.checked, autoUpdate: updI.checked, java: javaSel.value,
        });
        c();
        toast('Server added');
        S.servers = await api('GET', '/api/servers');
        if (onAdded) onAdded(r.id);
        selectServer(r.id);
      } catch (e) { toast(e.message, true); }
    } },
  ]);
  return m;
}

const KIND_LABEL = { vanilla: 'Vanilla', fabric: 'Fabric', forge: 'Forge', neoforge: 'NeoForge', quilt: 'Quilt', paper: 'Paper', spigot: 'Spigot', purpur: 'Purpur', folia: 'Folia', unknown: 'Unknown type' };

function bulkSummary(r) {
  const kids = [];
  kids.push(h('b', null, r.added.length ? `Added ${r.added.length} server${r.added.length === 1 ? '' : 's'}` : 'Nothing was added'));
  for (const a of r.added) {
    kids.push(h('div', { class: 'found' }, h('div', null, h('b', null, a.name), h('span', { class: 'badge' }, KIND_LABEL[a.kind] || a.kind)), h('div', { class: 'p' }, a.dir),
      ...(a.notes || []).map(n => h('div', { class: 'dim', style: 'font-size:12.5px' }, '• ' + n))));
  }
  if (r.skipped.length) {
    kids.push(h('b', { style: 'margin-top:6px' }, `Skipped ${r.skipped.length}`));
    for (const s of r.skipped) kids.push(h('div', { class: 'found' }, h('div', { class: 'p' }, s.dir), h('div', { class: 'dim', style: 'font-size:12.5px' }, s.reason)));
  }
  kids.push(h('div', { class: 'dim', style: 'font-size:12.5px' }, 'Open a server and use Properties > Manager settings to set its Java version, auto-start and more.'));
  modal('Servers added', h('div', { style: 'display:grid;gap:6px;max-height:60vh;overflow:auto' }, ...kids), [{ label: 'Done', cls: 'primary', run: (c) => c() }]);
}

function scanDialog() {
  let roots = [], jobId = null, timer = null, startedAt = 0, running = false;
  let mode = 'quick';
  let found = [];
  const selected = new Set();
  const rootsBox = h('div', { class: 'dim', style: 'display:grid;gap:4px' });
  const results = h('div');
  const status = h('div', { class: 'dim' });
  const setupBox = h('div', { style: 'display:grid;gap:10px' });
  const rconI = h('input', { type: 'checkbox', checked: true });
  const rconRow = h('label', { style: 'font-size:13px' }, rconI, ' Enable the remote console (RCON) on servers that do not have it, so the manager can run commands and stop them cleanly');

  const drawRoots = () => {
    rootsBox.replaceChildren(...roots.map((r, i) => h('div', { class: 'row' }, h('span', { class: 'mono', style: 'font-size:12px;word-break:break-all;flex:1' }, r),
      h('button', { class: 'btn sm', onclick: () => { roots.splice(i, 1); drawRoots(); } }, 'Remove'))),
      h('button', { class: 'btn sm', style: 'justify-self:start', onclick: async () => { const p = await pickFolder({ title: 'Folder to search in', button: 'Search this folder' }); if (p) { roots.push(p); drawRoots(); } } }, icon('plus'), ' Add folder'));
  };
  const drawMode = () => {
    setupBox.replaceChildren(...[
      h('div', { class: 'sub' },
        h('button', { class: 'chip' + (mode === 'quick' ? ' on' : ''), disabled: running, onclick: () => { mode = 'quick'; drawMode(); } }, 'Quick search'),
        h('button', { class: 'chip' + (mode === 'deep' ? ' on' : ''), disabled: running, onclick: () => { mode = 'deep'; drawMode(); } }, 'Choose folders')),
      mode === 'quick'
        ? h('div', { class: 'dim', style: 'font-size:12.5px' }, 'Looks in the usual places (Desktop, Documents, Downloads, CurseForge, ATLauncher, FTB, Technic, Prism), then through every drive, going deeper inside folders named like MC, Minecraft or Server. Disconnected drives are skipped.')
        : h('div', { class: 'dim', style: 'font-size:12.5px' }, 'Searches only the folders you pick, much deeper (can take several minutes for a whole drive).'),
      mode === 'deep' ? rootsBox : null,
      rconRow].filter(Boolean));
    if (mode === 'deep') drawRoots();
  };

  const looksBackup = (f) => /(^|[\s._\-()\[\]])(bak|bkp|backup|backups|old|copy|archive|archived|test|unused)($|[\s._\-()\[\]])/i.test(f.name + ' ' + f.dir.split(/[\\/]/).pop());
  const selectedDirs = () => found.filter(f => !f.managed && selected.has(f.dir)).map(f => f.dir);
  const row = (f) => {
    const cb = h('input', { type: 'checkbox', checked: !f.managed && selected.has(f.dir), disabled: f.managed, onchange: (e) => { if (e.target.checked) selected.add(f.dir); else selected.delete(f.dir); refreshButtons(); } });
    const addBtn = h('button', { class: 'btn sm', disabled: f.managed, onclick: () => importDialog(f, () => { f.managed = true; selected.delete(f.dir); draw(); }) }, f.managed ? 'Already added' : 'Add with options...');
    return h('div', { class: 'found' },
      h('label', { style: 'display:flex;gap:8px;align-items:center;flex-wrap:wrap' }, cb, h('b', null, f.name), h('span', { class: 'badge' }, (KIND_LABEL[f.kind] || 'Server') + (f.mc ? ' ' + f.mc : '')),
        f.hint ? h('span', { class: 'badge be' }, f.hint) : null, looksBackup(f) ? h('span', { class: 'badge be' }, 'looks like a backup') : null, f.firstRun ? h('span', { class: 'badge' }, 'not started yet') : null),
      h('div', { class: 'p' }, f.dir), h('div', { class: 'row' }, h('span', { class: 'dim' }, `port ${f.port} · ${f.mods} mods`), addBtn));
  };
  const draw = () => {
    results.replaceChildren(...found.map(row));
    refreshButtons();
  };

  let m;
  const refreshButtons = () => {
    if (!m) return;
    const n = selectedDirs().length;
    const [stopB, stopAddB, addB, startB] = m.btns;
    stopB.disabled = !running;
    stopAddB.disabled = !running || n === 0;
    stopAddB.textContent = `Stop search and add ${n || ''} selected`.replace('  ', ' ');
    addB.disabled = n === 0;
    addB.textContent = `Add ${n || ''} selected`.replace('  ', ' ');
    startB.disabled = running;
    startB.textContent = jobId ? 'Search again' : 'Start search';
  };

  const finish = () => { running = false; clearInterval(timer); drawMode(); refreshButtons(); };
  const poll = async () => {
    try {
      const j = await api('GET', `/api/jobs/${jobId}?from=0`);
      const list = j.result || [];
      for (const f of list) if (!found.some(x => x.dir === f.dir)) { found.push(f); if (!f.managed && !looksBackup(f)) selected.add(f.dir); }
      draw();
      const secs = Math.round((Date.now() - startedAt) / 1000);
      const last = j.lines[j.lines.length - 1] || 'Searching...';
      if (j.state === 'running') status.textContent = `Searching... ${secs}s · ${found.length} found so far · ${last}`;
      else {
        status.textContent = j.state === 'done' ? (found.length ? `Finished in ${secs}s: ${found.length} server(s) found. Tick the ones to add.` : `Finished in ${secs}s: no servers found.`)
          : j.state === 'cancelled' ? `Stopped after ${secs}s: ${found.length} found.` : 'The search failed: ' + last;
        finish();
      }
    } catch (e) { status.textContent = e.message; finish(); }
  };
  const start = async () => {
    if (mode === 'deep' && !roots.length) { toast('Add at least one folder to search', true); return; }
    try {
      found = [];
      selected.clear();
      draw();
      const r = await api('POST', '/api/import/scan', { mode, roots: mode === 'deep' ? roots : [] });
      jobId = r.job;
      running = true;
      startedAt = Date.now();
      status.textContent = 'Searching...';
      drawMode();
      refreshButtons();
      timer = setInterval(poll, 700);
      poll();
    } catch (e) { toast(e.message, true); }
  };
  const stopSearch = async () => {
    try { await api('POST', `/api/jobs/${jobId}/cancel`, {}); } catch (e) { /* already finished */ }
    for (let i = 0; i < 20 && running; i++) { await new Promise(r => setTimeout(r, 250)); await poll(); }
  };
  const addSelected = async (stopFirst) => {
    if (stopFirst && running) await stopSearch();
    const dirs = selectedDirs();
    if (!dirs.length) return;
    const names = dirs.map(d => d.split(/[\\/]/).pop()).join(', ');
    if (!await confirmBox('Add servers', `Add ${dirs.length} server${dirs.length === 1 ? '' : 's'} to the manager: ${names}?`, 'Add')) return;
    try {
      const r = await api('POST', '/api/import/bulk', { dirs, enableRcon: rconI.checked });
      for (const a of r.added) { const f = found.find(x => x.dir === a.dir); if (f) f.managed = true; selected.delete(a.dir); }
      draw();
      S.servers = await api('GET', '/api/servers');
      renderSide();
      if (r.added.length) selectServer(r.added[0].id);
      bulkSummary(r);
    } catch (e) { toast(e.message, true); }
  };

  const pickAll = (on) => { selected.clear(); if (on) for (const f of found) if (!f.managed) selected.add(f.dir); draw(); };
  const pickRow = h('div', { class: 'row', style: 'gap:8px' }, h('button', { class: 'btn sm', onclick: () => pickAll(true) }, 'Select all'), h('button', { class: 'btn sm', onclick: () => pickAll(false) }, 'Select none'),
    h('span', { class: 'dim', style: 'font-size:12.5px' }, 'Only ticked servers are added.'));
  m = modal('Search for servers', h('div', { style: 'display:grid;gap:10px' }, setupBox, status, pickRow, results), [
    { label: 'Stop search', cls: 'danger', disabled: true, run: () => stopSearch() },
    { label: 'Stop search and add selected', cls: 'primary', disabled: true, run: () => addSelected(true) },
    { label: 'Add selected', cls: 'primary', disabled: true, run: () => addSelected(false) },
    { label: 'Start search', run: () => start() },
    { label: 'Close', run: (c) => c() },
  ], { onClose: () => clearInterval(timer) });
  drawMode();
  refreshButtons();
  start();
}

function portsSection() {
  const box = h('div', { style: 'display:grid;gap:8px' }, h('b', null, 'Ports'), h('div', { class: 'dim' }, 'Checking...'));
  const load = async () => {
    let d;
    try { d = await api('GET', '/api/ports'); } catch (e) { box.replaceChildren(h('b', null, 'Ports'), h('div', { class: 'err' }, e.message)); return; }
    const num = (v) => (v > 0 ? String(v) : '–');
    const clashPorts = new Set(d.clashes.map(c => c.port));
    const kids = [h('b', null, 'Ports')];
    if (!d.servers.length) { kids.push(h('div', { class: 'dim' }, 'No servers yet.')); box.replaceChildren(...kids); return; }
    kids.push(h('table', { class: 'tbl' }, h('tr', null, ['Server', 'Game', 'RCON', 'Query', 'Bedrock'].map(c => h('th', null, c))),
      d.servers.map(s => h('tr', null, h('td', null, s.name),
        ...[s.game, s.rcon, s.query, s.bedrock].map(v => h('td', { class: 'mono', style: clashPorts.has(v) ? 'color:var(--err);font-weight:600' : '' }, num(v)))))));
    if (!d.clashes.length) kids.push(h('div', { class: 'dim', style: 'font-size:12.5px' }, 'No clashes: every server uses its own ports. New servers and newly enabled RCON ports are always chosen from free ones.'));
    else {
      for (const c of d.clashes) kids.push(h('div', { class: 'err', style: 'font-size:13px' }, `${c.proto} port ${c.port} is used by: ${c.who.join(', ')}`));
      const hasRcon = d.clashes.some(c => c.rcon);
      kids.push(h('div', { class: 'row' },
        hasRcon ? h('button', { class: 'btn primary', onclick: async () => {
          try {
            const r = await api('POST', '/api/ports/fix', {});
            toast(r.changes.length ? 'Assigned free RCON ports: ' + r.changes.map(c => `${c.name} ${c.from} → ${c.to}`).join(', ') + '. Restart those servers to apply.' : 'Nothing to change');
            load();
          } catch (e) { toast(e.message, true); }
        } }, 'Assign free RCON ports') : null,
        h('span', { class: 'dim', style: 'font-size:12.5px' }, 'Game and Bedrock port clashes must be changed by hand (Properties tab, or the Geyser config) because players connect to them.')));
    }
    box.replaceChildren(...kids);
  };
  load();
  return box;
}

function startupSection() {
  const box = h('div', { style: 'display:grid;gap:10px' }, h('b', null, 'Network and startup'), h('div', { class: 'dim' }, 'Checking...'));
  const MODES = [
    ['off', 'Do not start automatically', 'The manager only runs when you start it.'],
    ['logon', 'When I sign in to Windows', 'Starts after sign-in. Servers wait until someone has signed in.'],
    ['boot', 'When the PC starts (before sign-in)', 'Starts 30 seconds after power-on, even if nobody signs in, so your servers are back after a reboot or power cut. Windows asks for your password once.'],
  ];
  const load = async () => {
    let st;
    try { st = await api('GET', '/api/startup'); } catch (e) { box.replaceChildren(h('b', null, 'Network and startup'), h('div', { class: 'err' }, e.message)); return; }
    const kids = [];
    if (st.firewallAvailable) {
      const fwBtn = h('button', { class: 'btn' + (st.firewall ? '' : ' primary'), onclick: async () => {
        try { const r = await api('POST', '/api/startup/apply', { mode: 'firewall' }); openJob(r.job, 'Allow other devices', '', { onDone: () => load() }); } catch (e) { toast(e.message, true); }
      } }, st.firewall ? 'Re-apply firewall rule' : 'Allow other devices (firewall)');
      kids.push(h('b', null, 'Network access'),
        h('div', { class: 'dim', style: 'font-size:12.5px' }, st.firewall ? 'Windows Firewall rule is in place: phones and other PCs on your private network can open the manager.'
          : 'Other devices may be blocked by Windows Firewall. One click adds a rule for port ' + st.port + ' on private networks (Windows asks you to approve it on the PC).'),
        h('div', { class: 'row' }, fwBtn, st.firewall ? h('span', { class: 'badge ok' }, 'allowed') : h('span', { class: 'badge' }, 'not added')));
      const gp = st.gamePorts || [];
      if (gp.length) {
        const blocked = gp.filter(x => !x.open);
        const gameBtn = h('button', { class: 'btn' + (blocked.length ? ' primary' : ''), onclick: async () => {
          try { const r = await api('POST', '/api/startup/apply', { mode: 'gameFirewall' }); openJob(r.job, 'Allow Minecraft servers', '', { onDone: () => load() }); } catch (e) { toast(e.message, true); }
        } }, blocked.length ? 'Allow Minecraft servers (firewall)' : 'Re-apply server rules');
        kids.push(h('b', { style: 'margin-top:6px' }, 'Minecraft servers'),
          h('div', { class: 'dim', style: 'font-size:12.5px' }, blocked.length
            ? 'Windows Firewall rules that depend on one Java file stop working when Java is updated or moved, and then friends on your network time out with nothing in the console. One click opens each server\'s port for good (Windows asks you to approve it on the PC).'
            : 'Every server port has its own rule, so a Java update will not cut players off.'),
          h('div', { style: 'display:flex;flex-wrap:wrap;gap:6px' }, ...gp.map(x => h('span', { class: 'badge' + (x.open ? ' ok' : '') }, x.name + ' · ' + x.port + (x.open ? ' · allowed' : ' · not added')))),
          h('div', { class: 'row' }, gameBtn));
      }
    }
    kids.push(h('b', { style: kids.length ? 'margin-top:6px' : '' }, 'Start with Windows'));
    if (!st.available) {
      kids.push(h('div', { class: 'dim' }, 'This option is available in the installed version. Run Setup.ps1 from the downloaded package, then open Settings again.'));
      box.replaceChildren(...kids);
      return;
    }
    let sel = st.mode === 'other' ? 'off' : st.mode;
    const apply = h('button', { class: 'btn primary', disabled: true }, 'Apply');
    const radios = MODES.map(([k, label, text]) => {
      const r = h('input', { type: 'radio', name: 'startupmode', checked: k === sel, onchange: () => { sel = k; apply.disabled = sel === st.mode || (sel === 'off' && st.mode === 'other'); } });
      return h('label', { style: 'display:grid;grid-template-columns:auto 1fr;gap:2px 8px;align-items:start' }, r, h('span', null, label, k === st.mode ? h('span', { class: 'badge ok' }, 'current') : null), h('span'), h('span', { class: 'dim', style: 'font-size:12.5px' }, text));
    });
    const sysI = h('input', { type: 'checkbox' });
    apply.onclick = async () => {
      try {
        const r = await api('POST', '/api/startup/apply', { mode: sel, asSystem: sel === 'boot' && sysI.checked });
        openJob(r.job, 'Startup settings', '', { onDone: () => load() });
      } catch (e) { toast(e.message, true); }
    };
    kids.push(...radios,
      h('label', { style: 'font-size:12.5px' }, sysI, ' My Windows account has no password: run the startup task as the Windows system account instead (only used for "When the PC starts"; servers then run as SYSTEM)'),
      h('div', { class: 'row' }, apply, h('span', { class: 'dim', style: 'font-size:12.5px' }, 'Applying opens a Windows approval prompt on the PC' + (st.user ? ' (account ' + st.user + ')' : '') + '.')));
    if (st.mode === 'other') kids.push(h('div', { class: 'dim' }, 'A startup task with a custom trigger exists; choose a mode above to replace it.'));
    {
      const ti = st.taskInfo || {};
      const lines = ['This manager has been running since ' + new Date(st.managerSince).toLocaleString() + '.'];
      if (ti.lastRun) lines.push('Windows last ran the startup task at ' + ti.lastRun + (ti.lastResult ? ' (result: ' + (ti.lastResult === 'running' ? 'still running, which is normal' : ti.lastResult === 'ok' ? 'success' : ti.lastResult) + ')' : '') + '.');
      else if (st.mode !== 'off') lines.push('Windows has not run the startup task yet.');
      kids.push(h('div', { class: 'dim', style: 'font-size:12.5px' }, lines.join(' ')));
    }

    kids.push(h('b', { style: 'margin-top:6px' }, 'Servers that start with the manager'));
    kids.push(...S.servers.map(s => {
      const c = h('input', { type: 'checkbox', checked: !!s.autostart, onchange: async (e) => {
        try { await api('POST', `/api/servers/${s.id}/settings`, { autostart: e.target.checked }); s.autostart = e.target.checked; toast('Saved'); } catch (ex) { toast(ex.message, true); e.target.checked = !e.target.checked; }
      } });
      return h('label', null, c, ' ', s.name);
    }));
    if (!S.servers.length) kids.push(h('div', { class: 'dim' }, 'No servers yet.'));

    if (st.legacy.length) {
      kids.push(h('b', { style: 'margin-top:6px' }, 'Old Windows tasks that start your servers'));
      kids.push(h('div', { class: 'dim', style: 'font-size:12.5px' }, 'Move a server to the manager to replace its Windows task. Set "When the PC starts" above first so the server still comes back after a reboot. The server must be stopped.'));
      for (const t of st.legacy) {
        const srv = S.servers.find(x => x.id === t.server);
        const btn = h('button', { class: 'btn sm', disabled: !srv || srv.state !== 'stopped' || t.state === 'Running', title: srv && srv.state !== 'stopped' ? 'Stop the server first' : '', onclick: async () => {
          if (!await confirmBox('Move to the manager', `Remove the Windows task "${t.name}" and let the manager start ${t.serverName} instead? The server's files are not touched.`, 'Move it', true)) return;
          try {
            const r = await api('POST', '/api/startup/takeover', { task: t.name, server: t.server });
            openJob(r.job, `Move ${t.serverName} to the manager`, t.server, { onDone: () => load() });
          } catch (e) { toast(e.message, true); }
        } }, 'Move to the manager');
        kids.push(h('div', { class: 'row', style: 'justify-content:space-between' }, h('span', null, h('b', null, t.serverName), ' — task "' + t.name + '"' + (t.trigger ? ' (' + t.trigger.toLowerCase() + ')' : '')), btn));
      }
    }
    box.replaceChildren(...kids);
  };
  load();
  return box;
}

/** Theme choice for this browser only (kept in localStorage): Windows setting (default), Dark or Light. */
function themePicker() {
  let cur = 'auto';
  try { cur = localStorage.getItem('mcm.theme') || 'auto'; } catch (e) { /* private window */ }
  const sel = h('select', { onchange: () => {
    const v = sel.value;
    try { if (v === 'auto') localStorage.removeItem('mcm.theme'); else localStorage.setItem('mcm.theme', v); } catch (e) { /* ignore */ }
    if (v === 'auto') document.documentElement.removeAttribute('data-theme'); else document.documentElement.setAttribute('data-theme', v);
  } }, [['auto', 'Windows setting (default)'], ['dark', 'Dark'], ['light', 'Light']].map(([k, l]) => h('option', { value: k }, l)));
  sel.value = cur;
  return h('div', { class: 'row' }, sel);
}

/** Popup for the "create server" starting values and the default backups folder (Settings > Set Global Defaults). */
function globalDefaultsDialog(st) {
  const D0 = { type: 'vanilla', maxMb: 4096, difficulty: 'easy', gamemode: 'survival', maxPlayers: 20, bedrock: false, planMods: true, whitelist: false, autostart: false, lanBroadcast: true };
  const cur = Object.assign({}, D0, st.createDefaults || {});
  const sel = (opts, v) => { const e = h('select', null, opts.map(([k, l]) => h('option', { value: k }, l))); e.value = String(v); return e; };
  const lab = (t, el) => h('label', { class: 'dim', style: 'display:grid;gap:2px' }, t, el);
  const chk = (t, on) => { const i = h('input', { type: 'checkbox', checked: !!on }); return { i, el: h('label', null, i, ' ' + t) }; };
  const type = sel([['vanilla', 'Vanilla (no mods)'], ['fabric', 'Fabric'], ['forge', 'Forge'], ['neoforge', 'NeoForge']], cur.type);
  const mem = sel([2048, 4096, 6144, 8192, 12288, 16384].map(m => [m, (m / 1024) + ' GB']), cur.maxMb);
  const diff = sel([['peaceful', 'Peaceful'], ['easy', 'Easy'], ['normal', 'Normal'], ['hard', 'Hard']], cur.difficulty);
  const mode = sel([['survival', 'Survival'], ['creative', 'Creative'], ['adventure', 'Adventure'], ['spectator', 'Spectator']], cur.gamemode);
  const players = h('input', { type: 'number', min: 1, max: 500, value: cur.maxPlayers });
  const bedrock = chk('Let Bedrock players join (adds Geyser and Floodgate; needs a mod loader, and Geyser has no Forge build)', cur.bedrock);
  const jmc = chk('Install JourneyMap (live map, waypoints and player radar; needs a mod loader)', cur.journeymap);
  const mods = chk('I usually add mods (open the mod picker right after creating a modded server)', cur.planMods);
  const wl = chk('Turn the whitelist on', cur.whitelist);
  const auto = chk('Start servers automatically when the manager starts', cur.autostart);
  const lan = chk('Show new servers under LAN worlds in Minecraft (same network only; some Wi-Fi blocks it)', cur.lanBroadcast !== false);
  const note = h('div', { class: 'notice hidden' });
  const say = (msg) => { note.textContent = msg; note.classList.remove('hidden'); toast(msg); };
  const NAMES = { vanilla: 'Vanilla', fabric: 'Fabric', forge: 'Forge', neoforge: 'NeoForge' };
  // the loader a Vanilla default switches to when Bedrock or JourneyMap is ticked: the one chosen last, else Fabric
  const lastLoader = ['fabric', 'forge', 'neoforge'].includes(cur.modLoader) ? cur.modLoader : 'fabric';
  /** Keeps the choices consistent and says what it changed: Bedrock and JourneyMap need a mod loader, and Geyser has no Forge build. */
  const sync = (why) => {
    const msgs = [];
    if (type.value === 'vanilla' && (bedrock.i.checked || jmc.i.checked)) {
      if (why === 'type') { bedrock.i.checked = false; jmc.i.checked = false; msgs.push('Bedrock support and JourneyMap need a mod loader, so they were turned off for Vanilla. Select Fabric, Forge or NeoForge to use them.'); }
      else {
        type.value = bedrock.i.checked && lastLoader === 'forge' ? 'neoforge' : lastLoader;
        msgs.push('Server type changed from Vanilla to ' + NAMES[type.value] + ': ' + (bedrock.i.checked ? 'Bedrock support (Geyser)' : 'JourneyMap') + ' needs a mod loader. Pick another type above if you prefer.');
      }
    }
    if (bedrock.i.checked && type.value === 'forge') {
      if (why === 'type') { bedrock.i.checked = false; msgs.push('Bedrock support (Geyser) has no Forge build, so it was turned off for Forge. Select NeoForge or Fabric to use Bedrock.'); }
      else { type.value = 'neoforge'; msgs.push('Server type changed from Forge to NeoForge: Bedrock support (Geyser) has no Forge build.'); }
    }
    if (msgs.length && why !== 'init') say(msgs.join(' '));
    else note.classList.add('hidden'); // a compatible choice clears the warning
  };
  type.addEventListener('change', () => sync('type'));
  bedrock.i.addEventListener('change', () => sync('tick'));
  jmc.i.addEventListener('change', () => sync('tick'));
  sync('init');
  const restartForm = autoRestartForm(cur.autoRestart);
  let bk = st.backupsRoot || '';
  const bkIn = h('input', { value: bk, spellcheck: 'false', style: 'flex:1', oninput: () => { bk = bkIn.value.trim(); } });
  const bkBrowse = h('button', { class: 'btn', type: 'button', onclick: async () => { const p = await pickFolder({ title: 'Default folder for backups', start: bk, writable: true }); if (p) { bk = p; bkIn.value = p; } } }, icon('folder'), ' Browse');
  const body = h('div', { style: 'display:grid;gap:10px' },
    h('div', { class: 'dim' }, 'These only pre-fill the Add server wizard. Servers that already exist are not changed.'),
    h('div', { class: 'row' }, lab('Server type', type), lab('Memory (max)', mem)),
    h('div', { class: 'row' }, lab('Difficulty', diff), lab('Game mode', mode), lab('Max players', players)),
    bedrock.el, jmc.el, note, mods.el, wl.el, auto.el, lan.el,
    h('div', { style: 'display:grid;gap:6px' }, h('b', null, 'Automatic restart for new servers'), restartForm.el),
    h('div', { style: 'display:grid;gap:4px' }, h('b', null, 'Default folder for backups'),
      h('div', { class: 'dim' }, 'Servers that have no backup folder of their own keep their backups here, one folder per server. Existing backups stay where they are.'),
      h('div', { class: 'row', style: 'flex-wrap:nowrap' }, bkIn, bkBrowse)));
  // changing anything else also clears the notice
  body.addEventListener('change', (e) => { if (![type, bedrock.i, jmc.i].includes(e.target)) note.classList.add('hidden'); });
  const collect = () => ({ type: type.value, maxMb: +mem.value, difficulty: diff.value, gamemode: mode.value, maxPlayers: Math.max(1, Math.min(500, +players.value || 20)),
    autoRestart: restartForm.values(), modLoader: type.value === 'vanilla' ? lastLoader : type.value, bedrock: bedrock.i.checked, journeymap: jmc.i.checked, planMods: mods.i.checked, whitelist: wl.i.checked, autostart: auto.i.checked, lanBroadcast: lan.i.checked });
  modal('Global defaults', body, [
    { label: 'Cancel', run: (c) => c() },
    { label: 'Reset to MC.SESH defaults', run: async (c) => {
      try { await api('POST', '/api/settings', { createDefaults: {} }); st.createDefaults = {}; toast('Defaults reset'); c(); } catch (e) { toast(e.message, true); }
    } },
    { label: 'Save', cls: 'primary', run: async (c) => {
      try {
        const body2 = { createDefaults: collect() };
        if (bk && bk !== st.backupsRoot) body2.backupsRoot = bk;
        await api('POST', '/api/settings', body2);
        st.createDefaults = body2.createDefaults;
        if (body2.backupsRoot) st.backupsRoot = bk;
        toast('Global defaults saved');
        c();
      } catch (e) { toast(e.message, true); }
    } },
  ]);
}

async function settingsDialog() {
  let st;
  try { st = await api('GET', '/api/settings'); } catch (e) { toast(e.message, true); return; }
  let root = st.serversRoot;
  const rootIn = h('input', { value: root, spellcheck: 'false', style: 'flex:1', oninput: () => { root = rootIn.value.trim(); } });
  const browse = h('button', { class: 'btn', type: 'button', onclick: async () => { const p = await pickFolder({ title: 'Default folder for new servers', start: root, writable: true }); if (p) { root = p; rootIn.value = p; } } }, icon('folder'), ' Browse');
  const body = h('div', { style: 'display:grid;gap:14px' },
    h('div', { style: 'display:grid;gap:4px' }, h('b', null, 'Folder for new servers'),
      h('div', { class: 'dim' }, 'New servers are created here unless you choose another place in the Add server wizard.'),
      h('div', { class: 'row', style: 'flex-wrap:nowrap' }, rootIn, browse)),
    h('div', { style: 'display:grid;gap:4px' }, h('b', null, 'Appearance'),
      h('div', { class: 'dim' }, 'Dark or light for this browser or phone. "Windows setting" follows the device.'),
      themePicker()),
    h('div', { style: 'display:grid;gap:4px' }, h('b', null, 'Global defaults'),
      h('div', { class: 'dim' }, 'The starting choices in the Add server wizard (server type, memory, difficulty, Bedrock players and so on) and the default folder for backups. You can still change them each time.'),
      h('div', { class: 'row' }, h('button', { class: 'btn', onclick: () => globalDefaultsDialog(st) }, 'Set Global Defaults'))),
    portsSection(),
    startupSection(),
    h('div', { style: 'display:grid;gap:4px' }, h('b', null, 'Refresh and restart'),
      h('div', { class: 'dim' }, 'If a server shows the wrong state or the wrong players, Refresh re-reads every server from the PC (the circular arrow at the top right does the same). Restart manager restarts this program only: your Minecraft servers keep running and nobody is kicked.'),
      h('div', { class: 'row' }, h('button', { class: 'btn', onclick: () => refreshAll() }, icon('refresh'), ' Refresh server info'),
        h('button', { class: 'btn danger', onclick: () => restartManagerFlow() }, 'Restart manager'))),
    curseKeySection(),
    h('div', { style: 'display:grid;gap:4px' }, h('b', null, 'Find servers'), h('div', { class: 'dim' }, 'Search this PC for servers made by CurseForge, ATLauncher, FTB and others, or add one by its folder.'),
      h('div', { class: 'row' }, h('button', { class: 'btn', onclick: () => scanDialog() }, icon('search'), ' Search this PC'), h('button', { class: 'btn', onclick: () => addExistingFlow() }, icon('folder'), ' Add by folder'))),
    h('div', { style: 'display:grid;gap:4px' }, h('b', null, 'Java runtimes found'),
      h('div', { class: 'dim' }, st.bundledJava ? 'This app includes its own Java. Servers use the best match below unless you pick one per server.' : 'Servers use the best match below unless you pick one per server.'),
      h('div', { class: 'mono', style: 'font-size:12px;word-break:break-all' }, st.runtimes.length ? st.runtimes.map(r => h('div', null, r.label)) : 'None found - install Java 25 for current Minecraft versions.')),
    h('div', { style: 'display:grid;gap:4px' }, h('b', null, 'This app'), h('div', { class: 'dim' }, 'Version ' + st.version),
      h('div', { class: 'mono dim', style: 'font-size:12px;word-break:break-all' }, 'App folder: ' + st.home), h('div', { class: 'mono dim', style: 'font-size:12px;word-break:break-all' }, 'Data folder: ' + st.data),
      h('div', { class: 'mono dim', style: 'font-size:12px;word-break:break-all' }, 'Address: ' + (st.urls.join('  ') || 'http://localhost:' + st.port))));
  modal('Settings', body, [
    { label: 'Close', run: (c) => c() },
    { label: 'Save', cls: 'primary', run: async (c) => {
      try { await api('POST', '/api/settings', { serversRoot: root, configured: true }); toast('Settings saved'); c(); } catch (e) { toast(e.message, true); }
    } },
  ]);
}

async function firstRunCheck() {
  let st;
  try { st = await api('GET', '/api/settings'); } catch (e) { return; }
  if (st.configured) return;
  let root = st.serversRoot;
  const rootIn = h('input', { value: root, spellcheck: 'false', style: 'flex:1', oninput: () => { root = rootIn.value.trim(); } });
  const browse = h('button', { class: 'btn', type: 'button', onclick: async () => { const p = await pickFolder({ title: 'Where should your servers live?', start: root, writable: true }); if (p) { root = p; rootIn.value = p; } } }, icon('folder'), ' Browse');
  const searchI = h('input', { type: 'checkbox', checked: true });
  let su = null;
  try { su = await api('GET', '/api/startup'); } catch (e) { /* optional */ }
  const showFw = !!(su && su.firewallAvailable && !su.firewall);
  const fwI = h('input', { type: 'checkbox', checked: true });
  modal('Welcome to MC.SESH', h('div', { style: 'display:grid;gap:12px' },
    h('div', null, 'Where should new servers be stored? You can still pick a different place each time you create one.'),
    h('div', { class: 'row', style: 'flex-wrap:nowrap' }, rootIn, browse),
    h('label', null, searchI, ' Also search this PC for servers I already have'),
    showFw ? h('label', null, fwI, ' Let phones and other PCs on my home network open the manager (adds a Windows Firewall rule; Windows asks you to approve it)') : null), [
    { label: 'Skip', run: async (c) => { try { await api('POST', '/api/settings', { configured: true }); } catch (e) { /* ignore */ } c(); } },
    { label: 'Continue', cls: 'primary', run: async (c) => {
      try {
        await api('POST', '/api/settings', { serversRoot: root, configured: true });
        c();
        if (showFw && fwI.checked) {
          try { const r = await api('POST', '/api/startup/apply', { mode: 'firewall' }); openJob(r.job, 'Allow other devices', '', { onDone: () => { if (searchI.checked) scanDialog(); } }); return; } catch (e) { toast(e.message, true); }
        }
        if (searchI.checked) scanDialog();
      } catch (e) { toast(e.message, true); }
    } },
  ]);
}
