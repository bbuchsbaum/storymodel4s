// RecallPane: the whole recall as continuous text, grouped by review window. Words are selected
// with the mouse or the keyboard; committed decisions are drawn as marked spans. Windows do not
// bound sentences, so a selection may cross a window edge (Codex step 1).
import { h, clear } from './dom.js';

export class RecallPane {
  constructor(S) {
    this.S = S;
    this.wordEls = [];
    this.winEls = {};
    this.dragging = false;
    document.addEventListener('mouseup', () => (this.dragging = false));
  }

  build() {
    const L = document.getElementById('left');
    const words = this.S.index.words;
    const text = this.S.recallText;
    this.wordEls = new Array(words.length + 1);
    const blocks = this.S.index.windows.map(w => {
      const spans = [];
      for (let r = w.first; r <= w.last; r++) {
        const info = words[r - 1];
        const el = h('span.w', {
          class: `w${info.screen ? ' screen' : ''}${info.missing ? ' untimed' : ''}`,
          dataset: { row: r },
          onmousedown: e => {
            e.preventDefault();
            this.dragging = true;
            this.S.select(e.shiftKey ? this.S.sel?.a ?? r : r, r);
          },
          onmouseover: () => {
            if (this.dragging) this.S.select(this.S.sel?.a ?? r, r);
          },
          ondblclick: () => this.S.reopenAt(r),
        }, text[r - 1]);
        this.wordEls[r] = el;
        spans.push(el, ' ');
      }
      const head = h('div.winhead');
      const block = h('section.win', { dataset: { window: w.window } }, head, h('p.wtext', spans));
      this.winEls[w.window] = { block, head };
      return block;
    });
    clear(L, h('div.readhead', h('div.panehead', h('h2', 'Recall'), h('span.count#wcount')),
      h('p.blindbanner#blindbanner', { hidden: true }, 'Calibration words: timing, candidates and search stay hidden until the window is revealed.')),
      h('div.reader#reader', blocks));
    this.refresh();
  }

  windowOf(row) {
    return this.S.index.words[row - 1].window;
  }

  refreshHeads() {
    const read = this.S.state.windows;
    const words = this.S.index.words;
    for (const w of this.S.index.windows) {
      const { head, block } = this.winEls[w.window];
      const done = read[String(w.window)]?.[0];
      const one = new Set();
      let disagree = 0;
      let masked = 0;
      const m = this.S.masked();
      for (let r = w.first; r <= w.last; r++) {
        if (m.has(r)) { masked++; continue; } // calibration words never show their timing
        const n = words[r - 1].nom;
        n.one.forEach(s => one.add(s));
        if (words[r - 1].boundary) disagree++;
      }
      const isCal = (this.S.state.calibrationWindows || []).includes(w.window);
      const blind = isCal; // calibration words never carry timing chips, revealed or not
      block.classList.toggle('calwin', isCal);
      block.classList.toggle('current', w.window === this.S.win);
      block.classList.toggle('read', !!done);
      clear(
        head,
        h('button.readbox', {
          type: 'button',
          'aria-label': `Window ${w.window} read`,
          'aria-pressed': String(!!done),
          title: 'Mark this window read (M)',
          onclick: () => this.S.markRead(w.window, !done),
        }, done ? '✓' : ''),
        h('span.wnum', `Window ${w.window}`),
        h('span.wrange', `words ${w.first}–${w.last}`),
        isCal ? h('span.caltag', { class: masked ? 'caltag' : 'caltag revealed', title: masked ? 'Calibration window: timing, candidates and search stay hidden here. Commit every reference blind, then reveal the window (Shift+R).' : 'Calibration window, revealed' }, masked ? 'calibration' : 'calibration · revealed') : null,
        blind
          ? null
          : h('span.noms', [...one].sort((a, b) => a - b).map(s => h('button.scenechip', { type: 'button', title: `Timing points to scene ${s}; click to play it`, onclick: () => this.S.cueScene(s) }, `sc ${s}`))),
        !blind && disagree ? h('span.clockchip', { title: `${disagree} of these words point to a different scene under another timing convention` }, `${disagree} clock-sensitive`) : null,
      );
    }
    const banner = document.getElementById('blindbanner');
    if (banner) banner.hidden = !this.S.blind();
    const n = Object.values(read).filter(v => v[0]).length;
    const c = document.getElementById('wcount');
    if (c) c.textContent = `${n} of ${this.S.index.windows.length} windows read`;
  }

  refreshMarks() {
    for (let r = 1; r < this.wordEls.length; r++) {
      const el = this.wordEls[r];
      el.classList.remove('decided', 'dstart', 'dend', 'coref', 'active');
      delete el.dataset.did;
    }
    const active = this.S.active?.id;
    for (const d of Object.values(this.S.state.decisions)) {
      const f = d.reference;
      const mark = (a, b, cls) => {
        for (let r = a; r <= b; r++) {
          const el = this.wordEls[r];
          el.classList.add(cls);
          if (d.id === active) el.classList.add('active');
          el.dataset.did = d.id;
        }
        this.wordEls[a].classList.add('dstart');
        this.wordEls[b].classList.add('dend');
      };
      mark(f.first_word, f.last_word, 'decided');
      for (const [a, b] of f.other_coref_spans || []) mark(a, b, 'coref');
    }
  }

  refreshSelection() {
    for (const el of document.querySelectorAll('#reader .w.sel, #reader .w.cursor')) el.classList.remove('sel', 'cursor');
    const s = this.S.sel;
    if (s) {
      const [a, b] = [Math.min(s.a, s.b), Math.max(s.a, s.b)];
      for (let r = a; r <= b; r++) this.wordEls[r].classList.add('sel');
      this.wordEls[s.b]?.classList.add('cursor');
    }
  }

  refresh() {
    this.refreshHeads();
    this.refreshMarks();
    this.refreshSelection();
  }

  scrollToWindow(w) {
    this.winEls[w]?.block.scrollIntoView({ block: 'start', behavior: this.S.reducedMotion() ? 'auto' : 'smooth' });
  }

  scrollToRow(r) {
    this.wordEls[r]?.scrollIntoView({ block: 'nearest' });
  }
}
