'use strict';
/* JourneyMap tab: global and per-player waypoints, teleporting, sending waypoints to online players, server settings and JourneyMap admins. */

/** Friendly names for JourneyMap's server settings; settings it does not know about are listed under "Other settings". */
const JM_GROUPS = [
  ['Players on the map', [
    ['worldPlayerRadar', 'Who can see other players on the map'], ['seeUndergroundPlayers', 'Who can see players underground'], ['hideOps', 'Hide operators from the map'],
    ['opsSeeHiddenPlayers', 'Operators can still see hidden players'], ['hideSpectators', 'Hide spectators'], ['playerRadarEnabled', 'Player radar'], ['playerRadarNamesEnabled', 'Show player names on the radar']]],
  ['Waypoints', [
    ['allowWaypoints', 'Players can use waypoints'], ['allowDeathPoints', 'Create a waypoint where a player dies'], ['showInGameBeacons', 'Show waypoint beacons in the world'],
    ['globalWaypointsOnly', 'Only global waypoints (no personal ones)'], ['waypointTeleportOnly', 'Players can teleport only to waypoints']]],
  ['Teleporting from the map', [
    ['teleportEnabled', 'Teleporting from the map'], ['teleport', 'Who can teleport'], ['crossDimTeleport', 'Teleport between dimensions']]],
  ['The map', [
    ['minimapEnabled', 'Minimap'], ['hideCoordinates', 'Hide coordinates'], ['surfaceMapping', 'Surface map'], ['topoMapping', 'Topographic map'], ['biomeMapping', 'Biome map'], ['caveMapping', 'Cave map']]],
  ['Radar', [
    ['radarEnabled', 'Radar (who can use it)'], ['villagerRadarEnabled', 'Show villagers'], ['animalRadarEnabled', 'Show animals'], ['mobRadarEnabled', 'Show monsters']]],
];
const JM_HIDDEN = new Set(['configVersion', 'journeymapEnabled', 'useWorldId', 'viewOnlyServerProperties']);
const JM_WHO = ['ALL', 'OPS', 'NONE'];

const jmDimName = (d) => ({ 'minecraft:overworld': 'Overworld', 'minecraft:the_nether': 'Nether', 'minecraft:the_end': 'End' }[d] || d);

async function buildJourneyMap(s, pane, tok) {
  const wrap = h('div', { style: 'display:grid;gap:12px;grid-template-columns:minmax(0,1fr)' });
  pane.replaceChildren(wrap);
  const admin = lvl(s) >= 4;
  const onlineNames = () => ((sv() && sv().players) || []).map(p => p.name);
  const act = async (fn, okMsg) => { try { const r = await fn(); if (okMsg) toast(okMsg); return r; } catch (e) { toast(e.message, true); } };
  const swatch = (c) => h('span', { style: `display:inline-block;width:12px;height:12px;border-radius:3px;margin-right:6px;vertical-align:-1px;background:${c || '#888'};border:1px solid var(--line)` });
  const coords = (w) => `${w.x}, ${w.y}, ${w.z}`;

  /** Teleport an online player to a place. */
  const teleportDialog = (title, w, preferred) => {
    const names = onlineNames();
    if (!names.length) { toast('Nobody is online to teleport.', true); return; }
    const sel = h('select', null, names.map(n => h('option', { value: n }, n)));
    if (preferred && names.includes(preferred)) sel.value = preferred;
    modal(title, h('div', { style: 'display:grid;gap:10px' },
      h('div', null, 'Move a player to ', h('b', null, w.name), ' (' + jmDimName(w.dim) + ': ' + coords(w) + ').'),
      h('label', { class: 'dim', style: 'display:grid;gap:2px' }, 'Which online player', sel)), [
      { label: 'Cancel', run: (c) => c() },
      { label: 'Teleport', cls: 'primary', run: async (c) => {
        const r = await act(() => api('POST', `/api/servers/${s.id}/journeymap/teleport`, { player: sel.value, dim: w.dim, x: w.x, y: w.y, z: w.z }));
        if (r) { toast(r.output || ('Teleported ' + sel.value)); c(); }
      } },
    ]);
  };

  /** A table of waypoints; extra(w) returns the action buttons for a row. */
  const wpTable = (list, extra) => !list.length ? h('div', { class: 'dim' }, 'None.') :
    h('table', { class: 'tbl' }, h('tr', null, ['Name', 'Dimension', 'Position (x, y, z)'].map(c => h('th', null, c)), h('th')),
      list.map(w => h('tr', null, h('td', null, swatch(w.color), w.name), h('td', null, jmDimName(w.dim)), h('td', { class: 'mono' }, coords(w)),
        h('td', { class: 'act' }, h('div', { class: 'row', style: 'flex-wrap:nowrap;gap:4px' }, extra(w))))));

  const copyBtn = (w) => h('button', { class: 'btn sm', title: 'Copy the coordinates', onclick: () => copyValue(coords(w), 'Coordinates copied') }, 'Copy');

  const waitStopped = async (ms) => { const end = Date.now() + ms; while (Date.now() < end) { if (sv().state === 'stopped') return true; await new Promise(r => setTimeout(r, 1000)); } return false; };

  /** Copies a player's own waypoint into the global list. JourneyMap keeps the global list in memory while the server runs, so a running server is stopped for a moment and started again. */
  const makeGlobal = async (p, w, close) => {
    const up = sv().state !== 'stopped';
    const ok = await confirmBox('Make global', up
      ? `"${w.name}" will be copied to the global waypoints, so everyone with JourneyMap sees it. JourneyMap keeps that list in memory while the server runs, so the server will be stopped, the waypoint added, and the server started again. Players are disconnected for a minute.`
      : `Copy "${w.name}" to the global waypoints? Everyone with JourneyMap will see it. ${p.name || 'The player'} keeps their own copy.`,
      up ? 'Stop, add and start again' : 'Make global', up);
    if (!ok) return;
    const add = async () => {
      const r = await act(() => api('POST', `/api/servers/${s.id}/journeymap/promote`, { uuid: p.uuid, guid: w.guid }));
      if (r) toast(r.already ? `"${w.name}" is already a global waypoint.` : `"${w.name}" is now a global waypoint.`);
      return !!r;
    };
    if (!up) { if (await add()) { close(); renderPane(); } return; }
    close();
    startJob(s, 'stop', { delay: 0 }, `Stop ${s.name}`, { autoClose: true, onDone: async () => {
      if (!await waitStopped(120000)) { toast('The server did not stop in time, so nothing was changed.', true); return; }
      const done = await add();
      doStart(s);
      if (done) setTimeout(() => { if (S.tab === 'journeymap') renderPane(); }, 3000);
    } });
  };

  /** The popup with one player's personal waypoints. */
  const playerDialog = async (p) => {
    let list;
    try { list = (await api('POST', `/api/servers/${s.id}/journeymap/player`, { uuid: p.uuid })).waypoints; } catch (e) { toast(e.message, true); return; }
    const who = p.name || p.uuid;
    let closeDlg = () => { };
    const body = h('div', { style: 'display:grid;gap:8px;max-height:60vh;overflow:auto' },
      h('div', { class: 'dim' }, `${list.length} waypoint${list.length === 1 ? '' : 's'} saved by ${who} on this server. These are their own; only they can change them. "Make global" copies one to the global list.`),
      wpTable(list, (w) => [h('button', { class: 'btn sm', onclick: () => teleportDialog('Teleport to ' + w.name, w, p.name) }, 'Teleport'), copyBtn(w),
        admin ? h('button', { class: 'btn sm', title: 'Copy this waypoint to the global waypoints', onclick: () => makeGlobal(p, w, () => closeDlg()) }, 'Make global') : null]));
    modal(who + "'s waypoints", body, [{ label: 'Close', run: (c) => { closeDlg = c; c(); } }], { wide: true, onClose: () => { } });
    closeDlg = () => { const d = [...document.querySelectorAll('dialog')].pop(); if (d) { try { d.close(); } catch (e) { /* closed */ } d.remove(); } };
  };

  /** Give a waypoint to online players (it appears in their own list, through JourneyMap's own command). */
  const sendDialog = () => {
    const names = onlineNames();
    if (!names.length) { toast('Nobody is online to send a waypoint to.', true); return; }
    const name = h('input', { maxlength: 40, placeholder: 'Waypoint name' });
    const dim = h('select', null, [['minecraft:overworld', 'Overworld'], ['minecraft:the_nether', 'Nether'], ['minecraft:the_end', 'End']].map(([k, l]) => h('option', { value: k }, l)));
    const num = (ph) => h('input', { type: 'number', placeholder: ph, style: 'width:90px' });
    const x = num('x'), y = num('y'), z = num('z');
    const color = h('input', { type: 'color', value: '#ff6a00' });
    const boxes = names.map(n => ({ n, i: h('input', { type: 'checkbox', checked: true }) }));
    modal('Send a waypoint to players', h('div', { style: 'display:grid;gap:10px' },
      h('div', { class: 'dim' }, 'The waypoint is added to the chosen online players\' own waypoint lists (players need JourneyMap installed).'),
      h('label', { class: 'dim', style: 'display:grid;gap:2px' }, 'Name', name),
      h('div', { class: 'row' }, h('label', { class: 'dim', style: 'display:grid;gap:2px' }, 'Dimension', dim), h('label', { class: 'dim', style: 'display:grid;gap:2px' }, 'Colour', color)),
      h('div', { class: 'row' }, x, y, z),
      h('div', { style: 'display:grid;gap:4px' }, h('div', { class: 'row', style: 'gap:10px' }, h('b', null, 'Send to'),
        h('a', { href: '#', onclick: (e) => { e.preventDefault(); boxes.forEach(b => { b.i.checked = true; }); } }, 'Select all'),
        h('a', { href: '#', onclick: (e) => { e.preventDefault(); boxes.forEach(b => { b.i.checked = false; }); } }, 'Select none')),
        boxes.map(b => h('label', null, b.i, ' ' + b.n)))), [
      { label: 'Cancel', run: (c) => c() },
      { label: 'Send', cls: 'primary', run: async (c) => {
        const to = boxes.filter(b => b.i.checked).map(b => b.n);
        const r = await act(() => api('POST', `/api/servers/${s.id}/journeymap/send`, { name: name.value.trim(), dim: dim.value, x: +x.value, y: +y.value, z: +z.value, color: color.value, players: to }));
        if (r) { toast((r.results || []).join('  ') || 'Sent'); c(); }
      } },
    ]);
  };

  /** Settings box: friendly controls for the global JourneyMap config. */
  const settingsBox = (d) => {
    const vals = (d.settings && d.settings.values) || {};
    const changed = {};
    const note = h('div', { class: 'notice hidden' });
    const field = (key, label) => {
      const cur = String(vals[key]);
      let el;
      const set = (v) => { if (v === cur) delete changed[key]; else changed[key] = v; };
      if (cur === 'true' || cur === 'false') {
        const i = h('input', { type: 'checkbox', checked: cur === 'true', disabled: !admin, onchange: () => set(i.checked ? 'true' : 'false') });
        el = h('label', null, i, ' ' + label);
      } else if (JM_WHO.includes(cur) || /Mapping|teleport$|Radar$|radarEnabled|seeUndergroundPlayers/.test(key)) {
        const opts = JM_WHO.includes(cur) ? JM_WHO : [...JM_WHO, cur];
        const sel = h('select', { disabled: !admin, onchange: () => set(sel.value) }, opts.map(o => h('option', { value: o }, o)));
        sel.value = cur;
        el = h('label', { class: 'dim', style: 'display:grid;gap:2px' }, label, sel);
      } else {
        const i = h('input', { value: cur, disabled: !admin, oninput: () => set(i.value.trim()) });
        el = h('label', { class: 'dim', style: 'display:grid;gap:2px' }, label, i);
      }
      return el;
    };
    const used = new Set(JM_HIDDEN);
    const groups = [];
    for (const [title, keys] of JM_GROUPS) {
      const rows = keys.filter(([k]) => k in vals).map(([k, l]) => { used.add(k); return field(k, l); });
      if (rows.length) groups.push(h('div', { style: 'display:grid;gap:6px' }, h('b', null, title), h('div', { style: 'display:grid;gap:6px;grid-template-columns:repeat(auto-fill,minmax(240px,1fr))' }, rows)));
    }
    const rest = Object.keys(vals).filter(k => !used.has(k));
    if (rest.length) groups.push(h('div', { style: 'display:grid;gap:6px' }, h('b', null, 'Other settings'), h('div', { style: 'display:grid;gap:6px;grid-template-columns:repeat(auto-fill,minmax(240px,1fr))' }, rest.map(k => field(k, k)))));
    const save = h('button', { class: 'btn primary', disabled: !admin, onclick: async () => {
      if (!Object.keys(changed).length) { toast('Nothing changed'); return; }
      const r = await act(() => api('POST', `/api/servers/${s.id}/journeymap/settings`, { values: changed }));
      if (!r) return;
      for (const k of Object.keys(changed)) vals[k] = changed[k];
      for (const k of Object.keys(changed)) delete changed[k];
      note.textContent = r.running ? 'Saved. JourneyMap reads these settings when the server starts, so restart the server for them to apply. (JourneyMap\'s own in-game admin screen changes them live.)' : 'Saved. They apply the next time the server starts.';
      note.classList.remove('hidden');
    } }, 'Save settings');
    return h('div', { class: 'box', style: 'display:grid;gap:10px' }, h('h3', null, 'Server settings'),
      !(d.settings && d.settings.found) ? h('div', { class: 'dim' }, 'JourneyMap has not created its settings yet. Start the server once and they appear here.') : h('div', { style: 'display:grid;gap:12px' }, groups, h('div', { class: 'row' }, save), note));
  };

  const adminsBox = async (d) => {
    let known = [];
    try { known = (await api('GET', `/api/servers/${s.id}/players`)).known || []; } catch (e) { /* optional */ }
    const a = d.admins || { list: [], opAccess: true };
    const note = h('div', { class: 'notice hidden' });
    const saved = (r) => { note.textContent = r.running ? 'Saved. JourneyMap reads its admin list when the server starts, so restart the server for this to apply.' : 'Saved. It applies the next time the server starts.'; note.classList.remove('hidden'); };
    const op = h('input', { type: 'checkbox', checked: a.opAccess, disabled: !admin, onchange: async () => { const r = await act(() => api('POST', `/api/servers/${s.id}/journeymap/admins`, { opAccess: op.checked })); if (r) saved(r); } });
    const have = new Set(a.list.map(x => x.uuid.toLowerCase()));
    const pick = h('select', { disabled: !admin }, [h('option', { value: '' }, 'Add an administrator...'), ...known.filter(k => k.uuid && !have.has(k.uuid.toLowerCase())).map(k => h('option', { value: k.uuid }, k.name))]);
    const add = h('button', { class: 'btn', disabled: !admin, onclick: async () => {
      if (!pick.value) return;
      const r = await act(() => api('POST', `/api/servers/${s.id}/journeymap/admins`, { uuid: pick.value, add: true }));
      if (r) { saved(r); renderPane(); }
    } }, 'Add');
    return h('div', { class: 'box', style: 'display:grid;gap:8px' }, h('h3', null, 'JourneyMap administrators'),
      h('div', { class: 'dim' }, 'Administrators can change JourneyMap\'s settings and global waypoints in game.'),
      h('label', null, op, ' Server operators are JourneyMap administrators too'),
      a.list.length ? h('table', { class: 'tbl' }, a.list.map(x => h('tr', null, h('td', null, x.name || x.uuid), h('td', { class: 'act' },
        h('button', { class: 'btn sm', disabled: !admin, onclick: async () => { const r = await act(() => api('POST', `/api/servers/${s.id}/journeymap/admins`, { uuid: x.uuid, add: false })); if (r) { saved(r); renderPane(); } } }, 'Remove')))))
        : h('div', { class: 'dim' }, 'No separate administrators.'),
      h('div', { class: 'row' }, pick, add), note);
  };

  let drawing = false;
  const draw = async () => {
    if (drawing) return;
    drawing = true;
    try {
      let d;
      try { d = await api('GET', `/api/servers/${s.id}/journeymap`); } catch (e) { wrap.replaceChildren(h('div', { class: 'box' }, e.message)); return; }
      if (tok !== S.token) return;
      const up = sv().state === 'running';
      const head = h('div', { class: 'box' }, h('h3', null, 'JourneyMap' + (d.version ? ' ' + d.version : '')),
        h('div', { class: 'dim' }, `World "${d.world}". Players see the map when they have JourneyMap on their own computer. Waypoints below come from the world's saved files, so they are current as of the server's last save.`),
        h('div', { class: 'row', style: 'margin-top:6px' },
          h('button', { class: 'btn', disabled: !up, title: up ? '' : 'The server is not running', onclick: sendDialog }, 'Send a waypoint to players...'),
          h('span', { class: 'dim' }, 'Console shortcut: /tpwp <player> <waypoint name>')));
      const global = h('div', { class: 'box', style: 'display:grid;gap:8px' }, h('h3', null, `Global waypoints (${d.global.length})`),
        h('div', { class: 'dim' }, 'Every player with JourneyMap sees these.'),
        wpTable(d.global, (w) => [
          h('button', { class: 'btn sm', disabled: !up, title: up ? '' : 'The server is not running', onclick: () => teleportDialog('Teleport to ' + w.name, w) }, 'Teleport'),
          copyBtn(w),
          admin ? h('button', { class: 'btn sm danger', disabled: up, title: up ? 'Stop the server first: JourneyMap keeps global waypoints in memory while it runs' : '', onclick: async () => {
            if (!await confirmBox('Delete waypoint', `Delete the global waypoint "${w.name}"? A backup of the file is kept next to it.`, 'Delete', true)) return;
            const r = await act(() => api('POST', `/api/servers/${s.id}/journeymap/delete`, { guid: w.guid }), 'Waypoint deleted');
            if (r) renderPane();
          } }, 'Delete') : null]));
      const players = h('div', { class: 'box', style: 'display:grid;gap:8px' }, h('h3', null, `Players' own waypoints (${d.players.filter(p => p.count).length})`),
        h('div', { class: 'dim' }, 'Click a name to see everything that player has saved.'),
        d.players.filter(p => p.count).length ? h('table', { class: 'tbl' }, h('tr', null, h('th', null, 'Player'), h('th', null, 'Waypoints')),
          d.players.filter(p => p.count).map(p => h('tr', null, h('td', null, h('a', { href: '#', onclick: (e) => { e.preventDefault(); playerDialog(p); } }, p.name || (p.uuid.slice(0, 8) + '...')), p.name ? '' : h('span', { class: 'dim' }, ' (not seen recently)')), h('td', null, String(p.count)))))
          : h('div', { class: 'dim' }, 'Nobody has saved personal waypoints yet.'));
      wrap.replaceChildren(head, global, players, settingsBox(d), await adminsBox(d));
    } finally { drawing = false; }
  };

  S.view = { id: s.id, onStatus: () => { const key = sv().state + '|' + sv().players.length; if (key !== S.view.key) { S.view.key = key; } } };
  S.view.key = sv().state + '|' + sv().players.length;
  await draw();
}
