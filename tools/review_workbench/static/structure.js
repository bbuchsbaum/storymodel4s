// StructurePanel: raw-evidence views only while labelling (plan, "Structure panel"): who the
// annotation lists on screen across the film, and where the recall's own timing points against
// film order. Nothing here is built from committed decisions. During a calibration item the
// timing view is withheld, because it is a suggestion. Both views are clickable: presence cues the
// film, the timing view selects that recall word.
import { h, clear, clock, partName } from './dom.js';

const SVG = 'http://www.w3.org/2000/svg';
function s(tag, attrs = {}, ...kids) {
  const el = document.createElementNS(SVG, tag);
  for (const [k, v] of Object.entries(attrs)) el.setAttribute(k, v);
  for (const k of kids) el.append(k instanceof Node ? k : document.createTextNode(String(k)));
  return el;
}

export class Structure {
  constructor(S) {
    this.S = S;
    this.el = document.getElementById('structure');
  }

  toggle() {
    this.el.hidden = !this.el.hidden;
    document.body.classList.toggle('with-structure', !this.el.hidden);
    if (!this.el.hidden) this.render();
  }

  width() {
    // Draw at the real pixel width so 11 px labels stay 11 px.
    const half = (this.el.clientWidth || 1200) / 2 - 40;
    return Math.max(420, Math.round(half));
  }

  refresh() {
    if (!this.el.hidden) this.render();
  }

  presence() {
    const { index, film } = this.S;
    const parts = Object.entries(index.parts);
    const total = parts.reduce((a, [, p]) => a + p.seconds, 0);
    const offset = {};
    let acc = 0;
    for (const [p, info] of parts) {
      offset[p] = acc;
      acc += info.seconds;
    }
    const W = this.width();
    const people = film.people;
    const rowH = 16;
    const left = 130;
    const top = 16;
    const H = top + people.length * rowH + 22;
    const x = (p, t) => left + ((offset[p] + t) / total) * (W - left - 8);
    const svg = s('svg', { viewBox: `0 0 ${W} ${H}`, class: 'presence', role: 'img', 'aria-label': 'Who the annotation lists on screen across the film' });
    for (const [p, info] of parts) {
      svg.append(s('rect', { x: x(p, 0), y: top, width: x(p, info.seconds) - x(p, 0), height: people.length * rowH, class: Number(p) % 2 ? 'partbg odd' : 'partbg' }));
      svg.append(s('text', { x: x(p, 0) + 3, y: H - 4, class: 'axis strong' }, partName(p)));
      for (let t = 60; t < info.seconds; t += 60) {
        svg.append(s('line', { x1: x(p, t), x2: x(p, t), y1: top + people.length * rowH, y2: top + people.length * rowH + 4, class: 'tickmark' }));
        if (x(p, t) - x(p, 0) > 50) svg.append(s('text', { x: x(p, t), y: H - 4, class: 'axis', 'text-anchor': 'middle' }, `${t / 60}:00`));
      }
    }
    for (const sc of index.scenes) {
      const p = String(sc.part);
      svg.append(s('line', { x1: x(p, sc.t0), x2: x(p, sc.t0), y1: top, y2: top + people.length * rowH, class: 'sceneline' }));
      svg.append(s('text', { x: x(p, sc.t0) + 2, y: top - 2, class: 'axis small' }, String(sc.scene)));
    }
    people.forEach((name, i) => {
      const y = top + i * rowH + 2;
      svg.append(s('text', { x: left - 8, y: y + 10, class: 'pname', 'text-anchor': 'end' }, name));
      for (const r of index.rows) {
        const t = film.rows[r.row];
        if (!t.names.includes(name)) continue;
        const p = String(r.part);
        const cls = t.speaking.includes(name) ? 'pres speak' : t.focus.includes(name) ? 'pres focus' : 'pres';
        const rect = s('rect', { x: x(p, r.t0), y, width: Math.max(1.5, x(p, r.t1) - x(p, r.t0) - 0.5), height: rowH - 5, class: cls });
        rect.append(s('title', {}, `${name} · row ${r.row} · ${partName(p)} ${clock(r.t0)}`));
        rect.addEventListener('click', () => {
          this.S.filmPane.cue({ part: r.part, t0: r.t0, t1: r.t1 });
          this.S.onRow?.(r.row);
        });
        svg.append(rect);
      }
    });
    const f = this.S.filmPane;
    if (f.part) {
      const ph = s('line', { x1: x(f.part, f.video.currentTime), x2: x(f.part, f.video.currentTime), y1: top, y2: top + people.length * rowH, class: 'nowline' });
      svg.append(ph);
    }
    return svg;
  }

  nominations() {
    const { index } = this.S;
    const words = index.words;
    const scenes = index.scenes.map(x => x.scene);
    const W = this.width();
    const left = 52;
    const top = 8;
    const maxS = Math.max(...scenes);
    const step = maxS <= 20 ? 12 : maxS <= 40 ? 7 : 5;
    const H = top + maxS * step + 30;
    const x = r => left + ((r - 1) / Math.max(1, words.length - 1)) * (W - left - 8);
    const y = sc => top + (sc - 0.5) * step;
    const svg = s('svg', { viewBox: `0 0 ${W} ${H}`, class: 'nomplot', role: 'img', 'aria-label': 'Scene the timing points to for each recall word, in recall order' });
    for (const sc of scenes) {
      svg.append(s('line', { x1: left, x2: W - 8, y1: y(sc), y2: y(sc), class: 'grid' }));
      if (maxS <= 20 || sc % 5 === 0 || sc === 1) svg.append(s('text', { x: left - 6, y: y(sc) + 3.5, class: 'axis', 'text-anchor': 'end' }, String(sc)));
    }
    for (const win of index.windows) {
      svg.append(s('line', { x1: x(win.first), x2: x(win.first), y1: top, y2: top + maxS * step, class: 'winline' }));
      if (x(win.first) < W - 24) svg.append(s('text', { x: x(win.first) + 2, y: H - 16, class: 'axis small' }, `w${win.window}`));
    }
    words.forEach(w => {
      for (const sc of w.nom.one) svg.append(s('rect', { x: x(w.row) - 0.8, y: y(sc) - 3, width: 2, height: 6, class: w.boundary ? 'nom alt' : 'nom' }));
    });
    const sel = this.S.sel;
    if (sel) {
      const a = Math.min(sel.a, sel.b);
      const b = Math.max(sel.a, sel.b);
      svg.append(s('rect', { x: x(a) - 2, y: top, width: Math.max(4, x(b) - x(a) + 4), height: maxS * step, class: 'selband' }));
    }
    svg.append(s('text', { x: left, y: H - 3, class: 'axis' }, 'recall order →  (click to select a word)'));
    const yc = top + (maxS * step) / 2;
    svg.append(s('text', { x: 12, y: yc, class: 'axis', 'text-anchor': 'middle', transform: `rotate(-90 12 ${yc})` }, 'scene'));
    svg.addEventListener('click', e => {
      const box = svg.getBoundingClientRect();
      const vx = ((e.clientX - box.left) / box.width) * W;
      const r = Math.round(((vx - left) / (W - left - 8)) * (words.length - 1)) + 1;
      if (r >= 1 && r <= words.length) this.S.select(r, r);
    });
    return svg;
  }

  render() {
    const recall = this.S.mode === 'recall';
    const blind = recall && this.S.blind();
    clear(this.el,
      h('div.panehead', h('h2', 'Structure'), h('span.count', 'raw evidence only; nothing here comes from your decisions'), h('button.ghost', { type: 'button', onclick: () => this.toggle() }, 'Close', h('kbd', { 'aria-hidden': 'true' }, 'Esc'))),
      h('div.structgrid',
        h('section', h('h3', 'Who is on screen'), h('p.note', 'Rows of the annotation that list each person. Darker: in focus. Outlined: speaking. Red line: playhead. Click a bar to play it.'), this.presence()),
        recall
          ? h('section', h('h3', 'Where the recall’s timing points'),
            blind
              ? h('p.note', 'Hidden during a calibration item: timing is a suggestion.')
              : [h('p.note', 'Each mark is one recall word at the scene its one-based TR timing points to. Hollow: another clock points elsewhere. Shaded band: your selection.'), this.nominations()])
          : null),
    );
  }
}
