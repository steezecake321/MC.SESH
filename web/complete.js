'use strict';
/* In-game style command completion driven by the server's own Brigadier command tree (commands.json).
   The parser follows every reading of what has been typed at once (like Brigadier does), so
   `tp Steve <TAB>` offers both the destination player and the x y z form, all the way to the end of the command. */
const Complete = (() => {
  const COORD = { 'minecraft:block_pos': 3, 'minecraft:vec3': 3, 'minecraft:vec2': 2, 'minecraft:column_pos': 2, 'minecraft:rotation': 2 };
  const COLORS = ['black', 'dark_blue', 'dark_green', 'dark_aqua', 'dark_red', 'dark_purple', 'gold', 'gray', 'dark_gray', 'blue', 'green', 'aqua', 'red', 'light_purple', 'yellow', 'white', 'reset'];
  const ENUMS = {
    'minecraft:gamemode': ['survival', 'creative', 'adventure', 'spectator'],
    'minecraft:entity_anchor': ['eyes', 'feet'],
    'minecraft:heightmap': ['world_surface', 'motion_blocking', 'motion_blocking_no_leaves', 'ocean_floor'],
    'minecraft:operation': ['=', '+=', '-=', '*=', '/=', '%=', '<', '>', '><'],
    'minecraft:scoreboard_slot': ['list', 'sidebar', 'below_name'].concat(COLORS.filter(c => c !== 'reset').map(c => 'sidebar.team.' + c)),
    'minecraft:swizzle': ['x', 'y', 'z', 'xy', 'xz', 'yz', 'xyz'],
    'minecraft:item_slot': ['weapon', 'weapon.mainhand', 'weapon.offhand', 'armor.head', 'armor.chest', 'armor.legs', 'armor.feet', 'armor.body', 'horse.saddle', 'horse.chest.0', 'container.0', 'hotbar.0', 'inventory.0', 'enderchest.0', 'player.crafting.0'],
    'minecraft:team_color': COLORS,
    'minecraft:template_mirror': ['none', 'left_right', 'front_back'],
    'minecraft:template_rotation': ['none', 'clockwise_90', 'counterclockwise_90', '180'],
  };
  const PARSER_REG = {
    'minecraft:item_stack': 'item', 'minecraft:item_predicate': 'item', 'minecraft:block_state': 'block',
    'minecraft:block_predicate': 'block', 'minecraft:particle': 'particle_type', 'minecraft:loot_table': 'loot_table',
    'minecraft:loot_predicate': 'predicate', 'minecraft:loot_modifier': 'item_modifier', 'minecraft:dimension': 'dimension',
  };
  const REG_PARSERS = new Set(['minecraft:resource', 'minecraft:resource_key', 'minecraft:resource_or_tag', 'minecraft:resource_or_tag_key', 'minecraft:resource_selector']);
  const SELECTORS = ['@a', '@e', '@p', '@r', '@s', '@n'];
  const SEL_OPTS = ['x', 'y', 'z', 'distance', 'dx', 'dy', 'dz', 'x_rotation', 'y_rotation', 'scores', 'tag', 'team', 'name', 'type', 'predicate', 'nbt', 'level', 'gamemode', 'advancements', 'limit', 'sort'];
  const SEL_SORT = ['nearest', 'furthest', 'random', 'arbitrary'];
  const LOOSE = new Set(['minecraft:item_slot', 'minecraft:swizzle', 'minecraft:operation']);
  const SHOW_MAX = 300;
  const GREEDY = (n) => n.parser === 'minecraft:message' || (n.parser === 'brigadier:string' && n.properties && n.properties.type === 'greedy');
  const NUM = /^-?(\d+\.?\d*|\.\d+)([eE][-+]?\d+)?$/;
  const POS = /^([~^]-?(\d+\.?\d*|\.\d+)?|-?(\d+\.?\d*|\.\d+))$/;

  // Commands added by installed mods (taken from their /help usage). Parsed into the tree on load.
  const EXTRA_USAGE = [
    '/geyser connectiontest <address>', '/geyser dump [<args>]', '/geyser help', '/geyser list', '/geyser nethernet (claim|diagnostics|status)',
    '/geyser reload', '/geyser version', '/floodgate firewall', '/floodgate version', '/fwhitelist add <player>', '/fwhitelist remove <player>',
  ];

  function addUsage(tree, line) {
    const toks = line.trim().replace(/^\//, '').split(/\s+/);
    const build = (node, i) => {
      if (i >= toks.length) { node.executable = true; return; }
      node.children = node.children || {};
      const t = toks[i];
      let m;
      if ((m = t.match(/^\((.+)\)$/))) {
        for (const a of m[1].split('|')) build(lit(node, a), i + 1);
      } else if ((m = t.match(/^\[?<(.+?)>\]?$/))) {
        const a = node.children[m[1]] || (node.children[m[1]] = { type: 'argument', parser: 'brigadier:string', properties: { type: 'word' } });
        build(a, i + 1);
      } else {
        build(lit(node, t.replace(/^\[|\]$/g, '')), i + 1);
      }
    };
    const lit = (node, name) => (node.children[name] = node.children[name] || { type: 'literal' });
    build(tree, 0);
  }

  function prepare(data) {
    const tree = data.tree;
    tree.children = tree.children || {};
    for (const u of EXTRA_USAGE) {
      const root = u.trim().replace(/^\//, '').split(/\s+/)[0];
      if (!tree.children[root] || tree.children[root].__extra) {
        tree.children[root] = tree.children[root] || { type: 'literal', __extra: true };
        addUsage(tree, u);
      }
    }
    if (!tree.children.jm) tree.children.jm = { type: 'literal', __extra: true, executable: true, children: { args: { type: 'argument', parser: 'brigadier:string', properties: { type: 'greedy' }, executable: true } } };
    return data;
  }

  function tokenize(s) {
    const list = [];
    let i = 0, open = false;
    const n = s.length;
    while (i < n) {
      while (i < n && s[i] === ' ') i++;
      if (i >= n) break;
      const start = i;
      let depth = 0, quote = null;
      while (i < n) {
        const c = s[i];
        if (quote) { if (c === '\\') i++; else if (c === quote) quote = null; }
        else if ((c === '"' || c === "'") && i === start) quote = c;
        else if (c === '[' || c === '{' || c === '(') depth++;
        else if (c === ']' || c === '}' || c === ')') depth = Math.max(0, depth - 1);
        else if (c === ' ' && depth === 0) break;
        i++;
      }
      list.push({ text: s.slice(start, i), start, end: i });
      if (i >= n && (quote || depth > 0)) open = true;
    }
    return { list, open };
  }

  function follow(tree, path) {
    let n = tree;
    for (const p of path) { n = (n.children || {})[p]; if (!n) return null; }
    return n;
  }

  function kids(tree, node, parent, name) {
    let n = node, g = 0;
    while (n && n.redirect && g++ < 8) n = follow(tree, n.redirect);
    if (node.type === 'literal' && name === 'run' && parent && !node.children && !node.redirect) return tree.children || {};
    return (n && n.children) || {};
  }

  /** Could this typed word be this kind of argument? Only clearly wrong words are refused; the rest stay possible. */
  function accepts(a, tok) {
    switch (a.parser) {
      case 'brigadier:bool': return tok === 'true' || tok === 'false';
      case 'brigadier:integer': case 'brigadier:long': return /^-?\d+$/.test(tok);
      case 'brigadier:float': case 'brigadier:double': return NUM.test(tok);
      case 'minecraft:time': return /^(\d+\.?\d*|\.\d+)[dst]?$/.test(tok);
      case 'minecraft:block_pos': case 'minecraft:vec3': case 'minecraft:vec2': case 'minecraft:column_pos': case 'minecraft:rotation': return POS.test(tok);
      case 'minecraft:entity': case 'minecraft:score_holder': case 'minecraft:game_profile':
        if (tok[0] === '@') return /^@[aeprsn](\[.*)?$/.test(tok);
        if (a.parser === 'minecraft:score_holder' && tok === '*') return true;
        return !/^[~^]/.test(tok) && !NUM.test(tok);
      default:
        if (ENUMS[a.parser] && !LOOSE.has(a.parser)) return ENUMS[a.parser].includes(tok);
        return true;
    }
  }

  function match(list, partial, limit) {
    const p = partial.toLowerCase();
    const a = [], b = [];
    for (const id of list) {
      const l = id.toLowerCase();
      if (l.startsWith(p)) { a.push(id); if (a.length >= limit) break; }
      else if (p && (l.includes('_' + p) || l.includes('/' + p) || l.includes('.' + p))) b.push(id);
    }
    return a.concat(b).slice(0, limit);
  }

  function describe(name, a) {
    const pr = a.properties || {};
    let t = '';
    switch (a.parser) {
      case 'brigadier:integer': case 'brigadier:long': t = 'whole number'; break;
      case 'brigadier:float': case 'brigadier:double': t = 'number'; break;
      case 'brigadier:string': t = pr.type === 'greedy' ? 'text' : pr.type === 'phrase' ? 'text' : 'word'; break;
      case 'minecraft:entity': t = pr.type === 'players' ? 'player' : 'entity'; break;
      case 'minecraft:game_profile': t = 'player name'; break;
      case 'minecraft:block_pos': t = 'x y z'; break;
      case 'minecraft:vec3': t = 'x y z'; break;
      case 'minecraft:vec2': case 'minecraft:column_pos': t = 'x z'; break;
      case 'minecraft:time': t = 'time e.g. 10s, 1d, 200t'; break;
      case 'minecraft:component': t = 'JSON text'; break;
      case 'minecraft:nbt_compound_tag': case 'minecraft:nbt_tag': case 'minecraft:nbt_path': t = 'NBT'; break;
      default: t = a.parser.replace(/^(minecraft|brigadier):/, '');
    }
    if (pr.min !== undefined || pr.max !== undefined) t += ` ${pr.min !== undefined ? pr.min : ''}..${pr.max !== undefined ? pr.max : ''}`;
    return t;
  }

  function create(rawData, ctx) {
    const data = prepare(rawData);
    const tree = data.tree, regs = data.registries || {};
    const names = (f) => { try { return f ? f() : []; } catch (e) { return []; } };

    function profiles(path) {
      const c = ctx || {};
      if (path[0] === 'pardon') return names(c.banned);
      if (path[0] === 'deop') return names(c.ops);
      if (path[0] === 'whitelist' && path[1] === 'remove') return names(c.whitelist);
      return [...new Set([...names(c.players), ...names(c.known)])];
    }

    /** Completion inside @a[...]: option names, then values for the ones we know. Replaces the whole word. */
    function selectorItems(partial) {
      const out = [];
      const open = partial.indexOf('[');
      let depth = 0, cut = open;
      for (let i = open + 1; i < partial.length; i++) {
        const c = partial[i];
        if (c === '{' || c === '[') depth++; else if (c === '}' || c === ']') depth--;
        else if (c === ',' && depth === 0) cut = i;
      }
      if (partial.endsWith(']') && depth <= 0 && partial.lastIndexOf(']') > open) return out;
      const head = partial.slice(0, cut + 1);
      const seg = partial.slice(cut + 1);
      const eq = seg.indexOf('=');
      const add = (text, kind) => out.push({ text, kind: kind || 'option', more: false });
      if (eq < 0) {
        for (const o of match(SEL_OPTS, seg, 40)) add(head + o + '=');
        return out;
      }
      const key = seg.slice(0, eq), val = seg.slice(eq + 1);
      const neg = val.startsWith('!');
      const v = neg ? val.slice(1) : val;
      const base = head + key + '=' + (neg ? '!' : '');
      let list = [];
      if (key === 'gamemode') list = ENUMS['minecraft:gamemode'];
      else if (key === 'sort') list = SEL_SORT;
      else if (key === 'type') list = (regs.entity_type || []);
      for (const x of match(list, v.replace(/^minecraft:/, ''), 80)) add(base + x, 'value');
      if (list.length ? list.includes(v.replace(/^minecraft:/, '')) : v !== '') { add(head + key + '=' + val + ',', 'option'); add(head + key + '=' + val + ']', 'option'); }
      return out;
    }

    function argItems(name, a, partial, path) {
      const pr = a.properties || {};
      const p = a.parser;
      const out = [];
      const push = (list, kind, ns) => { for (const id of list) out.push({ text: (ns || '') + id, kind }); };
      let ns = '', q = partial;
      if (/^minecraft:/.test(q)) { ns = 'minecraft:'; q = q.slice(10); }
      if (p === 'brigadier:bool') push(match(['true', 'false'], partial, 5), 'value');
      else if (p === 'minecraft:game_profile' || p === 'minecraft:entity' || p === 'minecraft:score_holder') {
        if (/^@[aeprsn]\[/.test(partial)) return selectorItems(partial);
        const playersOnly = p === 'minecraft:game_profile' || pr.type === 'players';
        const single = pr.amount === 'single';
        const sel = SELECTORS.filter(s => !(playersOnly && s === '@e') && !(single && (s === '@a')) && !(single && s === '@e'));
        if (!partial.startsWith('@')) push(match(profiles(path), partial, 80), 'player');
        if (partial === '' || partial.startsWith('@')) push(sel.filter(s => s.startsWith(partial.slice(0, 2)) || partial === ''), 'selector');
        if (/^@[aeprsn]$/.test(partial)) out.push({ text: partial + '[', kind: 'selector', more: false });
        if (p === 'minecraft:score_holder' && partial === '') out.push({ text: '*', kind: 'selector' });
      } else if (p === 'minecraft:objective') push(match(names((ctx || {}).objectives), partial, 80), 'value');
      else if (p === 'minecraft:team') push(match(names((ctx || {}).teams), partial, 80), 'value');
      else if (ENUMS[p]) push(match(ENUMS[p], partial, 60), 'value');
      else if (REG_PARSERS.has(p)) {
        const r = (pr.registry || '').replace(/^minecraft:/, '');
        if (regs[r]) push(match(regs[r], q, SHOW_MAX), 'value', ns);
      } else if (PARSER_REG[p]) {
        const r = PARSER_REG[p];
        let list = regs[r];
        if (!list && r === 'dimension') list = ['overworld', 'the_nether', 'the_end'];
        if (list) push(match(list, q, SHOW_MAX), 'value', ns);
      } else if (p === 'minecraft:resource_location') {
        if (path[0] === 'time' && path[1] === 'set') push(match(['day', 'night', 'noon', 'midnight'], partial, 10), 'value');
        const reg = path[0] === 'playsound' || path[0] === 'stopsound' ? 'sound_event' : path[0] === 'advancement' ? 'advancement' : path[0] === 'recipe' ? 'recipe' : null;
        if (reg && regs[reg]) push(match(regs[reg], q, SHOW_MAX), 'value', ns);
      } else if (COORD[p]) {
        if (partial === '' || '~^'.includes(partial[0])) out.push({ text: '~', kind: 'value' });
      } else if (p === 'brigadier:integer' && (name === 'count' || name === 'amount')) push(match(['1', '16', '64'], partial, 5), 'value');
      return out;
    }

    function analyze(text) {
      const lead = text.startsWith('/') ? 1 : 0;
      const s = text.slice(lead);
      const tk = tokenize(s);
      const endsSp = s.length > 0 && s[s.length - 1] === ' ' && !tk.open;
      const done = endsSp ? tk.list : tk.list.slice(0, -1);
      const last = endsSp || !tk.list.length ? null : tk.list[tk.list.length - 1];
      const partial = last ? last.text : '';
      const from = lead + (last ? last.start : s.length);
      const res = { from, partial, items: [], usage: '', executable: false, error: null, hint: null };
      if (!s.trim()) { res.usage = 'Type a command. Tab completes, ↑↓ for history.'; res.items = rootItems(partial); return res; }

      // Every reading of the words typed so far that could still lead to a valid command.
      let states = [{ node: tree, parent: null, name: '', path: [], need: 0, greedy: false }];
      for (let i = 0; i < done.length; i++) {
        const tok = done[i].text;
        const next = [];
        const keys = new Set();
        const push = (st) => {
          const k = st.node.__id || (st.node.__id = ++uid);
          const key = k + ':' + st.need + (st.greedy ? 'g' : '');
          if (!keys.has(key)) { keys.add(key); next.push(st); }
        };
        for (const st of states) {
          if (st.greedy) { push(st); continue; }
          if (st.need > 0) { if (accepts(st.node, tok)) push({ ...st, need: st.need - 1 }); continue; }
          const ks = kids(tree, st.node, st.parent, st.name);
          if (ks[tok] && ks[tok].type === 'literal') { push({ node: ks[tok], parent: st.node, name: tok, path: [...st.path, tok], need: 0, greedy: false }); continue; }
          for (const [k, v] of Object.entries(ks)) {
            if (v.type !== 'argument' || !accepts(v, tok)) continue;
            if (GREEDY(v)) push({ node: v, parent: st.node, name: k, path: st.path, need: 0, greedy: true });
            else push({ node: v, parent: st.node, name: k, path: st.path, need: (COORD[v.parser] || 1) - 1, greedy: false });
          }
        }
        if (!next.length) {
          res.error = `Unknown or invalid: "${tok}" after /${done.slice(0, i).map(t => t.text).join(' ')}`.replace(' after /"', '"');
          return res;
        }
        states = next.length > 80 ? next.slice(0, 80) : next;
      }

      // Now offer what can follow, joined across every reading.
      const prefix = '/' + done.map(t => t.text).join(' ');
      const seen = new Set();
      const shown = [];
      const addShown = (x) => { if (!shown.includes(x)) shown.push(x); };
      for (const st of states) {
        if (st.greedy) {
          res.hint = `<${st.name}: ${describe(st.name, st.node)}>`;
          res.executable = res.executable || !!st.node.executable;
          addShown(`<${st.name}: ${describe(st.name, st.node)}>`);
          continue;
        }
        if (st.need > 0) {
          const n = COORD[st.node.parser];
          res.hint = `<${st.name}: ${describe(st.name, st.node)}>  (${n - st.need + 1} of ${n})`;
          addShown(`<${st.name}: ${describe(st.name, st.node)}> (${n - st.need + 1} of ${n})`);
          if ((partial === '' || '~^'.includes(partial[0])) && !seen.has('value~')) { seen.add('value~'); res.items.push({ text: '~', kind: 'value' }); }
          continue;
        }
        res.executable = res.executable || !!st.node.executable;
        const ks = kids(tree, st.node, st.parent, st.name);
        for (const [k, v] of Object.entries(ks)) {
          if (v.type === 'literal') {
            addShown(k);
            const key = 'literal' + k;
            if (!seen.has(key) && (partial === '' || k.toLowerCase().startsWith(partial.toLowerCase()))) {
              seen.add(key);
              res.items.push({ text: k, kind: 'literal', more: !!(v.children || v.redirect) || k === 'run', run: !!v.executable });
            }
          } else {
            addShown(`<${k}: ${describe(k, v)}>`);
            for (const it of argItems(k, v, partial, st.path)) {
              const key = it.kind + it.text;
              if (seen.has(key)) continue;
              seen.add(key);
              it.more = it.more !== undefined ? it.more : !!(v.children || v.redirect);
              it.run = !!v.executable;
              res.items.push(it);
            }
          }
        }
      }
      res.usage = shown.length ? `${prefix} ${shown.slice(0, 8).join(' | ')}${shown.length > 8 ? ' | …' : ''}` : prefix;
      if (res.items.length > SHOW_MAX) res.items.length = SHOW_MAX;
      return res;
    }

    function rootItems(partial) {
      return Object.keys(tree.children).filter(k => k.toLowerCase().startsWith(partial.toLowerCase())).map(k => ({ text: k, kind: 'literal', more: true }));
    }

    return { analyze, roots: () => Object.keys(tree.children) };
  }

  let uid = 0;
  return { create };
})();
