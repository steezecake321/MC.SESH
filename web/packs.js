// ---------- CurseForge (key, mods) and "Start from a modpack" ----------

/** The CurseForge API key field in Settings. The key is kept on this PC only and never shown again in full. */
function curseKeySection() {
  const box = h('div', { style: 'display:grid;gap:4px' }, h('b', null, 'CurseForge'));
  const note = h('div', { class: 'dim' }, 'Loading...');
  const key = h('input', { type: 'password', placeholder: 'Paste your CurseForge API key', autocomplete: 'new-password', spellcheck: 'false', style: 'flex:1;min-width:200px' });
  const remove = h('button', { class: 'btn sm hidden', type: 'button', onclick: async () => {
    if (!await confirmBox('Remove the CurseForge key', 'CurseForge search and modpacks stop working until a key is added again.', 'Remove', true)) return;
    try { await api('POST', '/api/curseforge', { apiKey: '' }); toast('Key removed'); load(); } catch (e) { toast(e.message, true); }
  } }, 'Remove key');
  const save = h('button', { class: 'btn', type: 'button', onclick: async () => {
    if (!key.value.trim()) return;
    try { await api('POST', '/api/curseforge', { apiKey: key.value.trim() }); key.value = ''; toast('CurseForge key saved'); load(); } catch (e) { toast(e.message, true); }
  } }, 'Save key');
  async function load() {
    try {
      const r = await api('GET', '/api/curseforge');
      note.textContent = r.configured ? `A key is saved (${r.masked}). CurseForge search and modpacks are available. Paste a new one to replace it.` : 'CurseForge needs your own free API key (sign in at console.curseforge.com, open API keys). It is stored only in this manager on this PC.';
      remove.classList.toggle('hidden', !r.configured);
    } catch (e) { note.textContent = e.message; }
  }
  load();
  const link = h('a', { href: 'https://console.curseforge.com/', target: '_blank', rel: 'noopener' }, 'Get your free CurseForge API key at console.curseforge.com');
  box.append(note, h('div', null, link), h('div', { class: 'row', style: 'gap:8px;flex-wrap:wrap' }, key, save, remove));
  return box;
}

const PK_SORTS = [['', 'Most popular'], ['downloads', 'Most downloaded'], ['updated', 'Recently updated'], ['newest', 'Newest']];
const PK_DATE = (d) => (d ? new Date(d).toLocaleDateString() : '');

/** Step 1 of "Start from a modpack": find one on CurseForge or Modrinth, or open a pack file. */
async function packDialog() {
  let cf = { configured: false };
  try { cf = await api('GET', '/api/curseforge'); } catch (e) { /* optional */ }
  let m, source = cf.configured ? 'curseforge' : 'modrinth';
  const q = h('input', { placeholder: 'Search modpacks, e.g. all the mods, better minecraft', style: 'flex:1;min-width:180px', autocomplete: 'off' });
  const sort = h('select', null, PK_SORTS.map(([v, l]) => h('option', { value: v }, l)));
  const list = h('div', { style: 'display:grid;gap:8px;max-height:46vh;overflow:auto;align-content:start' });
  const status = h('div', { class: 'dim', style: 'font-size:12.5px' });
  const more = h('button', { class: 'btn hidden', onclick: () => run(page + 1, true) }, 'Show more');
  const chips = h('div', { class: 'sub' });
  const searchRow = h('div', { class: 'row', style: 'gap:8px;flex-wrap:wrap' }, q, sort, h('button', { class: 'btn', onclick: () => run(0, false) }, 'Search'));
  const fileRow = h('div', { class: 'box', style: 'display:grid;gap:8px' }, h('b', null, 'From a file'),
    h('div', { class: 'dim', style: 'font-size:12.5px' }, 'A modpack you already downloaded: a CurseForge .zip (the one with manifest.json) or a Modrinth .mrpack.'),
    h('div', null, h('button', { class: 'btn', onclick: async () => {
      const p = await pickFolder({ title: 'Pick a modpack file (.zip or .mrpack)', files: 'pack', button: 'Use this file' });
      if (p) choosePack({ path: p }, null);
    } }, icon('folder'), ' Choose a modpack file...')));
  let page = 0, hits = [], seq = 0;

  const drawChips = () => {
    chips.replaceChildren(...[['curseforge', 'CurseForge'], ['modrinth', 'Modrinth'], ['file', 'From a file']].map(([k, l]) => h('button', { class: 'chip' + (source === k ? ' on' : ''), type: 'button', onclick: () => { source = k; drawChips(); fit(); if (k !== 'file') run(0, false); } }, l)));
  };
  const fit = () => {
    const f = source === 'file';
    searchRow.classList.toggle('hidden', f);
    list.classList.toggle('hidden', f);
    status.classList.toggle('hidden', f);
    more.classList.toggle('hidden', f || true);
    fileRow.classList.toggle('hidden', !f);
  };
  const card = (h0) => h('div', { class: 'found', style: 'display:flex;gap:10px;align-items:flex-start' },
    h0.icon ? h('img', { src: h0.icon, alt: '', width: 44, height: 44, style: 'border-radius:8px;flex:none;background:var(--panel2)', loading: 'lazy' }) : h('div', { style: 'width:44px;height:44px;flex:none' }),
    h('div', { style: 'flex:1;min-width:0' },
      h('div', { class: 'row', style: 'justify-content:space-between;gap:8px;align-items:flex-start' },
        h('div', null, h('b', null, h0.title), ' ', h('span', { class: 'dim', style: 'font-size:12.5px' }, 'by ' + (h0.author || '?') + ' · ' + fmtCount(h0.downloads) + ' downloads')),
        h('button', { class: 'btn sm primary', onclick: () => versionStep(h0) }, 'Choose')),
      h('div', { class: 'dim', style: 'font-size:13px;margin-top:2px' }, h0.summary || '')));
  async function run(p, append) {
    if (source === 'file') return;
    const my = ++seq;
    status.textContent = 'Searching...';
    try {
      const r = await api('GET', `/api/packs/search?source=${source}&q=${encodeURIComponent(q.value.trim())}&sort=${encodeURIComponent(sort.value)}&page=${p}`);
      if (my !== seq) return;
      page = p;
      hits = append ? hits.concat(r.hits) : r.hits;
      list.replaceChildren(...hits.map(card));
      status.textContent = `${r.total} modpack${r.total === 1 ? '' : 's'} found.`;
      more.classList.toggle('hidden', (page + 1) * 20 >= r.total);
    } catch (e) {
      if (my !== seq) return;
      list.replaceChildren(h('div', { class: 'err' }, e.message));
      status.textContent = '';
      more.classList.add('hidden');
    }
  }
  /** Step 2: pick which version of the pack. */
  async function versionStep(pack) {
    let vs;
    try { vs = (await api('GET', `/api/packs/versions?source=${source}&id=${encodeURIComponent(pack.id)}`)).versions; } catch (e) { toast(e.message, true); return; }
    if (!vs.length) { toast('That modpack has no downloadable versions.', true); return; }
    const sel = h('select', { style: 'max-width:100%' }, vs.map(v => h('option', { value: v.id, disabled: !v.downloadable },
      `${v.name}${v.mc ? ' · Minecraft ' + v.mc : ''}${v.loader ? ' · ' + v.loader : ''}${v.date ? ' · ' + PK_DATE(v.date) : ''}${v.downloadable ? '' : ' (not downloadable here)'}`)));
    const firstOk = vs.find(v => v.downloadable);
    if (firstOk) sel.value = firstOk.id;
    const body = h('div', { style: 'display:grid;gap:10px;width:100%;min-width:0' },
      h('div', null, h('b', null, pack.title), pack.url ? h('span', null, ' ', h('a', { href: pack.url, target: '_blank', rel: 'noopener' }, 'open the page')) : null),
      h('div', { class: 'dim' }, pack.summary || ''),
      h('label', { class: 'dim', style: 'display:grid;gap:2px' }, 'Version', sel),
      h('div', { class: 'dim', style: 'font-size:12.5px' }, 'The pack file is downloaded and read first, so you see which Minecraft and loader it needs before anything is created.'));
    const m2 = modal('Choose a version', body, [
      { label: 'Back', run: (c) => c() },
      { label: 'Use this version', cls: 'primary', run: async (c) => { c(); choosePack({ source, id: pack.id, fileId: sel.value }, pack); } },
    ]);
    return m2;
  }
  /** Reads the pack and opens the Add server wizard prefilled from it. */
  async function choosePack(ref, pack) {
    toast('Reading the modpack...');
    try {
      const info = await api('POST', '/api/packs/inspect', ref);
      if (info.problem) { toast(info.problem, true); return; }
      if (pack && !info.name) info.name = pack.title;
      if (m) m.close();
      addServerWizard({ pack: { ref, info } });
    } catch (e) { toast(e.message, true); }
  }
  q.addEventListener('keydown', (e) => { if (e.key === 'Enter') run(0, false); });
  sort.addEventListener('change', () => run(0, false));
  const body = h('div', { style: 'display:grid;gap:10px;width:100%;min-width:0' },
    h('div', null, 'Build a server from a modpack: the manager reads what the pack needs, installs the right Minecraft and loader, downloads every mod that runs on a server and copies the pack’s config files.'),
    !cf.configured ? h('div', { class: 'notice' }, 'CurseForge search needs your own API key (Settings > CurseForge). Modrinth search and files work without it.') : null,
    chips, searchRow, status, list, more, fileRow);
  m = modal('Start from a modpack', body, [{ label: 'Close', run: (c) => c() }], { wide: true });
  drawChips();
  fit();
  if (source !== 'file') run(0, false);
}

/** Search and install mods from CurseForge on the Mods tab (needs the API key). */
function curseBox(s) {
  const box = h('div', { class: 'box', style: 'display:grid;gap:8px' }, h('h3', null, 'Add mods from CurseForge'));
  const body = h('div', { style: 'display:grid;gap:8px' });
  box.append(body);
  (async () => {
    let st;
    try { st = await api('GET', '/api/curseforge'); } catch (e) { body.append(h('div', { class: 'err' }, e.message)); return; }
    if (!st.configured) {
      body.append(h('div', { class: 'dim' }, 'CurseForge needs your own free API key (console.curseforge.com). Add it in Settings > CurseForge, then search and install from here.'),
        h('div', null, h('button', { class: 'btn', onclick: () => settingsDialog() }, 'Open Settings')));
      return;
    }
    const q = h('input', { placeholder: 'Search CurseForge mods', style: 'flex:1;min-width:160px', autocomplete: 'off' });
    const sort = h('select', null, PK_SORTS.map(([v, l]) => h('option', { value: v }, l)));
    const list = h('div', { style: 'display:grid;gap:8px' });
    const status = h('div', { class: 'dim', style: 'font-size:12.5px' });
    const more = h('button', { class: 'btn hidden', onclick: () => run(page + 1, true) }, 'Show more');
    let page = 0, hits = [], seq = 0;
    const card = (x) => h('div', { class: 'found', style: 'display:flex;gap:10px;align-items:flex-start' },
      x.icon ? h('img', { src: x.icon, alt: '', width: 40, height: 40, style: 'border-radius:8px;flex:none;background:var(--panel2)', loading: 'lazy' }) : h('div', { style: 'width:40px;height:40px;flex:none' }),
      h('div', { style: 'flex:1;min-width:0' },
        h('div', { class: 'row', style: 'justify-content:space-between;gap:8px;align-items:flex-start' },
          h('div', null, h('b', null, x.title), ' ', h('span', { class: 'dim', style: 'font-size:12.5px' }, 'by ' + (x.author || '?') + ' · ' + fmtCount(x.downloads) + ' downloads')),
          h('button', { class: 'btn sm' + (x.installed ? '' : ' primary'), disabled: x.installed, onclick: () => plan(x) }, x.installed ? 'Installed' : 'Install')),
        h('div', { class: 'dim', style: 'font-size:13px;margin-top:2px' }, x.summary || '')));
    async function run(p, append) {
      const my = ++seq;
      status.textContent = 'Searching...';
      try {
        const r = await api('GET', `/api/servers/${s.id}/curseforge/search?q=${encodeURIComponent(q.value.trim())}&sort=${encodeURIComponent(sort.value)}&page=${p}`);
        if (my !== seq) return;
        page = p;
        hits = append ? hits.concat(r.hits) : r.hits;
        list.replaceChildren(...hits.map(card));
        status.textContent = `${r.total} mod${r.total === 1 ? '' : 's'} for ${r.loader} on Minecraft ${r.mc}. CurseForge does not say which mods are client-only; the manager warns when Modrinth knows one is.`;
        more.classList.toggle('hidden', (page + 1) * 20 >= r.total);
      } catch (e) {
        if (my !== seq) return;
        list.replaceChildren(h('div', { class: 'err' }, e.message));
        status.textContent = '';
        more.classList.add('hidden');
      }
    }
    async function plan(x) {
      try {
        const p = await api('POST', `/api/servers/${s.id}/curseforge/plan`, { mods: [x.id] });
        if (!p.items.length) { toast(p.warnings[0] || 'Nothing to install.', true); return; }
        const rows = p.items.map(i => h('div', { class: 'row', style: 'gap:8px' }, i.icon ? h('img', { src: i.icon, alt: '', width: 28, height: 28, style: 'border-radius:6px' }) : null,
          h('div', null, h('b', null, i.title), ' ', h('span', { class: 'dim' }, i.version || ''), i.dependency ? h('span', { class: 'badge', style: 'margin-left:6px' }, 'needed by another mod') : null,
            i.manual ? h('span', { class: 'badge be', style: 'margin-left:6px' }, 'download by hand') : null,
            h('div', { class: 'dim mono', style: 'font-size:12px' }, `${i.filename} (${Math.max(1, Math.round((i.size || 0) / 1024))} KB)`))));
        const ok = await confirmDlg(`Install ${p.items.length} file${p.items.length === 1 ? '' : 's'}?`, h('div', { style: 'display:grid;gap:8px;width:100%;min-width:0' },
          h('div', { class: 'dim' }, `For ${p.loader} on Minecraft ${p.mc}. Each download is checked against CurseForge's checksum.`), ...rows, ...p.warnings.map(w => h('div', { class: 'notice' }, w)),
          sv() && sv().state !== 'stopped' ? h('div', { class: 'dim' }, 'The server is running: the mods load after the next restart.') : null), 'Install');
        if (!ok) return;
        await startJob(s, 'curseforge/install', { mods: [x.id] }, 'Install ' + x.title, { onDone: () => run(0, false) });
      } catch (e) { toast(e.message, true); }
    }
    q.addEventListener('keydown', (e) => { if (e.key === 'Enter') run(0, false); });
    sort.addEventListener('change', () => run(0, false));
    body.append(h('div', { class: 'row', style: 'gap:8px;flex-wrap:wrap' }, q, sort, h('button', { class: 'btn', onclick: () => run(0, false) }, 'Search')), status, list, more);
    run(0, false);
  })();
  return { el: box };
}
