// ---------- Modrinth: find and install server-side mods ----------
function fmtCount(n) {
  n = Number(n) || 0;
  return n >= 1e6 ? (n / 1e6).toFixed(1) + 'M' : n >= 1e3 ? Math.round(n / 1e3) + 'K' : String(n);
}

/**
 * Search box + selection tray for installing server mods from Modrinth.
 * opts.preselect: [{ id, title }] mods that start in the tray (id may be a project id or a slug).
 */
function modrinthBox(s, opts = {}) {
  const box = h('div', { class: 'box' }, h('h3', null, 'Add mods from Modrinth'));
  const q = h('input', { placeholder: 'Search server mods, e.g. lithium, spark, ferrite core', style: 'flex:1;min-width:160px', autocomplete: 'off' });
  const sort = h('select', null, [['', 'Best match'], ['downloads', 'Most downloaded'], ['updated', 'Recently updated'], ['newest', 'Newest']].map(([v, l]) => h('option', { value: v }, l)));
  const list = h('div', { style: 'display:grid;gap:8px;margin-top:10px' });
  const more = h('button', { class: 'btn', style: 'margin-top:8px', onclick: () => run(page + 1, true) }, 'Show more');
  const status = h('div', { class: 'dim', style: 'font-size:12.5px;margin-top:6px' });
  const tray = h('div', { style: 'position:sticky;bottom:0;background:var(--panel);border-top:1px solid var(--line);margin:10px -2px -2px;padding:8px 2px;display:flex;gap:8px;align-items:center;flex-wrap:wrap' });
  let page = 0, seq = 0, hits = [];
  const picked = new Map();
  for (const p of opts.preselect || []) picked.set(p.id, p);

  const isPicked = (m) => picked.has(m.id) || picked.has(m.slug);
  const drawTray = () => {
    const chips = [...picked.values()].map(p => h('span', { class: 'badge', style: 'display:inline-flex;gap:4px;align-items:center' }, p.title || p.id,
      h('button', { class: 'btn ghost', style: 'padding:0 4px;min-height:0;line-height:1', title: 'Remove from the list', onclick: () => { picked.delete(p.id); draw(); } }, '×')));
    tray.replaceChildren(...[
      picked.size ? h('b', null, `${picked.size} selected:`) : h('span', { class: 'dim' }, 'Press Add on the mods you want, then Install.'),
      ...chips,
      h('button', { class: 'btn primary', style: 'margin-left:auto', disabled: !picked.size, onclick: () => plan() }, picked.size ? `Install ${picked.size} mod${picked.size === 1 ? '' : 's'}` : 'Install')]);
  };

  const card = (m) => {
    const need = m.client === 'required' ? h('span', { class: 'badge be', title: 'Players must also install this mod on their own game' }, 'Players need it too')
      : m.client === 'optional' ? h('span', { class: 'badge', title: 'Works on the server alone; players can add it for extras' }, 'Optional for players')
        : h('span', { class: 'badge' }, 'Server only');
    const on = isPicked(m);
    const btn = h('button', { class: 'btn sm' + (on ? '' : ' primary'), disabled: m.installed, onclick: () => {
      if (isPicked(m)) { picked.delete(m.id); picked.delete(m.slug); } else picked.set(m.id, { id: m.id, title: m.title, icon: m.icon, client: m.client });
      draw();
    } }, m.installed ? 'Installed' : on ? 'Added ✓' : 'Add');
    return h('div', { class: 'found', style: 'display:flex;gap:10px;align-items:flex-start' },
      m.icon ? h('img', { src: m.icon, alt: '', width: 40, height: 40, style: 'border-radius:8px;flex:none;background:var(--panel2)', loading: 'lazy' }) : h('div', { style: 'width:40px;height:40px;flex:none' }),
      h('div', { style: 'flex:1;min-width:0' },
        h('div', { class: 'row', style: 'justify-content:space-between;gap:8px;align-items:flex-start' },
          h('div', null, h('b', null, m.title), ' ', h('span', { class: 'dim', style: 'font-size:12.5px' }, 'by ' + m.author + ' · ' + fmtCount(m.downloads) + ' downloads')), btn),
        h('div', { class: 'dim', style: 'font-size:13px;margin:2px 0 4px' }, m.summary || ''), need));
  };

  const draw = () => { list.replaceChildren(...hits.map(card)); drawTray(); };

  async function run(p, append) {
    const my = ++seq;
    status.textContent = 'Searching...';
    try {
      const r = await api('GET', `/api/servers/${s.id}/modrinth/search?q=${encodeURIComponent(q.value.trim())}&sort=${encodeURIComponent(sort.value)}&page=${p}`);
      if (my !== seq) return;
      page = p;
      hits = append ? hits.concat(r.hits) : r.hits;
      for (const m of r.hits) if (m.installed) { picked.delete(m.id); picked.delete(m.slug); } // already on the server: nothing to add
      draw();
      status.textContent = `${r.total} mod${r.total === 1 ? '' : 's'} for ${r.loader} on Minecraft ${r.mc}. Client-only mods are hidden.`;
      more.classList.toggle('hidden', (page + 1) * 20 >= r.total);
    } catch (e) {
      if (my !== seq) return;
      list.replaceChildren();
      more.classList.add('hidden');
      status.textContent = '';
      list.append(h('div', { class: 'err' }, e.message));
    }
  }

  async function plan() {
    const projects = [...picked.keys()];
    if (!projects.length) return;
    try {
      const p = await api('POST', `/api/servers/${s.id}/modrinth/plan`, { projects });
      const rows = p.items.map(i => h('div', { class: 'row', style: 'gap:8px' }, i.icon ? h('img', { src: i.icon, alt: '', width: 28, height: 28, style: 'border-radius:6px' }) : null,
        h('div', null, h('b', null, i.title), ' ', h('span', { class: 'dim' }, i.version), i.dependency ? h('span', { class: 'badge', style: 'margin-left:6px' }, 'needed by another mod') : null,
          h('div', { class: 'dim mono', style: 'font-size:12px' }, `${i.filename} (${Math.max(1, Math.round((i.size || 0) / 1024))} KB)`))));
      const needClient = p.items.filter(i => i.client === 'required' && !i.dependency).map(i => i.title);
      const ok = await new Promise(res => {
        let done = false;
        const fin = (v) => { if (!done) { done = true; res(v); } };
        modal(`Install ${p.items.length} file${p.items.length === 1 ? '' : 's'}?`, h('div', { style: 'display:grid;gap:8px;width:100%;min-width:0' },
          h('div', { class: 'dim' }, `For ${p.loader} on Minecraft ${p.mc}. Each file is checked against Modrinth's checksum.`), ...rows,
          ...p.warnings.map(w => h('div', { class: 'notice' }, w)),
          needClient.length ? h('div', { class: 'notice' }, `Players must also install ${needClient.join(', ')} on their own game to join.`) : null,
          sv() && sv().state !== 'stopped' ? h('div', { class: 'dim' }, 'The server is running: the mods load after the next restart.') : null),
        [{ label: 'Cancel', run: (c) => { fin(false); c(); } }, { label: 'Install', cls: 'primary', run: (c) => { fin(true); c(); } }], { onClose: () => fin(false) });
      });
      if (!ok) return;
      await startJob(s, 'modrinth/install', { projects }, projects.length === 1 ? 'Install ' + (picked.get(projects[0]).title || 'mod') : `Install ${projects.length} mods`, {
        onDone: (state) => {
          if (state === 'done') picked.clear();
          run(0, false);
          box.dispatchEvent(new CustomEvent('installed'));
          if (opts.onInstalled) opts.onInstalled(state);
        },
      });
    } catch (e) { toast(e.message, true); }
  }

  q.addEventListener('keydown', (e) => { if (e.key === 'Enter') run(0, false); });
  sort.addEventListener('change', () => run(0, false));
  box.append(h('div', { class: 'dim', style: 'font-size:12.5px;margin-bottom:8px' }, 'Only mods that run on a server are shown, matched to this server’s loader and Minecraft version. Required helper mods (like Fabric API) are added for you.'),
    h('div', { class: 'row', style: 'gap:8px;flex-wrap:wrap' }, q, sort, h('button', { class: 'btn', onclick: () => run(0, false) }, 'Search')), status, list, more, tray);
  more.classList.add('hidden');
  drawTray();
  run(0, false);
  return { el: box, rerun: () => run(page, false) };
}

/** The prompt shown right after a new server is created when the "I plan to add mods" box was ticked. */
async function addModsWindow(s, opts = {}) {
  let haveApi = false;
  try { haveApi = (await api('GET', `/api/servers/${s.id}/mods`)).mods.some(m => /fabric-api/i.test(m.file)); } catch (e) { /* ignore */ }
  const pre = s.kind === 'fabric' && !haveApi;
  const mr = modrinthBox(s, { preselect: pre ? [{ id: 'fabric-api', title: 'Fabric API' }] : [] });
  modal(`Add mods to ${s.name}`, h('div', { style: 'display:grid;gap:10px;width:100%;min-width:0;max-height:68vh;overflow:auto;align-content:start' },
    h('div', { class: 'dim' }, (opts.converted ? `${s.name} now has a mod loader and is stopped. Pick the mods you want; they load the next time the server starts.` : `${s.name} has been created and is stopped. Pick the mods you want now and they are installed before its first start.`) + (pre ? ' Fabric API is already selected because most Fabric mods need it; remove it if you do not want it.' : (s.kind === 'fabric' && haveApi ? ' Fabric API is already installed.' : ''))),
    mr.el), [{ label: 'Skip / Done', cls: 'primary', run: (c) => c() }], { wide: true });
}
