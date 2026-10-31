// The dashboard's little speaker button: plays the MC.SESH tune (setup/music.js). On by default and remembered in this browser.
// Browsers only allow sound after the person has clicked or tapped something, so it starts on the first click (signing in counts).
(() => {
  'use strict';
  const KEY = 'mcm.music';
  const btn = document.getElementById('musicBtn');
  if (!btn || !window.McMusic) return;
  const read = () => { try { return localStorage.getItem(KEY) !== '0'; } catch (e) { return true; } };
  let on = read(), started = false;
  const draw = () => {
    btn.replaceChildren(icon(on ? 'speaker' : 'speakeroff'));
    btn.title = on ? 'Music is on. Click to mute' : 'Music is muted. Click to turn it on';
    btn.setAttribute('aria-pressed', String(on));
  };
  const begin = () => {
    if (started) return;
    started = true;
    McMusic.setCycle(4);   // the dashboard tune starts over at the piano after the 4th pass; the setup wizard keeps looping the full mix
    McMusic.setVolume(0.6);
    McMusic.setMuted(!on);
    McMusic.start();
    if (!on) McMusic.setMuted(true);
  };
  const arm = () => {
    const first = () => {
      ['pointerdown', 'keydown', 'touchstart'].forEach((e) => document.removeEventListener(e, first, true));
      if (on) begin();
    };
    ['pointerdown', 'keydown', 'touchstart'].forEach((e) => document.addEventListener(e, first, true));
  };
  btn.addEventListener('click', () => {
    on = !on;
    try { localStorage.setItem(KEY, on ? '1' : '0'); } catch (e) { /* private window: just not remembered */ }
    if (on && !started) begin(); else McMusic.setMuted(!on);
    draw();
  });
  draw();
  arm();
})();
