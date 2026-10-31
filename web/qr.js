// Minimal QR code generator (byte mode, versions 1-10, error correction M then L). No dependencies.
// Used to show the two-step sign-in setup as a scannable code; the typed setup key is always shown as well.
const QR = (() => {
  // ---- Reed-Solomon over GF(256), primitive polynomial 0x11D ----
  const EXP = new Array(512), LOG = new Array(256);
  for (let i = 0, x = 1; i < 255; i++) { EXP[i] = x; LOG[x] = i; x <<= 1; if (x & 0x100) x ^= 0x11d; }
  for (let i = 255; i < 512; i++) EXP[i] = EXP[i - 255];
  const mul = (a, b) => (a === 0 || b === 0 ? 0 : EXP[LOG[a] + LOG[b]]);

  function rsGenerator(degree) {
    let g = [1];
    for (let i = 0; i < degree; i++) {
      const next = new Array(g.length + 1).fill(0);
      for (let j = 0; j < g.length; j++) { next[j] ^= g[j]; next[j + 1] ^= mul(g[j], EXP[i]); }
      g = next;
    }
    return g;
  }

  function rsRemainder(data, gen) {
    const res = new Array(gen.length - 1).fill(0);
    for (const b of data) {
      const f = b ^ res.shift();
      res.push(0);
      for (let i = 0; i < res.length; i++) res[i] ^= mul(gen[i + 1], f);
    }
    return res;
  }

  // ---- block structure: version -> [ecCodewordsPerBlock, [[blocks, dataCodewordsPerBlock], ...]] ----
  const EC = {
    L: [null, [7, [[1, 19]]], [10, [[1, 34]]], [15, [[1, 55]]], [20, [[1, 80]]], [26, [[1, 108]]], [18, [[2, 68]]], [20, [[2, 78]]], [24, [[2, 97]]], [30, [[2, 116]]], [18, [[2, 68], [2, 69]]]],
    M: [null, [10, [[1, 16]]], [16, [[1, 28]]], [26, [[1, 44]]], [18, [[2, 32]]], [24, [[2, 43]]], [16, [[4, 27]]], [18, [[4, 31]]], [22, [[2, 38], [2, 39]]], [22, [[3, 36], [2, 37]]], [26, [[4, 43], [1, 44]]]],
  };
  const FORMAT_BITS = { L: 1, M: 0 };
  const ALIGN = [null, [], [6, 18], [6, 22], [6, 26], [6, 30], [6, 34], [6, 22, 38], [6, 24, 42], [6, 26, 46], [6, 28, 50]];

  const bit = (x, i) => ((x >>> i) & 1) !== 0;

  function formatBits(level, mask) {
    const data = (FORMAT_BITS[level] << 3) | mask;
    let rem = data;
    for (let i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
    return ((data << 10) | rem) ^ 0x5412;
  }

  function versionBits(v) {
    let rem = v;
    for (let i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1f25);
    return (v << 12) | rem;
  }

  // ---- codewords ----
  function codewords(bytes, v, level) {
    const [ecLen, groups] = EC[level][v];
    const dataCw = groups.reduce((s, [c, d]) => s + c * d, 0);
    const bits = [];
    const put = (val, len) => { for (let i = len - 1; i >= 0; i--) bits.push((val >>> i) & 1); };
    put(4, 4);
    put(bytes.length, v < 10 ? 8 : 16);
    for (const b of bytes) put(b, 8);
    put(0, Math.min(4, dataCw * 8 - bits.length));
    while (bits.length % 8) bits.push(0);
    for (let pad = 0xec; bits.length < dataCw * 8; pad ^= 0xec ^ 0x11) put(pad, 8);
    const data = [];
    for (let i = 0; i < bits.length; i += 8) { let b = 0; for (let j = 0; j < 8; j++) b = (b << 1) | bits[i + j]; data.push(b); }
    const gen = rsGenerator(ecLen);
    const blocks = [];
    let pos = 0;
    for (const [count, size] of groups) for (let i = 0; i < count; i++) {
      const d = data.slice(pos, pos + size);
      pos += size;
      blocks.push({ data: d, ec: rsRemainder(d, gen) });
    }
    const out = [];
    const maxData = Math.max(...blocks.map(b => b.data.length));
    for (let i = 0; i < maxData; i++) for (const b of blocks) if (i < b.data.length) out.push(b.data[i]);
    for (let i = 0; i < ecLen; i++) for (const b of blocks) out.push(b.ec[i]);
    return out;
  }

  // ---- matrix ----
  function build(bytes, v, level) {
    const size = 17 + 4 * v;
    const mod = Array.from({ length: size }, () => new Array(size).fill(false));
    const fn = Array.from({ length: size }, () => new Array(size).fill(false));
    const setFn = (x, y, dark) => { mod[y][x] = dark; fn[y][x] = true; };

    for (let i = 0; i < size; i++) { setFn(6, i, i % 2 === 0); setFn(i, 6, i % 2 === 0); }
    const finder = (cx, cy) => {
      for (let dy = -4; dy <= 4; dy++) for (let dx = -4; dx <= 4; dx++) {
        const x = cx + dx, y = cy + dy;
        if (x < 0 || x >= size || y < 0 || y >= size) continue;
        const d = Math.max(Math.abs(dx), Math.abs(dy));
        setFn(x, y, d !== 2 && d !== 4);
      }
    };
    finder(3, 3); finder(size - 4, 3); finder(3, size - 4);
    const pos = ALIGN[v];
    for (let i = 0; i < pos.length; i++) for (let j = 0; j < pos.length; j++) {
      if ((i === 0 && j === 0) || (i === 0 && j === pos.length - 1) || (i === pos.length - 1 && j === 0)) continue;
      for (let dy = -2; dy <= 2; dy++) for (let dx = -2; dx <= 2; dx++) setFn(pos[i] + dx, pos[j] + dy, Math.max(Math.abs(dx), Math.abs(dy)) !== 1);
    }
    const drawFormat = (mask) => {
      const bits = formatBits(level, mask);
      for (let i = 0; i <= 5; i++) setFn(8, i, bit(bits, i));
      setFn(8, 7, bit(bits, 6));
      setFn(8, 8, bit(bits, 7));
      setFn(7, 8, bit(bits, 8));
      for (let i = 9; i < 15; i++) setFn(14 - i, 8, bit(bits, i));
      for (let i = 0; i < 8; i++) setFn(size - 1 - i, 8, bit(bits, i));
      for (let i = 8; i < 15; i++) setFn(8, size - 15 + i, bit(bits, i));
      setFn(8, size - 8, true);
    };
    drawFormat(0);
    if (v >= 7) {
      const bits = versionBits(v);
      for (let i = 0; i < 18; i++) {
        const a = size - 11 + (i % 3), b = Math.floor(i / 3);
        setFn(a, b, bit(bits, i));
        setFn(b, a, bit(bits, i));
      }
    }

    // data in the zigzag order
    const cw = codewords(bytes, v, level);
    let k = 0;
    for (let right = size - 1; right >= 1; right -= 2) {
      if (right === 6) right = 5;
      for (let vert = 0; vert < size; vert++) for (let j = 0; j < 2; j++) {
        const x = right - j;
        const upward = ((right + 1) & 2) === 0;
        const y = upward ? size - 1 - vert : vert;
        if (!fn[y][x] && k < cw.length * 8) { mod[y][x] = bit(cw[k >>> 3], 7 - (k & 7)); k++; }
      }
    }

    const applyMask = (mask) => {
      for (let y = 0; y < size; y++) for (let x = 0; x < size; x++) {
        let inv;
        switch (mask) {
          case 0: inv = (x + y) % 2 === 0; break;
          case 1: inv = y % 2 === 0; break;
          case 2: inv = x % 3 === 0; break;
          case 3: inv = (x + y) % 3 === 0; break;
          case 4: inv = (Math.floor(x / 3) + Math.floor(y / 2)) % 2 === 0; break;
          case 5: inv = ((x * y) % 2) + ((x * y) % 3) === 0; break;
          case 6: inv = (((x * y) % 2) + ((x * y) % 3)) % 2 === 0; break;
          default: inv = (((x + y) % 2) + ((x * y) % 3)) % 2 === 0;
        }
        if (!fn[y][x] && inv) mod[y][x] = !mod[y][x];
      }
    };

    const penalty = () => {
      let p = 0;
      const line = (get) => {
        let run = 1;
        for (let i = 1; i < size; i++) {
          if (get(i) === get(i - 1)) { run++; } else { if (run >= 5) p += 3 + (run - 5); run = 1; }
        }
        if (run >= 5) p += 3 + (run - 5);
        let s = '';
        for (let i = 0; i < size; i++) s += get(i) ? '1' : '0';
        for (const pat of ['10111010000', '00001011101']) { let from = 0, at; while ((at = s.indexOf(pat, from)) >= 0) { p += 40; from = at + 1; } }
      };
      for (let y = 0; y < size; y++) line((i) => mod[y][i]);
      for (let x = 0; x < size; x++) line((i) => mod[i][x]);
      for (let y = 0; y < size - 1; y++) for (let x = 0; x < size - 1; x++) {
        const c = mod[y][x];
        if (c === mod[y][x + 1] && c === mod[y + 1][x] && c === mod[y + 1][x + 1]) p += 3;
      }
      let dark = 0;
      for (const row of mod) for (const c of row) if (c) dark++;
      const total = size * size;
      p += (Math.ceil(Math.abs(dark * 20 - total * 10) / total) - 1) * 10;
      return p;
    };

    let best = 0, bestScore = Infinity;
    for (let m = 0; m < 8; m++) {
      applyMask(m);
      drawFormat(m);
      const sc = penalty();
      if (sc < bestScore) { bestScore = sc; best = m; }
      applyMask(m);
    }
    applyMask(best);
    drawFormat(best);
    return { size, version: v, level, mask: best, get: (x, y) => mod[y][x], fn };
  }

  function encode(text) {
    const bytes = Array.from(new TextEncoder().encode(text));
    for (const level of ['M', 'L']) for (let v = 1; v <= 10; v++) {
      const dataCw = EC[level][v][1].reduce((s, [c, d]) => s + c * d, 0);
      if (4 + (v < 10 ? 8 : 16) + 8 * bytes.length <= dataCw * 8) return build(bytes, v, level);
    }
    return null;
  }

  /** An <svg> element for the text (black on white with the required 4-module quiet zone), or null if it is too long. */
  function svg(text, px) {
    const m = encode(text);
    if (!m) return null;
    const q = 4, n = m.size + 2 * q, NS = 'http://www.w3.org/2000/svg';
    let d = '';
    for (let y = 0; y < m.size; y++) for (let x = 0; x < m.size; x++) if (m.get(x, y)) d += `M${x + q},${y + q}h1v1h-1z`;
    const el = document.createElementNS(NS, 'svg');
    el.setAttribute('viewBox', `0 0 ${n} ${n}`);
    el.setAttribute('width', px);
    el.setAttribute('height', px);
    el.setAttribute('shape-rendering', 'crispEdges');
    el.setAttribute('role', 'img');
    el.setAttribute('aria-label', 'QR code');
    const bg = document.createElementNS(NS, 'rect');
    bg.setAttribute('width', n); bg.setAttribute('height', n); bg.setAttribute('fill', '#fff');
    const path = document.createElementNS(NS, 'path');
    path.setAttribute('d', d); path.setAttribute('fill', '#000');
    el.append(bg, path);
    return el;
  }

  return { encode, svg, _t: { rsGenerator, rsRemainder, formatBits, versionBits, codewords, EC, ALIGN } };
})();
