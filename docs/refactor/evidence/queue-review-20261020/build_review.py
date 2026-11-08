#!/usr/bin/env python3
"""Build a complete planning crosswalk from captured authoritative Mote records.

Reads only tracker metadata and repository evidence; never reads study payloads.
Does not mutate Mote. Planned operations are applied separately through its CLI.
"""
import hashlib
import json
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
OUT = Path(__file__).resolve().parent
LOCAL = ROOT / '.agent-work/queue-review-20261020'
ACTOR = 'codex-queue-review'
AUDIT = 'bd-01M5E33RPWBT8NAGKCKJMMN6FN'
INTEGRATION = 'bd-01M5E3J13V6CVABAMCRC69AG07'
TAG = 'queue-next-20261020'


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def save(name, value):
    (OUT / name).write_text(json.dumps(value, indent=2, ensure_ascii=False) + '\n')


def main():
    before = json.loads((LOCAL / 'before-full.json').read_text())
    all_before = {d['id']: d for d in json.loads((LOCAL / 'all-before.json').read_text())}
    ids = list(before)
    assert len(ids) == 108 and len(set(ids)) == 108
    index = {i: key for i, key in enumerate(ids)}
    decisions = {}

    def decide(n, completeness, correctness, stale, action, evidence, **changes):
        decisions[index[n]] = dict(completeness=completeness, correctness=correctness,
            staleness=stale, next_action=action, evidence=evidence, **changes)

    review_evidence = ['docs/refactor/mapping-audit-20261020.md',
                       'docs/refactor/mapping-audit-gates-20261020.json']
    for n in (9, 10, 11, 12, 13, 41, 42, 43, 44):
        decide(n, 'locally_qualified_not_landed', 'Independent exact-source and final evidence PASS retained; no fresh gate run in this planning audit.',
            'Review status is truthful. Original implementation-next prose is superseded by final handoffs; no new implementation is implied.',
            'Use ' + INTEGRATION + ' to audit all ancestors and qualify the actual integration/default-pin tree. Close this slice only after its authorized landing.',
            review_evidence, **({'priority': 2} if n in (10, 11, 12, 13) else {}))
    decide(0, 'incomplete_container', 'Engineering release and scientific efficacy remain separate; historical completed journeys stay completed.',
        'Container next-action list predates reviewed unmerged S1/mapping work.',
        'Track the selected bounded tranche, then remaining Native/release acceptance; claim children rather than this epic.',
        ['docs/refactor/PLAN.md', 'docs/refactor/RELEASE-QUEUE.md'])
    decide(1, 'incomplete_release_gate', 'No P0 found by this planning review; gate still lacks runtime/adoption, own-study and release acceptance.',
        'October14 last handoff lists constructor fixes that later completed; the current body/indices supersede it.',
        'Retain open gate for Native, own-study U3, infrastructure and actual exact-revision hosted/release acceptance. Do not reopen repaired constructor work.',
        ['docs/refactor/RELEASE-QUEUE.md', 'project/plugins.sbt'])
    decide(2, 'not_delivered', 'Valid synthetic consumer fixture work, not a release blocker; no new execution evidence.',
        'P1 contradicts its release-later lane and absence of an immediate consumer blocker.',
        'Retain exact Forgotten-bag/Silent River fixture requirements; schedule P2 after current delivery tranche.',
        ['docs/refactor/RELEASE-QUEUE.md'], priority=2)
    decide(3, 'repair_qualified_delivery_missing', 'Retained release/base and upstream-main patch tests do not establish a selected fetchable corrected runtime.',
        'Doing has no live claim/job; contribution/route decision remains missing.',
        'Record the accepted contribution/runtime route, then qualify fetched corrected runtime in producer and consumer. Reuse retained original/fixed evidence; no speculative fork or platform reduction.',
        ['project/plugins.sbt', 'docs/refactor/evidence/native-runtime-20261015/README.md'], status='blocked')
    decide(4, 'reviewed_plan_not_landed', 'Exact9e7af311 plan has independent GO and integrity evidence; not a runtime acceptance.',
        'Plan is complete locally; P1 implementation urgency is obsolete, landing remains.',
        'Account for this reviewed ancestor in the integration candidate; close only after authorized landing.',
        ['docs/refactor/PLAN.md', 'docs/refactor/RELEASE-QUEUE.md'], priority=2)
    decide(5, 'locally_qualified_not_landed', 'Exactc69a5d75 default Git-pin build, CLI/notices/offline/TZ and explicit Atlas override are reviewed local evidence.',
        'Review is current; old instructions to redo fresh S1 preparation are obsolete.',
        'Carry c69a5d75 as an ancestor of the single integration range. Requalify the actual packaged integration bytes and distinguish source/CLI from Maven publication.',
        ['docs/refactor/evidence/source-cli-20261015/README.md'])
    decide(6, 'reviewed_proposal_decision_missing', 'Contract distinguishes researcher declaration from authenticated issuer; proposal PASS is not owner ADR acceptance.',
        'Doing incorrectly suggests active implementation while trust-model decision is pending.',
        'Owner chooses declaration or independently provisioned issuer; record ADR0022 decision and bind independent contract review before U2.',
        ['docs/design/own-study-local-export-contract.md'], status='blocked')
    decide(7, 'not_implemented', 'Contract dependency correctly prevents minting authority from input JSON/receipts.',
        'Open dependency-waiting status is truthful; no expiry inferred from age.',
        'Implement the accepted U1 trust model through existing local publication/cache/resume seams, with scoped producer/consumer and refusal courts.',
        ['docs/design/own-study-local-export-contract.md'])
    decide(8, 'not_accepted', 'Separate unfamiliar-file acceptance is required; fixed project controls do not establish ordinary-file usability.',
        'S1/U2 prerequisites remain correct.',
        'After S1/U2 landing, independent unfamiliar synthetic/licensed source plus two recalls, one failure, three weights/readers and exact Atlas replay.',
        ['docs/design/own-study-local-export-contract.md'])
    for n, missing in [(15, 'corpus-specific hosted permission, retention/provider constraints and spend'),
                       (16, 'fresh eligible material, coder roles, blinding and approved resource/precision envelope'),
                       (52, 'owner-authenticated OSF inspection on the approved host'),
                       (56, 'remaining component licences and corpus-specific ethics bases')]:
        decide(n, 'external_decision_missing', 'No permission or corpus eligibility is inferred by this review.',
            'Listed ready despite an explicit missing external decision; no active work exists.',
            'Obtain and record ' + missing + '. Keep these decisions scoped to their named downstream work.',
            ['docs/refactor/RELEASE-QUEUE.md'], status='blocked')
    decide(19, 'historical_correction_locally_complete_not_landed',
        'Full parent later qualified reader54cf998b (467 cases plus3standalone) and reviewed historical readout/report; child-only older notes do not negate that later evidence.',
        'Doing and adapter last-note wording are obsolete; no live study job or new read is required.',
        'Reconcile and integrate the separate reviewed instrument branch after delivery scope is settled; preserve declared historical exposure/adverse attempt. Do not rerun gold or treat corrected history as prospective efficacy.',
        ['Mote parent 2026-10-16T13:01:41 handoff', 'codex/instrument-score-evidence-20261015'], status='review')
    decide(20, 'partially_delivered', 'CLI/source qualification and docs deployment are distinct from namespace/signing/Maven/baseline/release acceptance.',
        'Historical next-action language suggests rebuilding already qualified CLI/docs.',
        'Consume S1 integration, preserve GitHub grakern route; keep unresolved stable publication/signing/baseline acceptance explicit. No publication in this tranche.',
        ['docs/refactor/RELEASE-QUEUE.md', 'docs/refactor/evidence/source-cli-20261015/README.md'])
    decide(21, 'engineering_delivered_real_preview_missing',
        'OrganizationMap and exchange/readers exist; real ordinary-file preview acceptance remains separate from synthetic/development recovery.',
        'Body says the facade withholds organization despite the October13 implemented public command. Missing-media assumption predates current local media; published-index timing is allowed, physical sync remains unavailable.',
        'Verify current authorized input/custody metadata, then run the still-missing admitted real organization CLI/consumer journey. Reuse existing implementation and report timing at its actual evidence level.',
        ['docs/refactor/evidence/integration-20261014/README.md', 'docs/refactor/PLAN.md'])
    decide(24, 'incomplete_container_preview_remaining', 'Five children are closed, only public preview is open; adverse recovery findings must stay visible.',
        'Compatibility/count/recovery implementation-next list is obsolete.',
        'Finish existing G2 preview; reconcile all completed child receipts and limitations before closing container. No automatic repair/default or scientific promotion.',
        ['docs/refactor/evidence/integration-20261014/README.md', 'docs/refactor/evidence/entity-hypotheses-20261014/README.md'])
    decide(34, 'specific_test_witness_missing', 'StageTraceOriginSuite compares codec delegation and field presence; does not independently establish populated reduction/imputation/exclusion values.',
        'Still a valid small nonblocking follow-up, not redundant with completed codec work.',
        'Add one populated synthetic cost fixture plus an excluded bookkeeping cell; assert concrete values, retained baseCosts and excluded comparison-state absence; named mutations. Keep release nonblocking.',
        ['embed-bench/src/test/scala/storymodel4s/bench/video/StageTraceOriginSuite.scala'])
    decide(35, 'unfrozen_draft_resources_missing', 'No invented sample size/thresholds and no new evaluation read; actual launch checker depends on the chosen eligible study.',
        'Doing/ready is stale; the resource prerequisite is described but absent from the dependency graph.',
        'After existing coder/resource decision, freeze actual eligible population/independent units, numerical criteria and executable hashes; qualify the integrated launch guard before E2.',
        ['docs/refactor/prospective-evaluation-20261015.md'], status='blocked',
        add_dependency=index[16])
    for n in (38, 39, 40):
        decide(n, 'bounded_child_locally_qualified_not_landed',
            'Retain exact child qualifications; later parent integration/readout evidence is separate and broader. No new study read performed here.',
            'Review is correct. Adapter note saying doing is superseded by actual review state and later parent handoff.' if n == 40 else 'Review is correct; do not promote local evidence to landing.',
            'Consume the later parent54cf998b/fd5e3fe9 qualification and integrate the instrument branch within its own scope; close child only after actual landing.',
            ['Mote child receipts and parent2026-10-16 handoff'])
    decide(64, 'sources_and_transforms_complete_other_acceptance_open',
        'Original custody, corrected346/260-second clips, hashes/full decode and independent media review are retained; upstream alignment validation remains attributed.',
        'Title and body still request already supplied films. Credits/internal-cut verification and Memento yes/no are genuinely unresolved.',
        'Verify internal-cut/animated-credit correspondence with frame/dialogue evidence; obtain the separate Memento T4 yes/no only if that lane is pursued. Do not restage originals or reapply speed correction.',
        ['data/filmfestival/media/provenance/ingest-20261020/README.md', 'data/transcription/unified-v1/README.md'],
        title='[media] Finish FilmFestival clip-boundary verification and Memento T4 decision')
    decide(55, 'probe_audit_not_complete', 'Historical findings concern incremental recompilation and Mirror coverage; clean CI evidence does not establish incremental invalidation coverage. No fresh bypass demonstrated.',
        'Title count12of21 is a historical sample, not a current inventory. Consolidated children must not be treated as completed audit work.',
        'Inventory actual compile-time probes and non-string anchors, then add missing Mirror/anchor controls with one incremental mutation per affected module. Recount before making any current coverage claim.',
        ['Mote September22 consolidation decision', 'AGENTS.md rule8 and clean-recompile evidence rule'],
        title='[maintenance] Audit construction-probe Mirror coverage and incremental dependency anchors')
    decide(73, 'clock_descriptor_still_unqualified', 'New source correction and ASR receipts do not establish annotation/scanner equivalence or the complete scene-task descriptor.',
        'Eight-film/media-missing wording predates current11FilmFestival clips. The frozen task population must not silently expand.',
        'Reuse current media/transform receipts; explicitly reconcile original registered film set against current11clip inventory; retain bus_stop shortfall, mm.ss/Run2 ordinal courts and unestablished offsets.',
        ['data/transcription/unified-v1/manifest.json', 'data/filmfestival/media/provenance/ingest-20261020/README.md'])
    decide(83, 'inputs_advanced_study_not_executed', 'Uniform timed Whisper, separate Parakeet/Qwen trials and captions are evidence inputs, not qualified scientific ablations or validated accuracy.',
        'Dialogue acquisition-next prose predates current standardized ASR; native Qwen has no word timing.',
        'Consume verified unified-v1 full Whisper and separately labelled trial alternatives with exact source/model clocks; freeze task/cut/eligibility and licence/exposure rules before any preregistered ablation. Never copy Whisper word times to Qwen.',
        ['data/transcription/unified-v1/README.md', 'docs/refactor/prospective-evaluation-20261015.md'])
    decide(97, 'open_boundary_witness', 'NodeSummary is now a private non-case class, but public copy accepts Int level without a range check. Private constructor alone does not close the advertised level issue; downstream refusal is a separate boundary.',
        'Historical case-class shape is stale; negative-level question remains. No corpus occurrence or runnable new reproduction claimed.',
        'Write the minimal public-copy negative-level witness first; trace SourceView consumer consequence, then repair only the responsible boundary. Preserve downstream refusal controls.',
        ['align/src/main/scala/storymodel4s/align/source.scala'])
    decide(106, 'specific_assertion_missing', 'Many-error court asserts row order and B/C set, but not B-before-C for the first row.',
        'Still valid; no reader defect inferred from missing assertion.',
        'Add exact within-row refusal order to the existing260/100 synthetic workbook court; retain behavior and bounds.',
        ['corpus-intake/src/test/scala/storymodel4s/corpus/intake/CorpusReaderSuite.scala'])

    rows = []
    for key, d in before.items():
        body = d['body'].replace('\\n', '\n')
        actions = [p for p in body.split('\n\n') if p.startswith(('Remaining / next action:', 'Next action:'))]
        default_action = actions[0].split(':', 1)[1].strip() if actions else 'Retain original scoped acceptance; write its first discriminating witness before implementation.'
        if key not in decisions:
            decisions[key] = dict(completeness='open_requirement_not_newly_qualified',
                correctness='Acceptance/claim boundary reviewed; implementation correctness not freshly executed or independently re-audited here.',
                staleness='No evidence establishes complete acceptance/landing for this ticket. Older partial completions are preserved; age alone is not grounds for closure, and historical priority/host prose yields to the current queue.',
                next_action=default_action, evidence=['Mote current body and retained notes'])
        decision = decisions[key]
        pending = [dep['parent'] for dep in d['deps'] if all_before[dep['parent']]['status'] != 'closed']
        row = dict(id=key, title=d['title'], before_priority=d['priority'], before_status=d['status'],
            priority=decision.get('priority', d['priority']), status=decision.get('status', d['status']),
            body_sha256=hashlib.sha256(d['body'].encode()).hexdigest(), record_sha256=digest(d),
            last_activity=d['notes'][-1]['ts'] if d['notes'] else d['created_at'],
            open_prerequisites_before=pending, **{k: decision[k] for k in
                ('completeness', 'correctness', 'staleness', 'next_action', 'evidence')})
        rows.append(row)
    save('assessments.json', dict(scope='All108unfinished baseline Motes, including doing and review.',
        limitation='Planning/correctness-of-acceptance review with focused source and retained receipt checks, not108fresh runtime/scientific audits. No study payloads read or tests rerun.',
        source_revision='37f659277199d9d1922b772f13b0b260b1e028d9',
        main_revision='13aec7cc874ed1c2695c6dd281852523cc43ef90',
        full_snapshot='Ignored local .agent-work/queue-review-20261020/before-full.json',
        full_snapshot_sha256=hashlib.sha256((LOCAL/'before-full.json').read_bytes()).hexdigest(),
        records=rows))
    tranche = [dict(order=1, id=INTEGRATION, role='Prepare single exact S1+mapping integration candidate; no main movement.', depends_on=[]),
               dict(order=2, id=index[6], role='Choose and accept own-study trust model/ADR; owner decision pending.', depends_on=[]),
               dict(order=3, id=index[7], role='Implement accepted local admission, reuse existing pipeline.', depends_on=[index[6]]),
               dict(order=4, id=index[8], role='Independent unfamiliar-file end-to-end acceptance.', depends_on=[index[5], index[7]]),
               dict(order=5, id=index[34], role='Small ready fallback: populated StageTrace cost/exclusion witness; release nonblocking.', depends_on=[])]
    save('tranche.json', dict(tag=TAG, selected=tranche,
        companions=[index[5], index[9], index[10], index[11], index[12], index[13], index[41], index[42], index[43], index[44]],
        follow_after=['Separate reviewed historical instrument integration', 'Existing organization real-file preview'],
        held_separate=['Corrected Native route/adoption', 'Prospective E1-E3 resources/freeze', 'Hosted research/LLM campaigns', 'Film compiler and advanced view expansion', 'Maven publication/release']))
    ops = []
    for row in rows:
        d = before[row['id']]
        change = {k: row[k] for k in ('priority', 'status') if row[k] != d[k]}
        decision = decisions[row['id']]
        if 'title' in decision:
            change['title'] = decision['title']
        needs_update = bool(change) or row['id'] in {index[n] for n in (0, 1, 5, 7, 8, 20, 21, 24, 34, 40, 73, 83, 97)}
        if needs_update:
            preface = ('Current queue review,20October2026 (' + AUDIT + ')\n\n' +
                'Completeness: ' + row['completeness'] + '.\n' +
                'Assessment: ' + row['correctness'] + '\n' +
                'Staleness: ' + row['staleness'] + '\n' +
                'Remaining / next action: ' + row['next_action'] + '\n' +
                'Audit: docs/refactor/evidence/queue-review-20261020/README.md. '
                'This planning review does not grant publication, study-read or integration authority.\n\n'
                '--- Preserved prior scope, acceptance and history ---\n\n')
            change['body'] = preface + d['body']
            ops.append(dict(action='set', id=row['id'], fields=change))
    # Batch only actions supported by the installed CLI; set operations run individually.
    batch = [dict(action='tag_add', id=t['id'], tag=TAG) for t in tranche if t['id'] != INTEGRATION]
    batch += [dict(action='dep_add', child=index[35], parent=index[16]),
              dict(action='rel_add', child=INTEGRATION, parent=index[0], kind='parent')]
    batch.append(dict(action='note', id=AUDIT, note_kind='decision', text=
        'All108baseline unfinished records assessed in docs/refactor/evidence/queue-review-20261020/assessments.json. '
        'Keep14existing review records open; no closure on local gates alone. Selected5ticket tranche '+TAG+
        ': exact single-range S1+mapping integration candidate '+INTEGRATION+', U1->U2->U3 and nonblocking StageTrace fallback. '
        'Native/trust/resources/hosted/ethics/access waiting states and E1 resource dependency corrected. '
        'Prior source supply/organization implementation retained; staleness corrected without new study reads or repeated inference.'))
    save('planned-sets.json', ops)
    (OUT/'planned-batch.jsonl').write_text(''.join(json.dumps(o, ensure_ascii=False)+'\n' for o in batch))
    summary = dict(baseline=len(rows), before_status=dict(Counter(r['before_status'] for r in rows)),
        planned_status=dict(Counter(r['status'] for r in rows)),
        before_priority=dict(Counter(r['before_priority'] for r in rows)),
        planned_priority=dict(Counter(r['priority'] for r in rows)),
        changed_priority=sum(r['priority'] != r['before_priority'] for r in rows),
        changed_status=sum(r['status'] != r['before_status'] for r in rows),
        set_commands=len(ops), batch_operations=len(batch), new_integration_ticket=INTEGRATION,
        no_baseline_ticket_closed=True)
    save('summary.json', summary)
    table = ['# All unfinished Mote dispositions', '',
             'Baseline:108. Each row has full completeness/correctness/staleness, evidence and next action in [assessments.json](assessments.json).', '',
             '| Ticket | Priority / status after review | Completeness | Next action |',
             '| --- | --- | --- | --- |']
    for r in rows:
        table.append('| '+r['id']+' — '+r['title'].replace('|','\\|')+' | P'+str(r['priority'])+' / '+r['status']+' | '+r['completeness']+' | '+r['next_action'].replace('|','\\|').replace('\n',' ')+' |')
    (OUT/'dispositions.md').write_text('\n'.join(table)+'\n')
    print(json.dumps(summary))


if __name__ == '__main__':
    main()
