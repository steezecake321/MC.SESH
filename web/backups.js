// ---------- backups, restore, and adding a mod loader to a vanilla server ----------
const BK_MB = (n) => n >= 1024 ? (n / 1024).toFixed(1) + ' GB' : (n < 1 ? '<1' : Math.round(n)) + ' MB';
const BK_BYTES = (b) => BK_MB(b / 1048576);
const BK_WHEN = (t) => new Date(t).toLocaleString([], { dateStyle: 'medium', timeStyle: 'short' });
const BK_REASON = { 'before-restore': 'Safety copy made before a restore', 'before-conversion': 'Made before adding a mod loader', 'before-delete': 'Made before deleting the server', 'before-update': 'Made before an update', auto: 'Automatic backup' };

/** Take a backup of the server now: the whole server (default) or only the world. */
async function backupDialog(s) {
  let d;
  try { d = await api('GET', `/api/servers/${s.id}/backups`); } catch (e) { toast(e.message, true); return; }
  const full = h('input', { type: 'radio', name: 'bkscope', checked: true });
  const world = h('input', { type: 'radio', name: 'bkscope' });
  const note = h('input', { placeholder: 'Optional note, e.g. before the big build', maxlength: 200, style: 'width:100%', autocomplete: 'off' });
  const blocked = d.running && !d.rcon;
  const body = h('div', { style: 'display:grid;gap:10px;width:100%;min-width:0' },
    h('div', { style: 'display:grid;gap:4px' },
      h('label', null, full, ' Whole server ', h('span', { class: 'dim' }, `(about ${BK_MB(d.fullMb)})`)),
      h('div', { class: 'dim', style: 'font-size:12.5px;margin-left:24px' }, 'World, mods, configs, settings, whitelist and ops. Restoring it puts everything back exactly as it is now.'),
      h('label', null, world, ' World only ', h('span', { class: 'dim' }, `(about ${BK_MB(d.worldMb)})`)),
      h('div', { class: 'dim', style: 'font-size:12.5px;margin-left:24px' }, 'Just the world folders. Smaller and faster; settings and mods are not included.')),
    h('label', { class: 'dim' }, 'Note', note),
    d.running && d.rcon ? h('div', { class: 'dim' }, `${s.name} is running. World saving is paused for a moment while the files are copied; players stay connected.`) : null,
    blocked ? h('div', { class: 'notice' }, `${s.name} is running and RCON is off, so the manager cannot pause saving for a safe copy. Stop the server first, or turn RCON on in Properties and restart it.`) : null,
    h('div', { class: 'dim', style: 'font-size:12.5px' }, `Saved as a .zip in ${d.dir} (${BK_MB(d.freeMb)} free on that drive).`));
  modal(`Back up ${s.name}`, body, [
    { label: 'Cancel', run: (c) => c() },
    { label: 'Back up now', cls: 'primary', disabled: blocked, run: async (c) => {
      try {
        const r = await api('POST', `/api/servers/${s.id}/backups/create`, { scope: world.checked ? 'world' : 'full', note: note.value.trim() });
        c();
        openJob(r.job, `Backup ${s.name}`, s.id);
      } catch (e) { toast(e.message, true); }
    } },
  ]);
}

/** Lists the server's backups and restores one (server must be stopped). Administrators can also restore a zip from elsewhere and change retention. */
async function restoreDialog(s) {
  const wrap = h('div', { style: 'display:grid;gap:12px;width:100%;min-width:0;max-height:70vh;overflow:auto;align-content:start;overflow-wrap:anywhere' });
  wrap.append(h('div', { class: 'dim' }, 'Loading backups...'));
  let m;
  const stopped = () => (sv() ? sv().state : s.state) === 'stopped';
  const draw = async () => {
    let d;
    try { d = await api('GET', `/api/servers/${s.id}/backups`); } catch (e) { wrap.replaceChildren(h('div', { class: 'err' }, e.message)); return; }
    const up = !stopped();
    const nodes = [];
    nodes.push(h('div', { class: 'dim' }, `Backups of ${s.name} are kept in ${d.dir} (${BK_MB(d.freeMb)} free). Before any restore the current state is saved as a new backup, so a restore can always be undone.`));
    if (up) nodes.push(h('div', { class: 'notice' }, `${s.name} is ${sv() ? sv().state : s.state}. Stop it before restoring (you can still make backups).`));
    nodes.push(h('div', { class: 'row' }, h('button', { class: 'btn primary', onclick: () => { m.close(); backupDialog(s); } }, 'Back up now')));
    nodes.push(autoBackupBox(s, d, draw));
    if (!d.backups.length) nodes.push(h('div', { class: 'box dim' }, 'No backups yet. Press “Back up now” to make the first one.'));
    else {
      const rows = d.backups.map(b => {
        const label = b.reason === 'manual' || !BK_REASON[b.reason] ? (b.note || 'Manual backup') : BK_REASON[b.reason] + (b.note && b.reason === 'before-conversion' ? '' : '');
        const warnMc = b.mc && d.mc && b.mc !== d.mc;
        return h('div', { class: 'found', style: 'display:flex;gap:8px 12px;align-items:flex-start;flex-wrap:wrap;justify-content:space-between' },
          h('div', { style: 'flex:1;min-width:210px' },
            h('div', null, h('b', null, BK_WHEN(b.created)), ' ', h('span', { class: 'badge' }, b.scope === 'world' ? 'World' : 'Whole server'), ' ', h('span', { class: 'dim' }, BK_BYTES(b.size)),
              b.partial ? h('span', { class: 'badge be', title: 'Some files were locked and are missing from this backup' }, ' incomplete') : null),
            h('div', null, label),
            h('div', { class: 'dim', style: 'font-size:12px' }, [b.mc ? 'Minecraft ' + b.mc : '', b.kind || '', b.by ? 'by ' + b.by : ''].filter(Boolean).join(' · '))),
          h('div', { class: 'row', style: 'gap:6px;flex-wrap:nowrap' },
            h('button', { class: 'btn sm', disabled: up, title: up ? 'Stop the server first' : '', onclick: async () => {
              const msg = h('div', { style: 'display:grid;gap:8px' },
                h('div', null, b.scope === 'world' ? 'The current world folders are replaced with this backup.' : 'Everything in the server folder (world, mods, configs, settings) is replaced with this backup. Logs are kept.'),
                warnMc ? h('div', { class: 'notice' }, `This backup is from Minecraft ${b.mc} and the server now runs ${d.mc}. A world saved by a newer Minecraft cannot be opened by an older one.`) : null,
                h('div', { class: 'dim' }, 'A safety copy of what is there now is saved first.'));
              if (!await confirmDlg(`Restore ${s.name} to ${BK_WHEN(b.created)}?`, msg, 'Restore')) return;
              try {
                const r = await api('POST', `/api/servers/${s.id}/backups/restore`, { file: b.file });
                m.close();
                openJob(r.job, `Restore ${s.name}`, s.id, { onDone: () => { if (S.tab) renderPane(); } });
              } catch (e) { toast(e.message, true); }
            } }, 'Restore'),
            isAdmin() ? h('button', { class: 'btn sm danger', title: 'Delete this backup file permanently', onclick: async () => {
              if (!await confirmBox('Delete backup', `Delete the backup from ${BK_WHEN(b.created)} (${BK_BYTES(b.size)}) permanently?`, 'Delete', true)) return;
              try { await api('POST', `/api/servers/${s.id}/backups/delete`, { file: b.file }); toast('Deleted'); draw(); } catch (e) { toast(e.message, true); }
            } }, 'Delete') : null));
      });
      nodes.push(h('div', { style: 'display:grid;gap:8px' }, rows));
    }
    if (isAdmin()) {
      const path = h('input', { placeholder: 'D:\\Backups\\my-world.zip', spellcheck: 'false', style: 'flex:1;min-width:200px' });
      const keep = h('input', { type: 'number', min: 0, max: 1000, value: d.keep, style: 'width:80px' });
      const dir = h('input', { value: d.dir, spellcheck: 'false', style: 'flex:1;min-width:200px' });
      nodes.push(h('div', { class: 'box' }, h('h3', null, 'Restore a zip from somewhere else'),
        h('div', { class: 'dim', style: 'font-size:12.5px;margin-bottom:6px' }, 'A backup made by this manager (even on another PC), or a zip of a world folder. The full path of the file on this PC:'),
        h('div', { class: 'row', style: 'gap:8px' }, path, h('button', { class: 'btn', disabled: up, onclick: async () => {
          if (!path.value.trim()) return;
          if (!await confirmBox('Restore from this file?', `Replace the current files of ${s.name} with ${path.value.trim()}? A safety copy of the current state is saved first.`, 'Restore')) return;
          try {
            const r = await api('POST', `/api/servers/${s.id}/backups/restore`, { path: path.value.trim() });
            m.close();
            openJob(r.job, `Restore ${s.name}`, s.id, { onDone: () => { if (S.tab) renderPane(); } });
          } catch (e) { toast(e.message, true); }
        } }, 'Restore'))));
      nodes.push(h('div', { class: 'box' }, h('h3', null, 'Backup settings'),
        h('div', { class: 'row', style: 'gap:8px;flex-wrap:wrap' }, h('label', null, 'Keep only the newest ', keep, ' backups'), h('span', { class: 'dim' }, '(0 keeps them all; older ones are deleted automatically)')),
        h('div', { class: 'row', style: 'gap:8px;margin-top:8px;flex-wrap:wrap' }, h('span', null, 'Folder'), dir,
          h('button', { class: 'btn', type: 'button', onclick: async () => { const p = await pickFolder({ title: 'Where should backups of this server be saved?', start: dir.value }); if (p) dir.value = p; } }, icon('folder'), ' Browse')),
        h('div', { class: 'row', style: 'margin-top:8px' }, h('button', { class: 'btn primary', onclick: async () => {
          try { await api('POST', `/api/servers/${s.id}/backups/settings`, { keep: Number(keep.value) || 0, dir: dir.value.trim() }); toast('Saved'); draw(); } catch (e) { toast(e.message, true); }
        } }, 'Save settings'))));
    }
    wrap.replaceChildren(...nodes);
  };
  m = modal(`Restore ${s.name}`, wrap, [{ label: 'Close', cls: 'primary', run: (c) => c() }], { wide: true });
  draw();
}

/** The backup manager: backups of every server in one place (list, restore, new server from a backup, verify, download, copy, pin, notes, delete),
 *  backing up one or many servers at once, and per-server schedules. */
async function backupManager(startTab) {
  const wrap = h('div', { style: 'display:grid;gap:12px;width:100%;min-width:0;max-height:74vh;overflow:auto;align-content:start;overflow-wrap:anywhere' });
  const tabsBar = h('div', { class: 'sub' });
  const holder = h('div', { style: 'display:grid;gap:12px;min-width:0' });
  wrap.append(tabsBar, holder);
  const m = modal('Backup manager', wrap, [{ label: 'Close', cls: 'primary', run: (c) => c() }], { wide: true });
  let tab = startTab || 'backups';
  const view = {};
  const showTab = (t) => {
    tab = t;
    tabsBar.replaceChildren(...[['backups', 'Backups'], ['new', 'Back up now'], ['schedules', 'Schedules']].map(([k, l]) => h('button', { class: 'chip' + (tab === k ? ' on' : ''), onclick: () => showTab(k) }, l)));
    holder.replaceChildren(h('div', { class: 'dim' }, 'Loading...'));
    view[t]();
  };
  const section = (title, hint, ...kids) => h('div', { class: 'box', style: 'display:grid;gap:8px;border-width:2px' }, h('div', null, h('b', null, title), hint ? h('span', { class: 'dim', style: 'font-size:12.5px' }, '  ' + hint) : null), ...kids);
  const srvOf = (id) => S.servers.find(x => x.id === id);

  // =============================== Backups ===============================
  const sel = new Set();
  const f = { server: '', scope: '', q: '', sort: 'new' };
  let data = null;
  const key = (b) => b.server + '/' + b.file;
  const label = (b) => b.reason === 'manual' || !BK_REASON[b.reason] ? (b.note || 'Manual backup') : BK_REASON[b.reason] + (b.note ? ' - ' + b.note : '');
  const visible = () => {
    let l = data.backups.filter(b => (!f.server || b.server === f.server) && (!f.scope || b.scope === f.scope)
      && (!f.q || (b.serverName + ' ' + label(b) + ' ' + (b.note || '') + ' ' + (b.mc || '')).toLowerCase().includes(f.q)));
    if (f.sort === 'old') l = l.slice().reverse();
    else if (f.sort === 'big') l = l.slice().sort((a, b) => b.size - a.size);
    return l;
  };
  const ACTIONS = [
    ['restore', 'Restore this backup', 1], ['restoreNew', 'Make a new server from it', 1], ['verify', 'Verify (check it is not damaged)', 0], ['download', 'Download', 0],
    ['pin', 'Pin (never delete automatically)', 0], ['unpin', 'Unpin', 0], ['note', 'Edit note', 1], ['copy', 'Copy to another folder or drive', 0], ['delete', 'Delete', 0],
  ];
  const doAction = async (act, items, redraw) => {
    const one = items[0];
    const refs = items.map(b => ({ server: b.server, file: b.file }));
    try {
      if (act === 'restore') {
        const srv = srvOf(one.server);
        if (!srv || srv.state !== 'stopped') { toast(`Stop ${one.serverName} first, then restore.`, true); return; }
        const msg = h('div', { style: 'display:grid;gap:8px' }, h('div', null, one.scope === 'world' ? 'The current world folders are replaced with this backup.' : 'Everything in the server folder (world, mods, configs, settings) is replaced with this backup. Logs are kept.'),
          srv.mc && one.mc && srv.mc !== one.mc ? h('div', { class: 'notice' }, `This backup is from Minecraft ${one.mc} and the server now runs ${srv.mc}. A world saved by a newer Minecraft cannot be opened by an older one.`) : null,
          h('div', { class: 'dim' }, 'A safety copy of what is there now is saved first.'));
        if (!await confirmDlg(`Restore ${one.serverName} to ${BK_WHEN(one.created)}?`, msg, 'Restore')) return;
        const r = await api('POST', `/api/servers/${one.server}/backups/restore`, { file: one.file });
        m.close(); openJob(r.job, `Restore ${one.serverName}`, one.server, { onDone: () => refreshAll() });
      } else if (act === 'restoreNew') {
        if (one.scope !== 'full') { toast('Only a whole-server backup can become a new server.', true); return; }
        const name = h('input', { value: one.serverName.replace(/ \(removed\)$/, '') + ' copy', style: 'width:100%', spellcheck: 'false' });
        const ok = await new Promise(res => { let d = false; const fin = (v) => { if (!d) { d = true; res(v); } };
          modal('Make a new server from this backup', h('div', { style: 'display:grid;gap:8px' },
            h('div', null, `Unpacks the whole-server backup from ${BK_WHEN(one.created)} into a new folder next to the original and adds it to the list. It gets its own ports and is not started, so the original is not touched.`),
            h('label', null, 'Name of the new server', name)),
          [{ label: 'Cancel', run: (c) => { fin(false); c(); } }, { label: 'Create it', cls: 'primary', run: (c) => { fin(true); c(); } }], { onClose: () => fin(false) }); });
        if (!ok) return;
        const r = await api('POST', '/api/backups/clone', { server: one.server, file: one.file, name: name.value.trim() });
        openJob(r.job, 'New server from backup', '', { onDone: () => refreshAll() });
      } else if (act === 'verify') {
        for (const b of items.slice(0, 1)) { const r = await api('POST', '/api/backups/verify', { server: b.server, file: b.file }); openJob(r.job, 'Check backup', ''); }
        if (items.length > 1) toast('Checking the first one now; run Verify on the others one after another (each reads a whole zip).');
      } else if (act === 'download') {
        if (items.length > 5 && !await confirmBox('Download', `Download ${items.length} files? Your browser may ask to allow multiple downloads.`, 'Download')) return;
        items.forEach((b, i) => setTimeout(() => { const a = document.createElement('a'); a.href = `/api/backups/download?server=${encodeURIComponent(b.server)}&file=${encodeURIComponent(b.file)}`; document.body.append(a); a.click(); a.remove(); }, i * 900));
      } else if (act === 'pin' || act === 'unpin') {
        for (const b of items) { await api('POST', '/api/backups/lock', { server: b.server, file: b.file, locked: act === 'pin' }); b.locked = act === 'pin'; }
        toast(act === 'pin' ? 'Pinned' : 'Unpinned'); redraw();
      } else if (act === 'note') {
        const note = h('input', { value: one.reason === 'manual' ? (one.note || '') : (one.note || ''), maxlength: 200, style: 'width:100%' });
        const ok = await new Promise(res => { let d = false; const fin = (v) => { if (!d) { d = true; res(v); } };
          modal('Edit note', h('label', null, 'Note', note), [{ label: 'Cancel', run: (c) => { fin(false); c(); } }, { label: 'Save', cls: 'primary', run: (c) => { fin(true); c(); } }], { onClose: () => fin(false) }); });
        if (!ok) return;
        await api('POST', '/api/backups/note', { server: one.server, file: one.file, note: note.value }); one.note = note.value; toast('Saved'); redraw(true);
      } else if (act === 'copy') {
        if (!isAdmin()) { toast('Only administrators can copy backups to other folders.', true); return; }
        const p = await pickFolder({ title: 'Copy the selected backups to which folder?', start: '' });
        if (!p) return;
        const r = await api('POST', '/api/backups/copy', { items: refs, dir: p });
        openJob(r.job, 'Copy backups', '');
      } else if (act === 'delete') {
        const mb = items.reduce((n, b) => n + b.size, 0) / 1048576;
        const pinned = items.filter(b => b.locked).length;
        if (!await confirmBox('Delete backups', `Delete ${items.length} backup${items.length === 1 ? '' : 's'} (${BK_MB(mb)}) permanently?` + (pinned ? ` ${pinned} of them ${pinned === 1 ? 'is' : 'are'} pinned and will be skipped; unpin first to delete ${pinned === 1 ? 'it' : 'them'}.` : ''), 'Delete', true)) return;
        const r = await api('POST', '/api/backups/delete-many', { items: refs, includePinned: false });
        toast(`Deleted ${r.deleted} (freed ${BK_MB(r.freedMb)})` + (r.skipped.length ? `, skipped ${r.skipped.length}` : ''));
        items.forEach(b => sel.delete(key(b))); redraw(true);
      }
    } catch (e) { toast(e.message, true); }
  };
  const actionPicker = (items, multi) => {
    const opts = ACTIONS.filter(([k, , singleOnly]) => !(singleOnly && multi)).filter(([k]) => !(k === 'pin' && !multi && items[0].locked) && !(k === 'unpin' && !multi && !items[0].locked))
      .filter(([k]) => !(k === 'restoreNew' && !multi && items[0].scope !== 'full') && !(k === 'restore' && !multi && items[0].orphan) && !((k === 'copy' || k === 'restoreNew') && !isAdmin()) && !(k === 'download' && !isAdmin()));
    const dd = h('select', { 'aria-label': 'Action' }, h('option', { value: '' }, 'Action...'), opts.map(([k, l]) => h('option', { value: k }, l)));
    return dd;
  };
  const rowFor = (b, redraw) => {
    const dd = actionPicker([b], false);
    const go = h('button', { class: 'btn sm primary', onclick: () => { if (!dd.value) { toast('Choose an action first.'); return; } doAction(dd.value, [b], redraw).then(() => { dd.value = ''; }); } }, 'Go!');
    const cb = h('input', { type: 'checkbox', checked: sel.has(key(b)), onchange: (e) => { if (e.target.checked) sel.add(key(b)); else sel.delete(key(b)); redraw(); } });
    return h('div', { class: 'found', style: 'display:flex;gap:8px 12px;align-items:center;flex-wrap:wrap;justify-content:space-between' },
      h('div', { style: 'display:flex;gap:10px;flex:1;min-width:230px;align-items:flex-start' }, cb,
        h('div', null,
          h('div', null, h('b', null, b.serverName), ' ', h('span', { class: 'badge' }, b.scope === 'world' ? 'World' : 'Whole server'), ' ', h('span', { class: 'dim' }, BK_BYTES(b.size)),
            b.locked ? h('span', { class: 'badge ok', title: 'Pinned: never deleted automatically' }, ' pinned') : null,
            b.partial ? h('span', { class: 'badge be', title: 'Some files were locked and are missing' }, ' incomplete') : null),
          h('div', null, BK_WHEN(b.created), ' · ', label(b)),
          h('div', { class: 'dim', style: 'font-size:12px' }, [b.mc ? 'Minecraft ' + b.mc : '', b.kind || '', b.by ? 'by ' + b.by : '', b.file].filter(Boolean).join(' · ')))),
      h('div', { class: 'row', style: 'gap:6px;flex-wrap:nowrap;align-items:center' }, dd, go));
  };
  view.backups = async () => {
    const draw = async (reload) => {
      if (reload || !data) { try { data = await api('GET', '/api/backups/all'); } catch (e) { holder.replaceChildren(h('div', { class: 'err' }, e.message)); return; } }
      const all = data.backups, list = visible();
      const servers = [...new Map(all.map(b => [b.server, b.serverName])).entries()];
      const total = all.reduce((n, b) => n + b.size, 0);
      const orphans = all.filter(b => b.orphan);
      const nodes = [];
      nodes.push(h('div', { class: 'dim' }, `${all.length} backup${all.length === 1 ? '' : 's'} using ${BK_BYTES(total)}` + (data.drives.length ? '. Free space: ' + [...new Map(data.drives.map(d => [d.dir.slice(0, 2).toUpperCase(), d])).values()].map(d => `${BK_MB(d.freeMb)} on drive ${d.dir.slice(0, 2).toUpperCase()}`).join('; ') : '') + '.'));
      if (orphans.length) nodes.push(h('div', { class: 'notice' }, `${orphans.length} backup${orphans.length === 1 ? ' belongs' : 's belong'} to servers that are no longer managed (${BK_BYTES(orphans.reduce((n, b) => n + b.size, 0))}). They still use disk space: delete them here, or make a new server from one.`));
      // --- 1. which backups are shown
      const serverSel = h('select', { onchange: (e) => { f.server = e.target.value; draw(); } }, h('option', { value: '' }, 'All servers'), servers.map(([id, name]) => h('option', { value: id, selected: f.server === id }, name)));
      const scopeSel = h('select', { onchange: (e) => { f.scope = e.target.value; draw(); } }, [['', 'Whole server and world'], ['full', 'Whole server'], ['world', 'World only']].map(([v, l]) => h('option', { value: v, selected: f.scope === v }, l)));
      const sortSel = h('select', { onchange: (e) => { f.sort = e.target.value; draw(); } }, [['new', 'Newest first'], ['old', 'Oldest first'], ['big', 'Largest first']].map(([v, l]) => h('option', { value: v, selected: f.sort === v }, l)));
      const q = h('input', { placeholder: 'Search names and notes...', value: f.q, style: 'width:170px', oninput: (e) => { f.q = e.target.value.toLowerCase(); f.focusQ = true; clearTimeout(q._t); q._t = setTimeout(() => draw(), 250); } });
      nodes.push(section('1  Show', 'Narrow the list', h('div', { class: 'row', style: 'gap:8px;flex-wrap:wrap' }, serverSel, scopeSel, sortSel, q)));
      // --- 2. choose backups
      const days = h('input', { type: 'number', min: 1, value: 30, style: 'width:64px' });
      const keepN = h('input', { type: 'number', min: 1, value: 5, style: 'width:64px' });
      nodes.push(section('2  Choose', `${list.length} shown, ${sel.size} ticked`,
        h('div', { class: 'row', style: 'gap:8px;flex-wrap:wrap;align-items:center' },
          h('button', { class: 'btn sm', onclick: () => { list.forEach(b => sel.add(key(b))); draw(); } }, 'Tick all shown'),
          h('button', { class: 'btn sm', onclick: () => { sel.clear(); draw(); } }, 'Untick all')),
        h('div', { class: 'row', style: 'gap:8px;flex-wrap:wrap;align-items:center' }, h('span', null, 'Tick the ones older than'), days, h('span', null, 'days'),
          h('button', { class: 'btn sm', onclick: () => { const t = Date.now() - (+days.value || 30) * 86400000; list.forEach(b => { if (b.created < t && !b.locked) sel.add(key(b)); }); draw(); } }, 'Tick them')),
        h('div', { class: 'row', style: 'gap:8px;flex-wrap:wrap;align-items:center' }, h('span', null, 'Tick all but the newest'), keepN, h('span', null, 'of each server'),
          h('button', { class: 'btn sm', onclick: () => { const n = +keepN.value || 5; const seen = {}; list.slice().sort((a, b) => b.created - a.created).forEach(b => { seen[b.server] = (seen[b.server] || 0) + 1; if (seen[b.server] > n && !b.locked) sel.add(key(b)); }); draw(); } }, 'Tick them'))));
      // --- 3. do something with the ticked ones
      const chosen = all.filter(b => sel.has(key(b)));
      if (chosen.length) {
        const dd = actionPicker(chosen, chosen.length > 1);
        const go = h('button', { class: 'btn primary', onclick: () => { if (!dd.value) { toast('Choose an action first.'); return; } doAction(dd.value, chosen, draw).then(() => { dd.value = ''; }); } }, 'Go!');
        nodes.push(section('3  Do this to the ticked backups', `${chosen.length} ticked, ${BK_BYTES(chosen.reduce((n, b) => n + b.size, 0))}`, h('div', { class: 'row', style: 'gap:8px;flex-wrap:wrap;align-items:center' }, dd, go)));
      }
      // --- the list
      nodes.push(section('Backups', '', !list.length ? h('div', { class: 'dim' }, all.length ? 'No backups match these filters.' : 'No backups yet. Use the "Back up now" tab to make the first one.') : h('div', { style: 'display:grid;gap:6px' }, list.map(b => rowFor(b, draw)))));
      const keepScroll = wrap.scrollTop;
      holder.replaceChildren(...nodes);
      wrap.scrollTop = keepScroll;
      if (f.focusQ) { f.focusQ = false; q.focus(); q.setSelectionRange(q.value.length, q.value.length); }
    };
    draw(true);
  };

  // =============================== Back up now ===============================
  let sdata = null;
  const pick = new Set();
  const g = { q: '' };
  const loadServers = async () => { sdata = await api('GET', '/api/backups/servers'); return sdata.servers; };
  const matches = (s, q) => !q || q.toLowerCase().split(/\s+/).filter(Boolean).every(t => [s.name, s.mc, s.kind, s.loader, s.state, ...(s.mods || [])].filter(Boolean).join(' ').toLowerCase().includes(t));
  view.new = async () => {
    try { await loadServers(); } catch (e) { holder.replaceChildren(h('div', { class: 'err' }, e.message)); return; }
    const full = h('input', { type: 'radio', name: 'bmscope', checked: true });
    const world = h('input', { type: 'radio', name: 'bmscope' });
    const note = h('input', { placeholder: 'Optional note, e.g. before the big build', maxlength: 200, style: 'width:100%', autocomplete: 'off' });
    const listBox = h('div', { style: 'display:grid;gap:6px' });
    const count = h('span', { class: 'dim' });
    const goBtn = h('button', { class: 'btn primary' }, 'Go!');
    const draw = () => {
      const shown = sdata.servers.filter(s => matches(s, g.q));
      count.textContent = `${shown.length} of ${sdata.servers.length} shown, ${pick.size} ticked`;
      goBtn.textContent = pick.size ? `Go! Back up ${pick.size} server${pick.size === 1 ? '' : 's'}` : 'Go!';
      goBtn.disabled = !pick.size;
      listBox.replaceChildren(...(shown.length ? shown.map(s => h('label', { class: 'found', style: 'display:flex;gap:10px;align-items:flex-start;cursor:pointer' },
        h('input', { type: 'checkbox', checked: pick.has(s.id), onchange: (e) => { if (e.target.checked) pick.add(s.id); else pick.delete(s.id); draw(); } }),
        h('div', null, h('div', null, h('b', null, s.name), ' ', h('span', { class: 'badge' }, s.state), ' ', h('span', { class: 'dim' }, [s.mc ? 'Minecraft ' + s.mc : '', s.kind, s.loader && s.kind !== 'vanilla' ? s.loader : ''].filter(Boolean).join(' · '))),
          h('div', { class: 'dim', style: 'font-size:12px' }, `${s.count} backup${s.count === 1 ? '' : 's'} so far` + (s.mods.length ? ` · ${s.mods.length} mods` : '') + (s.state === 'running' && !s.rcon ? ' · running without RCON: cannot be backed up safely while running' : ''))))) : [h('div', { class: 'dim' }, 'No server matches.')]));
    };
    const q = h('input', { placeholder: 'Search by name, Minecraft version, loader, mod name or status (running/stopped)...', style: 'width:100%', value: g.q, oninput: (e) => { g.q = e.target.value; draw(); } });
    goBtn.onclick = async () => {
      const names = sdata.servers.filter(s => pick.has(s.id)).map(s => s.name);
      if (!await confirmBox('Back up now', `Back up ${names.join(', ')}? ${world.checked ? 'World only.' : 'Whole server.'} Running servers keep running; saving is paused briefly while files are copied.`, 'Back up')) return;
      try { const r = await api('POST', '/api/backups/create-many', { servers: [...pick], scope: world.checked ? 'world' : 'full', note: note.value.trim() }); openJob(r.job, 'Back up servers', '', { onDone: () => { data = null; } }); } catch (e) { toast(e.message, true); }
    };
    holder.replaceChildren(
      section('1  Find servers', 'Type words; every word must match', q),
      section('2  Choose servers', '', h('div', { class: 'row', style: 'gap:8px;flex-wrap:wrap;align-items:center' },
        h('button', { class: 'btn sm', onclick: () => { sdata.servers.filter(s => matches(s, g.q)).forEach(s => pick.add(s.id)); draw(); } }, 'Tick all shown'),
        h('button', { class: 'btn sm', onclick: () => { pick.clear(); draw(); } }, 'Untick all'), count), listBox),
      section('3  What to back up', '', h('label', null, full, ' Whole server (world, mods, configs, settings)'), h('label', null, world, ' World only (smaller and faster)'), h('label', { class: 'dim' }, 'Note', note)),
      section('4  Start', 'Servers are backed up one after another', goBtn));
    draw();
  };

  // =============================== Schedules ===============================
  const spick = new Set();
  const describe = (s) => {
    const a = s.auto;
    const when = !a.enabled ? 'No schedule' : a.mode === 'interval' ? `Every ${a.hours} h` : a.mode === 'daily' ? `Daily at ${a.at}` : `${a.days.map(d => ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'][d - 1]).join(' ')} at ${a.at}`;
    return [when, a.enabled ? (a.scope === 'world' ? 'world only' : 'whole server') : '', s.keep ? `keep newest ${s.keep}` : 'keeps all', s.mirror ? `copy to ${s.mirror}` : '', s.verify ? 'checked after backup' : ''].filter(Boolean).join(' · ');
  };
  const scheduleEditor = (targets) => {
    const first = targets[0];
    const a = first.auto;
    const on = h('input', { type: 'checkbox', checked: !!a.enabled });
    const mode = h('select', null, [['interval', 'every few hours'], ['daily', 'every day'], ['weekly', 'on the days I choose']].map(([v, l]) => h('option', { value: v, selected: v === a.mode }, l)));
    const hours = h('input', { type: 'number', min: 1, max: 168, value: a.hours, style: 'width:80px' });
    const at = h('input', { type: 'time', value: a.at, style: 'width:120px' });
    const NAMES = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
    const days = NAMES.map((n, i) => ({ n, c: h('input', { type: 'checkbox', checked: a.days.includes(i + 1) }) }));
    const full = h('input', { type: 'radio', name: 'sdscope', checked: a.scope !== 'world' });
    const world = h('input', { type: 'radio', name: 'sdscope', checked: a.scope === 'world' });
    const idle = h('input', { type: 'checkbox', checked: !!a.skipIfIdle });
    const rowHours = h('div', { class: 'row', style: 'gap:6px' }, 'Every ', hours, ' hours');
    const rowAt = h('div', { class: 'row', style: 'gap:6px' }, 'At ', at);
    const rowDays = h('div', { style: 'display:flex;flex-wrap:wrap;gap:2px' }, days.map(x => h('label', { style: 'display:inline-flex;gap:3px;margin-right:8px' }, x.c, x.n)));
    const detail = h('div', { style: 'display:grid;gap:8px;margin-left:24px' }, h('div', { class: 'row', style: 'gap:8px' }, 'Make a backup ', mode), rowHours, rowAt, rowDays,
      h('div', { style: 'display:grid;gap:2px' }, h('label', null, full, ' Whole server'), h('label', null, world, ' World only')), h('label', null, idle, ' Skip a backup when nobody has played since the last one'));
    const fit = () => { rowHours.classList.toggle('hidden', mode.value !== 'interval'); rowAt.classList.toggle('hidden', mode.value === 'interval'); rowDays.classList.toggle('hidden', mode.value !== 'weekly'); detail.classList.toggle('hidden', !on.checked); };
    mode.addEventListener('change', fit); on.addEventListener('change', fit); fit();
    const keep = h('input', { type: 'number', min: 0, max: 1000, value: first.keep, style: 'width:80px' });
    const mirror = h('input', { value: first.mirror, placeholder: 'e.g. E:\\Backup copies (empty = no copy)', spellcheck: 'false', style: 'flex:1;min-width:180px' });
    const verify = h('input', { type: 'checkbox', checked: !!first.verify });
    const extras = isAdmin() ? h('div', { style: 'display:grid;gap:8px' },
      h('label', null, 'Keep only the newest ', keep, ' backups ', h('span', { class: 'dim' }, '(0 keeps all; pinned ones never count or get deleted)')),
      h('div', { class: 'row', style: 'gap:8px;flex-wrap:wrap' }, 'Also copy each new backup to', mirror, h('button', { class: 'btn', type: 'button', onclick: async () => { const p = await pickFolder({ title: 'Copy each new backup to which folder?', start: mirror.value }); if (p) mirror.value = p; } }, icon('folder'), ' Browse')),
      h('label', null, verify, ' Check each new backup right after it is made (reads the zip back)')) : h('div', { class: 'dim' }, 'Ask an administrator to change how many backups are kept, copy folders or checking.');
    const body = h('div', { style: 'display:grid;gap:12px' },
      targets.length > 1 ? h('div', { class: 'notice' }, `These settings replace the current ones on all ${targets.length} servers: ${targets.map(t => t.name).join(', ')}.`) : null,
      section('Schedule', '', h('label', null, on, ' Back up automatically'), detail),
      section('After each backup', '', extras));
    return { body, collect: () => {
      const ds = days.map((x, i) => (x.c.checked ? i + 1 : 0)).filter(Boolean);
      const payload = { servers: targets.map(t => t.id), schedule: { enabled: on.checked, mode: mode.value, hours: +hours.value, at: at.value || '04:00', days: ds.length ? ds : [1, 2, 3, 4, 5, 6, 7], scope: world.checked ? 'world' : 'full', skipIfIdle: idle.checked } };
      if (isAdmin()) { payload.keep = Number(keep.value) || 0; payload.mirror = mirror.value.trim(); payload.verify = verify.checked; }
      return payload;
    } };
  };
  const openEditor = (targets) => {
    const ed = scheduleEditor(targets);
    modal(targets.length === 1 ? `Schedule for ${targets[0].name}` : `Schedule for ${targets.length} servers`, ed.body, [
      { label: 'Cancel', run: (c) => c() },
      { label: 'Save', cls: 'primary', run: async (c) => { try { const r = await api('POST', '/api/backups/schedule', ed.collect()); toast(`Saved for ${r.updated} server${r.updated === 1 ? '' : 's'}`); c(); showTab('schedules'); } catch (e) { toast(e.message, true); } } },
    ]);
  };
  view.schedules = async () => {
    try { await loadServers(); } catch (e) { holder.replaceChildren(h('div', { class: 'err' }, e.message)); return; }
    const gs = { q: '' };
    const listBox = h('div', { style: 'display:grid;gap:6px' });
    const count = h('span', { class: 'dim' });
    const bulkBox = h('div');
    const draw = () => {
      const shown = sdata.servers.filter(s => matches(s, gs.q));
      count.textContent = `${shown.length} of ${sdata.servers.length} shown, ${spick.size} ticked`;
      const chosen = sdata.servers.filter(s => spick.has(s.id));
      bulkBox.replaceChildren(...(chosen.length ? [section('3  Do this to the ticked servers', `${chosen.length} ticked`, (() => {
        const dd = h('select', null, h('option', { value: '' }, 'Action...'), h('option', { value: 'set' }, 'Set the schedule...'), h('option', { value: 'off' }, 'Turn automatic backups off'), h('option', { value: 'now' }, 'Back up now'));
        return h('div', { class: 'row', style: 'gap:8px;flex-wrap:wrap;align-items:center' }, dd, h('button', { class: 'btn primary', onclick: async () => {
          if (dd.value === 'set') openEditor(chosen);
          else if (dd.value === 'off') { try { await api('POST', '/api/backups/schedule', { servers: chosen.map(c => c.id), schedule: { enabled: false } }); toast('Automatic backups turned off'); showTab('schedules'); } catch (e) { toast(e.message, true); } }
          else if (dd.value === 'now') { pick.clear(); chosen.forEach(c => pick.add(c.id)); showTab('new'); }
          else toast('Choose an action first.');
        } }, 'Go!'));
      })())] : []));
      listBox.replaceChildren(...(shown.length ? shown.map(s => {
        const cb = h('input', { type: 'checkbox', checked: spick.has(s.id), onchange: (e) => { if (e.target.checked) spick.add(s.id); else spick.delete(s.id); draw(); } });
        return h('div', { class: 'found', style: 'display:flex;gap:8px 12px;align-items:center;flex-wrap:wrap;justify-content:space-between' },
          h('div', { style: 'display:flex;gap:10px;flex:1;min-width:230px;align-items:flex-start' }, cb, h('div', null, h('b', null, s.name), h('div', { class: s.auto.enabled ? '' : 'dim', style: 'font-size:13px' }, describe(s)),
            s.auto.enabled && s.auto.last ? h('div', { class: 'dim', style: 'font-size:12px' }, `Last run ${BK_WHEN(s.auto.last)}${s.auto.lastResult ? ' (' + s.auto.lastResult + ')' : ''}`) : null)),
          h('button', { class: 'btn sm', onclick: () => openEditor([s]) }, 'Edit schedule'));
      }) : [h('div', { class: 'dim' }, 'No server matches.')]));
    };
    const q = h('input', { placeholder: 'Search by name, Minecraft version, loader, mod name or status...', style: 'width:100%', oninput: (e) => { gs.q = e.target.value; draw(); } });
    holder.replaceChildren(
      section('1  Find servers', 'Type words; every word must match', q),
      section('2  Choose servers', 'Or just press Edit schedule on one', h('div', { class: 'row', style: 'gap:8px;flex-wrap:wrap;align-items:center' },
        h('button', { class: 'btn sm', onclick: () => { sdata.servers.filter(s => matches(s, gs.q)).forEach(s => spick.add(s.id)); draw(); } }, 'Tick all shown'),
        h('button', { class: 'btn sm', onclick: () => { spick.clear(); draw(); } }, 'Untick all'), count), listBox),
      bulkBox);
    draw();
  };
  showTab(tab);
}

/** A confirm dialog whose message is an element. */
function confirmDlg(title, msg, okLabel) {
  return new Promise(res => {
    let done = false;
    const fin = (v) => { if (!done) { done = true; res(v); } };
    modal(title, msg, [{ label: 'Cancel', run: (c) => { fin(false); c(); } }, { label: okLabel || 'OK', cls: 'primary', run: (c) => { fin(true); c(); } }], { onClose: () => fin(false) });
  });
}

/** Adds Fabric, Forge or NeoForge to an existing vanilla server. */
async function convertDialog(s) {
  let info;
  try { info = await api('GET', `/api/servers/${s.id}/convert`); } catch (e) { toast(e.message, true); return; }
  if (info.blocked) {
    modal('Add a mod loader', h('div', { class: 'notice' }, info.blocked), [{ label: 'Close', cls: 'primary', run: (c) => c() }]);
    return;
  }
  const mc = info.mc;
  const TYPES = [['fabric', 'Fabric', 'Lightweight and the most popular for server-side mods; the manager can keep it and its mods up to date.'],
    ['forge', 'Forge', 'The long-standing loader with a large library of big content mods.'],
    ['neoforge', 'NeoForge', 'The modern continuation of Forge (Minecraft 1.20.2 and newer).']];
  const radios = TYPES.map(([id], i) => h('input', { type: 'radio', name: 'cvtype', value: id, checked: i === 0, onchange: () => loadVersions() }));
  const ver = h('select', { style: 'min-width:160px' });
  const verNote = h('span', { class: 'dim' });
  const backup = h('input', { type: 'checkbox', checked: true });
  const auto = h('input', { type: 'checkbox', checked: true });
  const pin = h('input', { type: 'checkbox' });
  const mods = h('input', { type: 'checkbox', checked: true });
  const fabricOnly = h('div', { style: 'display:grid;gap:4px' },
    h('label', null, auto, ' Update Fabric and mods automatically each time the server starts'),
    h('label', null, pin, ` Keep Minecraft at ${mc} (updates change mods and Fabric only)`));
  let m;
  const type = () => radios.find(r => r.checked).value;
  async function loadVersions() {
    const t = type();
    fabricOnly.style.display = t === 'fabric' ? 'grid' : 'none';
    ver.replaceChildren(h('option', null, 'Loading...'));
    m && (m.btns[1].disabled = true);
    try {
      const r = await api('GET', `/api/install/loaders?type=${t}&mc=${encodeURIComponent(mc)}`);
      if (type() !== t) return;
      if (!r.loaders.length) { ver.replaceChildren(); verNote.textContent = `No ${t} version exists for Minecraft ${mc} yet.`; return; }
      ver.replaceChildren(...r.loaders.map(l => h('option', { value: l.id, selected: l.recommended }, l.label)));
      verNote.textContent = '';
      m && (m.btns[1].disabled = false);
    } catch (e) { ver.replaceChildren(); verNote.textContent = e.message; }
  }
  const jv = info.java;
  const body = h('div', { style: 'display:grid;gap:10px;width:100%;min-width:0;max-height:70vh;overflow:auto;align-content:start' },
    h('div', null, `${s.name} is a plain vanilla server on Minecraft ${mc}. Adding a mod loader keeps its world, settings, whitelist and ops.`),
    jv && jv.required > jv.installed ? h('div', { class: 'notice' }, `Minecraft ${mc} needs Java ${jv.required} but this PC only has Java ${jv.installed}.`) : null,
    h('div', { style: 'display:grid;gap:6px' }, TYPES.map(([id, label, desc], i) => h('div', null, h('label', null, radios[i], ' ', h('b', null, label)), h('div', { class: 'dim', style: 'font-size:12.5px;margin-left:24px' }, desc)))),
    h('div', { class: 'row', style: 'gap:8px' }, h('label', null, 'Version ', ver), verNote),
    fabricOnly,
    h('div', { style: 'display:grid;gap:4px' },
      h('label', null, backup, ' Back up the whole server first (recommended)'),
      h('div', { class: 'dim', style: 'font-size:12.5px;margin-left:24px' }, `Saved in ${info.backupDir}. If you change your mind, Restore puts the server back to plain vanilla.`)),
    h('label', null, mods, ' Then let me pick mods to install'),
    h('div', { class: 'notice' }, 'Some mods must also be installed on players’ own games; the mod list tells you which. Removing mods later from a world that already uses them can lose those blocks and items, so keep the backup until you are happy.'));
  m = modal(`Add a mod loader to ${s.name}`, body, [
    { label: 'Cancel', run: (c) => c() },
    { label: 'Add mod loader', cls: 'primary', disabled: true, run: async (c) => {
      const t = type();
      try {
        const r = await api('POST', `/api/servers/${s.id}/convert`, { type: t, loader: ver.value, backup: backup.checked, autoUpdate: auto.checked, pinMc: pin.checked });
        c();
        openJob(r.job, `Add ${t} to ${s.name}`, s.id, { onDone: async (state) => {
          if (state !== 'done') return;
          try {
            const fresh = await api('GET', `/api/servers/${s.id}`);
            S.servers = await api('GET', '/api/servers');
            renderSide(); renderMain();
            if (mods.checked) addModsWindow(fresh, { converted: true });
          } catch (e) { toast(e.message, true); }
        }, autoClose: true });
      } catch (e) { toast(e.message, true); }
    } },
  ], { wide: false });
  loadVersions();
}

/** The schedule for automatic backups of one server (level 4 and administrators). */
function autoBackupBox(s, d, redraw) {
  const a = d.auto || { enabled: false, mode: 'daily', hours: 6, at: '04:00', days: [1, 2, 3, 4, 5, 6, 7], scope: 'full', skipIfIdle: true };
  const on = h('input', { type: 'checkbox', checked: !!a.enabled });
  const mode = h('select', null, [['interval', 'every few hours'], ['daily', 'every day'], ['weekly', 'on the days I choose']].map(([v, l]) => h('option', { value: v, selected: v === a.mode }, l)));
  const hours = h('input', { type: 'number', min: 1, max: 168, value: a.hours, style: 'width:80px' });
  const at = h('input', { type: 'time', value: a.at, style: 'width:120px' });
  const NAMES = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
  const days = NAMES.map((n, i) => ({ n, c: h('input', { type: 'checkbox', checked: a.days.includes(i + 1) }) }));
  const full = h('input', { type: 'radio', name: 'abscope' + s.id, checked: a.scope !== 'world' });
  const world = h('input', { type: 'radio', name: 'abscope' + s.id, checked: a.scope === 'world' });
  const idle = h('input', { type: 'checkbox', checked: !!a.skipIfIdle });
  const rowHours = h('div', { class: 'row', style: 'gap:6px' }, 'Every ', hours, ' hours');
  const rowAt = h('div', { class: 'row', style: 'gap:6px' }, 'At ', at);
  const rowDays = h('div', { style: 'display:flex;flex-wrap:wrap;gap:2px' }, days.map(x => h('label', { style: 'display:inline-flex;gap:3px;margin-right:8px' }, x.c, x.n)));
  const detail = h('div', { style: 'display:grid;gap:8px;margin-left:24px' });
  const fit = () => {
    rowHours.classList.toggle('hidden', mode.value !== 'interval');
    rowAt.classList.toggle('hidden', mode.value === 'interval');
    rowDays.classList.toggle('hidden', mode.value !== 'weekly');
    detail.classList.toggle('hidden', !on.checked);
  };
  mode.addEventListener('change', fit);
  on.addEventListener('change', fit);
  const last = a.last ? `Last scheduled run: ${BK_WHEN(a.last)}${a.lastResult ? ' (' + a.lastResult + ')' : ''}.` : (a.enabled ? 'The first backup runs within a minute of saving.' : '');
  detail.append(
    h('div', { class: 'row', style: 'gap:8px' }, 'Make a backup ', mode), rowHours, rowAt, rowDays,
    h('div', { style: 'display:grid;gap:2px' }, h('label', null, full, ' Whole server'), h('label', null, world, ' World only')),
    h('label', null, idle, ' Skip a backup when nobody has played since the last one'),
    d.keep ? h('div', { class: 'dim', style: 'font-size:12.5px' }, `Only the newest ${d.keep} backups are kept; older ones are deleted when a new one is made.`)
      : h('div', { class: 'notice' }, 'There is no limit on how many backups are kept, so automatic backups will fill the drive over time. ' + (d.admin ? 'Set "Keep only the newest" below.' : 'Ask an administrator to set a limit.')));
  fit();
  const save = h('button', { class: 'btn primary', onclick: async () => {
    const ds = days.map((x, i) => (x.c.checked ? i + 1 : 0)).filter(Boolean);
    try {
      await api('POST', `/api/servers/${s.id}/backups/auto`, { enabled: on.checked, mode: mode.value, hours: +hours.value, at: at.value || '04:00', days: ds.length ? ds : [1, 2, 3, 4, 5, 6, 7], scope: world.checked ? 'world' : 'full', skipIfIdle: idle.checked });
      toast(on.checked ? 'Automatic backups are on.' : 'Automatic backups are off.');
      redraw();
    } catch (e) { toast(e.message, true); }
  } }, 'Save schedule');
  return h('div', { class: 'box', style: 'display:grid;gap:8px' }, h('h3', null, 'Automatic backups'),
    h('label', null, on, ' Back up ' + s.name + ' automatically'), detail,
    h('div', { class: 'row', style: 'gap:10px;align-items:center' }, save, last ? h('span', { class: 'dim', style: 'font-size:12.5px' }, last) : null));
}