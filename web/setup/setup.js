/* MC.SESH setup wizard (runs in the browser; the install itself is done by the Java backend behind /api/setup/*). */
(() => {
  'use strict';
  const $ = (s, r = document) => r.querySelector(s);
  const esc = (s) => String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const api = async (method, url, body) => {
    const r = await fetch(url, { method, headers: body ? { 'Content-Type': 'application/json' } : undefined, body: body ? JSON.stringify(body) : undefined });
    const j = await r.json().catch(() => ({}));
    if (!r.ok) throw new Error(j.error || 'Request failed (' + r.status + ')');
    return j;
  };

  const S = {
    info: {}, installDir: 'C:\\MC.SESH', serversDir: '', backupsDir: '', backupsTouched: false, port: 8585, firewall: true, shortcuts: true, startup: 'off',
    ownerUser: '', ownerPass: '', ownerPass2: '', cfKey: '',
    d: { type: 'vanilla', maxMb: 4096, difficulty: 'easy', gamemode: 'survival', maxPlayers: 20, bedrock: false, journeymap: false, planMods: false, whitelist: false, autostart: false, lanBroadcast: true },
    existing: false,
  };
  const LOADERS = { fabric: 'Fabric', forge: 'Forge', neoforge: 'NeoForge' };

  // ---------- pages ----------
  const PAGES = [
    { id: 'welcome', title: 'Welcome' },
    { id: 'install', title: 'Install folder' },
    { id: 'servers', title: 'Servers and backups' },
    { id: 'port', title: 'Web page and phone' },
    { id: 'account', title: 'Owner account' },
    { id: 'cf', title: 'CurseForge key' },
    { id: 'defaults', title: 'New-server defaults' },
    { id: 'startup', title: 'Shortcuts and startup' },
    { id: 'ready', title: 'Ready to install' },
    { id: 'run', title: 'Installing' },
  ];
  let idx = 0, busy = false;

  function pathField(id, label, key, hint) {
    return '<label class="f">' + label + '<input type="text" id="' + id + '" value="' + esc(S[key]) + '" spellcheck="false"></label><div class="drv" id="' + id + 'Info">' + (hint || '') + '</div>';
  }
  async function showPath(id, key) {
    const el = $('#' + id), out = $('#' + id + 'Info');
    S[key] = el.value.trim();
    out.className = 'drv'; out.textContent = 'Checking...';
    try {
      const r = await api('POST', '/api/setup/path', { path: S[key] });
      if (!r.ok) { out.className = 'drv bad'; out.textContent = r.message; return false; }
      out.className = 'drv ok';
      out.textContent = r.freeGb + ' GB free on ' + r.root + (r.existing && key === 'installDir' ? '  -  MC.SESH is already installed here: your settings and servers are kept.' : '');
      if (key === 'installDir') S.existing = !!r.existing;
      return true;
    } catch (e) { out.className = 'drv bad'; out.textContent = e.message; return false; }
  }
  const debounce = (fn, ms) => { let t; return (...a) => { clearTimeout(t); t = setTimeout(() => fn(...a), ms); }; };

  const render = {
    welcome() {
      return '<div class="hero"><img src="logo.png" alt=""><div><h1>MC.SESH</h1><p class="sub" style="margin:0">Minecraft Server Easy Self Hosting</p></div></div>' +
        '<p>MC.SESH runs and manages your Minecraft servers from one web page.</p>' +
        '<div class="killer">&#9733; Turn your single-player worlds into custom servers!</div>' +
        '<ul class="bul"><li>Create and manage local servers: Vanilla, Fabric, Forge and NeoForge</li><li>Add mods and modpacks from Modrinth and CurseForge</li><li>Back up, schedule and restore your servers</li>' +
        '<li>Keep Fabric servers, mods and Minecraft up to date</li><li>Let friends and family join on your home network, each with their own login</li><li>Let Bedrock players join your Java servers</li>' +
        '<li>Show your servers under "LAN worlds" in Minecraft, so players on your network find them without typing an address</li>' +
        '<li>Use it from your phone or any browser on your network</li><li>Bring in servers you already have</li></ul>' +
        '<p class="dim">Your servers and settings are kept if you are updating. Tip: press <b>Play music</b> (bottom left) for the setup soundtrack.</p>' +
        '<div class="credit"><img src="steeze.jpg" alt=""><span>Brought to you by <b>Steeze Cake</b></span></div>';
    },
    install() {
      return '<h2>Where should MC.SESH be installed?</h2><p class="sub">MC.SESH itself is small (about 60 MB). Your servers and backups go on the next page.</p>' + pathField('installDir', 'Install folder', 'installDir') +
        '<div class="note">Pick a folder you own, such as <b>C:\\MC.SESH</b> or <b>D:\\Apps\\MC.SESH</b>. Windows and Program Files folders need administrator rights and are not a good fit.</div>';
    },
    servers() {
      const drives = (S.info.drives || []).map((d) => esc(d.root) + ' ' + d.freeGb + ' GB free of ' + d.totalGb + ' GB').join('   |   ');
      return '<h2>Where should your servers and backups go?</h2><p class="sub">New servers and their backups each get a folder. You can pick a different place each time you create a server.</p>' +
        pathField('serversDir', 'Servers folder', 'serversDir') + pathField('backupsDir', 'Backups folder', 'backupsDir') +
        '<div class="note"><b>Free space:</b> ' + (drives || 'unknown') + '<br>Worlds and backups can get big, so a roomy drive is best. Backups go in "_backups" inside the servers folder unless you choose another place.</div>';
    },
    port() {
      const addr = S.info.ip || 'this-PC-address';
      return '<h2>Web page and phone</h2><p class="sub">MC.SESH is a web page that runs on this PC.</p>' +
        '<label class="f" style="max-width:180px">Port<input type="number" id="port" min="1024" max="65535" value="' + S.port + '"></label>' +
        '<label class="chk"><input type="checkbox" id="firewall"' + (S.firewall ? ' checked' : '') + '><span>Add a Windows Firewall rule: allow other devices on my home network to open MC.SESH<small>Needed to use MC.SESH from your phone or another PC. Windows will ask you to approve it.</small></span></label>' +
        '<div class="note"><b>MC.SESH can be used from your phone too!</b> Once it is running, open <b>http://' + esc(addr) + ':<span id="portEcho">' + S.port + '</span></b> in your phone\'s browser while on the same Wi-Fi.<br><br>' +
        'It can even open like an app, with its own Home Screen icon:<br>&bull; iPhone (Safari): Share &gt; Add to Home Screen.<br>&bull; Android (Chrome/Firefox): Menu &gt; Add to Home screen.</div>' +
        (S.info.ip ? '<div class="note">This PC: <b>' + esc(S.info.ip) + '</b>' + (S.info.mac ? '   MAC address: <b>' + esc(S.info.mac) + '</b>' : '') + '<br>If the address ever stops working, check the header in MC.SESH for the current one. To keep it from changing, enable <b>DHCP reservation</b> (or a static IP) on your router so it always gives this PC the same address. Recommended.' +
          (S.info.gateway ? '<br><br><a href="http://' + esc(S.info.gateway) + '/" target="_blank" rel="noopener"><button class="alt" type="button">Open my router\'s page (' + esc(S.info.gateway) + ')</button></a> <span class="dim">If it does not open, check the sticker on the router.</span>' : '') + '</div>' : '');
    },
    account() {
      return '<h2>Create the owner account</h2><p class="sub">The owner is the MC.SESH administrator (OP5). Other people get their own logins from invites later.</p>' +
        '<label class="f">Username<input type="text" id="ownerUser" value="' + esc(S.ownerUser) + '" autocomplete="off" placeholder="3-32 letters, numbers, dot, dash or underscore"></label>' +
        '<div class="cols"><label class="f">Password<input type="password" id="ownerPass" value="' + esc(S.ownerPass) + '" autocomplete="new-password"></label><label class="f">Repeat password<input type="password" id="ownerPass2" value="' + esc(S.ownerPass2) + '" autocomplete="new-password"></label></div>' +
        '<label class="chk"><input type="checkbox" id="showPw"><span>Show password</span></label>' +
        '<div class="note">At least 8 characters. Prefer to do this later? Leave everything blank and MC.SESH asks you to create the owner the first time you open it.</div>';
    },
    cf() {
      return '<h2>CurseForge key (optional)</h2><p class="sub">Needed only to browse CurseForge modpacks. Modrinth works without a key.</p>' +
        '<label class="f">CurseForge API key<input type="password" id="cfKey" value="' + esc(S.cfKey) + '" autocomplete="off" spellcheck="false"></label>' +
        '<div class="note">Get your own free key at <a href="https://console.curseforge.com/" target="_blank" rel="noopener">console.curseforge.com</a> (sign in, then open API Keys). MC.SESH checks the key during installation. If it does not work you can enter it again later in Settings.</div>' +
        '<button class="alt" type="button" id="cfSkip">Skip for now</button>';
    },
    defaults() {
      const d = S.d, ram = S.info.ramGb || 8, mems = [2048, 4096, 6144, 8192, 12288, 16384].filter((m, i) => i === 0 || m / 1024 <= ram - 2);
      const sel = (id, opts, v) => '<select id="' + id + '">' + opts.map(([k, l]) => '<option value="' + k + '"' + (String(k) === String(v) ? ' selected' : '') + '>' + l + '</option>').join('') + '</select>';
      const chk = (id, text, on, small) => '<label class="chk"><input type="checkbox" id="' + id + '"' + (on ? ' checked' : '') + '><span>' + text + (small ? '<small>' + small + '</small>' : '') + '</span></label>';
      return '<h2>Create-server defaults</h2><p class="sub">These only pre-fill the "create server" form. Global Defaults can be changed later in Settings.</p>' +
        '<div class="cols"><label class="f">Server type' + sel('d_type', [['vanilla', 'Vanilla (no mods)'], ['fabric', 'Fabric (mods)'], ['forge', 'Forge (mods)'], ['neoforge', 'NeoForge (mods)']], d.type) + '</label>' +
        '<label class="f">Memory (max) - this PC has ' + ram + ' GB' + sel('d_mem', mems.map((m) => [m, (m / 1024) + ' GB']), d.maxMb) + '</label></div>' +
        '<div class="cols3"><label class="f">Difficulty' + sel('d_diff', [['peaceful', 'Peaceful'], ['easy', 'Easy'], ['normal', 'Normal'], ['hard', 'Hard']], d.difficulty) + '</label>' +
        '<label class="f">Game mode' + sel('d_mode', [['survival', 'Survival'], ['creative', 'Creative'], ['adventure', 'Adventure'], ['spectator', 'Spectator']], d.gamemode) + '</label>' +
        '<label class="f">Max players<input type="number" id="d_players" min="1" max="500" value="' + d.maxPlayers + '"></label></div>' +
        '<div class="note warn hidden" id="dNote" style="display:none"></div>' +
        chk('d_bedrock', 'Let Bedrock players join (adds Geyser; Vanilla servers switch to Fabric)', d.bedrock) +
        chk('d_jm', 'Install JourneyMap (live map and waypoints; Vanilla servers switch to Fabric)', d.journeymap) +
        chk('d_mods', 'I usually add mods (open the mod picker right after creating a modded server)', d.planMods) +
        chk('d_wl', 'Turn the whitelist on', d.whitelist) + chk('d_auto', 'Start servers automatically when MC.SESH starts', d.autostart) +
        chk('d_lan', 'Show servers under LAN worlds in Minecraft (same network only; some Wi-Fi blocks it)', d.lanBroadcast);
    },
    startup() {
      const r = (v, t, small) => '<label class="chk"><input type="radio" name="st" value="' + v + '"' + (S.startup === v ? ' checked' : '') + '><span>' + t + '<small>' + small + '</small></span></label>';
      return '<h2>Shortcuts and startup</h2><p class="sub">How should MC.SESH start?</p>' +
        '<label class="chk"><input type="checkbox" id="shortcuts"' + (S.shortcuts ? ' checked' : '') + '><span>Create Desktop and Start Menu shortcuts</span></label><br>' +
        r('off', 'Do not start MC.SESH automatically', 'You start it yourself from the shortcut.') +
        r('logon', 'When I sign in to Windows', 'MC.SESH starts as soon as you sign in.') +
        r('boot', 'When the PC starts, before anyone signs in', 'Servers come back after a reboot, even if nobody signs in. MC.SESH then runs as the Windows system account.') +
        '<div class="note">You can change this any time in MC.SESH under <b>Settings &gt; Start with Windows</b>.</div>';
    },
    ready() {
      const d = S.d, type = effectiveType();
      const rows = [['Install folder', S.installDir + (S.existing ? ' (existing install: settings kept)' : '')], ['Servers folder', S.serversDir], ['Backups folder', S.backupsDir],
        ['Web page port', S.port + (S.firewall ? ' (Windows Firewall rule will be added)' : ' (no firewall rule)')],
        ['Owner account', S.existing ? 'An account already exists' : S.ownerUser ? S.ownerUser + ' (administrator)' : 'Will be created when you first open MC.SESH'],
        ['CurseForge key', S.cfKey ? 'Entered (checked during installation)' : 'Skipped (add it later in Settings)'],
        ['Defaults', type + ', ' + d.maxMb / 1024 + ' GB, ' + d.difficulty + ', ' + d.gamemode + ', ' + d.maxPlayers + ' players'],
        ['Extras', 'Bedrock: ' + (d.bedrock ? 'yes' : 'no') + ', JourneyMap: ' + (d.journeymap ? 'yes' : 'no') + ', whitelist: ' + (d.whitelist ? 'yes' : 'no') + ', LAN worlds: ' + (d.lanBroadcast ? 'yes' : 'no')],
        ['Shortcuts / startup', (S.shortcuts ? 'shortcuts' : 'no shortcuts') + ' / ' + { off: 'manual start', logon: 'start at sign-in', boot: 'start with the PC' }[S.startup]]];
      return '<h2>Ready to install</h2><p class="sub">Check your choices, then press Install.</p><dl class="sum">' + rows.map(([k, v]) => '<dt>' + esc(k) + '</dt><dd>' + esc(v) + '</dd>').join('') + '</dl>' +
        '<div class="note"><b>What happens next:</b> the files are copied, then MC.SESH starts and opens right here in this tab. ' + (S.firewall || S.startup !== 'off' ? 'Windows will ask you to approve a step. Look for the shield prompt. ' : '') +
        'Sign in with your owner account. A welcome box then offers to search this PC for existing servers and single-player worlds to bring in (you can skip it). Use the +Add Server button any time to create or import more.</div>';
    },
    run() {
      return '<h2 id="runTitle">Installing...</h2><p class="sub" id="runSub">This usually takes under a minute.</p><div class="bar"><i id="runBar"></i></div><pre class="log" id="runLog"></pre><div id="runErr"></div>';
    },
  };

  function effectiveType() {
    const d = S.d;
    return (d.bedrock || d.journeymap) && d.type === 'vanilla' ? 'fabric' : d.type;
  }

  // ---------- navigation ----------
  function drawSteps() {
    $('#steps').innerHTML = PAGES.slice(0, -1).map((p, i) => '<div class="step ' + (i === idx ? 'on' : i < idx ? 'done' : '') + '"><i>' + (i < idx ? '&#10003;' : i + 1) + '</i>' + esc(p.title) + '</div>').join('');
  }
  async function show(i) {
    idx = i;
    const p = PAGES[idx];
    $('#page').innerHTML = render[p.id]();
    $('#page').scrollTop = 0;
    drawSteps();
    $('#back').style.visibility = idx === 0 || p.id === 'run' ? 'hidden' : 'visible';
    $('#next').textContent = p.id === 'ready' ? 'Install' : p.id === 'run' ? 'Finish' : 'Next';
    $('#next').style.display = p.id === 'run' ? 'none' : '';
    $('#hint').textContent = '';
    wire(p.id);
  }

  function wire(id) {
    if (id === 'install') { const f = () => showPath('installDir', 'installDir'); $('#installDir').addEventListener('input', debounce(f, 350)); f(); }
    if (id === 'servers') {
      const sv = $('#serversDir'), bk = $('#backupsDir');
      if (!S.backupsTouched || !S.backupsDir) { S.backupsDir = sv.value.trim().replace(/\\+$/, '') + '\\_backups'; bk.value = S.backupsDir; }
      const fs = () => { if (!S.backupsTouched) { bk.value = sv.value.trim().replace(/\\+$/, '') + '\\_backups'; fb(); } showPath('serversDir', 'serversDir'); };
      const fb = () => showPath('backupsDir', 'backupsDir');
      sv.addEventListener('input', debounce(fs, 350)); bk.addEventListener('input', () => { S.backupsTouched = true; debounce(fb, 350)(); });
      showPath('serversDir', 'serversDir'); showPath('backupsDir', 'backupsDir');
    }
    if (id === 'port') { $('#port').addEventListener('input', () => { $('#portEcho').textContent = $('#port').value; }); }
    if (id === 'account') {
      $('#showPw').addEventListener('change', (e) => { ['#ownerPass', '#ownerPass2'].forEach((s) => { $(s).type = e.target.checked ? 'text' : 'password'; }); });
    }
    if (id === 'cf') $('#cfSkip').addEventListener('click', () => { S.cfKey = ''; $('#cfKey').value = ''; go(1); });
    if (id === 'defaults') wireDefaults();
  }

  function wireDefaults() {
    const type = $('#d_type'), bed = $('#d_bedrock'), jm = $('#d_jm'), mods = $('#d_mods'), note = $('#dNote');
    const say = (m) => { note.textContent = m; note.style.display = m ? '' : 'none'; };
    let last = ['fabric', 'forge', 'neoforge'].includes(S.d.type) ? S.d.type : 'fabric';
    const sync = (why) => {
      let m = '';
      if (type.value === 'vanilla' && (bed.checked || jm.checked)) {
        if (why === 'type') { bed.checked = false; jm.checked = false; m = 'Bedrock support and JourneyMap need a mod loader, so they were turned off for Vanilla. Select Fabric, Forge or NeoForge to use them.'; }
        else { type.value = bed.checked && last === 'forge' ? 'neoforge' : last; m = 'Server type changed from Vanilla to ' + LOADERS[type.value] + ': ' + (bed.checked ? 'Bedrock support (Geyser)' : 'JourneyMap') + ' needs a mod loader. Pick another type above if you prefer.'; }
      }
      if (bed.checked && type.value === 'forge') {
        if (why === 'type') { bed.checked = false; m = 'Bedrock support (Geyser) has no Forge build, so it was turned off for Forge. Select NeoForge or Fabric to use Bedrock.'; }
        else { type.value = 'neoforge'; m = 'Server type changed from Forge to NeoForge: Bedrock support (Geyser) has no Forge build.'; }
      }
      if (type.value !== 'vanilla') last = type.value;
      mods.disabled = type.value === 'vanilla'; if (type.value === 'vanilla') mods.checked = false;
      say(m);
    };
    type.addEventListener('change', () => sync('type')); bed.addEventListener('change', () => sync('tick')); jm.addEventListener('change', () => sync('tick'));
    $('#page').addEventListener('change', (e) => { if (![type, bed, jm].includes(e.target)) say(''); });
    sync('init');
  }

  function collect(id) {
    if (id === 'port') { S.port = +$('#port').value; S.firewall = $('#firewall').checked; }
    if (id === 'account') { S.ownerUser = $('#ownerUser').value.trim(); S.ownerPass = $('#ownerPass').value; S.ownerPass2 = $('#ownerPass2').value; }
    if (id === 'cf') S.cfKey = $('#cfKey').value.trim();
    if (id === 'defaults') {
      const d = S.d;
      d.type = $('#d_type').value; d.maxMb = +$('#d_mem').value; d.difficulty = $('#d_diff').value; d.gamemode = $('#d_mode').value;
      d.maxPlayers = Math.max(1, Math.min(500, +$('#d_players').value || 20)); d.bedrock = $('#d_bedrock').checked; d.journeymap = $('#d_jm').checked;
      d.planMods = $('#d_mods').checked; d.whitelist = $('#d_wl').checked; d.autostart = $('#d_auto').checked; d.lanBroadcast = $('#d_lan').checked;
    }
    if (id === 'startup') { S.shortcuts = $('#shortcuts').checked; S.startup = ($('input[name=st]:checked') || {}).value || 'off'; }
  }

  async function validate(id) {
    if (id === 'install') return (await showPath('installDir', 'installDir')) ? '' : 'Please choose a usable install folder.';
    if (id === 'servers') return (await showPath('serversDir', 'serversDir')) && (await showPath('backupsDir', 'backupsDir')) ? '' : 'Please fix the folders marked in red.';
    if (id === 'port') {
      if (!(S.port >= 1024 && S.port <= 65535)) return 'Enter a port between 1024 and 65535.';
      return '';
    }
    if (id === 'account') {
      if (!S.ownerUser && !S.ownerPass && !S.ownerPass2) return '';
      if (!/^[A-Za-z0-9._-]{3,32}$/.test(S.ownerUser)) return 'Username must be 3-32 characters: letters, numbers, dot, dash or underscore.';
      if (S.ownerPass.length < 8) return 'The password must be at least 8 characters.';
      if (S.ownerPass !== S.ownerPass2) return 'The two passwords are not the same.';
    }
    if (id === 'cf' && S.cfKey && !(S.cfKey.length >= 20 && /^[A-Za-z0-9$./_=-]+$/.test(S.cfKey))) return 'That does not look like a CurseForge API key. Leave it empty to skip.';
    return '';
  }

  async function go(dir) {
    if (busy) return;
    const id = PAGES[idx].id;
    if (dir > 0) {
      collect(id);
      busy = true; const err = await validate(id); busy = false;
      if (err) { $('#hint').className = 'bad'; $('#hint').textContent = err; return; }
      if (id === 'ready') return install();
    } else collect(id);
    // an existing install keeps its folders, account, key and defaults, so those pages are skipped
    const skip = (i) => S.existing && ['servers', 'account', 'cf', 'defaults'].includes(PAGES[i].id);
    let n = Math.max(0, Math.min(PAGES.length - 1, idx + dir));
    while (n > 0 && n < PAGES.length - 1 && skip(n)) n += dir;
    show(n);
  }

  // ---------- install ----------
  async function install() {
    show(PAGES.length - 1);
    const log = $('#runLog'), bar = $('#runBar');
    try {
      await api('POST', '/api/setup/install', {
        installDir: S.installDir, serversDir: S.serversDir, backupsDir: S.backupsDir, port: S.port, firewall: S.firewall, shortcuts: S.shortcuts, startup: S.startup,
        ownerUser: S.existing ? '' : S.ownerUser, ownerPass: S.existing ? '' : S.ownerPass, cfKey: S.existing ? '' : S.cfKey,
        defaults: Object.assign({}, S.d, { type: effectiveType(), modLoader: ['fabric', 'forge', 'neoforge'].includes(S.d.type) ? S.d.type : 'fabric', planMods: effectiveType() === 'vanilla' ? true : S.d.planMods }),
      });
    } catch (e) { return failed(e.message); }
    S.ownerPass = S.ownerPass2 = S.cfKey = '';
    let from = 0;
    for (;;) {
      await new Promise((r) => setTimeout(r, 700));
      let p;
      try { p = await api('GET', '/api/setup/progress?from=' + from); } catch (e) { continue; }
      from = p.next;
      p.lines.forEach((l) => { log.textContent += l + '\n'; }); log.scrollTop = log.scrollHeight;
      bar.style.width = p.percent + '%';
      if (p.done) { return p.ok ? finished(p.url) : failed(p.error); }
    }
  }
  function failed(msg) {
    $('#runTitle').textContent = 'Setup did not finish';
    $('#runSub').textContent = '';
    $('#runErr').innerHTML = '<div class="note bad">' + esc(msg) + '</div><button id="retry">Try again</button> <button class="alt" id="toReady">Back to my choices</button>';
    $('#retry').onclick = () => install();
    $('#toReady').onclick = () => show(PAGES.findIndex((p) => p.id === 'ready'));
  }
  async function finished(url) {
    $('#runTitle').textContent = 'MC.SESH is ready!';
    $('#runSub').textContent = 'Opening it now in this tab...';
    $('#runBar').style.width = '100%';
    try { await api('POST', '/api/setup/finish', {}); } catch (e) { /* ignore */ }
    setTimeout(() => { window.location.href = url; }, 1800);
  }

  // ---------- music ----------
  function wireMusic() {
    const btn = $('#musicBtn'), now = $('#now'), vol = $('#vol');
    McMusic.onStage((name) => { now.innerHTML = '&#9835; ' + esc(name); });
    const set = (on) => { btn.textContent = on ? 'Stop music' : 'Play music'; if (!on) now.innerHTML = '&#9835; Music is off'; try { localStorage.setItem('mcsesh.setup.music', on ? '1' : '0'); } catch (e) { /* ignore */ } };
    btn.onclick = () => { if (McMusic.playing) { McMusic.stop(); set(false); } else { McMusic.setVolume(+vol.value); McMusic.start(); set(true); } };
    vol.oninput = () => McMusic.setVolume(+vol.value);
    McMusic.setVolume(+vol.value);
  }

  // ---------- start ----------
  async function init() {
    wireMusic();
    $('#next').onclick = () => go(1);
    $('#back').onclick = () => go(-1);
    try {
      S.info = await api('GET', '/api/setup/info');
      S.serversDir = S.info.serversDir; S.installDir = S.info.installDir; S.port = S.info.port;
      S.backupsDir = S.serversDir + '\\_backups';
      if (S.info.customMusic) window.MCSESH_CUSTOM_MUSIC = '/music/custom';
    } catch (e) { $('#page').innerHTML = '<div class="note bad">Setup could not start: ' + esc(e.message) + '<br>Close this tab and double-click Install.bat again.</div>'; return; }
    show(0);
  }
  init();
})();
