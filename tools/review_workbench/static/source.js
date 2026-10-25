// SourcePass: scene by scene in film order. For each annotation row the reviewer confirms who
// is present, adds unnamed people, and resolves each recognizer person-mention. No recall is
// loaded or shown in this pass (plan constraint 3).
import { h, clear, clock, partName, toast } from './dom.js';
import { post } from './api.js';

export class SourcePass {
  constructor(S) {
    this.S = S;
    this.scene = S.index.scenes[0].scene;
    this.rowIdx = 0;
    this.drafts = {};
  }

  get scenes() {
    return this.S.index.scenes;
  }

  sceneRows() {
    const s = this.scenes.find(x => x.scene === this.scene);
    return s.rows;
  }

  record(row) {
    return this.S.state.source.rows[row] || null;
  }

  draft(row) {
    if (!this.drafts[row]) {
      const rec = this.record(row);
      const t = this.S.film.rows[row];
      this.drafts[row] = rec
        ? { present: new Set(rec.present), unnamed: new Set(rec.unnamed), mentions: { ...rec.mentions } }
        : { present: new Set(t.names), unnamed: new Set(), mentions: {} };
    }
    return this.drafts[row];
  }

  firstOpenRow() {
    for (const s of this.scenes) {
      for (const r of s.rows) if (!this.record(r)?.done) return { scene: s.scene, row: r };
    }
    return null;
  }

  enter() {
    const open = this.firstOpenRow();
    if (open) {
      this.scene = open.scene;
      this.rowIdx = this.sceneRows().indexOf(open.row);
    }
    this.render();
    this.cueRow();
  }

  currentRow() {
    return this.sceneRows()[this.rowIdx];
  }

  cueRow() {
    const row = this.currentRow();
    const r = this.S.index.rows.find(x => x.row === row);
    this.S.filmPane.cue({ part: r.part, t0: r.t0, t1: r.t1 });
    this.S.filmPane.showMoment(row);
    this.S.filmPane.markScenes([this.scene]);
  }

  move(d) {
    const rows = this.sceneRows();
    let i = this.rowIdx + d;
    const si = this.scenes.findIndex(s => s.scene === this.scene);
    if (i >= rows.length && si < this.scenes.length - 1) {
      this.scene = this.scenes[si + 1].scene;
      i = 0;
    } else if (i < 0 && si > 0) {
      this.scene = this.scenes[si - 1].scene;
      i = this.sceneRows().length - 1;
    }
    this.rowIdx = Math.max(0, Math.min(this.sceneRows().length - 1, i));
    this.render();
    this.cueRow();
  }

  goRow(row) {
    const s = this.scenes.find(x => x.rows.includes(row));
    if (!s) return;
    this.scene = s.scene;
    this.rowIdx = s.rows.indexOf(row);
    this.render();
    this.S.filmPane.showMoment(row);
    this.S.filmPane.markScenes([this.scene]);
  }

  goScene(id) {
    this.scene = id;
    this.rowIdx = 0;
    this.render();
    this.cueRow();
  }

  async done(row) {
    const d = this.draft(row);
    const mentions = this.S.state.source.mentions[row] || [];
    const open = mentions.filter(m => !d.mentions[m.id]);
    if (open.length) {
      toast(`Resolve ${open.length} mention${open.length > 1 ? 's' : ''} before marking the row done.`, 'error');
      return false;
    }
    try {
      const out = await post('/api/source/row', {
        row,
        present: [...d.present],
        unnamed: [...d.unnamed],
        mentions: d.mentions,
        done: true,
      });
      this.S.setState(out.state);
      delete this.drafts[row];
      return true;
    } catch (e) {
      toast(e.message, 'error');
      return false;
    }
  }

  async doneAndNext() {
    const row = this.currentRow();
    if (await this.done(row)) this.move(1);
  }

  async addUnnamed(row) {
    const input = document.getElementById(`unnamed-${row}`);
    const text = input?.value?.trim();
    if (!text) {
      input?.focus();
      return;
    }
    try {
      const out = await post('/api/source/unnamed', { label: text, scene: this.scene });
      this.S.setState(out.state);
      this.draft(row).unnamed.add(out.uid);
      input.value = '';
      this.render();
    } catch (e) {
      toast(e.message, 'error');
    }
  }

  renderLeft() {
    const L = document.getElementById('left');
    const st = this.S.state.source.scenes;
    const total = Object.values(st).reduce((a, s) => a + s.rows, 0);
    const done = Object.values(st).reduce((a, s) => a + s.done, 0);
    clear(
      L,
      h('div.panehead', h('h2', 'Scenes'), h('span.count', `${done} of ${total} rows done`)),
      h('p.note', 'Every row of every scene must be done before the recall pass opens. The recall is not loaded in this pass.'),
      h('ol.scenes', this.scenes.map(s => {
        const c = st[s.scene];
        const pct = c.rows ? (100 * c.done) / c.rows : 0;
        return h('li', h('button.scene', {
          type: 'button',
          class: `scene${s.scene === this.scene ? ' current' : ''}${c.done === c.rows ? ' complete' : ''}`,
          onclick: () => this.goScene(s.scene),
        },
        h('span.sid', String(s.scene)),
        h('span.sinfo', `${partName(s.part)} ${clock(s.t0)}–${clock(s.t1)}`),
        h('span.meter', h('span.fill', { style: { width: `${pct}%` } })),
        h('span.sct', `${c.done}/${c.rows}`)));
      })),
    );
  }

  renderRight() {
    const R = document.getElementById('right');
    const rows = this.sceneRows();
    const unnamed = this.S.state.source.unnamed;
    const cards = rows.map((row, i) => {
      const t = this.S.film.rows[row];
      const r = this.S.index.rows.find(x => x.row === row);
      const rec = this.record(row);
      const d = this.draft(row);
      const mentions = this.S.state.source.mentions[row] || [];
      const current = i === this.rowIdx;
      const persons = [...d.present, ...d.unnamed];
      const nextOpen = mentions.find(m => !d.mentions[m.id])?.id;
      const label = p => unnamed[p]?.label ? `${unnamed[p].label} (${p})` : p;
      const lineOf = m => {
        const words = this.S.film.lines[r.part] || [];
        return words.find(l => l.t0 <= m.t0 + 0.001 && m.t0 < l.t1 + 0.001);
      };
      return h('article.rowcard', {
        class: `rowcard${current ? ' current' : ''}${rec?.done ? ' done' : ''}`,
        onclick: () => {
          if (this.rowIdx !== i) {
            this.rowIdx = i;
            this.render();
            this.cueRow();
          }
        },
      },
      h('div.rchead', h('span.rid', `Row ${row}`), h('span.rmeta', `${clock(r.t0)}–${clock(r.t1)}`), rec?.done ? h('span.state.ok', 'done') : h('span.state', 'open')),
      h('p.rdetails', t.details),
      h('div.group',
        h('span.k', 'Present'),
        t.names.length ? null : h('span.none', 'Name - All is empty'),
        t.names.map(n => h('label.toggle', h('input', {
          type: 'checkbox', checked: d.present.has(n), onchange: e => { e.target.checked ? d.present.add(n) : d.present.delete(n); this.render(); },
        }), n)),
        [...d.unnamed].map(u => h('label.toggle.unnamed', h('input', {
          type: 'checkbox', checked: true, onchange: () => { d.unnamed.delete(u); this.render(); },
        }), label(u)))),
      current ? h('div.group.add',
        h('input.text', { id: `unnamed-${row}`, type: 'text', 'aria-label': 'Describe an unnamed person', placeholder: 'Unnamed person, e.g. "man in grey coat" (N)', onkeydown: e => { e.stopPropagation(); if (e.key === 'Enter') { e.preventDefault(); this.addUnnamed(row); } if (e.key === 'Escape') { e.target.value = ''; e.target.blur(); } } }),
        h('button.small', { type: 'button', onclick: () => this.addUnnamed(row) }, 'Add'),
        Object.keys(unnamed).filter(u => !d.unnamed.has(u)).length
          ? h('select.small', { 'aria-label': 'Reuse an unnamed person', onchange: e => { if (e.target.value) { d.unnamed.add(e.target.value); this.render(); } } },
            h('option', { value: '' }, 'Same person as…'),
            Object.entries(unnamed).filter(([u]) => !d.unnamed.has(u)).map(([u, x]) => h('option', { value: u }, `${x.label} (${u}, scene ${x.scene})`)))
          : null) : null,
      mentions.length ? h('div.group.mentions',
        h('span.k', 'Mentions'),
        h('ul', mentions.map(m => {
          const line = lineOf(m);
          return h('li', h('span.mclass', m.class), h('span.mword', `“${m.text}”`), line ? h('span.mline', line.text) : null,
            h('div.mopts', { role: 'radiogroup', 'aria-label': `Who “${m.text}” means` },
              [...persons.map(p => [p, label(p)]), ['absent', 'absent from the frame'], ['unknown', 'unknown']].map(([v, lab], k) =>
                h('button.mopt', {
                  type: 'button',
                  role: 'radio',
                  'aria-checked': String(d.mentions[m.id] === v),
                  onclick: e => { e.stopPropagation(); d.mentions[m.id] = v; this.render(); },
                }, current && !d.mentions[m.id] && m.id === nextOpen ? h('kbd', { 'aria-hidden': 'true' }, String(k + 1)) : null, lab))));
        }))) : null,
      current ? h('div.actions', h('button.primary', { type: 'button', onclick: e => { e.stopPropagation(); this.doneAndNext(); } }, rec?.done ? 'Save and next (Enter)' : 'Done, next row (Enter)')) : null,
      );
    });
    const s = this.scenes.find(x => x.scene === this.scene);
    clear(R, h('div.panehead', h('h2', `Scene ${s.scene}`), h('span.count', `${partName(s.part)} · ${rows.length} rows`)), h('div.rowcards', cards));
    R.querySelector('.rowcard.current')?.scrollIntoView({ block: 'nearest' });
  }

  render() {
    this.renderLeft();
    this.renderRight();
  }

  key(e) {
    const k = e.key;
    if (k === 'j' || k === 'ArrowDown') return this.move(1), true;
    if (k === 'k' || k === 'ArrowUp') return this.move(-1), true;
    if (k === 'Enter') return this.doneAndNext(), true;
    if (k === 'n') {
      document.getElementById(`unnamed-${this.currentRow()}`)?.focus();
      return true;
    }
    const digit = /^Digit[1-9]$/.test(e.code) ? Number(e.code.slice(5)) : null;
    if (digit) {
      const row = this.currentRow();
      const d = this.draft(row);
      const open = (this.S.state.source.mentions[row] || []).find(m => !d.mentions[m.id]);
      if (e.shiftKey) {
        // Shift+1-9 toggle presence; a bare digit never does, so a repeated keypress cannot
        // silently remove someone.
        const n = this.S.film.rows[row].names[digit - 1];
        if (n) d.present.has(n) ? d.present.delete(n) : d.present.add(n);
      } else if (open) {
        // 1-9 resolve the next open mention to its nth option, in the order shown.
        const opts = [...d.present, ...d.unnamed, 'absent', 'unknown'];
        if (opts[digit - 1]) d.mentions[open.id] = opts[digit - 1];
      } else {
        toast('No open mention in this row. Shift+digit toggles who is present.');
        return true;
      }
      this.render();
      return true;
    }
    return false;
  }
}
