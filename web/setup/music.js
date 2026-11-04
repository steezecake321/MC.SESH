/* MC.SESH setup music: an original ambient-piano tune that builds in layers, synthesised live with the Web Audio API (no audio files).
   Timeline (one pass = 16 bars = about 44 s; a half pass = 8 bars):
     pass 1        piano
     pass 1.5      + reese bass and retro arp
     pass 2        + drum & bass drums
     pass 2.5      + boom bap
     pass 3 on     everything together, looping
   If the install folder has a music\theme.mp3 (or .ogg/.wav) the wizard plays that file instead. */
(function () {
  'use strict';
  const BPM = 88, BEAT = 60 / BPM, BAR_SECS = 4 * BEAT, BARS_PER_PASS = 16, PASS = BARS_PER_PASS * BAR_SECS;
  const SIXT = 60 / 176 / 4;   // one 16th at 176 BPM (double time of the piano)
  const hz = (m) => 440 * Math.pow(2, (m - 69) / 12);

  // ---------- the tune: 16 bars of 4/4 that loops from the last bar back to the first ----------
  const CH = { C: [48, [52, 55, 59]], GB: [47, [50, 55, 59]], Am: [45, [52, 55, 60]], F: [41, [52, 57, 60]], Em: [40, [50, 55, 59]], G: [43, [50, 55, 59]], EmG: [43, [50, 55, 59]], CE: [40, [55, 59, 64]], Dm: [38, [53, 57, 60]] };
  const PROG = ['C', 'GB', 'Am', 'F', 'C', 'Em', 'F', 'G', 'Am', 'EmG', 'F', 'CE', 'Dm', 'G', 'Am', 'G'];
  const MEL = [
    [[0, 1.5, 76], [2, 1, 79], [3, 1, 76]], [[0, 2, 74], [2, 2, 71]], [[0, 1, 72], [1, 1, 76], [2, 2, 81]], [[0, 1, 79], [1, 1, 76], [2, 2, 72]],
    [[0, 1.5, 76], [2, 1, 79], [3, 1, 83]], [[0, 2, 81], [2, 2, 79]], [[0, 1, 81], [1, 1, 79], [2, 2, 77]], [[0, 1, 74], [2, 2, 71]],
    [[1, 1, 76], [2, 2, 72]], [[0, 2, 71], [2, 2, 67]], [[0, 1, 69], [1, 1, 72], [2, 2, 76]], [[0, 3, 79]],
    [[0, 1, 77], [1, 1, 81], [2, 2, 84]], [[0, 1, 83], [1, 1, 81], [2, 2, 79]], [[0, 1, 76], [1, 1, 81], [2, 2, 72]], [[0, 2, 74], [2, 2, 71]]];
  const NOTES = [];   // [startBeat, lengthBeats, midi, velocity]
  PROG.forEach((name, bar) => {
    const [root, tones] = CH[name], b = bar * 4;
    NOTES.push([b, 2.2, root, 88]);
    tones.forEach((m, i) => NOTES.push([b + 1 + i, 1, m, 64]));
    MEL[bar].forEach(([at, len, m]) => NOTES.push([b + at, len, m, 100]));
  });
  const activeAt = (b) => NOTES.filter((n) => n[0] <= b + 1e-6 && n[0] + n[1] > b + 1e-6).map((n) => n[2]);

  // ---------- sound engine (light on purpose: drum hits are sample buffers, bass and arp share one filter each) ----------
  function mkBuf(c, secs, fn) {
    const n = Math.floor(c.sampleRate * secs), b = c.createBuffer(1, n, c.sampleRate), d = b.getChannelData(0);
    for (let i = 0; i < n; i++) d[i] = fn(i / c.sampleRate, i, d);
    return b;
  }
  function makeGraph(c) {
    const g = {}, sr = c.sampleRate;
    g.master = c.createGain(); g.master.gain.value = 0.8;
    // A fixed soft clipper, not a compressor: a compressor turns everything down while a new layer plays and back up afterwards, which sounds like the music
    // getting quieter and the new layer fading in. A clipper never changes its gain over time, so every layer lands at full punch and the level stays put.
    const lim = c.createWaveShaper(), curve = new Float32Array(2049);
    for (let i = 0; i < curve.length; i++) curve[i] = Math.tanh((i / 1024 - 1) * 1.4) / Math.tanh(1.4);
    lim.curve = curve; lim.oversample = '2x';
    g.master.connect(lim); lim.connect(c.destination);
    const len = Math.floor(sr * 1.8), ir = c.createBuffer(2, len, sr);
    for (let ch = 0; ch < 2; ch++) { const d = ir.getChannelData(ch); for (let i = 0; i < len; i++) d[i] = (Math.random() * 2 - 1) * Math.pow(1 - i / len, 3); }
    g.verb = c.createConvolver(); g.verb.buffer = ir;
    const verbOut = c.createGain(); verbOut.gain.value = 0.3; g.verb.connect(verbOut); verbOut.connect(g.master);
    g.piano = c.createGain(); g.piano.gain.value = 0.7; g.piano.connect(g.master);
    const ps = c.createGain(); ps.gain.value = 0.5; g.piano.connect(ps); ps.connect(g.verb);
    g.drums = c.createGain(); g.drums.gain.value = 0.55; g.drums.connect(g.master);
    g.bass = c.createGain(); g.bass.gain.value = 0.45; g.bass.connect(g.master);
    g.bassLp = c.createBiquadFilter(); g.bassLp.type = 'lowpass'; g.bassLp.Q.value = 5; g.bassLp.frequency.value = 520; g.bassLp.connect(g.bass);
    const lfo = c.createOscillator(); lfo.frequency.value = 176 / 60 / 2; const lg = c.createGain(); lg.gain.value = 300; lfo.connect(lg); lg.connect(g.bassLp.frequency); lfo.start();
    g.arp = c.createGain(); g.arp.gain.value = 0.5; g.arp.connect(g.master); g.arp.connect(g.verb);
    g.arpLp = c.createBiquadFilter(); g.arpLp.type = 'lowpass'; g.arpLp.Q.value = 2; g.arpLp.frequency.value = 2600; g.arpLp.connect(g.arp);
    const dl = c.createDelay(1); dl.delayTime.value = SIXT * 3; const fb = c.createGain(); fb.gain.value = 0.36; const dlOut = c.createGain(); dlOut.gain.value = 0.5;
    g.arp.connect(dl); dl.connect(fb); fb.connect(dl); dl.connect(dlOut); dlOut.connect(g.master);
    g.wave = c.createPeriodicWave(new Float32Array(9), new Float32Array([0, 1, 0.55, 0.3, 0.18, 0.1, 0.05, 0.03, 0.015]));
    let ph = 0, ph2 = 0, p1 = 0, p2 = 0;
    g.kick = mkBuf(c, 0.4, (t, i) => { if (!i) ph = 0; ph += 2 * Math.PI * (42 + 120 * Math.exp(-t / 0.028)) / sr; return Math.sin(ph) * Math.exp(-t / 0.11) * 0.95 + (t < 0.004 ? (Math.random() - 0.5) * 0.4 : 0); });
    g.snare = mkBuf(c, 0.3, (t, i) => { if (!i) p1 = 0; const n = Math.random() * 2 - 1, a = n - p1; p1 = n; return a * Math.exp(-t / 0.055) * 0.6 + Math.sin(2 * Math.PI * (170 + 70 * Math.exp(-t / 0.02)) * t) * Math.exp(-t / 0.05) * 0.5; });
    const hat = (decay, secs) => mkBuf(c, secs, (t, i) => { if (!i) { p1 = 0; p2 = 0; } const n = Math.random() * 2 - 1, a = n - p1; p1 = n; const b = a - p2; p2 = a; return b * Math.exp(-t / decay) * 0.45; });
    g.hatC = hat(0.012, 0.09); g.hatO = hat(0.07, 0.4); g.crash = hat(0.55, 2.4);
    g.bb = c.createGain(); g.bb.gain.value = 0.6;   // the boom bap alone peaks near 3x full scale: it is balanced here, at the source, instead of squashing the whole mix with a compressor g.bb.connect(g.master);
    g.kickBB = mkBuf(c, 0.9, (t, i) => {
      if (!i) ph2 = 0; ph2 += 2 * Math.PI * (47 + 90 * Math.exp(-t / 0.035)) / sr;
      return Math.tanh(2.2 * Math.sin(ph2)) * Math.exp(-t / 0.28) * 0.95 + (t < 0.012 ? (Math.random() * 2 - 1) * (1 - t / 0.012) * 0.55 : 0) + Math.sin(2 * Math.PI * 1500 * t) * Math.exp(-t / 0.004) * 0.35;
    });
    g.snareBB = mkBuf(c, 0.4, (t, i) => {
      if (!i) { p1 = 0; p2 = 0; } const n = Math.random() * 2 - 1, a = n - p1; p1 = n; const b = a - p2; p2 = a;
      return (a * 0.55 + b * 0.35) * Math.exp(-t / 0.085) + (t < 0.006 ? (1 - t / 0.006) * 0.9 : 0) * (Math.random() * 2 - 1) + Math.sin(2 * Math.PI * (185 + 60 * Math.exp(-t / 0.015)) * t) * Math.exp(-t / 0.07) * 0.55;
    });
    return g;
  }
  function pianoNote(c, g, t, m, dur, vel) {
    const f = hz(m), v = (vel / 127) * 0.2, tau = Math.max(0.5, 1.7 - (m - 43) * 0.027), end = t + dur + 0.3;
    const out = c.createGain(); out.gain.setValueAtTime(0.0001, t); out.gain.linearRampToValueAtTime(v, t + 0.006);
    out.gain.setTargetAtTime(v * 0.001, t + 0.006, tau); out.gain.setTargetAtTime(0, end - 0.25, 0.12);
    const lp = c.createBiquadFilter(); lp.type = 'lowpass'; lp.frequency.setValueAtTime(Math.min(f * 9, 9000), t); lp.frequency.setTargetAtTime(f * 2.5, t, 0.4);
    [0, 3].forEach((cents) => { const o = c.createOscillator(); o.setPeriodicWave(g.wave); o.frequency.value = f; o.detune.value = cents; o.connect(lp); o.start(t); o.stop(end + 0.5); });
    lp.connect(out); out.connect(g.piano);
  }
  function hit(c, g, buf, t, amp, bus) {
    const s = c.createBufferSource(); s.buffer = buf; const dest = bus || g.drums;
    if (amp === 1) s.connect(dest); else { const a = c.createGain(); a.gain.value = amp; s.connect(a); a.connect(dest); }
    s.start(t);
  }
  function bassNote(c, g, t, m, dur) {
    const f = hz(m), end = t + dur, a = c.createGain();
    a.gain.setValueAtTime(0.0001, t); a.gain.linearRampToValueAtTime(0.5, t + 0.01); a.gain.setValueAtTime(0.5, end - 0.03); a.gain.linearRampToValueAtTime(0.0001, end + 0.03);
    const o = c.createOscillator(); o.type = 'sawtooth'; o.frequency.value = f; o.detune.value = 8;
    const sub = c.createOscillator(); sub.type = 'sine'; sub.frequency.value = f;
    o.connect(a); sub.connect(a); a.connect(g.bassLp); o.start(t); sub.start(t); o.stop(end + 0.06); sub.stop(end + 0.06);
  }
  function arpNote(c, g, t, m) {
    const o = c.createOscillator(); o.type = 'sawtooth'; o.frequency.value = hz(m);
    const a = c.createGain(); a.gain.setValueAtTime(0.0001, t); a.gain.linearRampToValueAtTime(0.18, t + 0.005); a.gain.exponentialRampToValueAtTime(0.0008, t + 0.2);
    o.connect(a); a.connect(g.arpLp); o.start(t); o.stop(t + 0.25);
  }

  /** Schedules one piano bar of pass `pass` (0-based) that starts at `start` (the start of that pass). Small chunks keep the main thread calm. */
  function scheduleBar(c, g, start, pass, pb) {
    const b0 = pb * 4, b1 = b0 + 4, half = pass * 2 + (pb >= 8 ? 1 : 0);
    NOTES.forEach((n) => { if (n[0] >= b0 && n[0] < b1) pianoNote(c, g, start + n[0] * BEAT, n[2], n[1] * BEAT, n[3]); });
    const hasBA = half >= 1, hasDrums = half >= 2, boom = half >= 3;
    if (!hasBA) return;
    if ((pass >= 1 && pb === 0) || (pass === 1 && pb === 8)) hit(c, g, g.crash, start + b0 * BEAT, 0.7);
    for (let i = pb * 32; i < pb * 32 + 32; i++) {
      const t = start + i * SIXT, bar = i >> 4, s = i & 15, B = bar & 1, last = bar === 31, beatPos = (i * SIXT) / BEAT;
      if (hasDrums) {
        if (s === 0 || s === 10 || (B && s === 7)) hit(c, g, g.kick, t, 1);
        if (!last && (s === 4 || s === 12)) hit(c, g, g.snare, t, 1);
        if (!last && B && s === 15) hit(c, g, g.snare, t, 0.25);
        if (!last && !B && s === 7) hit(c, g, g.snare, t, 0.18);
        if (last && [4, 8, 10, 12, 13, 14, 15].includes(s)) hit(c, g, g.snare, t, 0.5 + s * 0.03);
        if (s === 6 || (s === 14 && !B)) hit(c, g, g.hatO, t, 0.7);
        else hit(c, g, g.hatC, t, s % 4 === 2 ? 1 : s % 2 ? 0.45 : 0.7);
      }
      if ([0, 3, 6, 10, 13].includes(s)) {
        const act = activeAt(beatPos); if (act.length) { let m = Math.min(...act); while (m > 52) m -= 12; bassNote(c, g, t, m, SIXT * (s === 0 ? 3 : 2.2)); }
      }
      if ((s & 1) === 0) {
        const act = [...new Set(activeAt(beatPos).map((m) => m % 12))].sort((a, b) => a - b);
        if (act.length) {
          const tones = act.map((pc) => { const m = 60 + pc; return m > 83 ? m - 12 : m; }).sort((a, b) => a - b);
          arpNote(c, g, t, tones[(i >> 1) % tones.length] + (((i >> 3) & 1) ? 12 : 0));
        }
      }
      if (boom) {
        const j = i & 31, odd = (i >> 5) & 1;
        if (j === 0 || j === 12 || j === 20 || (odd && j === 28)) hit(c, g, g.kickBB, t, j === 0 ? 1 : 0.8, g.bb);
        if (j === 8 || j === 24) hit(c, g, g.snareBB, t, 1, g.bb);
        if (odd && j === 31) hit(c, g, g.snareBB, t, 0.22, g.bb);
      }
    }
  }

  // ---------- player ----------
  let ctx = null, graph = null, timer = null, t0 = 0, cursor = 0, volume = 0.8, muted = false, onStage = null, audioEl = null;
  const STAGES = ['Piano', 'Reese bass + arp', 'Drums', 'Boom bap', 'Full mix'];
  const level = () => (muted ? 0 : volume * 0.72);   // the full mix peaks just over 1.0 at the old 0.8 ceiling; 0.72 keeps the loudest hits clean
  let cycle = 0;   // after this many passes the tune starts over at the piano (0 = never)
  function stageOf(el) { const e = cycle ? el % (cycle * PASS) : el; return Math.min(STAGES.length - 1, Math.floor(e / (PASS / 2))); }

  /** opts.fromPass skips ahead (used by the test page to hear a restart without waiting for it). */
  function start(opts) {
    if (ctx || audioEl) return;
    if (window.MCSESH_CUSTOM_MUSIC) {
      audioEl = new Audio(window.MCSESH_CUSTOM_MUSIC); audioEl.loop = true; audioEl.volume = level();
      audioEl.play().catch(() => { });
      if (onStage) onStage('Your music', -1);
      return;
    }
    ctx = new (window.AudioContext || window.webkitAudioContext)({ latencyHint: 'playback' });
    graph = makeGraph(ctx);
    graph.master.gain.setValueAtTime(0, ctx.currentTime); graph.master.gain.linearRampToValueAtTime(level(), ctx.currentTime + 0.03);   // just enough to avoid a click
    cursor = ((opts && opts.fromPass) || 0) * BARS_PER_PASS;
    t0 = ctx.currentTime + 0.4 - cursor * BAR_SECS;
    const tick = () => {
      if (!ctx) return;
      while (t0 + cursor * BAR_SECS < ctx.currentTime + 5) {
        const pass = Math.floor(cursor / BARS_PER_PASS);
        scheduleBar(ctx, graph, t0 + pass * PASS, cycle ? pass % cycle : pass, cursor % BARS_PER_PASS);   // a restart is just pass 0 again: piano only
        cursor++;
      }
      if (onStage) { const i = stageOf(Math.max(0, ctx.currentTime - t0)); onStage(STAGES[i], i); }
    };
    tick(); timer = setInterval(tick, 200);
  }
  function stop() {
    clearInterval(timer); timer = null;
    if (audioEl) { audioEl.pause(); audioEl = null; }
    if (ctx) { const c = ctx, g = graph; ctx = null; graph = null; g.master.gain.cancelScheduledValues(0); g.master.gain.setTargetAtTime(0, c.currentTime, 0.05); setTimeout(() => c.close(), 400); }
  }
  function apply() {
    if (graph && ctx) {
      graph.master.gain.setTargetAtTime(level(), ctx.currentTime, 0.03);
      // a muted tune is paused (not just silent): it costs nothing and picks up where it left off
      if (muted) setTimeout(() => { if (ctx && muted && ctx.state === 'running') ctx.suspend(); }, 250);
      else if (ctx.state === 'suspended') ctx.resume();
    }
    if (audioEl) { audioEl.volume = level(); if (muted) audioEl.pause(); else audioEl.play().catch(() => { }); }
  }
  async function renderOffline(passes) {
    const sr = 44100, oc = new OfflineAudioContext(2, Math.ceil(sr * (PASS * passes + 4)), sr), g = makeGraph(oc);
    for (let k = 0; k < passes * BARS_PER_PASS; k++) scheduleBar(oc, g, 0.2 + Math.floor(k / BARS_PER_PASS) * PASS, Math.floor(k / BARS_PER_PASS), k % BARS_PER_PASS);
    return oc.startRendering();
  }
  window.McMusic = {
    start, stop, STAGES, PASS, renderOffline,
    get playing() { return !!(ctx || audioEl); },
    setCycle(n) { cycle = Math.max(0, Math.floor(n) || 0); },
    setVolume(v) { volume = v; apply(); },
    setMuted(m) { muted = !!m; apply(); },
    onStage(fn) { onStage = fn; },
  };
})();
