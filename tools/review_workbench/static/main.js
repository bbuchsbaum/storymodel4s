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
    ['Space', 'play or pause'], ['R', 'replay the moment'], ['L', 'loop the moment'],
    [', .', 'step one frame'], ['[ ]', 'slower or faster'], ['Shift+S', 'structure panel'],
    ['?', 'this list'],
  ],
  source: [
    ['J K or ↓ ↑', 'next or previous row'], ['1–9', 'toggle the nth listed person'],
    ['N', 'add an unnamed person'], ['Enter', 'row done, go to the next'],
  ],
  recall: [
    ['← →', 'move one word'], ['Shift+← →', 'extend the selection'], ['J K', 'next or previous window'],
    ['M', 'mark the window read'], ['Enter', 'start a decision or commit the current step'],
    ['1–8', 'open candidate n'], ['/', 'search'], ['C', 'add selection as co-referring span'],
    ['A', 'add the open moment as source evidence'], ['n p d e o', 'reference form (step 1)'],
    ['y x u', 'person? (step 1) or resolvable (step 2)'], ['s c v e', 'outcome (step 2)'],
    ['Z', 'undo the last edit'], ['Shift+E', 'reopen the decision under the cursor'], ['Esc', 'close the form'],
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
  clearTimeout(candTimer);
  candTimer = setTimeout(() => {
    const [x, y] = span();
    S.decide.load(x, y);
  }, 120);
  if (!S.active) S.decide.render();
};

function span() {
  const s = S.sel;
  return s ? [Math.min(s.a, s.b), Math.max(s.a, s.b)] : null;
}

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
S.onRow = row => S.filmPane.showMoment(row);

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
    const out = await post('/api/decision/start');
    S.setState(out.state);
    S.active = { id: out.id, calibration: out.calibration, step: 1, fields: blankFields(...sp), source: blankSource(), started: Date.now() };
    S.dupes = [];
    S.recallPane.refreshMarks();
    await S.decide.load(...sp);
    if (out.calibration) toast('Calibration item: suggestions are hidden until you commit step 1.');
  } catch (e) {
    toast(e.message, 'error');
  }
};

S.reopen = function (id) {
  const d = S.decision(id);
  if (!d) return;
  S.active = {
    id,
    calibration: d.calibration,
    step: 1,
    fields: structuredClone(d.reference),
    source: d.source ? structuredClone(d.source) : blankSource(),
    started: Date.now(),
  };
  S.sel = { a: d.reference.first_word, b: d.reference.last_word };
  S.recallPane.refresh();
  S.decide.load(d.reference.first_word, d.reference.last_word);
};

S.reopenAt = function (row) {
  const el = S.recallPane.wordEls[row];
  if (el?.dataset.did) S.reopen(el.dataset.did);
};

S.closeDecision = function () {
  S.active = null;
  S.dupes = [];
  S.recallPane.refreshMarks();
  S.decide.render();
};

S.setField = function (key, value, rerender = true) {
  if (!S.active) return;
  if (S.active.step === 2 && key in S.active.source) return S.setSource(key, value, rerender);
  S.active.fields[key] = value;
  if (key === 'referent') checkDupes();
  if (rerender) S.decide.render();
};

S.setSource = function (key, value, rerender = true) {
  if (!S.active) return;
  S.active.source[key] = value;
  if (rerender) S.decide.render();
};

async function checkDupes() {
  const f = S.active.fields;
  try {
    const out = await post('/api/duplicates', { id: S.active.id, first: f.first_word, last: f.last_word, referent: f.referent });
    S.dupes = out.ids;
    S.decide.render();
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
  S.decide.render();
};

S.removeCoref = function (i) {
  S.active.fields.other_coref_spans.splice(i, 1);
  S.decide.render();
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
  if (!f.reference_form) return toast('Choose the reference form (n p d e o).', 'error');
  try {
    const out = await post('/api/decision/reference', { id: a.id, fields: f });
    S.setState(out.state);
    a.step = 2;
    S.recallPane.refreshMarks();
    S.decide.render();
    toast(`${a.id}: step 1 saved`);
  } catch (e) {
    toast(e.message, 'error');
  }
};

S.editStep1 = function () {
  if (S.active) {
    S.active.step = 1;
    S.decide.render();
  }
};

S.reveal = async function () {
  try {
    const out = await post('/api/decision/reveal', { id: S.active.id });
    S.setState(out.state);
    S.active.step = 1;
    const f = S.active.fields;
    await S.decide.load(f.first_word, f.last_word);
    toast('Suggestions revealed. Revise step 1 if they change your judgment; revisions are logged.');
  } catch (e) {
    toast(e.message, 'error');
  }
};

S.toggleSourceScene = function (id) {
  const s = S.active.source;
  s.source_scene_ids = s.source_scene_ids.includes(id) ? s.source_scene_ids.filter(x => x !== id) : [...s.source_scene_ids, id].sort((x, y) => x - y);
  S.decide.render();
};

S.addAnchorFromMoment = function () {
  const row = S.filmPane.focusRow;
  if (!S.active || S.active.step !== 2 || !row) return;
  const s = S.active.source;
  const anchor = `row-${row}`;
  if (!s.source_anchor_ids.includes(anchor)) s.source_anchor_ids = [...s.source_anchor_ids, anchor];
  const r = S.index.rows.find(x => x.row === row);
  for (const sc of r.scenes) if (!s.source_scene_ids.includes(sc)) s.source_scene_ids = [...s.source_scene_ids, sc].sort((x, y) => x - y);
  S.decide.render();
};

S.removeAnchor = function (id) {
  S.active.source.source_anchor_ids = S.active.source.source_anchor_ids.filter(x => x !== id);
  S.decide.render();
};

S.commitSource = async function () {
  const a = S.active;
  const s = a.source;
  if (!s.source_resolvable || !s.reference_outcome) return toast('Choose resolvable (y x u) and outcome (s c v e).', 'error');
  try {
    const out = await post('/api/decision/source', { id: a.id, fields: s });
    S.setState(out.state);
    toast(`${a.id}: committed`);
    S.closeDecision();
  } catch (e) {
    toast(e.message, 'error');
  }
};

S.undo = async function () {
  try {
    const out = await post('/api/undo');
    S.setState(out.state);
    if (S.active && !S.decision(S.active.id) && S.active.step === 2) S.active.step = 1;
    if (S.mode === 'source') S.source.render();
    else S.decide.render();
    toast('Undone');
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

function renderKeys() {
  const O = document.getElementById('keys');
  const list = rows => h('dl', rows.map(([k, v]) => [h('dt', h('kbd', k)), h('dd', v)]));
  clear(O, h('div.keysbox', { role: 'dialog', 'aria-label': 'Keyboard' },
    h('div.panehead', h('h2', 'Keyboard'), h('button.ghost', { type: 'button', onclick: () => (O.hidden = true) }, 'Close')),
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
    S.recallPane.build();
    S.filmPane.showMoment(null);
    S.goWindow(S.win);
    S.decide.render();
  }
  renderBar();
}

function inText(e) {
  const t = e.target;
  return t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.tagName === 'SELECT' || t.isContentEditable);
}

function onKey(e) {
  if (e.metaKey || e.ctrlKey || e.altKey) return;
  if (inText(e)) return;
  const k = e.key;
  const F = S.filmPane;
  const handled = (() => {
    if (k === '?') { const O = document.getElementById('keys'); renderKeys(); O.hidden = !O.hidden; return true; }
    if (k === 'Escape' && !document.getElementById('keys').hidden) { document.getElementById('keys').hidden = true; return true; }
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
  if (k === 'j') { S.goWindow(S.win + 1); return true; }
  if (k === 'k') { S.goWindow(S.win - 1); return true; }
  if (k === 'm') { const done = S.state.windows[String(S.win)]?.[0]; S.markRead(S.win, !done); return true; }
  if (k === '/') { S.decide.tab = 'search'; S.decide.render(); document.getElementById('q')?.focus(); return true; }
  if (k === 'z') { S.undo(); return true; }
  if (/^[1-8]$/.test(k)) { S.decide.tab = 'candidates'; S.decide.choose(Number(k) - 1); return true; }
  if (k === 'Escape') { if (a) S.closeDecision(); else { S.sel = null; S.recallPane.refreshSelection(); S.decide.render(); } return true; }
  if (k === 'E' && !a) { S.reopenAt(cur); return true; }
  if (k === 'Enter') {
    if (!a) S.startDecision();
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
  document.getElementById('btn-keys').addEventListener('click', () => { const O = document.getElementById('keys'); renderKeys(); O.hidden = !O.hidden; });
  document.getElementById('btn-export').addEventListener('click', async () => {
    try {
      const out = await post('/api/export');
      toast(`Wrote ${out.written.join(' and ')} to the private export folder.`);
    } catch (e) {
      toast(e.message, 'error');
    }
  });
  document.addEventListener('keydown', onKey);
  setInterval(tickTimer, 500);
  const start = new URLSearchParams(location.hash.slice(1)).get('mode');
  await setMode(S.state.recallOpen && start !== 'source' ? 'recall' : 'source');
  document.body.dataset.ready = 'true';
}

boot();
