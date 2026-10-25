// Workbench controller: loads the packet index and film text, then runs the source pass until
// the server reports it complete, and only then fetches the recall and opens the recall pass.
import { post } from './api.js';
import { h, clear, toast } from './dom.js';
import { Film } from './film.js';
import { SourcePass } from './source.js';
import { RecallPane } from './recall.js';
import { DecidePane } from './decide.js';
import { Structure } from './structure.js';

const KEYS = {
  both: [
    ['Space', 'play or pause'], ['r', 'replay the moment'], ['l', 'loop the moment'],
    [', .', 'step one frame'], ['< >', 'previous or next annotation row'], ['{ }', 'previous or next scene'],
    ['Shift+P', 'presence lanes: open row or whole cast'], ['[ ]', 'slower or faster'],
    ['Shift+S', 'structure panel'],
    ['?', 'this list'],
  ],
  source: [
    ['J K or ↓ ↑', 'next or previous row'],
    ['1–9', 'resolve the next open mention to option n'], ['Shift+1–9', 'toggle the nth listed person'],
    ['N', 'add an unnamed person'], ['Enter', 'row done, go to the next'],
  ],
  recall: [
    ['W B', 'jump to the next or previous likely reference'], ['Shift+W B', 'reopen the next or previous decision'],
    ['Shift+N', 'reopen the next decision still needing evidence'],
    ['← →', 'move one word'],
    ['Shift+← →', 'extend the selection'], ['J K', 'next or previous window'],
    ['M', 'mark the window read'], ['Enter', 'start a decision or commit the current step'],
    ['1–8', 'open candidate n'], ['/', 'search'], ['C', 'add selection as co-referring span'],
    ['A', 'add the open moment as source evidence'], ['F', 'find a person (step 1)'],
    ['n p d e o', 'reference form (step 1)'],
    ['y x u', 'person? (step 1) or resolvable (step 2)'], ['s c v e', 'outcome (step 2)'],
    ['Shift+R', 'reveal this calibration window (after every reference in it is committed)'],

    ['Z', 'undo the last edit'], ['Shift+E', 'reopen the decision under the cursor; in step 2, edit step 1'], ['Esc', 'close the form'],
  ],
};

const S = {
  mode: 'source',
  sel: null,
  win: 1,
  active: null,
  dupes: [],
};
window.__workbench = S; // for the synthetic-specimen checks only; holds no text beyond the DOM

S.setState = function (state) {
  S.state = state;
  renderBar();
  if (S.mode === 'recall' && S.recallPane) S.recallPane.refresh();
};

S.decision = id => S.state.decisions[id] || null;
S.blind = () => (S.decide ? S.decide.blind() : false);
let maskedCache = { key: null, set: new Set() };
S.masked = () => {
  const rows = S.state.maskedRows || [];
  const key = rows.length + ':' + (rows[0] ?? '') + ':' + (rows[rows.length - 1] ?? '');
  if (maskedCache.key !== key) maskedCache = { key, set: new Set(rows) };
  return maskedCache.set;
};
const reduced = matchMedia('(prefers-reduced-motion: reduce)');
S.reducedMotion = () => reduced.matches;
S.revealed = id => !!S.state.decisions[id]?.revealed;

// -- selection and windows -----------------------------------------------------------------
let candTimer = null;
S.select = function (a, b) {
  const n = S.index.words.length;
  a = Math.max(1, Math.min(n, a));
  b = Math.max(1, Math.min(n, b));
  S.sel = { a, b };
  S.win = S.index.words[b - 1].window;
  S.recallPane.refreshSelection();
  S.recallPane.refreshHeads();
  S.recallPane.scrollToRow(b);
  S.structure.refresh();
  if (S.decide.tab === 'search' && document.activeElement !== S.decide.q) S.decide.setTab('candidates');
  clearTimeout(candTimer);
  candTimer = setTimeout(() => {
    const [x, y] = span();
    S.decide.load(x, y);
  }, 120);
  if (!S.active) S.decide.renderForm();
};

function span() {
  const s = S.sel;
  return s ? [Math.min(s.a, s.b), Math.max(s.a, s.b)] : null;
}

// Reopen the next committed decision with no source evidence; with sameWindow, only in the
// window of `from`. Returns whether one was found.
S.nextAwaitingEvidence = function (from, sameWindow = false) {
  const w = S.index.words[from - 1].window;
  const todo = Object.values(S.state.decisions)
    .filter(d => !d.source && (!sameWindow || S.index.words[d.reference.first_word - 1].window === w))
    .sort((x, y) => x.reference.first_word - y.reference.first_word);
  const next = todo.find(d => d.reference.first_word > from) || todo[0];
  if (!next) return false;
  S.reopen(next.id);
  return true;
};

S.nextLikely = function (after) {
  const words = S.index.words;
  for (let r = after + 1; r <= words.length; r++) {
    if (words[r - 1].screen && !S.recallPane.wordEls[r].dataset.did) return S.select(r, r);
  }
};

S.goWindow = function (w) {
  const ws = S.index.windows;
  w = Math.max(1, Math.min(ws.length, w));
  S.win = w;
  S.sel = null;
  S.recallPane.refreshSelection();
  S.recallPane.refreshHeads();
  S.recallPane.scrollToWindow(w);
  const win = ws[w - 1];
  S.decide.load(win.first, win.last);
};

S.markRead = async function (w, value = true) {
  try {
    const out = await post('/api/window', { window: w, value });
    S.setState(out.state);
    toast(`Window ${w} marked ${value ? 'read' : 'unread'}`);
  } catch (e) {
    toast(e.message, 'error');
  }
};

S.cueScene = function (id) {
  const s = S.index.scenes.find(x => x.scene === id);
  if (!s) return;
  S.filmPane.cue({ part: s.part, t0: s.t0, t1: s.t1 });
  S.filmPane.showMoment(s.rows[0]);
};

S.onPick = i => S.decide.choose(i);
S.onRow = row => {
  if (S.mode === 'source') return S.source.goRow(row);
  S.filmPane.showMoment(row);
  if (S.active?.step === 2) S.decide.renderForm();
};

// -- decisions ------------------------------------------------------------------------------
function blankFields(a, b) {
  return {
    first_word: a,
    last_word: b,
    other_coref_spans: [],
    reference_form: null,
    claim_role: '',
    occurrence_group: S.newGroup(),
    person_reference: 'yes',
    referent: null,
  };
}

function blankSource() {
  return { source_scene_ids: [], source_anchor_ids: [], source_resolvable: null, reference_outcome: null, ambiguity_reason: '' };
}

S.groups = function () {
  const g = new Set(Object.values(S.state.decisions).map(d => d.reference.occurrence_group).filter(Boolean));
  return [...g].sort((x, y) => x.localeCompare(y, undefined, { numeric: true }));
};

S.newGroup = function () {
  let n = 1;
  const used = new Set(S.groups());
  while (used.has(`g${n}`)) n++;
  return `g${n}`;
};

S.usedRoles = () => [...new Set(Object.values(S.state.decisions).map(d => d.reference.claim_role).filter(Boolean))];

S.startDecision = async function () {
  const sp = span();
  if (!sp) return toast('Select the words of the referring expression first.');
  try {
    const out = await post('/api/decision/start', { span: sp });
    S.setState(out.state);
    S.active = { id: out.id, calibration: out.calibration, step: 1, fields: blankFields(...sp), source: blankSource(), started: Date.now() };
    S.dupes = [];
    S.recallPane.refresh();
    S.decide.render();
    if (out.calibration) S.filmPane.showMoment(null);
    await S.decide.load(...sp);
  } catch (e) {
    toast(e.message, 'error');
  }
};

S.reopen = function (id) {
  const d = S.decision(id);
  if (!d) return;
  const open = S.active;
  if (open && open.calibration && !S.revealed(open.id) && open.id !== id) return toast(`Finish calibration item ${open.id} first.`, 'error');
  S.active = {
    id,
    calibration: d.calibration,
    step: 2, // a committed decision reopens at its evidence; Shift+E goes back to step 1
    fields: structuredClone(d.reference),
    source: d.source ? structuredClone(d.source) : blankSource(),
    started: Date.now(),
  };
  S.sel = { a: d.reference.first_word, b: d.reference.last_word };
  S.win = S.index.words[d.reference.first_word - 1].window; // the reader follows the reopened decision
  S.recallPane.refresh();
  S.recallPane.scrollToRow(d.reference.first_word);
  S.decide.render();
  S.decide.load(d.reference.first_word, d.reference.last_word);
};

S.reopenAt = function (row) {
  const el = S.recallPane.wordEls[row];
  if (el?.dataset.did) S.reopen(el.dataset.did);
};

let closeArmed = 0;
S.closeDecision = function (force = false) {
  const a = S.active;
  if (!a) return;
  const f = a.fields;
  const dirty = a.step === 1 && !S.decision(a.id) && (f.reference_form || f.referent || (f.other_coref_spans || []).length);
  if (dirty && !force && Date.now() - closeArmed > 2500) {
    closeArmed = Date.now();
    return toast('Step 1 is not committed. Press Esc again to discard it.');
  }
  S.active = null;
  S.dupes = [];
  S.recallPane.refresh();
  S.decide.render();
};

S.setField = function (key, value, rerender = true) {
  if (!S.active) return;
  if (S.active.step === 2 && key in S.active.source) return S.setSource(key, value, rerender);
  S.active.fields[key] = value;
  if (key === 'referent') checkDupes();
  if (rerender) S.decide.renderForm();
};

S.setSource = function (key, value, rerender = true) {
  if (!S.active) return;
  S.active.source[key] = value;
  if (rerender) S.decide.renderForm();
};

async function checkDupes() {
  const f = S.active.fields;
  try {
    const out = await post('/api/duplicates', { id: S.active.id, first: f.first_word, last: f.last_word, referent: f.referent });
    S.dupes = out.ids;
    S.decide.renderForm();
  } catch {
    S.dupes = [];
  }
}

S.addCoref = function () {
  const sp = span();
  if (!S.active || !sp) return;
  const f = S.active.fields;
  if (sp[0] === f.first_word && sp[1] === f.last_word) return toast('That is the main span. Select another stretch of words first.');
  f.other_coref_spans = [...(f.other_coref_spans || []), sp];
  S.decide.renderForm();
};

S.removeCoref = function (i) {
  S.active.fields.other_coref_spans.splice(i, 1);
  S.decide.renderForm();
};

S.createPerson = async function (label) {
  try {
    const out = await post('/api/person', { label });
    S.setState(out.state);
    S.setField('referent', out.id);
  } catch (e) {
    toast(e.message, 'error');
  }
};

S.commitReference = async function () {
  const a = S.active;
  if (!a) return;
  const f = a.fields;
  const missing = S.decide.problems();
  if (missing.length) return toast(`Still needed: ${missing.join(', ')}`, 'error');
  try {
    const out = await post('/api/decision/reference', { id: a.id, fields: f });
    S.setState(out.state);
    a.step = 2;
    S.recallPane.refresh();
    S.decide.render();
    toast(`${a.id}: step 1 saved${a.calibration && !S.revealed(a.id) ? ' (blind)' : ''}`);
  } catch (e) {
    toast(e.message, 'error');
  }
};

S.editStep1 = function () {
  if (S.active) {
    S.active.step = 1;
    S.decide.renderForm();
  }
};

// A reveal covers a whole calibration window and cannot be undone; do it after every reference
// in the window has been committed blind.
S.calWindowOf = function (a) {
  const cw = new Set(S.state.maskedWindows || []);
  // With no item open the reader's own window counts, so a reveal is never stranded by Esc.
  const sp = a ? [a.fields.first_word, a.fields.last_word] : span() || (cw.has(S.win) ? [S.index.windows[S.win - 1].first, S.index.windows[S.win - 1].first] : null);
  if (!sp) return null;
  for (let r = sp[0]; r <= sp[1]; r++) {
    const w = S.index.words[r - 1].window;
    if (cw.has(w)) return w;
  }
  return null;
};

let revealArmed = { t: 0, w: null };
S.revealArmedFor = w => revealArmed.w === w && Date.now() - revealArmed.t < 6000;
S.reveal = async function () {
  const w = S.calWindowOf(S.active);
  if (!w) return toast('Nothing to reveal here.');
  if (S.active && !S.decision(S.active.id)) return toast('Commit step 1 of this item before revealing the window.', 'error');
  // Warn once about likely references in the window that have no decision yet: after the
  // reveal they can no longer be judged blind.
  const win = S.index.windows[w - 1];
  let open = 0;
  for (let r = win.first; r <= win.last; r++) if (S.index.words[r - 1].screen && !S.recallPane.wordEls[r].dataset.did) open++;
  if (open && !(revealArmed.w === w && Date.now() - revealArmed.t < 6000)) {
    revealArmed = { t: Date.now(), w }; // armed for this window only
    S.decide.renderBody();
    return toast(`${open} likely reference${open > 1 ? 's' : ''} in window ${w} have no decision yet and will no longer be blind. Press Shift+R again to reveal anyway.`, 'error');
  }
  try {
    const out = await post('/api/calibration/reveal', { window: w });
    S.setState(out.state);
    S.index = await post('/api/index'); // revealed words still carry no timing; candidates do
    S.decide.render();
    S.recallPane.refreshHeads();
    const f = S.active?.fields;
    if (f) await S.decide.load(f.first_word, f.last_word);
    else if (S.sel) { const [x, y] = span(); await S.decide.load(x, y); }
    else { const win = S.index.windows[S.win - 1]; await S.decide.load(win.first, win.last); }
    toast(`Window ${w} revealed. Edit step 1 (Shift+E) if the suggestions change your judgment; revisions are logged.`);
  } catch (e) {
    toast(e.message, 'error');
  }
};

S.toggleSourceScene = function (id) {
  const s = S.active.source;
  s.source_scene_ids = s.source_scene_ids.includes(id) ? s.source_scene_ids.filter(x => x !== id) : [...s.source_scene_ids, id].sort((x, y) => x - y);
  S.decide.renderForm();
};

S.addAnchorFromMoment = function () {
  const row = S.filmPane.focusRow;
  if (!S.active || S.active.step !== 2 || !row) return;
  const s = S.active.source;
  const anchor = `row-${row}`;
  if (!s.source_anchor_ids.includes(anchor)) s.source_anchor_ids = [...s.source_anchor_ids, anchor];
  // The row's scene is the one containing its midpoint (a row ending on the next scene's
  // first TR is not part of that scene).
  const r = S.index.rows.find(x => x.row === row);
  const mid = (r.t0 + r.t1) / 2;
  const sc = S.index.scenes.find(x => x.part === r.part && r.scenes.includes(x.scene) && x.t0 <= mid && mid < x.t1)?.scene ?? r.scenes[0];
  if (sc !== undefined && !s.source_scene_ids.includes(sc)) s.source_scene_ids = [...s.source_scene_ids, sc].sort((x, y) => x - y);
  S.decide.renderForm();
};

S.removeAnchor = function (id) {
  S.active.source.source_anchor_ids = S.active.source.source_anchor_ids.filter(x => x !== id);
  S.decide.renderForm();
};

S.commitSource = async function () {
  const a = S.active;
  const s = a.source;
  const missing = S.decide.problems();
  if (missing.length) return toast(`Still needed: ${missing.join(', ')}`, 'error');
  try {
    const out = await post('/api/decision/source', { id: a.id, fields: s });
    S.setState(out.state);
    toast(`${a.id}: committed`);
    S.closeDecision(true);
    // After evidence for a calibration item, stay in its window: go to the next item there
    // that still needs evidence; otherwise continue to the next likely reference.
    if (!S.nextAwaitingEvidence(a.fields.first_word, true)) S.nextLikely(a.fields.last_word);
  } catch (e) {
    toast(e.message, 'error');
  }
};

S.undo = async function () {
  try {
    const out = await post('/api/undo');
    S.setState(out.state);
    const what = { 'decision.reference': 'step 1', 'decision.revise': 'step 1 revision', 'decision.source': 'step 2', 'window.read': 'window mark', 'person.create': 'new person', 'decision.delete': 'deletion', 'source.row': 'source row', 'source.unnamed': 'unnamed person' }[out.undone?.kind] || 'last edit';
    if (S.active) {
      // Keep the open form in step with the log: an undone commit must not linger in the form.
      const d = S.decision(S.active.id);
      if (!d && S.active.step === 2) S.active.step = 1;
      if (d) {
        S.active.fields = structuredClone(d.reference);
        if (d.source) S.active.source = structuredClone(d.source);
      }
    }
    if (S.mode === 'source') S.source.render();
    else S.decide.render();
    toast(`Undid ${what}${out.undone?.id ? ` of ${out.undone.id}` : ''}${out.undone?.row ? ` (row ${out.undone.row})` : ''}`);
    if (S.mode === 'recall' && S.sel) { const [x, y] = span(); S.decide.load(x, y); }
  } catch (e) {
    toast(e.message, 'error');
  }
};

// -- chrome ---------------------------------------------------------------------------------
function renderBar() {
  document.getElementById('subject').textContent = `${S.index.subject}`;
  document.getElementById('synthetic').hidden = !S.index.synthetic;
  const P = document.getElementById('progress');
  const st = S.state;
  const rec = document.getElementById('mode-recall');
  rec.disabled = !st.recallOpen;
  rec.title = st.recallOpen ? 'Recall pass' : 'Opens when every source row is done';
  document.getElementById('mode-source').setAttribute('aria-pressed', String(S.mode === 'source'));
  rec.setAttribute('aria-pressed', String(S.mode === 'recall'));
  if (S.mode === 'source') {
    const sc = Object.values(st.source.scenes);
    const done = sc.reduce((a, s) => a + s.done, 0);
    const total = sc.reduce((a, s) => a + s.rows, 0);
    clear(P, h('span.stat', h('b', `${done}`), ` / ${total} source rows`), h('span.stat', h('b', String(sc.filter(s => s.done === s.rows).length)), ` / ${sc.length} scenes complete`));
  } else {
    const read = Object.values(st.windows).filter(v => v[0]).length;
    const ds = Object.values(st.decisions);
    clear(P, h('span.stat', h('b', String(read)), ` / ${S.index.windows.length} windows read`), h('span.stat', h('b', String(ds.length)), ' decisions'), h('span.stat', h('b', String(ds.filter(d => d.source).length)), ' with source evidence'));
  }
}

let keysReturn = null;
function openKeys() {
  keysReturn = document.activeElement;
  renderKeys();
  document.getElementById('keys').hidden = false;
  document.querySelector('#keys button')?.focus();
}
function closeKeys() {
  document.getElementById('keys').hidden = true;
  if (keysReturn && keysReturn.isConnected && keysReturn !== document.body) keysReturn.focus();
  else document.activeElement?.blur();
}

function renderKeys() {
  const O = document.getElementById('keys');
  const list = rows => h('dl', rows.map(([k, v]) => [h('dt', h('kbd', k)), h('dd', v)]));
  clear(O, h('div.keysbox', { role: 'dialog', 'aria-modal': 'true', 'aria-label': 'Keyboard' },
    h('div.panehead', h('h2', 'Keyboard'), h('button.ghost', { type: 'button', onclick: () => closeKeys() }, 'Close')),
    h('div.keycols', h('section', h('h3', S.mode === 'source' ? 'Source pass' : 'Recall pass'), list(KEYS[S.mode])), h('section', h('h3', 'Film'), list(KEYS.both)))));
}

async function setMode(mode) {
  if (mode === 'recall' && !S.state.recallOpen) return toast('The recall pass opens when every source row is done.');
  S.mode = mode;
  document.body.dataset.mode = mode;
  if (mode === 'source') {
    S.source.enter();
  } else {
    if (!S.recallText) S.recallText = (await post('/api/recall-text')).words;
    if (!S.recallPane) {
      S.recallPane = new RecallPane(S);
      S.decide = new DecidePane(S);
    }
    S.decide.mounted = false;
    S.recallPane.build();
    S.filmPane.showMoment(null);
    S.goWindow(S.win);
    S.decide.render();
  }
  renderBar();
}

function inText(e) {
  const t = e.target;
  if (!t) return false;
  // Checkboxes and radios take only Space; every other key still belongs to the workbench.
  if (t.tagName === 'INPUT' && ['checkbox', 'radio'].includes(t.type)) return e.key === ' ';
  return t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.tagName === 'SELECT' || t.isContentEditable;
}

function onKey(e) {
  if (e.metaKey || e.ctrlKey || e.altKey) return;
  if (inText(e)) return;
  const k = e.key;
  const F = S.filmPane;
  const keys = document.getElementById('keys');
  // The keys overlay is modal: only ? and Esc reach anything while it is open, and focus
  // stays on its close button.
  if (!keys.hidden) {
    if (k === '?' || k === 'Escape') closeKeys();
    e.preventDefault();
    return;
  }
  // Enter and Space on a focused control activate that control, never a global action.
  const t = e.target;
  if ((k === 'Enter' || k === ' ') && t && t !== document.body && t.closest('button, [role=radio], [role=tab], [role=option], a, summary')) return;
  const handled = (() => {
    if (k === '?') { openKeys(); return true; }
    if (k === '<' || k === '>') { F.stepRow(k === '>' ? 1 : -1); return true; }
    if (k === '{' || k === '}') { F.stepScene(k === '}' ? 1 : -1); return true; }
    if (k === 'P') { F.toggleCast(); return true; }
    if (k === 'Escape' && !document.getElementById('structure').hidden) { S.structure.toggle(); return true; }
    if (k === 'S') { S.structure.toggle(); return true; }
    if (k === ' ') { F.toggle(); return true; }
    if (k === 'r') { F.replay(); return true; }
    if (k === 'l') { F.toggleLoop(); return true; }
    if (k === ',') { F.step(-1); return true; }
    if (k === '.') { F.step(1); return true; }
    if (k === '[') { F.setRate(-1); return true; }
    if (k === ']') { F.setRate(1); return true; }
    if (S.mode === 'source') return S.source.key(e);
    return recallKey(e);
  })();
  if (handled) e.preventDefault();
}

function recallKey(e) {
  const k = e.key;
  const a = S.active;
  const n = S.index.words.length;
  const cur = S.sel ? S.sel.b : S.index.windows[S.win - 1].first;
  if (k === 'ArrowRight' || k === 'ArrowLeft') {
    const d = k === 'ArrowRight' ? 1 : -1;
    const b = Math.max(1, Math.min(n, (S.sel ? cur + d : cur)));
    S.select(e.shiftKey && S.sel ? S.sel.a : b, b);
    return true;
  }
  if (k === 'N' && !a) {
    if (!S.nextAwaitingEvidence(cur)) toast('Every committed decision has its evidence.');
    return true;
  }
  if ((k === 'W' || k === 'B') && !a) {
    // Hop between committed decisions and reopen one, e.g. to add evidence after a reveal.
    const ids = Object.values(S.state.decisions).sort((x, y) => x.reference.first_word - y.reference.first_word);
    const lo = S.sel ? Math.min(S.sel.a, S.sel.b) : cur;
    const hi = S.sel ? Math.max(S.sel.a, S.sel.b) : cur;
    const next = k === 'W' ? ids.find(d => d.reference.first_word > hi) : [...ids].reverse().find(d => d.reference.first_word < lo);
    if (next) S.reopen(next.id);
    else toast('No more decisions in that direction.');
    return true;
  }
  if (k === 'w' || k === 'b') {
    const d = k === 'w' ? 1 : -1;
    const words = S.index.words;
    const from = S.sel ? cur + d : cur; // with nothing selected, the window's first word counts
    for (let r = from; r >= 1 && r <= n; r += d) {
      if (words[r - 1].screen && !S.recallPane.wordEls[r].dataset.did) { S.select(r, r); return true; }
    }
    toast(d > 0 ? 'No more likely references after this point.' : 'No likely references before this point.');
    return true;
  }
  if (k === 'f' && a && a.step === 1) { S.decide.focusFilter(); return true; }
  if (k === 'j') { S.goWindow(S.win + 1); return true; }
  if (k === 'k') { S.goWindow(S.win - 1); return true; }
  if (k === 'm') { const done = S.state.windows[String(S.win)]?.[0]; S.markRead(S.win, !done); return true; }
  if (k === '/') {
    if (S.decide.searchBlind()) { S.decide.setTab('search'); S.decide.sink?.focus(); return true; }
    S.decide.setTab('search');
    return true;
  }
  if (k === 'z') { S.undo(); return true; }
  if (/^[1-8]$/.test(k)) { if (S.decide.tab === 'search') S.decide.openHit(Number(k) - 1); else S.decide.choose(Number(k) - 1); return true; }
  if (k === 'R') { S.reveal(); return true; }
  if (k === 'E' && a && a.step === 2) { S.editStep1(); return true; }
  if (k === 'Escape') { if (a) S.closeDecision(); else { S.sel = null; S.recallPane.refreshSelection(); S.decide.renderForm(); } return true; }
  if (k === 'E' && !a) { S.reopenAt(cur); return true; }
  if (k === 'Enter') {
    if (!a && S.sel && S.recallPane.wordEls[Math.min(S.sel.a, S.sel.b)]?.dataset.did) S.reopenAt(Math.min(S.sel.a, S.sel.b));
    else if (!a) S.startDecision();
    else if (a.step === 1) S.commitReference();
    else S.commitSource();
    return true;
  }
  if (!a) return false;
  if (k === 'c' && a.step === 1) { S.addCoref(); return true; }
  if (k === 'a' && a.step === 2) { S.addAnchorFromMoment(); return true; }
  if (a.step === 1) {
    const form = { n: 'name', p: 'pronoun', d: 'description', e: 'ellipsis', o: 'other' }[k];
    if (form) { S.setField('reference_form', form); return true; }
    const pr = { y: 'yes', x: 'no', u: 'unknown' }[k];
    if (pr) { S.setField('person_reference', pr); return true; }
  } else {
    const rs = { y: 'yes', x: 'no', u: 'unknown' }[k];
    if (rs) { S.setSource('source_resolvable', rs); return true; }
    const oc = { s: 'supported', c: 'contradicted', v: 'unresolved', e: 'external' }[k];
    if (oc) { S.setSource('reference_outcome', oc); return true; }
  }
  return false;
}

function tickTimer() {
  const t = document.getElementById('timer');
  if (t && S.active) {
    const s = Math.floor((Date.now() - S.active.started) / 1000);
    t.textContent = `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
  }
}

async function boot() {
  try {
    S.index = await post('/api/index');
    S.film = await post('/api/film-text');
    S.state = await post('/api/state');
  } catch (e) {
    document.body.replaceChildren(h('p.fatal', `The workbench could not load: ${e.message}`));
    return;
  }
  S.filmPane = new Film(S);
  S.source = new SourcePass(S);
  S.structure = new Structure(S);
  document.getElementById('mode-source').addEventListener('click', () => setMode('source'));
  document.getElementById('mode-recall').addEventListener('click', () => setMode('recall'));
  document.getElementById('btn-structure').addEventListener('click', () => S.structure.toggle());
  document.getElementById('btn-keys').addEventListener('click', () => openKeys());
  document.getElementById('btn-export').addEventListener('click', async () => {
    try {
      const out = await post('/api/export');
      toast(`Wrote ${out.written.join(' and ')} to the private export folder.`);
    } catch (e) {
      toast(e.message, 'error');
    }
  });
  document.addEventListener('keydown', onKey);
  // A pointer click should not leave focus on the clicked button, or the next Enter or Space
  // would re-press it; keyboard activation (detail 0) keeps focus where the reviewer put it.
  document.addEventListener('click', e => {
    const b = e.target.closest?.('button');
    if (b && e.detail > 0) setTimeout(() => b.blur(), 0);
  });
  setInterval(tickTimer, 500);
  const start = new URLSearchParams(location.hash.slice(1)).get('mode');
  await setMode(S.state.recallOpen && start !== 'source' ? 'recall' : 'source');
  document.body.dataset.ready = 'true';
}

boot();
