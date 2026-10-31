'use strict';
/* Automatic restart: by time, when the server is empty, or both. Used on the server's Properties tab and in Settings > Set Global Defaults (for new servers). */

const AR_DAYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
const AR_DEFAULT = { timeOn: false, at: '04:00', days: [1, 2, 3, 4, 5, 6, 7], mode: 'countdown', waitMin: 30, warnMin: 5, idleOn: false, idleMin: 30, minUpHours: 6 };

/** The form fields. Returns { el, values() }. */
function autoRestartForm(cfg) {
  const a = Object.assign({}, AR_DEFAULT, cfg || {});
  const num = (v, min, max, w) => h('input', { type: 'number', min, max, value: v, style: `width:${w || 70}px` });
  const timeOn = h('input', { type: 'checkbox', checked: !!a.timeOn });
  const at = h('input', { type: 'time', value: a.at, style: 'width:120px' });
  const days = AR_DAYS.map((n, i) => ({ n, c: h('input', { type: 'checkbox', checked: a.days.includes(i + 1) }) }));
  const countdown = h('input', { type: 'radio', name: 'arm' + Math.random().toString(36).slice(2), checked: a.mode !== 'wait' });
  const wait = h('input', { type: 'radio', name: countdown.name, checked: a.mode === 'wait' });
  const warnMin = num(a.warnMin, 1, 30);
  const waitMin = num(a.waitMin, 1, 720, 80);
  const idleOn = h('input', { type: 'checkbox', checked: !!a.idleOn });
  const idleMin = num(a.idleMin, 1, 1440, 80);
  const minUp = num(a.minUpHours, 0, 168);

  const timeBox = h('div', { style: 'display:grid;gap:8px;margin-left:24px' },
    h('div', { class: 'row', style: 'gap:6px' }, 'Restart at ', at, ' on:'),
    h('div', { style: 'display:flex;flex-wrap:wrap;gap:2px' }, days.map(x => h('label', { style: 'display:inline-flex;gap:3px;margin-right:8px' }, x.c, x.n))),
    h('div', { style: 'display:grid;gap:4px' }, h('b', null, 'If players are online at that time'),
      h('label', null, countdown, ' Warn them and restart: they see a countdown starting ', warnMin, ' minutes before'),
      h('label', null, wait, ' Wait until the server is empty (restarts as soon as the last player leaves). After ', waitMin, ' minutes it restarts anyway, with the countdown.')));
  const idleBox = h('div', { style: 'display:grid;gap:8px;margin-left:24px' },
    h('div', { class: 'row', style: 'gap:6px' }, 'Restart when nobody has been online for ', idleMin, ' minutes,'),
    h('div', { class: 'row', style: 'gap:6px' }, 'but only if the server has been running at least ', minUp, ' hours (so an empty server is not restarted over and over).'));
  const fit = () => { timeBox.classList.toggle('hidden', !timeOn.checked); idleBox.classList.toggle('hidden', !idleOn.checked); };
  timeOn.addEventListener('change', fit);
  idleOn.addEventListener('change', fit);
  fit();
  const el = h('div', { style: 'display:grid;gap:10px' },
    h('label', null, timeOn, ' Restart on a schedule'), timeBox,
    h('label', null, idleOn, ' Restart when the server is empty'), idleBox,
    h('div', { class: 'dim', style: 'font-size:12.5px' }, 'Both can be on at once. Players are warned in chat before any restart that happens while they are online. A restart needs the remote console (RCON) turned on.'));
  const values = () => {
    const ds = days.map((x, i) => (x.c.checked ? i + 1 : 0)).filter(Boolean);
    return { timeOn: timeOn.checked, at: at.value || '04:00', days: ds.length ? ds : [1, 2, 3, 4, 5, 6, 7], mode: wait.checked ? 'wait' : 'countdown', waitMin: +waitMin.value || 30, warnMin: +warnMin.value || 5,
      idleOn: idleOn.checked, idleMin: +idleMin.value || 30, minUpHours: Math.max(0, +minUp.value || 0) };
  };
  return { el, values };
}

const AR_WHEN = (ms) => new Date(ms).toLocaleString([], { weekday: 'short', hour: '2-digit', minute: '2-digit', month: 'short', day: 'numeric' });

/** The section on the Properties tab. */
async function autoRestartBox(s) {
  let d;
  try { d = await api('GET', `/api/servers/${s.id}/autorestart`); } catch (e) { return null; }
  const form = autoRestartForm(d.auto);
  const admin = lvl(s) >= 4;
  const a = d.auto;
  const save = h('button', { class: 'btn primary', disabled: !admin, onclick: async () => {
    try {
      const r = await api('POST', `/api/servers/${s.id}/autorestart`, form.values());
      const v = r.auto;
      toast(v.timeOn || v.idleOn ? 'Automatic restart saved.' + (v.timeOn && v.next ? ' Next scheduled restart: ' + AR_WHEN(v.next) + '.' : '') : 'Automatic restart is off.');
      renderPane();
    } catch (e) { toast(e.message, true); }
  } }, 'Save');
  const info = [];
  if (a.timeOn && a.next) info.push('Next scheduled restart: ' + AR_WHEN(a.next) + '.');
  if (a.last) info.push('Last automatic restart: ' + AR_WHEN(a.last) + (a.lastResult ? ' (' + a.lastResult + ')' : '') + '.');
  return h('div', { class: 'box', style: 'display:grid;gap:10px' }, h('h3', null, 'Automatic restart'),
    !d.rcon ? h('div', { class: 'notice' }, 'Remote console (RCON) is off, so the manager cannot stop this server cleanly. Turn RCON on first (see the top of this page), or automatic restarts will fail.') : null,
    form.el, admin ? null : h('div', { class: 'notice' }, 'View only: you need level 4 on this server to change this.'),
    h('div', { class: 'row', style: 'gap:10px;align-items:center' }, save, info.length ? h('span', { class: 'dim', style: 'font-size:12.5px' }, info.join(' ')) : null));
}
