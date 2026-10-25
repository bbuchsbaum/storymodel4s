// FilmPane: one video element, a two-part timeline with scene bands, annotation row ticks and
// numbered candidate pins, and the moment card for the row under review.
import { h, clear, clock, partName } from './dom.js';

const PAD = 1.0; // seconds of lead-in before a cued moment
const RATES = [0.75, 1, 1.25, 1.5, 2];

export class Film {
  constructor(S) {
    this.S = S;
    this.video = document.getElementById('video');
    this.part = null;
    this.span = null; // {part, t0, t1} of the cued moment
    this.loop = false;
    this.rate = 1;
    this.pins = [];
    this.focusRow = null;
    // Cueing plays by default; with reduced motion the reviewer starts playback (Space).
    this.autoplay = !matchMedia('(prefers-reduced-motion: reduce)').matches;
    this.video.addEventListener('timeupdate', () => this.tick());
    this.video.addEventListener('play', () => this.transport());
    this.video.addEventListener('pause', () => this.transport());
    this.buildTimeline();
    this.transport();
    const loopTick = () => {
      this.tick();
      requestAnimationFrame(loopTick);
    };
    requestAnimationFrame(loopTick);
  }

  get rows() {
    return this.S.index.rows;
  }

  rowAt(part, t) {
    return this.rows.find(r => r.part === Number(part) && r.t0 <= t && t < r.t1) || null;
  }

  load(part) {
    part = String(part);
    if (this.part === part) return;
    this.part = part;
    this.video.src = this.S.index.parts[part].media;
    this.video.playbackRate = this.rate;
  }

  async cue(m, { play = true } = {}) {
    this.load(m.part);
    this.span = { part: String(m.part), t0: m.t0, t1: m.t1 };
    const at = Math.max(0, (m.cue ?? m.t0) - PAD);
    const seek = () => {
      this.video.currentTime = at;
      if (play && this.autoplay) this.video.play().catch(() => {});
    };
    if (this.video.readyState >= 1) seek();
    else this.video.addEventListener('loadedmetadata', seek, { once: true });
    this.transport();
  }

  replay() {
    if (this.span) this.cue(this.span);
  }

  toggle() {
    if (!this.part) return;
    if (this.video.paused) this.video.play().catch(() => {});
    else this.video.pause();
  }

  stepRow(d) {
    // Move the open moment to the previous or next annotation row, in film order across parts.
    const rows = this.rows;
    let i = rows.findIndex(r => r.row === this.focusRow);
    if (i < 0 && this.part) {
      const t = this.video.currentTime;
      i = rows.findIndex(r => String(r.part) === this.part && r.t0 <= t && t < r.t1);
    }
    const r = rows[Math.max(0, Math.min(rows.length - 1, (i < 0 ? 0 : i + d)))];
    this.cue({ part: r.part, t0: r.t0, t1: r.t1 });
    this.S.onRow?.(r.row);
  }

  step(frames) {
    this.video.pause();
    this.video.currentTime = Math.max(0, this.video.currentTime + frames / 12);
  }

  setRate(dir) {
    const i = Math.max(0, Math.min(RATES.length - 1, RATES.indexOf(this.rate) + dir));
    this.rate = RATES[i];
    this.video.playbackRate = this.rate;
    this.transport();
  }

  toggleLoop() {
    this.loop = !this.loop;
    this.transport();
  }

  tick() {
    const v = this.video;
    if (this.loop && this.span && !v.paused && v.currentTime > this.span.t1 + PAD) {
      v.currentTime = Math.max(0, this.span.t0 - PAD);
    }
    if (!this.part) return;
    const head = this.heads[this.part];
    const secs = this.S.index.parts[this.part].seconds;
    for (const [p, el] of Object.entries(this.heads)) el.hidden = p !== this.part;
    head.style.left = `${(100 * v.currentTime) / secs}%`;
    const now = this.timeEl;
    if (now) {
      const r = this.rowAt(this.part, v.currentTime);
      now.textContent = `playhead ${partName(this.part)} ${clock(v.currentTime)}${r ? ` · row ${r.row}` : ''}`;
    }
  }

  transport() {
    const T = document.getElementById('transport');
    const playing = this.part && !this.video.paused;
    this.timeEl = h('span.now', '–');
    clear(
      T,
      h('button.tbtn', { type: 'button', title: 'Play or pause (Space)', onclick: () => this.toggle(), 'aria-label': playing ? 'Pause' : 'Play' }, playing ? '❚❚' : '▶'),
      h('button.tbtn', { type: 'button', title: 'Replay moment (R)', onclick: () => this.replay(), disabled: !this.span }, '⟲'),
      h('button.tbtn', { type: 'button', title: 'Step back one frame (,)', onclick: () => this.step(-1) }, '‹'),
      h('button.tbtn', { type: 'button', title: 'Step forward one frame (.)', onclick: () => this.step(1) }, '›'),
      this.timeEl,
      h('span.spacer'),
      h('button.tbtn', { type: 'button', title: 'Loop the moment (L)', 'aria-pressed': String(this.loop), onclick: () => this.toggleLoop() }, 'loop'),
      h('button.tbtn', { type: 'button', title: 'Slower ([)', onclick: () => this.setRate(-1) }, '−'),
      h('span.rate', `${this.rate}×`),
      h('button.tbtn', { type: 'button', title: 'Faster (])', onclick: () => this.setRate(1) }, '+'),
    );
    this.tick();
  }

  buildTimeline() {
    const el = document.getElementById('timeline');
    this.heads = {};
    this.tracks = {};
    const tracks = Object.entries(this.S.index.parts).map(([p, info]) => {
      const secs = info.seconds;
      const pct = t => `${(100 * t) / secs}%`;
      const bands = this.S.index.scenes
        .filter(s => String(s.part) === p)
        .map((s, i) =>
          h('div.band', {
            class: `band ${i % 2 ? 'odd' : 'even'}`,
            dataset: { scene: s.scene },
            style: { left: pct(s.t0), width: pct(s.t1 - s.t0) },
            title: `scene ${s.scene}`,
          }, h('span.bandlabel', String(s.scene))),
        );
      const ticks = this.rows
        .filter(r => String(r.part) === p)
        .map(r => h('div.tick', { style: { left: pct(r.t0) } }));
      const head = h('div.head', { hidden: true });
      const pins = h('div.pins');
      this.heads[p] = head;
      const track = h('div.track', {
        dataset: { part: p },
        onclick: e => {
          const box = track.getBoundingClientRect();
          const t = ((e.clientX - box.left) / box.width) * secs;
          const r = this.rowAt(p, t);
          this.cue({ part: p, t0: t, t1: r ? r.t1 : t + 5, cue: t + PAD });
          if (r) this.S.onRow?.(r.row);
        },
      }, bands, ticks, pins, head);
      this.tracks[p] = { track, pins, pct, frac: t => t / secs };
      return [h('div.trackrow', h('span.partlabel', partName(p)), track), h('div.lanerow', h('span.partlabel'), h('div.lanes', { id: `lanes-${p}` }))];
    });
    clear(el, tracks);
  }

  setPins(moments, active) {
    for (const { pins } of Object.values(this.tracks)) clear(pins);
    // Pins that would overlap are stacked on a second lane instead of drawn over each other.
    const lanes = {};
    const placed = moments.map((m, i) => ({ m, i, x: this.tracks[String(m.part)].frac(m.cue ?? m.t0) })).sort((a, b) => a.x - b.x);
    const minGap = 0.028;
    for (const p of placed) {
      const L = (lanes[p.m.part] ||= [-1, -1]);
      p.lane = p.x - L[0] >= minGap ? 0 : p.x - L[1] >= minGap ? 1 : 0;
      L[p.lane] = p.x;
    }
    placed.forEach(({ m, i, x, lane }) => {
      const t = this.tracks[String(m.part)];
      t.pins.append(
        h('button.pin', {
          type: 'button',
          class: `pin lane${lane}${m.row === active ? ' active' : ''}`,
          style: { left: `clamp(8px, ${(100 * x).toFixed(3)}%, calc(100% - 8px))` },
          title: `candidate ${i + 1}: row ${m.row}`,
          onclick: e => {
            e.stopPropagation();
            this.S.onPick?.(i);
          },
        }, String(i + 1)),
      );
    });
  }

  markScenes(sceneIds) {
    const set = new Set(sceneIds.map(Number));
    for (const b of document.querySelectorAll('#timeline .band')) {
      b.classList.toggle('nominated', set.has(Number(b.dataset.scene)));
    }
  }

  // Under the timeline: for each person the open row lists, where else the annotation lists
  // them, on the same time axis. Raw evidence about the open moment, never a ranking.
  lanes(row) {
    for (const p of Object.keys(this.S.index.parts)) {
      const el = document.getElementById(`lanes-${p}`);
      if (!el) continue;
      if (row === null || row === undefined) { clear(el); continue; }
      const names = this.S.film.rows[row].names.slice(0, 6);
      const secs = this.S.index.parts[p].seconds;
      clear(el, names.map(name => h('div.lane', { title: `${name}: rows that list them in ${partName(p)}` },
        h('span.lanename', name),
        this.rows.filter(r => String(r.part) === p && this.S.film.rows[r.row].names.includes(name)).map(r =>
          h('div.lseg', { class: `lseg${r.row === row ? ' here' : ''}`, style: { left: `${(100 * r.t0) / secs}%`, width: `${Math.max(0.3, (100 * (r.t1 - r.t0)) / secs)}%` } })))));
    }
  }

  showMoment(row) {
    const el = document.getElementById('moment');
    this.focusRow = row;
    this.lanes(row);
    if (row === null || row === undefined) {
      clear(el, h('p.empty', 'Choose a candidate, a search result or a point on the timeline to see the annotation for that moment.'));
      return;
    }
    const r = this.rows.find(x => x.row === row);
    const t = this.S.film.rows[row];
    const lines = (this.S.film.lines[r.part] || []).filter(l => l.t1 > r.t0 && l.t0 < r.t1);
    const frames = [0.2, 0.5, 0.8].map(f => Math.round((r.t0 + f * (r.t1 - r.t0)) * 1000));
    const chip = (n, cls) => h('span.chip', { class: `chip ${cls}` }, n);
    clear(
      el,
      h('div.mhead',
        h('span.mtitle', `Row ${r.row}`),
        h('span.mmeta', `${partName(r.part)} ${clock(r.t0)}–${clock(r.t1)} · scene ${r.scenes.join('/') || '–'}`),
        h('button.tbtn', { type: 'button', onclick: () => this.cue({ part: r.part, t0: r.t0, t1: r.t1 }) }, 'Play row')),
      h('div.frames', frames.map(ms => h('img.frame', { src: `/frame/${r.part}/${ms}.jpg`, alt: `frame at ${clock(ms / 1000)}`, loading: 'lazy', onclick: () => this.cue({ part: r.part, t0: ms / 1000, t1: r.t1, cue: ms / 1000 + PAD }, { play: false }) }))),
      h('p.details', t.details || '—'),
      h('div.people',
        h('span.k', 'In scene'), t.names.length ? t.names.map(n => chip(n, t.focus.includes(n) ? 'focus' : '')) : h('span.none', 'no one listed'),
        t.speaking.length ? [h('span.k', 'Speaking'), t.speaking.map(n => chip(n, 'speak'))] : null),
      h('div.facts', h('span.k', 'Location'), h('span', t.location || '—'), t.onscreen ? [h('span.k', 'On screen'), h('span', t.onscreen)] : null),
      lines.length
        ? h('ol.lines', lines.map(l => h('li', h('button.ts', { type: 'button', onclick: () => this.cue({ part: r.part, t0: l.t0, t1: l.t1 }) }, clock(l.t0)), h('span', l.text))))
        : h('p.none', 'No dialogue in this row.'),
    );
  }
}
