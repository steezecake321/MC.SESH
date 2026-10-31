// ---------- worlds: make a server from a singleplayer world, and what players need to join a modded server ----------
const WD_CAP = (t) => (t || '').charAt(0).toUpperCase() + (t || '').slice(1);

/** Step 1 of "Create a server from a world": pick a world found on this PC, or choose one (folder, launcher folder or zip) from anywhere. */
function worldImportDialog() {
  let m;
  const list = h('div', { style: 'display:grid;gap:8px;max-height:40vh;overflow:auto;align-content:start' });
  const status = h('div', { class: 'dim' }, 'Looking through the Minecraft launcher folders on this PC...');
  const pathIn = h('input', { placeholder: 'Or type a path here', spellcheck: 'false', style: 'flex:1;min-width:200px', autocomplete: 'off' });
  const card = (w) => h('div', { class: 'found', style: 'display:flex;gap:8px 12px;flex-wrap:wrap;justify-content:space-between;align-items:flex-start' },
    h('div', { style: 'flex:1;min-width:220px' },
      h('div', null, h('b', null, w.name), ' ', w.modCount ? h('span', { class: 'badge' }, `${w.modCount} mods${w.loader ? ' · ' + WD_CAP(w.loader) : ''}`) : h('span', { class: 'badge' }, 'No mods')),
      h('div', { class: 'dim', style: 'font-size:12.5px' }, [w.mc ? 'Minecraft ' + w.mc : 'version unknown', WD_CAP(w.gamemode), w.hardcore ? 'hardcore' : '', w.sizeMb != null ? BK_MB(w.sizeMb) : '', w.lastPlayed ? 'played ' + BK_WHEN(w.lastPlayed) : ''].filter(Boolean).join(' · ')),
      h('div', { class: 'dim', style: 'font-size:12.5px' }, [w.source, w.instance].filter(Boolean).join(' · ')),
      h('div', { class: 'dim mono', style: 'font-size:11.5px' }, w.kind === 'zip' ? w.path + (w.root ? ' > ' + w.root : '') : w.path)),
    h('button', { class: 'btn primary', onclick: () => { m.close(); addServerWizard({ world: w }); } }, 'Use this world'));
  const show = (ws, empty) => list.replaceChildren(...(ws.length ? ws.map(card) : empty === '' ? [] : [h('div', { class: 'dim' }, empty || 'No worlds found.')]));
  const inspect = async (p) => {
    if (!p) return;
    status.textContent = /\.(7z|rar|tar|tgz|txz|tbz2|tzst|gz|xz|bz2|zst)$/i.test(p) ? 'Unpacking ' + p + ' (this can take a minute) ...' : 'Looking in ' + p + ' ...';
    try {
      const r = await api('POST', '/api/worlds/inspect', { path: p });
      status.textContent = `${r.worlds.length} world${r.worlds.length === 1 ? '' : 's'} found in ${p}.`;
      show(r.worlds);
    } catch (e) { status.textContent = ''; toast(e.message, true); }
  };
  const body = h('div', { style: 'display:grid;gap:10px;width:100%;min-width:0;overflow-wrap:anywhere' },
    h('div', null, 'Turn a world you play in singleplayer into a server. The world is ', h('b', null, 'copied'), ' into the new server; the original is never changed or moved. Close Minecraft first so the copy is complete.'),
    status, list,
    h('div', { class: 'box', style: 'display:grid;gap:8px' }, h('h3', null, 'Bring a world from anywhere'),
      h('div', { class: 'dim', style: 'font-size:12.5px' }, 'Pick a world folder, a launcher or instance folder (the manager looks for the worlds in it), or an archive of a world (.zip, .7z, .rar, .tar, .tar.gz ...), for example from a friend or another drive. Archives other than .zip are unpacked into a temporary folder first, which can take a minute.'),
      h('div', { class: 'row', style: 'gap:8px' },
        h('button', { class: 'btn', onclick: async () => { const p = await pickFolder({ title: 'Pick a world folder, or a launcher / instance folder', button: 'Look in this folder' }); if (p) inspect(p); } }, icon('folder'), ' Choose a folder...'),
        h('button', { class: 'btn', onclick: async () => { const p = await pickFolder({ title: 'Pick an archive file that holds a world (.zip, .7z, .rar, .tar.gz ...)', files: 'archive', button: 'Use this file' }); if (p) inspect(p); } }, 'Choose an archive file...')),
      h('div', { class: 'row', style: 'gap:8px;flex-wrap:wrap' }, pathIn, h('button', { class: 'btn', onclick: () => inspect(pathIn.value.trim()) }, 'Look here'))));
  m = modal('Create a server from a world', body, [{ label: 'Cancel', run: (c) => c() }], { wide: true });
  pathIn.addEventListener('keydown', e => { if (e.key === 'Enter') inspect(pathIn.value.trim()); });
  (async () => {
    try {
      const r = await api('GET', '/api/worlds/scan');
      status.textContent = r.worlds.length ? `Found ${r.worlds.length} world${r.worlds.length === 1 ? '' : 's'} on this PC (newest first). Pick one, or choose another place below.` : 'No worlds found in the usual launcher folders. Use the buttons below to pick one from anywhere.';
      show(r.worlds, '');
    } catch (e) { status.textContent = e.message; }
  })();
}

/** The world-specific part of the Add server wizard: world facts, the character, and the mods. */
function worldSection(det) {
  const chk = (checked) => h('input', { type: 'checkbox', checked });
  const box = h('div', { class: 'box', style: 'display:grid;gap:10px' });
  const facts = [det.mc ? 'Minecraft ' + det.mc : 'unknown version', WD_CAP(det.gamemode), WD_CAP(det.difficulty), det.hardcore ? 'hardcore' : '', det.sizeMb != null ? BK_MB(det.sizeMb) : '', det.lastPlayed ? 'played ' + BK_WHEN(det.lastPlayed) : ''].filter(Boolean).join(' · ');
  const ignore = chk(false);
  const head = h('div', { style: 'display:grid;gap:4px' },
    h('div', null, h('b', null, 'World: ' + det.name), h('div', { class: 'dim', style: 'font-size:12.5px' }, facts), h('div', { class: 'dim mono', style: 'font-size:11.5px;overflow-wrap:anywhere' }, det.path)),
    h('div', { class: 'dim', style: 'font-size:12.5px' }, 'Copied into the new server. Seed, world type, difficulty and game mode come from the world.'),
    det.inUse ? h('div', { class: 'notice' }, 'Minecraft seems to have this world open. Close the game first so the copy is complete. ', h('label', null, ignore, ' Copy it anyway')) : null);

  // character
  const char = chk(!!det.hasPlayer);
  const nameIn = h('input', { placeholder: 'Your Minecraft username', maxlength: 16, spellcheck: 'false', autocomplete: 'off', style: 'max-width:240px' });
  const op = chk(true);
  const charBox = det.hasPlayer ? h('div', { style: 'display:grid;gap:6px' },
    h('label', null, char, ' Bring my character over'),
    h('div', { class: 'dim', style: 'font-size:12.5px;margin-left:24px' }, 'In singleplayer your inventory, position and experience live inside the world file, which a server ignores. This saves them under your Minecraft account so you arrive with your things instead of at spawn.'),
    h('div', { class: 'row', style: 'margin-left:24px;gap:8px' }, nameIn, h('label', null, op, ' Make me an operator'))) :
    h('div', { class: 'dim', style: 'font-size:12.5px' }, 'This world has no saved singleplayer character, so there is nothing to bring over.');
  char.addEventListener('change', () => { nameIn.disabled = !char.checked; op.disabled = !char.checked; });

  // mods
  const modChecks = new Map();
  const keep = chk(true);
  const items = (det.mods && det.mods.items) || [];
  const serverMods = items.filter(i => i.server !== 'unsupported'), clientMods = items.filter(i => i.server === 'unsupported' && i.client !== 'unsupported');
  const modsBox = h('div', { style: 'display:grid;gap:6px' });
  if (items.length || (det.mods && det.mods.unknown && det.mods.unknown.length) || det.modsError) {
    modsBox.append(h('div', { style: 'font-weight:600' }, `Mods in your game (${det.modCount})`));
    if (det.modsError) modsBox.append(h('div', { class: 'notice' }, 'The mods could not be looked up on Modrinth (' + det.modsError + '). You can add mods to the server later from the Mods tab.'));
    if (serverMods.length) {
      modsBox.append(h('div', { class: 'dim', style: 'font-size:12.5px' }, 'These can run on a server. Tick the ones to install; helper mods they need are added automatically.'));
      modsBox.append(h('div', { style: 'display:grid;gap:3px;max-height:26vh;overflow:auto' }, serverMods.map(i => {
        const c = chk(true);
        modChecks.set(i.projectId, c);
        return h('label', null, c, ' ', i.title, ' ', h('span', { class: 'dim' }, i.version || ''), i.client === 'required' ? h('span', { class: 'badge', title: 'Players also need this mod on their own game' }, 'players need it too') : null);
      })));
    }
    if (clientMods.length) {
      modsBox.append(h('div', { class: 'dim', style: 'font-size:12.5px' }, `Client-only mods stay on your own game, a server cannot use them: ${clientMods.map(i => i.title).join(', ')}.`));
      modsBox.append(h('label', null, keep, ' Remember them so I can offer them in the client list and modpack for friends'));
    }
    if (det.mods && det.mods.unknown && det.mods.unknown.length)
      modsBox.append(h('div', { class: 'dim', style: 'font-size:12.5px' }, `Not found on Modrinth, so they cannot be installed automatically (copy them by hand if they are server mods): ${det.mods.unknown.join(', ')}.`));
  }
  const vanillaWarn = h('div', { class: 'notice' }, `This world used ${det.modCount || 0} mods. A Vanilla server cannot load them, so modded blocks and items would be lost. Choose Fabric, Forge or NeoForge above to keep them.`);
  const quiltWarn = det.loader === 'quilt' ? h('div', { class: 'notice' }, 'This game uses Quilt, which the manager cannot run. Fabric is the closest match; many Quilt mods also work on Fabric.') : null;
  // JourneyMap waypoints (the .json files that only live on the player's PC become global waypoints on the server)
  const jmSets = [...(det.journeymap || [])];
  const jmOn = chk(jmSets.length > 0);
  const jmSel = h('select', { style: 'max-width:100%;margin-left:24px' });
  const jmInfo = h('div', { class: 'dim', style: 'font-size:12.5px' });
  const jmHead = h('div', null);
  const jmDraw = () => {
    jmSel.replaceChildren(...jmSets.map(s => h('option', { value: s.id }, `${s.label}: ${s.count} waypoint${s.count === 1 ? '' : 's'}`)));
    jmSel.classList.toggle('hidden', jmSets.length < 2);
    jmHead.replaceChildren(jmSets.length
      ? h('label', null, jmOn, ' Import the JourneyMap waypoints that came with this world' + (jmSets.length === 1 ? ' (' + jmSets[0].count + ')' : ''))
      : h('div', { class: 'dim', style: 'font-size:12.5px' }, 'No JourneyMap waypoints were found with this world.'));
  };
  const jmPick = h('button', { class: 'btn', type: 'button', onclick: async () => {
    const p = await pickFolder({ title: 'Pick the folder with the JourneyMap waypoints (the waypoints folder, or a journeymap / .minecraft folder)', button: 'Look in this folder' });
    if (!p) return;
    try {
      const r = await api('POST', '/api/worlds/waypoints', { path: p });
      if (!r.sets.length) { toast('No JourneyMap waypoint files were found in that folder.', true); return; }
      for (const s of r.sets) if (!jmSets.some(x => x.id === s.id)) jmSets.push(s);
      jmDraw();
      jmSel.value = r.sets[0].id;
      jmOn.checked = true;
    } catch (e) { toast(e.message, true); }
  } }, icon('folder'), ' Choose a waypoints folder...');
  const jmBox = h('div', { style: 'display:grid;gap:6px' },
    h('div', { style: 'font-weight:600' }, 'JourneyMap waypoints'), jmHead, jmSel,
    h('div', { class: 'dim', style: 'font-size:12.5px' }, 'JourneyMap normally keeps your waypoints on your own PC only. This adds them to the server as global waypoints, so every player who has the JourneyMap mod sees them. JourneyMap is installed on the server if it is missing. Needs a Fabric, Forge or NeoForge server.'),
    jmInfo, h('div', null, jmPick));
  jmDraw();
  let curType = 'vanilla';
  box.append(...[head, charBox, modsBox, jmBox, vanillaWarn, quiltWarn].filter(Boolean));
  const refresh = (type) => {
    curType = type;
    const modded = type !== 'vanilla';
    modsBox.classList.toggle('hidden', !modded);
    vanillaWarn.classList.toggle('hidden', modded || !det.modCount);
    jmOn.disabled = !modded;
    jmPick.disabled = !modded;
    jmInfo.textContent = modded ? '' : 'Choose Fabric, Forge or NeoForge above to import them.';
  };
  const collect = () => ({
    kind: det.kind, path: det.path, root: det.root || '', ignoreInUse: ignore.checked,
    bringCharacter: !!det.hasPlayer && char.checked, playerName: nameIn.value.trim(), makeOp: op.checked,
    mods: [...modChecks.entries()].filter(([, c]) => c.checked).map(([id]) => id), keepClientMods: keep.checked,
    journeymap: jmSets.length && jmOn.checked && curType !== 'vanilla' ? { id: jmSel.value } : null,
  });
  return { el: box, refresh, collect };
}

/** The same client list as a CurseForge modpack (.zip). Shown only when a CurseForge key is set; it says first which mods CurseForge does not know. */
function cfPackButton(baseUrl) {
  const b = h('button', { class: 'btn hidden', onclick: async () => {
    try {
      const r = await api('GET', baseUrl + '&format=curseforge&dry=1');
      const msg = h('div', { style: 'display:grid;gap:8px' },
        h('div', null, `${r.total - r.missing.length} of ${r.total} mods can be listed. A CurseForge modpack lists mods by CurseForge id, so players’ launchers (CurseForge app, Prism, ATLauncher) download them.`),
        r.missing.length ? h('div', { class: 'notice' }, 'CurseForge does not know these mods, so they are not in the file: ' + r.missing.join(', ') + '. Send those by hand or use the Modrinth modpack instead.') : null);
      if (!await confirmDlg('Download the CurseForge modpack', msg, 'Download')) return;
      window.location.href = baseUrl + '&format=curseforge';
    } catch (e) { toast(e.message, true); }
  } }, 'Download as CurseForge modpack (.zip)');
  if (isAdmin()) api('GET', '/api/curseforge').then(r => { if (r.configured) b.classList.remove('hidden'); }).catch(() => { });
  return b;
}

/** What players need on their own game to join: a copyable list and a Modrinth modpack file, with an explanation of both. */
async function clientPackDialog(s) {
  const wrap = h('div', { style: 'display:grid;gap:12px;width:100%;min-width:0;max-height:72vh;overflow:auto;align-content:start;overflow-wrap:anywhere' }, h('div', { class: 'dim' }, 'Looking up the mods on Modrinth...'));
  const opt = new Set(), extra = new Set();
  let model = null;
  const qs = () => `opt=${encodeURIComponent([...opt].join(','))}&extra=${encodeURIComponent([...extra].join(','))}`;
  const load = async () => {
    try { model = await api('GET', `/api/servers/${s.id}/clientmods?${qs()}`); } catch (e) { wrap.replaceChildren(h('div', { class: 'err' }, e.message)); return; }
    draw();
  };
  const line = (e, ctl) => h('div', { class: 'row', style: 'gap:6px;flex-wrap:nowrap;align-items:flex-start' }, ctl || null,
    h('div', { style: 'flex:1;min-width:0' }, h('a', { href: e.url, target: '_blank', rel: 'noopener' }, e.title), ' ', h('span', { class: 'dim' }, e.version || ''), e.dependency ? h('span', { class: 'badge', title: 'Another mod needs it' }, 'needed by another mod') : null));
  const pick = (set, e, disabled) => h('input', { type: 'checkbox', checked: e.selected, disabled: !!disabled, onchange: (ev) => { if (ev.target.checked) set.add(e.projectId); else set.delete(e.projectId); load(); } });
  const draw = () => {
    const ln = model.loader === 'fabric' ? 'Fabric' : model.loader === 'forge' ? 'Forge' : 'NeoForge';
    const nodes = [];
    nodes.push(h('div', null, `To join ${s.name}, each player needs Minecraft ${model.mc} with ${ln}${model.loaderVersion ? ' ' + model.loaderVersion : ''} and the mods below. Mods that only run on the server are not listed.`));
    nodes.push(h('div', { class: 'box', style: 'display:grid;gap:6px' }, h('h3', null, `Required (${model.required.length})`),
      model.required.length ? model.required.map(e => line(e)) : h('div', { class: 'dim' }, 'No mods are required on the players’ side. Players only need Minecraft with ' + ln + '.')));
    if (model.optional.length) nodes.push(h('div', { class: 'box', style: 'display:grid;gap:6px' }, h('h3', null, 'Optional'),
      h('div', { class: 'dim', style: 'font-size:12.5px' }, 'Installed on the server and helpful on the player’s side, but not needed to join. Tick the ones to put in the list and modpack.'), model.optional.map(e => line(e, pick(opt, e)))));
    if (model.extras.length) nodes.push(h('div', { class: 'box', style: 'display:grid;gap:6px' }, h('h3', null, 'From your own game'),
      h('div', { class: 'dim', style: 'font-size:12.5px' }, 'Client-only mods you used when you played this world, such as a minimap. Tick the ones to share.'),
      model.extras.map(e => h('div', null, line(e, pick(extra, e, !e.compatible)), e.compatible ? null : h('div', { class: 'dim', style: 'font-size:12px;margin-left:24px' }, 'No build for this loader and Minecraft version')))));
    if (model.serverOnly.length) nodes.push(h('div', { class: 'dim', style: 'font-size:12.5px' }, 'Server-only mods players do not need: ' + model.serverOnly.join(', ') + '.'));
    if (model.unknown.length) nodes.push(h('div', { class: 'notice' }, 'Not found on Modrinth, so they are not in the list or modpack. If players need any of these, send the files by hand: ' + model.unknown.join(', ') + '.'));
    for (const w of model.warnings) nodes.push(h('div', { class: 'notice' }, w));
    const ta = h('textarea', { readonly: true, rows: 8, style: 'width:100%;font:12.5px/1.4 ui-monospace,Consolas,monospace' });
    ta.value = model.text;
    const url = `/api/servers/${s.id}/clientpack?${qs()}`;
    nodes.push(h('div', { class: 'box', style: 'display:grid;gap:8px' }, h('h3', null, 'Share it'),
      h('div', { class: 'row', style: 'gap:8px' },
        h('button', { class: 'btn primary', onclick: () => copyText(model.text, ta) }, 'Copy client mods list'),
        h('a', { class: 'btn', href: url, download: '', style: 'text-decoration:none' }, 'Download client modpack (.mrpack)'),
        cfPackButton(url)),
      ta));
    nodes.push(h('details', { class: 'box' }, h('summary', { style: 'cursor:pointer;font-weight:600' }, 'How these two work'),
      h('div', { style: 'display:grid;gap:8px;margin-top:8px' },
        h('div', null, h('b', null, 'Copy client mods list. '), 'Copies a plain-text message to your clipboard: the Minecraft and ', ln, ' versions, then each mod with its Modrinth link. Paste it into a chat. Each friend installs the game version with ', ln, ' (Modrinth App and Prism Launcher do this in a couple of clicks), then adds each mod. Good for one or two mods, or players who like to choose.'),
        h('div', null, h('b', null, 'Download client modpack. '), 'Saves one .mrpack file with a recipe: the exact Minecraft and ', ln, ' versions and every mod with its checksum and download address. Send the file to a friend. In ', h('b', null, 'Modrinth App'), ': Add instance > Import from file. In ', h('b', null, 'Prism Launcher'), ': Add instance > Import. The launcher sets up the right game and downloads and checks every mod itself, so nothing is bundled and nothing is copied from your PC.'),
        h('div', { class: 'dim', style: 'font-size:12.5px' }, 'Neither one changes the server. Players join the usual way with your address. The mod versions listed are the ones installed on the server right now, so make a new list or modpack after you add or update mods.'))));
    const keepTop = wrap.scrollTop;
    wrap.replaceChildren(...nodes);
    wrap.scrollTop = keepTop;
  };
  modal(`What players need to join ${s.name}`, wrap, [{ label: 'Close', cls: 'primary', run: (c) => c() }], { wide: true });
  load();
}
