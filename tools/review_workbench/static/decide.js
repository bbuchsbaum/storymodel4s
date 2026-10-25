// Right pane of the recall pass: candidate moments (or search results) on top, the two-step
// decision form below with a fixed commit footer. Candidates point to film moments and never to
// persons; the person picker lists annotation names alphabetically, people the reviewer created,
// and fixed options, with nothing preselected (plan constraint 2).
//
// Text inputs (search, person filter, claim role, ambiguity) are created once and kept, so a
// re-render never moves the caret or drops what the reviewer is typing.
import { h, clear, clock, partName, toast } from './dom.js';
import { post } from './api.js';

const SIGNAL = {
  nomination: 'timing',
  'nomination-alt': 'timing (other clock)',
  name: 'name',
  lexical: 'words',
  echo: 'dialogue',
};
const FORMS = [['name', 'n'], ['pronoun', 'p'], ['description', 'd'], ['ellipsis', 'e'], ['other', 'o']];
const PERSONREF = [['yes', 'y'], ['no', 'x'], ['unknown', 'u']];
const RESOLVABLE = [['yes', 'y'], ['no', 'x'], ['unknown', 'u']];
const OUTCOME = [['supported', 's'], ['contradicted', 'c'], ['unresolved', 'v'], ['external', 'e']];
const FIXED = [['absent', 'absent / off-screen'], ['none', 'no one in the film'], ['unknown', 'Unknown']];
const ROLES = ['agent', 'patient', 'experiencer', 'recipient', 'possessor', 'theme', 'speaker', 'addressee'];
const LABELS = {
  reference_form: 'Reference form',
  person_reference: 'Is this a person reference?',
  source_resolvable: 'Can the source resolve it?',
  reference_outcome: 'Outcome',
};

export class DecidePane {
  constructor(S) {
    this.S = S;
    this.tab = 'candidates';
    this.cands = null;
    this.hits = [];
    this.pick = null;
    this.hi = 0; // highlighted person in the filtered list
    this.mounted = false;
  }

  // -- persistent inputs -----------------------------------------------------------------
  mount() {
    const R = document.getElementById('right');
    this.tabsEl = h('div.tabs', { role: 'tablist', 'aria-label': 'Suggestions' });
    this.bodyEl = h('div.tabbody');
    this.formEl = h('div.formwrap');
    this.q = h('input.text#q', {
      type: 'search',
      'aria-label': 'Search annotation and dialogue',
      placeholder: 'Search annotation and dialogue, then Enter',
      onkeydown: e => {
        e.stopPropagation();
        if (e.key === 'Enter') { e.preventDefault(); this.search(this.q.value); }
        if (e.key === 'Escape') { this.q.blur(); }
      },
    });
    this.filter = h('input.text.filter', {
      type: 'text',
      'aria-label': 'Filter people',
      placeholder: 'Type to filter (F), ↑↓, Enter',
      oninput: () => { this.hi = 0; this.renderPeople(); },
      onkeydown: e => this.filterKey(e),
    });
    this.role = h('input.text', {
      list: 'roles',
      'aria-label': 'Claim role',
      onkeydown: e => { e.stopPropagation(); if (e.key === 'Escape' || e.key === 'Enter') { e.preventDefault(); this.role.blur(); } },
      oninput: () => this.S.setField('claim_role', this.role.value, false),
    });
    this.ambig = h('input.text', {
      'aria-label': 'Ambiguity reason',
      placeholder: 'disagreement or missing evidence, if any',
      onkeydown: e => { e.stopPropagation(); if (e.key === 'Escape' || e.key === 'Enter') { e.preventDefault(); this.ambig.blur(); } },
      oninput: () => this.S.setSource('ambiguity_reason', this.ambig.value, false),
    });
    this.plist = h('div.plist', { role: 'listbox', 'aria-label': 'People' });
    // An inert box takes whatever is typed after a blind "/", so no keystroke runs a command.
    // Created once, like the real search box, so a re-render never drops its focus.
    this.sink = h('input.text.sink', { type: 'text', readonly: true, 'aria-label': 'Search is hidden here', placeholder: 'Search is hidden in a calibration window (Esc)', onkeydown: e => { e.stopPropagation(); if (e.key === 'Escape') { this.sink.blur(); this.setTab('candidates'); } } });
    clear(R, h('div.suggest', this.tabsEl, this.bodyEl), this.formEl);
    this.mounted = true;
  }

  // -- candidates ------------------------------------------------------------------------
  searchBlind() {
    const a = this.S.active;
    if (a && a.calibration && !this.S.revealed(a.id)) return true;
    return (this.S.state.maskedWindows || []).includes(this.S.win) || this.blind();
  }

  blind() {
    // Mirrors the server's rule; the server still decides what it sends.
    const a = this.S.active;
    if (a && a.calibration && !this.S.revealed(a.id)) return true;
    const sel = this.S.sel;
    if (!sel) return false;
    const m = this.S.masked();
    for (let r = Math.min(sel.a, sel.b); r <= Math.max(sel.a, sel.b); r++) if (m.has(r)) return true;
    return false;
  }

  async load(first, last) {
    const a = this.S.active;
    try {
      this.cands = await post('/api/candidates', { first, last, id: a?.id ?? null, hidden: this.blind() });
    } catch (e) {
      toast(e.message, 'error');
      this.cands = null;
    }
    this.pick = null;
    const hidden = !this.cands || this.cands.hidden;
    this.S.filmPane.setPins(hidden ? [] : this.cands.moments, null);
    this.S.filmPane.markScenes(hidden ? [] : this.cands.nominations.one);
    this.renderBody();
  }

  choose(i) {
    const m = this.cands?.moments?.[i];
    if (!m || this.cands.hidden) return;
    this.pick = i;
    this.S.filmPane.cue(m);
    this.S.filmPane.showMoment(m.row);
    this.S.filmPane.setPins(this.cands.moments, m.row);
    this.renderBody();
    if (this.S.active?.step === 2) this.renderForm();
  }

  async search(q) {
    if (!q.trim()) {
      this.hits = [];
      return this.renderBody();
    }
    try {
      const sel = this.S.sel;
      const span = sel ? [Math.min(sel.a, sel.b), Math.max(sel.a, sel.b)] : null;
      this.hits = (await post('/api/search', { q, id: this.S.active?.id ?? null, span, window: this.S.win })).hits;
      this.q.blur(); // so digits open hits
    } catch (e) {
      toast(e.message, 'error');
    }
    this.renderBody();
  }

  hiddenBox() {
    const a = this.S.active;
    const w = this.S.calWindowOf(a);
    let progress = null;
    let n = 0, k = 0;
    if (w) {
      const win = this.S.index.windows[w - 1];
      for (let r = win.first; r <= win.last; r++) {
        if (!this.S.index.words[r - 1].screen) continue;
        n++;
        if (this.S.recallPane.wordEls[r].dataset.did) k++;
      }
      progress = `${k} of ${n} likely references in window ${w} decided`;
    }
    return h('div.hiddenbox',
      h('strong', 'Calibration item'),
      h('p', a
        ? 'Timing, candidates and search stay hidden in this calibration window. Find the moment yourself: < > step rows, { } step scenes. Commit every reference in the window blind, then reveal the window (Shift+R) and revise any you would change.'
        : this.S.sel
          ? 'These words are in a calibration window, so timing, candidates and search stay hidden. Press Enter to decide them blind.'
          : 'This is a calibration window: timing, candidates and search stay hidden. Select a reference (W) and press Enter to decide it blind.'),
      progress ? h('p.calprogress', progress) : null,
      w && (!a || this.S.decision(a.id))
        ? (k < n
          // An early reveal is a deliberate, outlined choice; it turns solid once all are decided.
          ? h('button.revealearly', { type: 'button', onclick: () => this.S.reveal() }, this.S.revealArmedFor(w) ? `Press again: reveal with ${n - k} undecided` : `Reveal early (${n - k} undecided)`, h('kbd', { 'aria-hidden': 'true' }, 'Shift+R'))
          : h('button.primary', { type: 'button', onclick: () => this.S.reveal() }, `Reveal window ${w}`, h('kbd', { 'aria-hidden': 'true' }, 'Shift+R')))
        : null,
      this.tab === 'search' ? this.sink : null);
  }

  openHit(i) {
    if (this.searchBlind()) return;
    const x = this.hits[i];
    if (!x) return;
    this.S.filmPane.cue({ part: x.part, t0: x.t0, t1: x.t1 });
    this.S.filmPane.showMoment(x.row);
    if (this.S.active?.step === 2) this.renderForm();
  }

  renderCandidates() {
    if (this.blind()) return this.hiddenBox();
    if (this.cands?.hidden) return this.hiddenBox();
    const c = this.cands;
    if (!c) return h('p.empty', 'Select recall words to see candidate moments.');
    const noms = c.nominations;
    const fmt = list => (list.length ? `scene ${list.join(', ')}` : 'no scene');
    const same = fmt(noms.one) === fmt(noms.zero) && fmt(noms.one) === fmt(noms.legacy);
    return [
      h('div.convs', { title: 'Where the recall timing of these words points, under the three clock conventions (one-based TR, zero-based TR, closed seconds)' },
        h('span.k', 'Timing'),
        same
          ? h('span.conv', `${fmt(noms.one)} (all three clocks agree)`)
          : [h('span.conv', `${fmt(noms.one)} (TR)`), h('span.conv.alt', `${fmt(noms.zero)} (TR−1)`), h('span.conv.alt', `${fmt(noms.legacy)} (seconds)`)]),
      c.moments.length
        ? h('ol.cands', c.moments.map((m, i) => {
          const sigs = dedupe(m.signals);
          const lead = sigs.find(s => s.kind === 'echo') || sigs.find(s => s.kind === 'lexical') || sigs[0];
          return h('li', h('button.cand', {
            type: 'button',
            class: `cand${this.pick === i ? ' picked' : ''}`,
            'aria-label': `Candidate ${i + 1}: ${partName(m.part)} ${clock(m.cue ?? m.t0)}, row ${m.row}`,
            onclick: () => this.choose(i),
          },
          h('span.cnum', String(i + 1)),
          h('span.cbody',
            h('span.cline',
              h('span.cwhere', `${partName(m.part)} ${clock(m.cue ?? m.t0)}`),
              h('span.crow', `row ${m.row} · sc ${m.scenes.join('/')}`),
              h('span.csig', sigs.map(s => h('span.sig', { class: `sig ${s.kind}` }, SIGNAL[s.kind])))),
            h('span.creason', lead.reason))));
        }))
        : h('p.empty', 'No signal points anywhere for these words. Try search (/).'),
    ];
  }

  renderSearch() {
    if (this.searchBlind()) return this.hiddenBox();
    return [
      this.q,
      this.hits.length
        ? h('ol.cands', this.hits.slice(0, 8).map((x, i) => h('li', h('button.cand', {
          type: 'button',
          onclick: () => this.openHit(i),
        }, h('span.cnum', String(i + 1)), h('span.cbody',
          h('span.cline', h('span.cwhere', `${partName(x.part)} ${clock(x.t0)}`), h('span.crow', `row ${x.row} · sc ${x.scenes.join('/')}`)),
          h('span.creason', x.kind === 'row' ? this.S.film.rows[x.row].details : `“${(this.S.film.lines[x.part] || []).find(l => Math.abs(l.t0 - x.t0) < 0.01)?.text ?? ''}”`))))))
        : h('p.empty', this.q.value ? 'Nothing matches.' : 'Search names, places, objects or a line of dialogue.'),
    ];
  }

  // -- the form --------------------------------------------------------------------------
  seg(key, opts, val) {
    // One tab stop per group (the checked option, else the first); arrows move and choose.
    const set = v => (this.S.active.step === 2 && key in this.S.active.source ? this.S.setSource(key, v) : this.S.setField(key, v));
    const stop = opts.some(([v]) => v === val) ? val : opts[0][0];
    return h('div.seg', { role: 'radiogroup', 'aria-label': LABELS[key] || key },
      opts.map(([v, k], i) => h('button.segbtn', {
        type: 'button',
        role: 'radio',
        tabindex: v === stop ? '0' : '-1',
        dataset: { fid: `${key}:${v}` },
        'aria-checked': String(val === v),
        onclick: () => set(v),
        onkeydown: e => {
          const d = { ArrowRight: 1, ArrowDown: 1, ArrowLeft: -1, ArrowUp: -1 }[e.key];
          if (!d) return;
          e.preventDefault();
          e.stopPropagation();
          const next = opts[(i + d + opts.length) % opts.length][0];
          set(next);
          this.formEl.querySelector(`[data-fid="${key}:${next}"]`)?.focus();
        },
      }, v, h('kbd', { 'aria-hidden': 'true' }, k))));
  }

  people() {
    const q = this.filter.value.trim().toLowerCase();
    // Rank: name starts with the query, then a word in it does, then it merely contains it.
    const rank = l => (l.startsWith(q) ? 0 : l.split(/\s+/).some(w => w.startsWith(q)) ? 1 : 2);
    const listed = this.S.state.people
      .filter(p => !q || p.label.toLowerCase().includes(q))
      .map((p, i) => ({ p, i, r: q ? rank(p.label.toLowerCase()) : 0 }))
      .sort((a, b) => a.r - b.r || a.i - b.i)
      .map(x => x.p);
    const fixed = FIXED.filter(([, label]) => !q || label.toLowerCase().includes(q)).map(([id, label]) => ({ id, label, kind: 'fixed' }));
    return [...listed.map(p => ({ ...p, label: p.kind === 'created' ? `${p.label} (${p.id})` : p.label })), ...fixed];
  }

  renderPeople() {
    const value = this.S.active?.fields.referent ?? null;
    const list = this.people();
    this.hi = Math.min(this.hi, Math.max(0, list.length - 1));
    const q = this.filter.value.trim();
    const filtering = document.activeElement === this.filter && q;
    clear(this.plist,
      list.map((p, i) => h('button.person', {
        type: 'button',
        role: 'option',
        class: `person ${p.kind}${value === p.id ? ' on' : ''}${filtering && i === this.hi ? ' hi' : ''}`,
        'aria-selected': String(value === p.id),
        dataset: { fid: `person:${p.id}` },
        onclick: () => this.choosePerson(p.id),
      }, p.label)),
      q && !list.length ? h('span.hint', `No match. Shift+Enter adds “${q}” as a new person.`) : null,
      q && list.length ? h('button.person.new', { type: 'button', onclick: () => this.createFromFilter() }, `+ new “${q}”`) : null);
  }

  choosePerson(id) {
    this.S.setField('referent', this.S.active.fields.referent === id ? null : id, false);
    this.filter.value = '';
    this.filter.blur();
    this.renderForm();
  }

  async createFromFilter() {
    const q = this.filter.value.trim();
    if (!q) return;
    await this.S.createPerson(q);
    this.filter.value = '';
    this.filter.blur();
    this.renderForm();
  }

  filterKey(e) {
    e.stopPropagation();
    const list = this.people();
    if (e.key === 'ArrowDown') { e.preventDefault(); this.hi = Math.min(list.length - 1, this.hi + 1); this.renderPeople(); }
    else if (e.key === 'ArrowUp') { e.preventDefault(); this.hi = Math.max(0, this.hi - 1); this.renderPeople(); }
    else if (e.key === 'Enter') {
      e.preventDefault();
      if (e.shiftKey) return this.createFromFilter();
      if (!this.filter.value.trim()) return toast('Type part of a name first.');
      if (list[this.hi]) this.choosePerson(list[this.hi].id);
    } else if (e.key === 'Escape') {
      this.filter.value = '';
      this.filter.blur();
      this.renderPeople();
    }
  }

  focusFilter() {
    if (this.S.active?.step !== 1) return;
    this.filter.focus();
    this.renderPeople();
  }

  problems() {
    const a = this.S.active;
    if (!a) return [];
    if (a.step === 1) {
      const f = a.fields;
      const out = [];
      if (!f.reference_form) out.push('form');
      if (f.person_reference === 'yes' && !f.referent) out.push('person (F)');
      return out;
    }
    const s = a.source;
    const out = [];
    if (!s.source_resolvable) out.push('resolvable');
    if (s.source_resolvable === 'yes' && !s.source_anchor_ids.length && !['supported', 'contradicted'].includes(s.reference_outcome)) out.push('an evidence row (A)');
    if (!s.reference_outcome) out.push('outcome');
    if (['supported', 'contradicted'].includes(s.reference_outcome) && !s.source_anchor_ids.length) out.push('an evidence row (A)');
    const anchored = new Set(s.source_anchor_ids.filter(x => x.startsWith('row-')).flatMap(x => this.S.index.rows.find(r => r.row === Number(x.slice(4)))?.scenes || []));
    if (s.source_scene_ids.some(id => !anchored.has(id)) && !s.source_anchor_ids.some(x => !x.startsWith('row-'))) out.push('an anchor for each scene');
    return out;
  }

  renderForm() {
    const fid = document.activeElement?.dataset?.fid;
    const a = this.S.active;
    if (!a) {
      const sel = this.S.sel;
      clear(this.formEl, h('div.formidle',
        h('p', sel ? 'Enter starts a decision for the selected words.' : 'Select a referring expression: click a word, or press W and B to jump between likely ones.'),
        sel ? h('button.primary', { type: 'button', onclick: () => this.S.startDecision() }, 'Start decision', h('kbd', { 'aria-hidden': 'true' }, 'Enter')) : null));
      return;
    }
    const f = a.fields;
    const committed = this.S.decision(a.id);
    const text = r => this.S.recallText.slice(r[0] - 1, r[1]).join(' ');
    const personLabel = id => {
      const p = this.S.state.people.find(x => x.id === id);
      return p ? p.label : (FIXED.find(x => x[0] === id)?.[1] ?? '—');
    };
    let body;
    if (a.step === 1) {
      if (this.role.value !== (f.claim_role || '')) this.role.value = f.claim_role || '';
      this.renderPeople();
      body = h('fieldset.step',
        h('legend', h('span.stepn', '1'), 'Reference'),
        h('div.field', h('span.k', 'Span'), h('span.spantext', `“${text([f.first_word, f.last_word])}”`), h('span.ids', `w${f.first_word}–${f.last_word}`)),
        h('div.field', h('span.k', 'Also'),
          (f.other_coref_spans || []).length
            ? f.other_coref_spans.map((s, i) => h('span.corefchip', `“${text(s)}”`, h('button.x', { type: 'button', 'aria-label': 'Remove span', onclick: () => this.S.removeCoref(i) }, '×')))
            : h('span.none', 'no other spans'),
          h('button.small', { type: 'button', title: 'Select another stretch of words, then press C', onclick: () => this.S.addCoref() }, '+ selection', h('kbd', { 'aria-hidden': 'true' }, 'C'))),
        h('div.field', h('span.k', 'Form'), this.seg('reference_form', FORMS, f.reference_form)),
        h('div.field', h('span.k', 'Person?'), this.seg('person_reference', PERSONREF, f.person_reference)),
        h('div.field.col', h('span.k', 'Refers to', f.referent ? h('span.chosen', personLabel(f.referent)) : null), h('div.picker', this.filter, this.plist)),
        h('div.field.two',
          h('label', h('span.k', 'Claim role'), this.role, h('datalist#roles', [...new Set([...ROLES, ...this.S.usedRoles()])].map(r => h('option', { value: r })))),
          h('label', h('span.k', 'Occurrence'), h('select.text', { 'aria-label': 'Occurrence group', onchange: e => this.S.setField('occurrence_group', e.target.value, false) },
            this.S.groups().map(g => h('option', { value: g, selected: f.occurrence_group === g }, g)),
            h('option', { value: this.S.newGroup(), selected: !this.S.groups().includes(f.occurrence_group) }, `${this.S.newGroup()} (new)`)))),
        this.S.dupes.length ? h('p.warn', `Possible duplicate of ${this.S.dupes.join(', ')}: same person, overlapping words.`) : null);
    } else {
      const s = a.source;
      if (this.ambig.value !== (s.ambiguity_reason || '')) this.ambig.value = s.ambiguity_reason || '';
      const row = this.S.filmPane.focusRow;
      body = [
        h('div.summary', { role: 'group', 'aria-label': 'Step 1 summary' },
          h('span.stepn.done', '1'),
          h('span.sumtext', `“${text([f.first_word, f.last_word])}” → `, h('b', personLabel(f.referent)), ` · ${f.reference_form} · ${f.claim_role || 'no role'} · ${f.occurrence_group}`),
          h('button.ghost.small', { type: 'button', onclick: () => this.S.editStep1() }, 'Edit', h('kbd', { 'aria-hidden': 'true' }, 'Shift+E'))),
        h('fieldset.step',
          h('legend', h('span.stepn', '2'), 'Source evidence'),
          h('div.field', h('span.k', 'Evidence'),
            s.source_anchor_ids.length ? s.source_anchor_ids.map(id => h('span.corefchip', id, h('button.x', { type: 'button', 'aria-label': `Remove ${id}`, onclick: () => this.S.removeAnchor(id) }, '×'))) : h('span.none', 'none yet'),
            row ? h('button.small', { type: 'button', onclick: () => this.S.addAnchorFromMoment() }, `+ row ${row}`, h('kbd', { 'aria-hidden': 'true' }, 'A')) : h('span.hint', 'open a moment, then A')),
          h('div.field', h('span.k', 'Scenes'),
            s.source_scene_ids.length ? s.source_scene_ids.map(id => h('span.corefchip', `scene ${id}`, h('button.x', { type: 'button', 'aria-label': `Remove scene ${id}`, onclick: () => this.S.toggleSourceScene(id) }, '×'))) : h('span.none', 'added with each row')),
          h('div.field', h('span.k', 'Resolvable'), this.seg('source_resolvable', RESOLVABLE, s.source_resolvable)),
          h('div.field', h('span.k', 'Outcome'), this.seg('reference_outcome', OUTCOME, s.reference_outcome)),
          h('label.field.col', h('span.k', 'Ambiguity'), this.ambig)),
      ];
    }
    const missing = this.problems();
    const footer = h('div.footer',
      h('div.status', { 'aria-live': 'polite' }, missing.length ? h('span.need', `Needs ${missing.join(' · ')}`) : h('span.ready', a.step === 1 ? 'Step 1 complete' : 'Ready to commit')),
      h('span.grow'),
      h('button.ghost', { type: 'button', onclick: () => this.S.closeDecision() }, 'Close', h('kbd', { 'aria-hidden': 'true' }, 'Esc')),
      a.step === 1
        ? h('button.primary', { type: 'button', disabled: missing.length > 0, onclick: () => this.S.commitReference() }, committed ? 'Save step 1' : 'Commit step 1', h('kbd', { 'aria-hidden': 'true' }, 'Enter'))
        : h('button.primary', { type: 'button', disabled: missing.length > 0, onclick: () => this.S.commitSource() }, 'Commit decision', h('kbd', { 'aria-hidden': 'true' }, 'Enter')));
    clear(this.formEl, h('form.decision', { onsubmit: e => e.preventDefault(), 'aria-label': `Decision ${a.id}` },
      h('div.dhead', h('span.did', a.id), a.calibration ? h('span.calib', this.S.revealed(a.id) ? 'calibration · revealed' : 'calibration') : null, h('span.timer#timer')),
      h('div.dbody', body), footer));
    if (fid) this.formEl.querySelector(`[data-fid="${fid}"]`)?.focus();
  }

  renderTabs() {
    clear(this.tabsEl, ['candidates', 'search'].map(t => h('button.tab', {
      type: 'button', role: 'tab', 'aria-selected': String(this.tab === t),
      onclick: () => this.setTab(t),
    }, t === 'candidates' ? 'Candidates' : 'Search', h('kbd', { 'aria-hidden': 'true' }, t === 'candidates' ? '1–8' : '/'))));
  }

  setTab(t) {
    this.tab = t;
    this.renderTabs();
    this.renderBody();
    if (t === 'search' && !this.blind()) this.q.focus();
  }

  renderBody() {
    const keep = document.activeElement === this.q;
    clear(this.bodyEl, this.tab === 'candidates' ? this.renderCandidates() : this.renderSearch());
    if (keep && this.q.isConnected) this.q.focus();
  }

  render() {
    if (!this.mounted) this.mount();
    this.renderTabs();
    this.renderBody();
    this.renderForm();
  }
}

function dedupe(signals) {
  const seen = new Map();
  for (const s of signals) {
    if (!seen.has(s.kind)) seen.set(s.kind, { ...s, n: 1 });
    else seen.get(s.kind).n++;
  }
  return [...seen.values()];
}
