// StructurePanel: raw-evidence views only while labelling (plan, "Structure panel"): who is on
// screen across the film, and where the recall's own timing nominations fall against film
// order. Nothing here is built from committed decisions.
import { h, clear, partName } from './dom.js';

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
    const W = 1000;
    const people = film.people;
    const rowH = 18;
    const left = 150;
    const H = people.length * rowH + 28;
    const x = (p, t) => left + ((offset[p] + t) / total) * (W - left - 8);
    const svg = s('svg', { viewBox: `0 0 ${W} ${H}`, class: 'presence', role: 'img', 'aria-label': 'Who is listed on screen across the film' });
    for (const [p, info] of parts) {
      svg.append(s('rect', { x: x(p, 0), y: 0, width: x(p, info.seconds) - x(p, 0), height: H - 20, class: Number(p) % 2 ? 'partbg odd' : 'partbg' }));
      svg.append(s('text', { x: x(p, 0) + 4, y: H - 6, class: 'axis' }, partName(p)));
    }
    for (const sc of index.scenes) {
      svg.append(s('line', { x1: x(String(sc.part), sc.t0), x2: x(String(sc.part), sc.t0), y1: 0, y2: H - 20, class: 'sceneline' }));
    }
    people.forEach((name, i) => {
      const y = i * rowH + 4;
      svg.append(s('text', { x: left - 8, y: y + 11, class: 'pname', 'text-anchor': 'end' }, name));
      for (const r of index.rows) {
        const t = film.rows[r.row];
        if (!t.names.includes(name)) continue;
        const cls = t.speaking.includes(name) ? 'pres speak' : t.focus.includes(name) ? 'pres focus' : 'pres';
        svg.append(s('rect', { x: x(String(r.part), r.t0), y, width: Math.max(1, x(String(r.part), r.t1) - x(String(r.part), r.t0)), height: rowH - 6, class: cls }));
      }
    });
    return svg;
  }

  nominations() {
    const { index } = this.S;
    const words = index.words;
    const scenes = index.scenes.map(x => x.scene);
    const W = 1000;
    const left = 40;
    const H = 200;
    const maxS = Math.max(...scenes);
    const x = r => left + ((r - 1) / Math.max(1, words.length - 1)) * (W - left - 8);
    const y = sc => 8 + ((sc - 1) / Math.max(1, maxS - 1)) * (H - 36);
    const svg = s('svg', { viewBox: `0 0 ${W} ${H}`, class: 'nomplot', role: 'img', 'aria-label': 'Scene nominated for each recall word, in recall order' });
    for (const sc of scenes) {
      if (sc % 5 === 1 || sc === maxS) svg.append(s('text', { x: left - 6, y: y(sc) + 4, class: 'axis', 'text-anchor': 'end' }, String(sc)));
      svg.append(s('line', { x1: left, x2: W - 8, y1: y(sc), y2: y(sc), class: 'grid' }));
    }
    words.forEach(w => {
      for (const sc of w.nom.one) svg.append(s('rect', { x: x(w.row) - 0.6, y: y(sc) - 2, width: 1.6, height: 4, class: w.boundary ? 'nom alt' : 'nom' }));
    });
    for (const win of index.windows) svg.append(s('line', { x1: x(win.first), x2: x(win.first), y1: H - 26, y2: H - 20, class: 'tickmark' }));
    svg.append(s('text', { x: left, y: H - 6, class: 'axis' }, 'recall order →'));
    svg.append(s('text', { x: 4, y: 12, class: 'axis' }, 'scene'));
    return svg;
  }

  render() {
    const recall = this.S.mode === 'recall';
    clear(this.el,
      h('div.panehead', h('h2', 'Structure'), h('span.count', 'raw evidence only while labelling'), h('button.ghost', { type: 'button', onclick: () => this.toggle() }, 'Close (Shift+S)')),
      h('section', h('h3', 'Who is on screen'), h('p.note', 'Annotation Name - All per row. Darker: in focus. Outlined: speaking.'), this.presence()),
      recall ? h('section', h('h3', 'Timing nominations'), h('p.note', 'Scene nominated for each recall word by the one-based TR convention. Hollow marks change across conventions.'), this.nominations()) : null,
    );
  }
}
