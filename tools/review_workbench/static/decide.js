// Right pane of the recall pass: candidate moments (or search results) on top, the two-step
// decision form below. Candidates point to film moments and never to persons; the person
// picker lists annotation names alphabetically, people the reviewer created, and fixed options,
// with nothing preselected (plan constraint 2).
import { h, clear, clock, partName, toast } from './dom.js';
import { post } from './api.js';

const SIGNAL = {
  nomination: 'timing',
  'nomination-alt': 'timing (alt.)',
  name: 'name',
  lexical: 'words',
  echo: 'dialogue',
};
const FORMS = [['name', 'n'], ['pronoun', 'p'], ['description', 'd'], ['ellipsis', 'e'], ['other', 'o']];
const PERSONREF = [['yes', 'y'], ['no', 'x'], ['unknown', 'u']];
const RESOLVABLE = [['yes', 'y'], ['no', 'x'], ['unknown', 'u']];
const OUTCOME = [['supported', 's'], ['contradicted', 'c'], ['unresolved', 'v'], ['external', 'e']];
const FIXED = [['absent', 'absent / off-screen person'], ['none', 'no one in the film'], ['unknown', 'Unknown']];
const ROLES = ['agent', 'patient', 'experiencer', 'recipient', 'possessor', 'theme', 'speaker', 'addressee'];

export class DecidePane {
  constructor(S) {
    this.S = S;
    this.tab = 'candidates';
    this.cands = null;
    this.hits = [];
    this.query = '';
    this.pick = null; // index of the chosen candidate
    this.filter = '';
  }

  // -- candidates ------------------------------------------------------------------------
  async load(first, last) {
    const a = this.S.active;
    const hidden = !!(a && a.calibration && !this.S.revealed(a.id));
    try {
      this.cands = await post('/api/candidates', { first, last, id: a?.id ?? null, hidden });
      this.cands.hidden = hidden;
    } catch (e) {
      toast(e.message, 'error');
      this.cands = null;
    }
    this.pick = null;
    const moments = this.cands?.moments || [];
    this.S.filmPane.setPins(moments, null);
    this.S.filmPane.markScenes(hidden ? [] : this.cands?.nominations?.one || []);
    this.render();
  }

  choose(i) {
    const m = this.cands?.moments?.[i];
    if (!m) return;
    this.pick = i;
    this.S.filmPane.cue(m);
    this.S.filmPane.showMoment(m.row);
    this.S.filmPane.setPins(this.cands.moments, m.row);
    this.render();
  }

  async search(q) {
    this.query = q;
    if (!q.trim()) {
      this.hits = [];
      return this.render();
    }
    try {
      this.hits = (await post('/api/search', { q })).hits;
    } catch (e) {
      toast(e.message, 'error');
    }
    this.render();
    document.getElementById('q')?.focus();
  }

  suggestionsHidden() {
    const a = this.S.active;
    return !!(a && a.calibration && !this.S.revealed(a.id));
  }

  renderCandidates() {
    if (this.suggestionsHidden()) {
      return h('div.hiddenbox',
        h('strong', 'Calibration item'),
        h('p', 'Suggestions and search are hidden. Find the moment yourself, then commit step 1. You can reveal the suggestions afterwards and revise.'),
        this.S.decision(this.S.active.id)
          ? h('button.primary', { type: 'button', onclick: () => this.S.reveal() }, 'Reveal suggestions')
          : null);
    }
    const c = this.cands;
    if (!c) return h('p.empty', 'Select recall words to see candidate moments.');
    const noms = c.nominations;
    const conv = (label, list) => h('span.conv', h('span.k', label), list.length ? list.join(', ') : '–');
    return [
      h('div.convs', { title: 'Scene nominations for the selected words under the three timing conventions' },
        conv('one-based', noms.one), conv('zero-based', noms.zero), conv('closed-sec.', noms.legacy)),
      c.moments.length
        ? h('ol.cands', c.moments.map((m, i) => h('li', h('button.cand', {
          type: 'button',
          class: `cand${this.pick === i ? ' picked' : ''}`,
          onclick: () => this.choose(i),
        },
        h('span.cnum', String(i + 1)),
        h('span.cbody',
          h('span.cwhere', `${partName(m.part)} ${clock(m.cue ?? m.t0)} · row ${m.row} · scene ${m.scenes.join('/')}`),
          h('span.csig', dedupe(m.signals).map(s => h('span.sig', { class: `sig ${s.kind}`, title: s.reason }, SIGNAL[s.kind])))),
        h('span.cscore', { title: 'Combined signal weight (aids only)' }, m.score.toFixed(1))))))
        : h('p.empty', 'No signal points anywhere for these words. Try search.'),
    ];
  }

  renderSearch() {
    if (this.suggestionsHidden()) return this.renderCandidates();
    return [
      h('input.text#q', {
        type: 'search',
        placeholder: 'Search annotation and dialogue',
        value: this.query,
        onkeydown: e => {
          if (e.key === 'Enter') { e.preventDefault(); this.search(e.target.value); }
          if (e.key === 'Escape') { e.target.blur(); }
          e.stopPropagation();
        },
      }),
      this.hits.length
        ? h('ol.cands', this.hits.map(x => h('li', h('button.cand', {
          type: 'button',
          onclick: () => {
            this.S.filmPane.cue({ part: x.part, t0: x.t0, t1: x.t1 });
            this.S.filmPane.showMoment(x.row);
          },
        }, h('span.cnum', x.kind === 'row' ? 'A' : '“'), h('span.cbody', h('span.cwhere', `${partName(x.part)} ${clock(x.t0)} · row ${x.row} · scene ${x.scenes.join('/')}`), h('span.csub', this.S.film.rows[x.row].details)), h('span.cscore', x.score.toFixed(1))))))
        : h('p.empty', this.query ? 'Nothing matches.' : 'Search names, places, objects or a line of dialogue. Press Enter.'),
    ];
  }

  // -- the form --------------------------------------------------------------------------
  renderForm() {
    const a = this.S.active;
    if (!a) {
      return h('div.formidle',
        h('p', this.S.sel ? 'Press Enter to start a decision for the selected words.' : 'Select a referring expression in the recall to start a decision.'),
        this.S.sel ? h('button.primary', { type: 'button', onclick: () => this.S.startDecision() }, 'Start decision (Enter)') : null);
    }
    const f = a.fields;
    const committed = this.S.decision(a.id);
    const text = r => this.S.recallText.slice(r[0] - 1, r[1]).join(' ');
    const seg = (key, opts, val, disabled) => h('div.seg', { role: 'radiogroup', 'aria-label': key },
      opts.map(([v, k]) => h('button.segbtn', {
        type: 'button',
        role: 'radio',
        'aria-checked': String(val === v),
        disabled,
        onclick: () => this.S.setField(key, v),
      }, v, h('kbd', k))));
    const step1 = h('fieldset.step', { disabled: a.step === 2 },
      h('legend', h('span.stepn', '1'), 'Reference'),
      h('div.field', h('span.k', 'Span'),
        h('span.spantext', `“${text([f.first_word, f.last_word])}”`),
        h('span.ids', `w${f.first_word}–w${f.last_word}`)),
      h('div.field', h('span.k', 'Co-referring'),
        (f.other_coref_spans || []).length
          ? f.other_coref_spans.map((s, i) => h('span.corefchip', `“${text(s)}”`, h('button.x', { type: 'button', title: 'Remove', onclick: () => this.S.removeCoref(i) }, '×')))
          : h('span.none', 'none'),
        h('button.small', { type: 'button', title: 'Add the current selection as another span for this referent (C)', onclick: () => this.S.addCoref() }, '+ selection (C)')),
      h('div.field', h('span.k', 'Form'), seg('reference_form', FORMS, f.reference_form)),
      h('div.field', h('span.k', 'Person?'), seg('person_reference', PERSONREF, f.person_reference)),
      h('div.field', h('span.k', 'Refers to'), this.renderPicker(f.referent)),
      h('div.field.two',
        h('label', h('span.k', 'Claim role'), h('input.text', { list: 'roles', value: f.claim_role || '', onkeydown: e => e.stopPropagation(), oninput: e => this.S.setField('claim_role', e.target.value, false) }),
          h('datalist#roles', [...new Set([...ROLES, ...this.S.usedRoles()])].map(r => h('option', { value: r })))),
        h('label', h('span.k', 'Occurrence'), h('select.text', { onchange: e => this.S.setField('occurrence_group', e.target.value, false) },
          this.S.groups().map(g => h('option', { value: g, selected: f.occurrence_group === g }, g)),
          h('option', { value: this.S.newGroup(), selected: !this.S.groups().includes(f.occurrence_group) }, `${this.S.newGroup()} (new)`)))),
      this.S.dupes.length ? h('p.warn', `Possible duplicate of ${this.S.dupes.join(', ')}: same referent, overlapping span.`) : null,
      a.step === 1 ? h('div.actions',
        h('button.primary', { type: 'button', onclick: () => this.S.commitReference() }, committed ? 'Save step 1 (Enter)' : 'Commit step 1 (Enter)'),
        h('button.ghost', { type: 'button', onclick: () => this.S.closeDecision() }, 'Close (Esc)')) : null,
    );
    const s = a.source;
    const step2 = committed && a.step === 2 ? h('fieldset.step',
      h('legend', h('span.stepn', '2'), 'Source evidence'),
      h('div.field', h('span.k', 'Scenes'),
        s.source_scene_ids.length ? s.source_scene_ids.map(id => h('span.corefchip', `scene ${id}`, h('button.x', { type: 'button', onclick: () => this.S.toggleSourceScene(id) }, '×'))) : h('span.none', 'none'),
        this.S.filmPane.focusRow ? h('button.small', { type: 'button', onclick: () => this.S.addAnchorFromMoment() }, `+ row ${this.S.filmPane.focusRow} (A)`) : h('span.hint', 'open a moment to add it')),
      h('div.field', h('span.k', 'Anchors'),
        s.source_anchor_ids.length ? s.source_anchor_ids.map(id => h('span.corefchip', id, h('button.x', { type: 'button', onclick: () => this.S.removeAnchor(id) }, '×'))) : h('span.none', 'none')),
      h('div.field', h('span.k', 'Resolvable'), seg('source_resolvable', RESOLVABLE, s.source_resolvable)),
      h('div.field', h('span.k', 'Outcome'), seg('reference_outcome', OUTCOME, s.reference_outcome)),
      h('div.field', h('label.block', h('span.k', 'Ambiguity'), h('input.text', { value: s.ambiguity_reason || '', placeholder: 'disagreement or missing evidence, if any', onkeydown: e => e.stopPropagation(), oninput: e => this.S.setSource('ambiguity_reason', e.target.value, false) }))),
      h('div.actions',
        h('button.primary', { type: 'button', onclick: () => this.S.commitSource() }, 'Commit step 2 (Enter)'),
        h('button.ghost', { type: 'button', onclick: () => this.S.editStep1() }, 'Back to step 1'))) : null;
    return h('form.decision', { onsubmit: e => e.preventDefault() },
      h('div.dhead', h('span.did', a.id), a.calibration ? h('span.calib', 'calibration') : null, h('span.timer#timer')),
      step1, step2);
  }

  renderPicker(value) {
    const people = this.S.state.people;
    const q = this.filter.trim().toLowerCase();
    const match = p => !q || p.label.toLowerCase().includes(q);
    const opt = (id, label, kind) => h('button.person', {
      type: 'button',
      class: `person ${kind}${value === id ? ' on' : ''}`,
      'aria-pressed': String(value === id),
      onclick: () => this.S.setField('referent', value === id ? null : id),
    }, label);
    const listed = people.filter(match);
    return h('div.picker',
      h('input.text.filter', {
        type: 'text',
        placeholder: 'Type to filter, Enter to choose',
        value: this.filter,
        oninput: e => { this.filter = e.target.value; this.render(); document.querySelector('.picker .filter')?.focus(); },
        onkeydown: e => {
          e.stopPropagation();
          if (e.key === 'Enter') {
            e.preventDefault();
            const first = people.filter(match)[0];
            if (first) { this.S.setField('referent', first.id); this.filter = ''; this.render(); }
            else if (this.filter.trim()) this.S.createPerson(this.filter.trim()).then(() => { this.filter = ''; this.render(); });
          }
          if (e.key === 'Escape') { this.filter = ''; e.target.blur(); this.render(); }
        },
      }),
      h('div.plist',
        listed.map(p => opt(p.id, p.kind === 'created' ? `${p.label} (${p.id})` : p.label, p.kind)),
        q && !listed.length ? h('button.person.new', { type: 'button', onclick: () => this.S.createPerson(this.filter.trim()).then(() => { this.filter = ''; this.render(); }) }, `+ new person “${this.filter.trim()}”`) : null,
        FIXED.map(([id, label]) => opt(id, label, 'fixed'))));
  }

  render() {
    const R = document.getElementById('right');
    const tabs = h('div.tabs', { role: 'tablist' },
      ['candidates', 'search'].map(t => h('button.tab', {
        type: 'button', role: 'tab', 'aria-selected': String(this.tab === t),
        onclick: () => { this.tab = t; this.render(); if (t === 'search') document.getElementById('q')?.focus(); },
      }, t === 'candidates' ? 'Candidates' : 'Search (/)')));
    clear(R,
      h('div.suggest', tabs, h('div.tabbody', this.tab === 'candidates' ? this.renderCandidates() : this.renderSearch())),
      h('div.formwrap', this.renderForm()));
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
