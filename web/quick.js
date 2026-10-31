'use strict';
/* Quick command catalog. Field types: player, enum, int, text, item, entity, effect, rule, bool. */
const QuickCmds = (() => {
  const GM = ['survival', 'creative', 'adventure', 'spectator'];
  const P = (label = 'Player', def = '') => ({ k: 'p', t: 'player', label, def });
  const q = (s) => (/\s/.test(s) ? JSON.stringify(s) : s);
  const opt = (s) => (s && s.trim() ? ' ' + s.trim() : '');

  const groups = [
    { name: 'Players', items: [
      { id: 'gm', label: 'Change gamemode', fields: [{ k: 'm', t: 'enum', label: 'Mode', opts: GM }, P()], cmd: v => `gamemode ${v.m} ${v.p}` },
      { id: 'give', label: 'Give item', fields: [P(), { k: 'i', t: 'item', label: 'Item', def: 'diamond' }, { k: 'n', t: 'int', label: 'Count', def: 1, min: 1, max: 6400 }], cmd: v => `give ${v.p} ${v.i} ${v.n}` },
      { id: 'tpp', label: 'Teleport player to player', fields: [P('Who'), { k: 'to', t: 'player', label: 'To player' }], cmd: v => `tp ${v.p} ${v.to}` },
      { id: 'tpc', label: 'Teleport to coordinates', fields: [P('Who'), { k: 'x', t: 'text', label: 'X', def: '~' }, { k: 'y', t: 'text', label: 'Y', def: '~' }, { k: 'z', t: 'text', label: 'Z', def: '~' }], cmd: v => `tp ${v.p} ${v.x} ${v.y} ${v.z}` },
      { id: 'kick', label: 'Kick', fields: [P(), { k: 'r', t: 'text', label: 'Reason (optional)' }], cmd: v => `kick ${v.p}${opt(v.r)}` },
      { id: 'ban', label: 'Ban', danger: true, fields: [P(), { k: 'r', t: 'text', label: 'Reason (optional)' }], cmd: v => `ban ${v.p}${opt(v.r)}` },
      { id: 'pardon', label: 'Unban (pardon)', fields: [P()], cmd: v => `pardon ${v.p}` },
      { id: 'op', label: 'Make operator', fields: [P()], cmd: v => `op ${v.p}` },
      { id: 'deop', label: 'Remove operator', fields: [P()], cmd: v => `deop ${v.p}` },
      { id: 'wla', label: 'Whitelist: add', fields: [P('Name')], cmd: v => `whitelist add ${v.p}` },
      { id: 'wlr', label: 'Whitelist: remove', fields: [P('Name')], cmd: v => `whitelist remove ${v.p}` },
      { id: 'eff', label: 'Give effect', fields: [P(), { k: 'e', t: 'effect', label: 'Effect', def: 'speed' }, { k: 's', t: 'int', label: 'Seconds', def: 60, min: 1, max: 1000000 }, { k: 'a', t: 'int', label: 'Level (0 = I)', def: 0, min: 0, max: 255 }], cmd: v => `effect give ${v.p} ${v.e} ${v.s} ${v.a}` },
      { id: 'effc', label: 'Clear effects', fields: [P()], cmd: v => `effect clear ${v.p}` },
      { id: 'xp', label: 'Give experience levels', fields: [P(), { k: 'n', t: 'int', label: 'Levels', def: 10, min: -100000, max: 100000 }], cmd: v => `xp add ${v.p} ${v.n} levels` },
      { id: 'clear', label: 'Clear inventory', danger: true, fields: [P()], cmd: v => `clear ${v.p}` },
      { id: 'kill', label: 'Kill', danger: true, fields: [P('Target', '')], cmd: v => `kill ${v.p}` },
    ] },
    { name: 'World', items: [
      { id: 'time', label: 'Set time', fields: [{ k: 't', t: 'enum', label: 'Time', opts: ['day', 'noon', 'night', 'midnight'] }], cmd: v => `time set ${v.t}` },
      { id: 'weather', label: 'Set weather', fields: [{ k: 'w', t: 'enum', label: 'Weather', opts: ['clear', 'rain', 'thunder'] }, { k: 'd', t: 'text', label: 'Duration (optional, e.g. 10m)' }], cmd: v => `weather ${v.w}${opt(v.d)}` },
      { id: 'diff', label: 'Set difficulty', fields: [{ k: 'd', t: 'enum', label: 'Difficulty', opts: ['peaceful', 'easy', 'normal', 'hard'] }], cmd: v => `difficulty ${v.d}` },
      { id: 'rule', label: 'Change game rule', fields: [{ k: 'r', t: 'rule', label: 'Rule' }, { k: 'v', t: 'text', label: 'Value (true / false / number)', def: 'true' }], cmd: v => `gamerule ${v.r} ${v.v}` },
      { id: 'spawn', label: 'Set world spawn here', fields: [{ k: 'p', t: 'player', label: 'At player (their position)', def: '' }], cmd: v => v.p ? `execute at ${v.p} run setworldspawn ~ ~ ~` : 'setworldspawn' },
      { id: 'wb', label: 'Set world border size', fields: [{ k: 's', t: 'int', label: 'Diameter (blocks)', def: 10000, min: 1, max: 59999968 }], cmd: v => `worldborder set ${v.s}` },
      { id: 'summon', label: 'Summon entity', fields: [{ k: 'e', t: 'entity', label: 'Entity', def: 'pig' }, P('At player', '')], cmd: v => v.p ? `execute at ${v.p} run summon ${v.e} ~ ~ ~` : `summon ${v.e}` },
      { id: 'seed', label: 'Show seed', fields: [], cmd: () => 'seed' },
    ] },
    { name: 'Chat', items: [
      { id: 'say', label: 'Broadcast message', fields: [{ k: 'm', t: 'text', label: 'Message' }], cmd: v => `say ${v.m}` },
      { id: 'tell', label: 'Private message', fields: [P(), { k: 'm', t: 'text', label: 'Message' }], cmd: v => `tell ${v.p} ${v.m}` },
      { id: 'title', label: 'Show title on screen', fields: [P('To', '@a'), { k: 'm', t: 'text', label: 'Title text' }], cmd: v => `title ${v.p} title ${JSON.stringify({ text: v.m })}` },
    ] },
    { name: 'Server', items: [
      { id: 'list', label: 'List players', fields: [], cmd: () => 'list' },
      { id: 'save', label: 'Save world now', fields: [], cmd: () => 'save-all flush' },
      { id: 'soff', label: 'Pause autosave (for backups)', fields: [], cmd: () => 'save-off' },
      { id: 'son', label: 'Resume autosave', fields: [], cmd: () => 'save-on' },
      { id: 'wlon', label: 'Whitelist: turn on', fields: [], cmd: () => 'whitelist on' },
      { id: 'wloff', label: 'Whitelist: turn off', fields: [], cmd: () => 'whitelist off' },
      { id: 'wlrl', label: 'Whitelist: reload from file', fields: [], cmd: () => 'whitelist reload' },
      { id: 'banlist', label: 'Show ban list', fields: [], cmd: () => 'banlist' },
      { id: 'reload', label: 'Reload data packs', fields: [], cmd: () => 'reload' },
      { id: 'ver', label: 'Server version', fields: [], cmd: () => 'version' },
    ] },
  ];
  return { groups, q };
})();
