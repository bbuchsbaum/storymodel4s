#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CHECKER="${PREMERGE_CHECKER:-$ROOT/tools/premerge-check.sh}"
REAL_GIT_BIN="$(command -v git)"
export REAL_GIT_BIN
TEST_DIR="$(mktemp -d "${TMPDIR:-/tmp}/storymodel-premerge.XXXXXX")"
trap 'rm -rf "$TEST_DIR"' EXIT

REPO="$TEST_DIR/repo"
BIN="$TEST_DIR/bin"
CANDIDATES="$TEST_DIR/candidates.json"
GATE_LOG="$TEST_DIR/gate.log"
SHOW_COUNT="$TEST_DIR/show-count"
MAIN_COUNT="$TEST_DIR/main-count"
WHO_COUNT="$TEST_DIR/who-count"
BRANCH_COUNT="$TEST_DIR/branch-count"
LIST_COUNT="$TEST_DIR/list-count"
mkdir -p "$REPO" "$BIN"

git -C "$REPO" init -q -b main
git -C "$REPO" config user.name "Premerge Court"
git -C "$REPO" config user.email "premerge-court@example.invalid"
printf 'base\n' > "$REPO/base.txt"
printf 'rename interaction base\n' > "$REPO/old.txt"
printf 'pure candidate rename source\n' > "$REPO/pure-rename-source.txt"
git -C "$REPO" add base.txt old.txt pure-rename-source.txt
git -C "$REPO" commit -q -m base
BASE_OID="$(git -C "$REPO" rev-parse HEAD)"
git -C "$REPO" branch candidate-base

printf 'direct first-parent candidate\n' > "$REPO/bypass.txt"
git -C "$REPO" add bypass.txt
git -C "$REPO" commit -q -m "direct candidate commit"
BYPASS_OID="$(git -C "$REPO" rev-parse HEAD)"

git -C "$REPO" switch -q -c ordinary-merge-side
printf 'ordinary merge side\n' > "$REPO/ordinary-merge-side.txt"
git -C "$REPO" add ordinary-merge-side.txt
git -C "$REPO" commit -q -m "ordinary merge side"
ORDINARY_MERGE_SIDE_OID="$(git -C "$REPO" rev-parse HEAD)"
git -C "$REPO" switch -q main
git -C "$REPO" merge -q --no-ff -m "ordinary landing merge" ordinary-merge-side
MERGE_BYPASS_OID="$(git -C "$REPO" rev-parse HEAD)"

git -C "$REPO" mv old.txt renamed.txt
git -C "$REPO" commit -q -m "main renames candidate path"

git -C "$REPO" switch -q -c candidate candidate-base
printf 'candidate to merge\n' > "$REPO/candidate.txt"
printf 'segment-aware scope control\n' > "$REPO/foobar.txt"
printf 'candidate edits pre-rename path\n' > "$REPO/old.txt"
git -C "$REPO" mv pure-rename-source.txt pure-rename-destination.txt
NEWLINE_PATH=$'line\nbreak.txt'
printf 'newline path must retain raw identity\n' > "$REPO/$NEWLINE_PATH"
mkdir -p "$REPO/reserved"
printf 'human output must not be data\n' > "$REPO/reserved/no live reservations.txt"
git -C "$REPO" add -- candidate.txt foobar.txt old.txt "$NEWLINE_PATH" 'reserved/no live reservations.txt'
git -C "$REPO" commit -q -m "ordinary candidate"
CANDIDATE_OID="$(git -C "$REPO" rev-parse HEAD)"
git -C "$REPO" switch -q main

MAIN_OID="$(git -C "$REPO" rev-parse main)"
MERGED_TREE="$(git -C "$REPO" merge-tree --write-tree "$MAIN_OID" "$CANDIDATE_OID")"

write_gate_log_values() {
  local totals_line="$1"
  local exit_status="$2"
  local bound_main="$3"
  local bound_candidate="$4"
  local bound_tree="$5"
  printf '%s\nGATE_MAIN=%s\nGATE_CANDIDATE=%s\nGATE_TREE=%s\nGATE_EXIT=%s\n' \
    "$totals_line" "$bound_main" "$bound_candidate" "$bound_tree" "$exit_status" > "$GATE_LOG"
}

write_gate_log() {
  write_gate_log_values "$1" "$2" "$MAIN_OID" "$CANDIDATE_OID" "$MERGED_TREE"
}

write_gate_log 'Passed: Total 1, Failed 0, Errors 0, Passed 1' 0

cat > "$BIN/git" <<'GIT'
#!/usr/bin/env bash
set -euo pipefail
if [ "${GIT_MAIN_ADVANCE_AFTER_FIRST:-0}" = 1 ] &&
    [ "${1:-}" = rev-parse ] && [ "${2:-}" = --verify ] && [ "${3:-}" = 'main^{commit}' ]; then
  count=0
  if [ -f "$GIT_MAIN_COUNT_FILE" ]; then read -r count < "$GIT_MAIN_COUNT_FILE"; fi
  count=$((count + 1))
  printf '%s\n' "$count" > "$GIT_MAIN_COUNT_FILE"
  if [ "$count" -gt 1 ]; then
    printf '%s\n' "$GIT_ADVANCED_MAIN_OID"
    exit 0
  fi
fi
if [ "${GIT_BRANCH_FAIL_AFTER_OUTPUT:-0}" = 1 ] &&
    [ "${1:-}" = symbolic-ref ] && [ "${2:-}" = --quiet ] && [ "${3:-}" = HEAD ]; then
  printf 'refs/heads/main\n'
  exit 9
fi
if [ "${GIT_DETACHED_ON_FINAL:-0}" = 1 ] &&
    [ "${1:-}" = symbolic-ref ] && [ "${2:-}" = --quiet ] && [ "${3:-}" = HEAD ]; then
  count=0
  if [ -f "$GIT_BRANCH_COUNT_FILE" ]; then read -r count < "$GIT_BRANCH_COUNT_FILE"; fi
  count=$((count + 1))
  printf '%s\n' "$count" > "$GIT_BRANCH_COUNT_FILE"
  if [ "$count" -gt 1 ]; then
    printf 'HEAD\n'
    exit 0
  fi
fi
if [ "${GIT_OTHER_BRANCH_ON_FINAL:-0}" = 1 ] &&
    [ "${1:-}" = symbolic-ref ] && [ "${2:-}" = --quiet ] && [ "${3:-}" = HEAD ]; then
  count=0
  if [ -f "$GIT_BRANCH_COUNT_FILE" ]; then read -r count < "$GIT_BRANCH_COUNT_FILE"; fi
  count=$((count + 1))
  printf '%s\n' "$count" > "$GIT_BRANCH_COUNT_FILE"
  if [ "$count" -gt 1 ]; then
    printf 'refs/heads/same-tip-side-branch\n'
    exit 0
  fi
fi
if [ "${GIT_DIFF_FAIL_AFTER_OUTPUT:-0}" = 1 ] &&
    [ "${1:-}" = diff ] && [ "${2:-}" = --name-only ]; then
  "$REAL_GIT_BIN" "$@"
  exit 9
fi
if [ "${GIT_DIFF_MANIFEST_MODE:-}" = unterminated_only ] &&
    [ "${1:-}" = diff ] && [ "${2:-}" = --name-only ] && [ "${3:-}" = -z ]; then
  printf 'candidate.txt'
  exit 0
fi
if [ "${GIT_DIFF_MANIFEST_MODE:-}" = valid_prefix_unterminated_tail ] &&
    [ "${1:-}" = diff ] && [ "${2:-}" = --name-only ] && [ "${3:-}" = -z ]; then
  printf 'candidate.txt\0reserved/no live reservations.txt'
  exit 0
fi
if [ "${GIT_SYMBOLIC_MERGE_TREE:-0}" = 1 ] &&
    [ "${1:-}" = merge-tree ] && [ "${2:-}" = --write-tree ]; then
  printf 'main\n'
  exit 0
fi
if [ "${GIT_REV_LIST_FAIL_AFTER_OUTPUT:-0}" = 1 ] &&
    [ "${1:-}" = rev-list ]; then
  "$REAL_GIT_BIN" "$@"
  exit 9
fi
if [ "${GIT_REV_LIST_MALFORMED:-0}" = 1 ] &&
    [ "${1:-}" = rev-list ]; then
  printf 'not-an-object plausible-parent\n'
  exit 0
fi
if [ "${GIT_FORCE_SHALLOW:-0}" = 1 ] &&
    [ "${1:-}" = rev-parse ] && [ "${2:-}" = --is-shallow-repository ]; then
  printf 'true\n'
  exit 0
fi
exec "$REAL_GIT_BIN" "$@"
GIT
chmod +x "$BIN/git"

cat > "$BIN/mote" <<'MOTE'
#!/usr/bin/env bash
set -euo pipefail
case "${1:-}" in
  actor)
    [ "${2:-}" = show ] || exit 64
    if [ "${MOTE_ACTOR_SHOW_FAIL:-0}" = 1 ]; then
      exit 9
    fi
    printf '{"actor":"%s","source":"test"}\n' "${MOTE_ACTOR_ID:-chief-test}"
    ;;
  candidate)
    case "${2:-}" in
      show)
        case " $* " in
          *" --json "*) ;;
          *)
            printf 'candidate %s\nrepositories: proposal=repo-test landing=repo-test\n' "${3:-<missing>}"
            exit 0
            ;;
        esac
        candidate_id="${MOTE_EXPECTED_CANDIDATE:-${3:-<missing>}}"
        commit_oid="${MOTE_EXPECTED_COMMIT:-<missing>}"
        grantee="${MOTE_SHOW_GRANTEE:-${MOTE_ACTOR_ID:-chief-test}}"
        gate_digest="${MOTE_GATE_DIGEST_OVERRIDE:-${MOTE_GATE_DIGEST:-missing}}"
        gate_producer="${MOTE_GATE_PRODUCER:-${MOTE_ACTOR_ID:-chief-test}}"
        gate_ref="${MOTE_GATE_REF:-$commit_oid}"
        landing_repository_id="${MOTE_LANDING_REPOSITORY_ID:-repo-test}"
        store_id="${MOTE_CANDIDATE_STORE_ID:-${MOTE_STORE_ID:-store-test}}"
        object_format="${MOTE_OBJECT_FORMAT:-sha1}"
        policy_paths="${MOTE_POLICY_PATHS_JSON:-[\"candidate.txt\",\"foobar.txt\",\"line\\nbreak.txt\",\"old.txt\",\"pure-rename-destination.txt\",\"pure-rename-source.txt\",\"renamed.txt\",\"reserved/no live reservations.txt\"]}"
        gate_item="$(printf '{"candidate_oid":"%s","evidence_kind":"external","name":"chief-merged-tree-gate","outcome":"pass","payload":{"kind":"external","digest":"%s"},"producer":"%s","refs":["%s"]}' \
          "$commit_oid" "$gate_digest" "$gate_producer" "$gate_ref")"
        if [ "${MOTE_GATE_DUPLICATE:-0}" = 1 ]; then
          evidence="[$gate_item,$gate_item]"
        else
          evidence="[$gate_item]"
        fi
        show_mode="${MOTE_SHOW_MODE:-landable}"
        if [ "${MOTE_BLOCK_ON_SECOND_SHOW:-0}" = 1 ] && [ -n "${MOTE_SHOW_COUNT_FILE:-}" ]; then
          show_count=0
          if [ -f "$MOTE_SHOW_COUNT_FILE" ]; then read -r show_count < "$MOTE_SHOW_COUNT_FILE"; fi
          show_count=$((show_count + 1))
          printf '%s\n' "$show_count" > "$MOTE_SHOW_COUNT_FILE"
          if [ "$show_count" -ge 2 ]; then show_mode=blocked; fi
        fi
        case "$show_mode" in
          landable)
            printf '{"candidate_id":"%s","identity":{"commit_oid":"%s","landing_repository_id":"%s","store_id":"%s","object_format":"%s"},"phase":{"value":"pending"},"policy":{"paths":%s},"authorization":{"status":"granted","grantees":["%s"]},"landability":{"landable":true,"reason_codes":[],"reasons":[]},"evidence":%s}\n' \
              "$candidate_id" "$commit_oid" "$landing_repository_id" "$store_id" "$object_format" "$policy_paths" "$grantee" "$evidence"
            ;;
          blocked)
            printf '{"candidate_id":"%s","identity":{"commit_oid":"%s","landing_repository_id":"%s","store_id":"%s","object_format":"%s"},"phase":{"value":"pending"},"policy":{"paths":%s},"authorization":null,"landability":{"landable":false,"reason_codes":["review_blocking"],"reasons":[{"code":"review_blocking","subject":"reviewer","detail":"latest verdict is block"}]},"evidence":%s}\n' \
              "$candidate_id" "$commit_oid" "$landing_repository_id" "$store_id" "$object_format" "$policy_paths" "$evidence"
            ;;
          contradictory)
            printf '{"candidate_id":"%s","identity":{"commit_oid":"%s","landing_repository_id":"%s","store_id":"%s","object_format":"%s"},"phase":{"value":"pending"},"policy":{"paths":%s},"authorization":{"status":"granted","grantees":["%s"]},"landability":{"landable":true,"reason_codes":["review_blocking"],"reasons":[]},"evidence":%s}\n' \
              "$candidate_id" "$commit_oid" "$landing_repository_id" "$store_id" "$object_format" "$policy_paths" "$grantee" "$evidence"
            ;;
          wrong_candidate)
            printf '{"candidate_id":"cand-other","identity":{"commit_oid":"%s","landing_repository_id":"%s","store_id":"%s","object_format":"%s"},"phase":{"value":"pending"},"policy":{"paths":%s},"authorization":{"status":"granted","grantees":["%s"]},"landability":{"landable":true,"reason_codes":[],"reasons":[]},"evidence":%s}\n' \
              "$commit_oid" "$landing_repository_id" "$store_id" "$object_format" "$policy_paths" "$grantee" "$evidence"
            ;;
          wrong_commit)
            printf '{"candidate_id":"%s","identity":{"commit_oid":"0000000000000000000000000000000000000000","landing_repository_id":"%s","store_id":"%s","object_format":"%s"},"phase":{"value":"pending"},"policy":{"paths":%s},"authorization":{"status":"granted","grantees":["%s"]},"landability":{"landable":true,"reason_codes":[],"reasons":[]},"evidence":%s}\n' \
              "$candidate_id" "$landing_repository_id" "$store_id" "$object_format" "$policy_paths" "$grantee" "$evidence"
            ;;
          no_authorization)
            printf '{"candidate_id":"%s","identity":{"commit_oid":"%s","landing_repository_id":"%s","store_id":"%s","object_format":"%s"},"phase":{"value":"pending"},"policy":{"paths":%s},"authorization":null,"landability":{"landable":true,"reason_codes":[],"reasons":[]},"evidence":%s}\n' \
              "$candidate_id" "$commit_oid" "$landing_repository_id" "$store_id" "$object_format" "$policy_paths" "$evidence"
            ;;
          duplicate_landability)
            printf '{"candidate_id":"%s","identity":{"commit_oid":"%s","landing_repository_id":"%s","store_id":"%s","object_format":"%s"},"phase":{"value":"pending"},"policy":{"paths":%s},"authorization":{"status":"granted","grantees":["%s"]},"landability":{"landable":false,"reason_codes":["review_blocking"],"reasons":[{"code":"review_blocking"}]},"landability":{"landable":true,"reason_codes":[],"reasons":[]},"evidence":%s}\n' \
              "$candidate_id" "$commit_oid" "$landing_repository_id" "$store_id" "$object_format" "$policy_paths" "$grantee" "$evidence"
            ;;
          duplicate_authorization)
            printf '{"candidate_id":"%s","identity":{"commit_oid":"%s","landing_repository_id":"%s","store_id":"%s","object_format":"%s"},"phase":{"value":"pending"},"policy":{"paths":%s},"authorization":null,"authorization":{"status":"granted","grantees":["%s"]},"landability":{"landable":true,"reason_codes":[],"reasons":[]},"evidence":%s}\n' \
              "$candidate_id" "$commit_oid" "$landing_repository_id" "$store_id" "$object_format" "$policy_paths" "$grantee" "$evidence"
            ;;
          duplicate_phase)
            printf '{"candidate_id":"%s","identity":{"commit_oid":"%s","landing_repository_id":"%s","store_id":"%s","object_format":"%s"},"phase":{"value":"superseded"},"phase":{"value":"pending"},"policy":{"paths":%s},"authorization":{"status":"granted","grantees":["%s"]},"landability":{"landable":true,"reason_codes":[],"reasons":[]},"evidence":%s}\n' \
              "$candidate_id" "$commit_oid" "$landing_repository_id" "$store_id" "$object_format" "$policy_paths" "$grantee" "$evidence"
            ;;
          malformed)
            printf '{'
            ;;
          fail)
            exit 8
            ;;
          *) exit 64 ;;
        esac
        ;;
      list)
        if [ "${MOTE_LIST_FAIL:-0}" = 1 ]; then
          exit 9
        fi
        list_count=0
        if [ -n "${MOTE_LIST_COUNT_FILE:-}" ]; then
          if [ -f "$MOTE_LIST_COUNT_FILE" ]; then read -r list_count < "$MOTE_LIST_COUNT_FILE"; fi
          list_count=$((list_count + 1))
          printf '%s\n' "$list_count" > "$MOTE_LIST_COUNT_FILE"
        fi
        if [ "${MOTE_LIST_BLOCK_ON_SECOND:-0}" = 1 ] && [ "$list_count" -gt 1 ]; then
          printf '[{"candidate_id":"cand-late-bypass","identity":{"commit_oid":"%s","landing_repository_id":"repo-test","store_id":"store-test","object_format":"sha1"},"phase":{"value":"pending"}}]\n' "$MOTE_BYPASS_OID"
          exit 0
        fi
        if [ "${MOTE_LIST_MALFORMED:-0}" = 1 ]; then
          printf '{'
        else
          command cat "$MOTE_CANDIDATES_FILE"
        fi
        ;;
      *) exit 64 ;;
    esac
    ;;
  audit)
    if [ "${MOTE_AUDIT_FAIL:-0}" = 1 ]; then exit 9; fi
    target_oid="${MOTE_AUDIT_TARGET_OID:-<missing>}"
    if [ "${MOTE_AUDIT_DUPLICATE_CONTEXT:-0}" = 1 ]; then
      printf '{"context":{"store_id":"store-foreign","target_ref":"main","target_oid":"%s","repository_id":"repo-foreign"},"context":{"store_id":"%s","target_ref":"main","target_oid":"%s","repository_id":"%s"}}\n' \
        "$target_oid" "${MOTE_STORE_ID:-store-test}" "$target_oid" "${MOTE_AUDIT_REPOSITORY_ID:-repo-test}"
    else
      printf '{"context":{"store_id":"%s","target_ref":"main","target_oid":"%s","repository_id":"%s"},"findings":[]}\n' \
        "${MOTE_STORE_ID:-store-test}" "$target_oid" "${MOTE_AUDIT_REPOSITORY_ID:-repo-test}"
    fi
    ;;
  who-has)
    path="${2:-<missing>}"
    reserved=0
    who_count=0
    if [ -n "${MOTE_WHO_COUNT_FILE:-}" ]; then
      if [ -f "$MOTE_WHO_COUNT_FILE" ]; then read -r who_count < "$MOTE_WHO_COUNT_FILE"; fi
      who_count=$((who_count + 1))
      printf '%s\n' "$who_count" > "$MOTE_WHO_COUNT_FILE"
    fi
    if [ "${MOTE_RESERVE_NEWLINE:-0}" = 1 ] && [ "$path" = $'line\nbreak.txt' ]; then reserved=1; fi
    if [ "${MOTE_RESERVE_INJECTABLE:-0}" = 1 ] && [ "$path" = 'reserved/no live reservations.txt' ]; then reserved=1; fi
    if [ "${MOTE_RESERVE_RENAMED:-0}" = 1 ] && [ "$path" = 'renamed.txt' ]; then reserved=1; fi
    if [ "${MOTE_RESERVE_RENAME_SOURCE:-0}" = 1 ] && [ "$path" = 'pure-rename-source.txt' ]; then reserved=1; fi
    if [ "${MOTE_LATE_RESERVATION:-0}" = 1 ] && [ "$who_count" -gt "${MOTE_RESERVE_AFTER_N:-6}" ] && [ "$path" = 'candidate.txt' ]; then reserved=1; fi
    case " $* " in
      *" --json "*)
        if [ "${MOTE_WHO_HAS_DUPLICATE_KEY:-0}" = 1 ]; then
          escaped_path="$(python3 -c 'import json,sys; print(json.dumps(sys.argv[1]))' "$path")"
          printf '[{"actor":"hidden-holder","actor":"adversarial-holder","entity":"bd-reserved","path":%s,"reservation_id":"rv-reserved"}]\n' "$escaped_path"
        elif [ "$reserved" -eq 1 ]; then
          escaped_path="$(python3 -c 'import json,sys; print(json.dumps(sys.argv[1]))' "$path")"
          printf '[{"actor":"adversarial-holder","disposition":"active","entity":"bd-reserved","lease_until_ts":"2099-01-01T00:00:00Z","path":%s,"reservation_id":"rv-reserved"}]\n' "$escaped_path"
        else
          printf '[]\n'
        fi
        ;;
      *)
        if [ "$reserved" -eq 1 ]; then
          printf 'live reservation by adversarial-holder on %s\n' "$path"
        else
          printf 'no live reservations overlap %s\n' "$path"
        fi
        ;;
    esac
    if [ "${MOTE_WHO_HAS_FAIL_AFTER_CLEAR:-0}" = 1 ] && [ "$reserved" -eq 0 ]; then
        exit 9
    fi
    ;;
  discuss)
    [ "${2:-}" = unread ] || exit 64
    if [ "${MOTE_UNREAD_FAIL:-0}" = 1 ]; then
      exit 9
    fi
    if [ "${MOTE_UNREAD_MALFORMED:-0}" = 1 ]; then
      printf '{'
    elif [ "${MOTE_UNREAD_DUPLICATE_PAGE_KEY:-0}" = 1 ]; then
      printf '{"posts":[],"page":{"count":0,"has_older":true,"has_older":false,"has_newer":false}}\n'
    elif [ "${MOTE_UNREAD_HAS_OLDER:-0}" = 1 ]; then
      printf '{"posts":[{"post_id":"post-held","topic":"coordination","from":"reviewer","body":"do not merge"}],"page":{"count":1,"has_older":true,"has_newer":false}}\n'
    elif [ "${MOTE_UNREAD_ONE:-0}" = 1 ]; then
      printf '{"posts":[{"post_id":"post-new","topic":"coordination","from":"reviewer","body":"fresh full body"}],"page":{"count":1,"has_older":false,"has_newer":false}}\n'
    else
      printf '{"posts":[],"page":{"count":0,"has_older":false,"has_newer":false}}\n'
    fi
    ;;
  *) exit 64 ;;
esac
MOTE
chmod +x "$BIN/mote"

write_phase() {
  local phase="$1"
  printf '[{"candidate_id":"cand-bypass","identity":{"commit_oid":"%s","landing_repository_id":"repo-test","store_id":"store-test","object_format":"sha1"},"phase":{"value":"%s"}}]\n' \
    "$BYPASS_OID" "$phase" > "$CANDIDATES"
}

write_candidate_row() {
  local candidate_id="$1"
  local oid="$2"
  local phase="$3"
  printf '[{"candidate_id":"%s","identity":{"commit_oid":"%s","landing_repository_id":"repo-test","store_id":"store-test","object_format":"sha1"},"phase":{"value":"%s"}}]\n' \
    "$candidate_id" "$oid" "$phase" > "$CANDIDATES"
}

write_foreign_terminal_row() {
  local phase="$1"
  printf '[{"candidate_id":"cand-bypass","identity":{"commit_oid":"%s","landing_repository_id":"repo-foreign","store_id":"store-test","object_format":"sha1"},"phase":{"value":"%s"}}]\n' \
    "$BYPASS_OID" "$phase" > "$CANDIDATES"
}

write_bridged_terminal_row() {
  local phase="${1:-landed_out_of_band}"
  local target_oid="${2:-$MAIN_OID}"
  local expected_op="${3:-op-foreign}"
  local observed_parent="${4:-$BASE_OID}"
  printf '[{"candidate_id":"cand-bypass","identity":{"commit_oid":"%s","parent_oids":["%s"],"landing_repository_id":"repo-foreign","landing_repository_op_id":"op-foreign","store_id":"store-test","object_format":"sha1"},"phase":{"value":"%s"},"reconciliation":{"target_ref":"main","target_oid":"%s","repository_bridge":{"expect_landing_repository_op_id":"%s","object_availability":{"candidate_oid":"%s","observed_parent_oids":["%s"],"repository_id":"repo-test","object_format":"sha1","object_available":true}}}}]\n' \
    "$BYPASS_OID" "$BASE_OID" "$phase" "$target_oid" "$expected_op" "$BYPASS_OID" "$observed_parent" > "$CANDIDATES"
}

write_local_landed_plus_foreign_sha256() {
  printf '[{"candidate_id":"cand-bypass","identity":{"commit_oid":"%s","landing_repository_id":"repo-test","store_id":"store-test","object_format":"sha1"},"phase":{"value":"landed"}},{"candidate_id":"cand-foreign-sha256","identity":{"commit_oid":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","landing_repository_id":"repo-foreign","store_id":"store-test","object_format":"sha256"},"phase":{"value":"landed"}}]\n' \
    "$BYPASS_OID" > "$CANDIDATES"
}

write_duplicate_phase_keys() {
  printf '[{"candidate_id":"cand-bypass","identity":{"commit_oid":"%s","landing_repository_id":"repo-test","store_id":"store-test","object_format":"sha1"},"phase":{"value":"superseded"},"phase":{"value":"landed"}}]\n' \
    "$BYPASS_OID" > "$CANDIDATES"
}

write_missing_phase() {
  printf '[{"candidate_id":"cand-bypass","identity":{"commit_oid":"%s","landing_repository_id":"repo-test","store_id":"store-test","object_format":"sha1"}}]\n' \
    "$BYPASS_OID" > "$CANDIDATES"
}

write_missing_candidate_id() {
  printf '[{"identity":{"commit_oid":"%s","landing_repository_id":"repo-test","store_id":"store-test","object_format":"sha1"},"phase":{"value":"landed"}}]\n' \
    "$BYPASS_OID" > "$CANDIDATES"
}

write_empty_candidate_id() {
  printf '[{"candidate_id":"","identity":{"commit_oid":"%s","landing_repository_id":"repo-test","store_id":"store-test","object_format":"sha1"},"phase":{"value":"landed"}}]\n' \
    "$BYPASS_OID" > "$CANDIDATES"
}

write_missing_oid() {
  printf '[{"candidate_id":"cand-bypass","identity":{"landing_repository_id":"repo-test","store_id":"store-test","object_format":"sha1"},"phase":{"value":"landed"}}]\n' \
    > "$CANDIDATES"
}

write_valid_landed_plus_malformed_identity() {
  printf '[{"candidate_id":"cand-bypass","identity":{"commit_oid":"%s","landing_repository_id":"repo-test","store_id":"store-test","object_format":"sha1"},"phase":{"value":"landed"}},{"identity":{"commit_oid":"%s","landing_repository_id":"repo-test","store_id":"store-test","object_format":"sha1"},"phase":{"value":"landed"}}]\n' \
    "$BYPASS_OID" "$BYPASS_OID" > "$CANDIDATES"
}

write_duplicate_landed() {
  printf '[{"candidate_id":"cand-bypass-a","identity":{"commit_oid":"%s","landing_repository_id":"repo-test","store_id":"store-test","object_format":"sha1"},"phase":{"value":"landed"}},{"candidate_id":"cand-bypass-b","identity":{"commit_oid":"%s","landing_repository_id":"repo-test","store_id":"store-test","object_format":"sha1"},"phase":{"value":"landed_out_of_band"}}]\n' \
    "$BYPASS_OID" "$BYPASS_OID" > "$CANDIDATES"
}

write_malformed_oid() {
  printf '[{"candidate_id":"cand-bypass","identity":{"commit_oid":"not-an-oid","landing_repository_id":"repo-test","store_id":"store-test","object_format":"sha1"},"phase":{"value":"landed"}}]\n' \
    > "$CANDIDATES"
}

write_conflicting_oids() {
  printf '[{"candidate_id":"cand-bypass","commit_oid":"%s","identity":{"commit_oid":"%s","landing_repository_id":"repo-test","store_id":"store-test","object_format":"sha1"},"phase":{"value":"landed"}}]\n' \
    "$CANDIDATE_OID" "$BYPASS_OID" > "$CANDIDATES"
}

write_duplicate_candidate_ids() {
  printf '[{"candidate_id":"cand-duplicate","identity":{"commit_oid":"%s","landing_repository_id":"repo-test","store_id":"store-test","object_format":"sha1"},"phase":{"value":"landed"}},{"candidate_id":"cand-duplicate","identity":{"commit_oid":"%s","landing_repository_id":"repo-test","store_id":"store-test","object_format":"sha1"},"phase":{"value":"landed"}}]\n' \
    "$BYPASS_OID" "$CANDIDATE_OID" > "$CANDIDATES"
}

gate_digest() {
  python3 -c 'import hashlib,pathlib,sys; print(hashlib.sha256(pathlib.Path(sys.argv[1]).read_bytes()).hexdigest())' \
    "$GATE_LOG"
}

run_checker() {
  local gate_digest
  gate_digest="$(gate_digest)"
  rm -f "$SHOW_COUNT" "$MAIN_COUNT" "$WHO_COUNT" "$BRANCH_COUNT" "$LIST_COUNT"
  (
    cd "$REPO"
    PATH="$BIN:$PATH" MOTE_CANDIDATES_FILE="$CANDIDATES" \
      MOTE_EXPECTED_CANDIDATE=cand-current MOTE_EXPECTED_COMMIT="$CANDIDATE_OID" \
      MOTE_ACTOR_ID=chief-test MOTE_ACTOR=chief-test MOTE_GATE_DIGEST="$gate_digest" \
      MOTE_AUDIT_TARGET_OID="$MAIN_OID" MOTE_STORE_ID=store-test \
      MOTE_WHO_COUNT_FILE="$WHO_COUNT" GIT_MAIN_COUNT_FILE="$MAIN_COUNT" \
      GIT_BRANCH_COUNT_FILE="$BRANCH_COUNT" MOTE_LIST_COUNT_FILE="$LIST_COUNT" \
      MOTE_BYPASS_OID="$BYPASS_OID" \
      GIT_ADVANCED_MAIN_OID="$BASE_OID" \
      MOTE_BLOCK_ON_SECOND_SHOW="${MOTE_BLOCK_ON_SECOND_SHOW:-0}" \
      MOTE_SHOW_COUNT_FILE="$SHOW_COUNT" \
      bash "$CHECKER" cand-current "$CANDIDATE_OID" "$GATE_LOG"
  ) 2>&1
}

run_checker_with_show_mode() {
  local mode="$1"
  local gate_digest
  gate_digest="$(gate_digest)"
  rm -f "$SHOW_COUNT" "$MAIN_COUNT" "$WHO_COUNT" "$BRANCH_COUNT" "$LIST_COUNT"
  (
    cd "$REPO"
    PATH="$BIN:$PATH" MOTE_CANDIDATES_FILE="$CANDIDATES" MOTE_SHOW_MODE="$mode" \
      MOTE_EXPECTED_CANDIDATE=cand-current MOTE_EXPECTED_COMMIT="$CANDIDATE_OID" \
      MOTE_ACTOR_ID=chief-test MOTE_ACTOR=chief-test MOTE_GATE_DIGEST="$gate_digest" \
      MOTE_AUDIT_TARGET_OID="$MAIN_OID" MOTE_STORE_ID=store-test \
      MOTE_WHO_COUNT_FILE="$WHO_COUNT" GIT_MAIN_COUNT_FILE="$MAIN_COUNT" \
      GIT_BRANCH_COUNT_FILE="$BRANCH_COUNT" MOTE_LIST_COUNT_FILE="$LIST_COUNT" \
      MOTE_BYPASS_OID="$BYPASS_OID" \
      GIT_ADVANCED_MAIN_OID="$BASE_OID" \
      MOTE_BLOCK_ON_SECOND_SHOW="${MOTE_BLOCK_ON_SECOND_SHOW:-0}" \
      MOTE_SHOW_COUNT_FILE="$SHOW_COUNT" \
      bash "$CHECKER" cand-current "$CANDIDATE_OID" "$GATE_LOG"
  ) 2>&1
}

expect_pass() {
  local phase="$1"
  local output
  write_phase "$phase"
  if ! output="$(run_checker)"; then
    printf 'expected phase %s to pass, got:\n%s\n' "$phase" "$output" >&2
    exit 1
  fi
  printf '%s\n' "$output" | grep -q "reconciled first-parent candidate:.*($phase)"
}

expect_phase_failure() {
  local phase="$1"
  local output status
  write_phase "$phase"
  set +e
  output="$(run_checker)"
  status=$?
  set -e
  if [ "$status" -ne 3 ]; then
    printf 'expected phase %s to exit 3, got %s:\n%s\n' "$phase" "$status" "$output" >&2
    exit 1
  fi
  printf '%s\n' "$output" | grep -q "FAIL: gate bypass:.*phase=$phase"
}

expect_enumeration_failure() {
  local label="$1"
  local output status
  set +e
  output="$(run_checker)"
  status=$?
  set -e
  if [ "$status" -ne 3 ]; then
    printf 'expected %s to exit 3, got %s:\n%s\n' "$label" "$status" "$output" >&2
    exit 1
  fi
  printf '%s\n' "$output" | grep -q 'could not enumerate candidate commits'
  printf '%s\n' "$output" | grep -q 'PRE-MERGE CHECK FAILED'
}

expect_validation_failure() {
  local label="$1"
  local output status
  set +e
  output="$(run_checker)"
  status=$?
  set -e
  if [ "$status" -ne 3 ]; then
    printf 'expected %s to exit 3, got %s:\n%s\n' "$label" "$status" "$output" >&2
    exit 1
  fi
  printf '%s\n' "$output" | grep -q 'candidate or first-parent history validation failed'
  printf '%s\n' "$output" | grep -q 'PRE-MERGE CHECK FAILED'
}

expect_pass landed
expect_pass landed_out_of_band

# A foreign proposal/landing identity is not a terminal exemption in this
# checkout unless an out-of-band reconciliation binds the exact object,
# parents, landing-op CAS, target history, object format, and repository.
write_foreign_terminal_row landed_out_of_band
set +e
foreign_terminal_output="$(run_checker)"
foreign_terminal_status=$?
set -e
[ "$foreign_terminal_status" -eq 3 ]
printf '%s\n' "$foreign_terminal_output" | grep -q 'unbound terminal candidate'

write_bridged_terminal_row
bridged_terminal_output="$(run_checker)"
printf '%s\n' "$bridged_terminal_output" | grep -q 'reconciled first-parent candidate'

write_local_landed_plus_foreign_sha256
foreign_format_output="$(run_checker)"
printf '%s\n' "$foreign_format_output" | grep -q 'Mechanical checks passed'

for bridge_case in wrong_phase wrong_target wrong_target_older wrong_op wrong_parent; do
  case "$bridge_case" in
    wrong_phase) write_bridged_terminal_row landed ;;
    wrong_target) write_bridged_terminal_row landed_out_of_band "$CANDIDATE_OID" ;;
    wrong_target_older) write_bridged_terminal_row landed_out_of_band "$BASE_OID" ;;
    wrong_op) write_bridged_terminal_row landed_out_of_band "$MAIN_OID" op-other ;;
    wrong_parent) write_bridged_terminal_row landed_out_of_band "$MAIN_OID" op-foreign "$CANDIDATE_OID" ;;
  esac
  set +e
  bridge_output="$(run_checker)"
  bridge_status=$?
  set -e
  [ "$bridge_status" -eq 3 ]
  printf '%s\n' "$bridge_output" | grep -q 'unbound terminal candidate'
done
write_phase landed

# grep -c prints a zero even though no match gives it exit 1. The checker must not
# append a second zero and then skip its numeric guard on a malformed multiline value.
write_gate_log '' 0
write_phase landed
set +e
zero_totals_output="$(run_checker)"
zero_totals_status=$?
set -e
[ "$zero_totals_status" -eq 3 ]
printf '%s\n' "$zero_totals_output" | grep -q "ZERO 'Passed: Total' lines"
write_gate_log 'Passed: Total 1, Failed 0, Errors 0, Passed 1' 0

# A captured status is evidence only when it is zero. The former court checked merely
# that some marker existed, so GATE_EXIT=9 passed beside plausible test totals.
write_gate_log 'Passed: Total 1, Failed 0, Errors 0, Passed 1' 9
write_phase landed
set +e
nonzero_exit_output="$(run_checker)"
nonzero_exit_status=$?
set -e
[ "$nonzero_exit_status" -eq 3 ]
printf '%s\n' "$nonzero_exit_output" | grep -q 'gate log records nonzero command exit(s): GATE_EXIT=9'

# A zero from some unrelated command is not the overall gate status. The receipt
# must contain exactly one named GATE_EXIT; otherwise a backgrounded/forked gate
# can inherit an innocent command's status.
printf 'Passed: Total 1, Failed 0, Errors 0, Passed 1\nGATE_MAIN=%s\nGATE_CANDIDATE=%s\nGATE_TREE=%s\nUNRELATED_EXIT=0\n' \
  "$MAIN_OID" "$CANDIDATE_OID" "$MERGED_TREE" > "$GATE_LOG"
set +e
unrelated_exit_output="$(run_checker)"
unrelated_exit_status=$?
set -e
[ "$unrelated_exit_status" -eq 3 ]
printf '%s\n' "$unrelated_exit_output" | grep -q 'gate log could not be read or parsed'
write_gate_log 'Passed: Total 1, Failed 0, Errors 0, Passed 1' 0

for extra_exit in 'BROKEN_EXIT=not-a-status' 'GATE_EXIT=0'; do
  write_gate_log 'Passed: Total 1, Failed 0, Errors 0, Passed 1' 0
  printf '%s\n' "$extra_exit" >> "$GATE_LOG"
  set +e
  malformed_exit_output="$(run_checker)"
  malformed_exit_status=$?
  set -e
  [ "$malformed_exit_status" -eq 3 ]
  printf '%s\n' "$malformed_exit_output" | grep -q 'gate log could not be read or parsed'
done
write_gate_log 'Passed: Total 1, Failed 0, Errors 0, Passed 1' 0

write_gate_log 'Passed: Total 9, Failed 0, Errors 9, Passed 0' 0
write_phase landed
set +e
errors_output="$(run_checker)"
errors_status=$?
set -e
[ "$errors_status" -eq 3 ]
printf '%s\n' "$errors_output" | grep -q '0 failed and 9 errored test(s)'

# Python accepts arbitrary-precision receipt counts; Bash arithmetic does not.
# These values wrap to a plausible one-test, zero-failure result in fixed-width
# shell arithmetic, so the semantic decision must stay in the parser.
write_gate_log 'Passed: Total 18446744073709551617, Failed 18446744073709551616, Errors 0, Passed 1' 0
set +e
oversized_failure_output="$(run_checker)"
oversized_failure_status=$?
set -e
[ "$oversized_failure_status" -eq 3 ]
printf '%s\n' "$oversized_failure_output" | grep -q '18446744073709551616 failed and 0 errored test(s)'

write_gate_log 'Passed: Total 2, Failed 0, Errors 0, Passed 1' 0
set +e
inconsistent_totals_output="$(run_checker)"
inconsistent_totals_status=$?
set -e
[ "$inconsistent_totals_status" -eq 3 ]
printf '%s\n' "$inconsistent_totals_output" | grep -q 'gate log could not be read or parsed'

for malformed_totals in \
  'Passed: Total 1' \
  'No Passed: Total receipt; Failed 0'; do
  write_gate_log "$malformed_totals" 0
  set +e
  malformed_totals_output="$(run_checker)"
  malformed_totals_status=$?
  set -e
  [ "$malformed_totals_status" -eq 3 ]
  printf '%s\n' "$malformed_totals_output" | grep -q 'gate log could not be read or parsed'
done
write_gate_log 'Passed: Total 1, Failed 0, Errors 0, Passed 1' 0

for binding_case in main candidate tree; do
  case "$binding_case" in
    main)
      write_gate_log_values 'Passed: Total 1, Failed 0, Errors 0, Passed 1' 0 \
        0000000000000000000000000000000000000000 "$CANDIDATE_OID" "$MERGED_TREE"
      ;;
    candidate)
      write_gate_log_values 'Passed: Total 1, Failed 0, Errors 0, Passed 1' 0 \
        "$MAIN_OID" 0000000000000000000000000000000000000000 "$MERGED_TREE"
      ;;
    tree)
      write_gate_log_values 'Passed: Total 1, Failed 0, Errors 0, Passed 1' 0 \
        "$MAIN_OID" "$CANDIDATE_OID" 0000000000000000000000000000000000000000
      ;;
  esac
  set +e
  binding_output="$(run_checker)"
  binding_status=$?
  set -e
  [ "$binding_status" -eq 3 ]
  printf '%s\n' "$binding_output" | grep -q 'gate log could not be read or parsed'
done
write_gate_log 'Passed: Total 1, Failed 0, Errors 0, Passed 1' 0

# Dereferenceability is not identity. A symbolic ref printed by merge-tree must
# not be accepted merely because `main^{tree}` happens to resolve.
set +e
symbolic_tree_output="$(GIT_SYMBOLIC_MERGE_TREE=1 run_checker)"
symbolic_tree_status=$?
set -e
[ "$symbolic_tree_status" -eq 3 ]
printf '%s\n' "$symbolic_tree_output" | grep -q 'cannot compute one exact clean merge-result tree'

set +e
unbound_gate_output="$(MOTE_GATE_DIGEST_OVERRIDE=0000000000000000000000000000000000000000000000000000000000000000 run_checker)"
unbound_gate_status=$?
set -e
[ "$unbound_gate_status" -eq 3 ]
printf '%s\n' "$unbound_gate_output" | grep -q 'gate log digest is not bound to this candidate'

for evidence_case in producer ref duplicate; do
  set +e
  case "$evidence_case" in
    producer) evidence_output="$(MOTE_GATE_PRODUCER=not-the-chief run_checker)" ;;
    ref) evidence_output="$(MOTE_GATE_REF=0000000000000000000000000000000000000000 run_checker)" ;;
    duplicate) evidence_output="$(MOTE_GATE_DUPLICATE=1 run_checker)" ;;
  esac
  evidence_status=$?
  set -e
  [ "$evidence_status" -eq 3 ]
  printf '%s\n' "$evidence_output" | grep -q 'gate log digest is not bound to this candidate'
done

write_phase landed
for mode in blocked contradictory malformed fail wrong_candidate wrong_commit no_authorization \
  duplicate_landability duplicate_authorization duplicate_phase; do
  set +e
  show_output="$(run_checker_with_show_mode "$mode")"
  show_status=$?
  set -e
  if [ "$show_status" -ne 3 ]; then
    printf 'expected candidate-show mode %s to exit 3, got %s:\n%s\n' \
      "$mode" "$show_status" "$show_output" >&2
    exit 1
  fi
  if [ "$mode" = blocked ]; then
    printf '%s\n' "$show_output" | grep -q 'review_blocking: reviewer: latest verdict is block'
  elif [ "$mode" = fail ]; then
    printf '%s\n' "$show_output" | grep -q 'mote candidate show failed --'
  else
    printf '%s\n' "$show_output" | grep -q 'candidate show failed or returned invalid identity, scope, phase, or landability'
  fi
done

# A blocker can arrive after the first candidate snapshot while the remaining checks run.
# The final same-row/same-object reread must observe that change before declaring success.
write_phase landed
set +e
late_block_output="$(MOTE_BLOCK_ON_SECOND_SHOW=1 run_checker)"
late_block_status=$?
set -e
[ "$late_block_status" -eq 3 ]
printf '%s\n' "$late_block_output" | grep -q 'candidate became blocked during the gate'
printf '%s\n' "$late_block_output" | grep -q 'review_blocking: reviewer: latest verdict is block'

# A globally landable row is not authority for this actor. This specifically kills a
# checker that trusts landable=true without binding the current actor to the grantee set.
write_phase landed
set +e
wrong_grantee_output="$(MOTE_SHOW_GRANTEE=some-other-actor run_checker)"
wrong_grantee_status=$?
set -e
[ "$wrong_grantee_status" -eq 3 ]
printf '%s\n' "$wrong_grantee_output" | grep -q 'authorization_not_granted_to_actor: chief-test'

# Candidate identity is authority only in the repository/store/object format it
# names. Readable object bytes from another repository are not landing authority.
for identity_case in repository store object_format; do
  set +e
  case "$identity_case" in
    repository) identity_output="$(MOTE_LANDING_REPOSITORY_ID=repo-foreign run_checker)" ;;
    store) identity_output="$(MOTE_CANDIDATE_STORE_ID=store-foreign run_checker)" ;;
    object_format) identity_output="$(MOTE_OBJECT_FORMAT=sha256 run_checker)" ;;
  esac
  identity_status=$?
  set -e
  [ "$identity_status" -eq 3 ]
  printf '%s\n' "$identity_output" | grep -q 'invalid identity, scope, phase, or landability'
done

# Review scope is a path-segment claim: every conservative diff/effect path must
# be covered. `foo` must not cover `foobar.txt`; a directory scope may cover an
# actual descendant.
for scope_json in \
  '["candidate.txt"]' \
  '["candidate.txt","foo","line\nbreak.txt","old.txt","renamed.txt","reserved/no live reservations.txt"]'; do
  set +e
  scope_output="$(MOTE_POLICY_PATHS_JSON="$scope_json" run_checker)"
  scope_status=$?
  set -e
  [ "$scope_status" -eq 3 ]
  printf '%s\n' "$scope_output" | grep -q 'invalid identity, scope, phase, or landability'
done
directory_scope_output="$(MOTE_POLICY_PATHS_JSON='["candidate.txt","foobar.txt","line\nbreak.txt","old.txt","pure-rename-destination.txt","pure-rename-source.txt","renamed.txt","reserved"]' run_checker)"
printf '%s\n' "$directory_scope_output" | grep -q 'Mechanical checks passed'

# A pure rename has two governed paths. Rename detection commonly renders only
# the destination, so the checker must disable it and retain the deleted source
# in both policy and reservation checks.
set +e
rename_scope_output="$(MOTE_POLICY_PATHS_JSON='["candidate.txt","foobar.txt","line\nbreak.txt","old.txt","pure-rename-destination.txt","renamed.txt","reserved"]' run_checker)"
rename_scope_status=$?
set -e
[ "$rename_scope_status" -eq 3 ]
printf '%s\n' "$rename_scope_output" | grep -q 'invalid identity, scope, phase, or landability'

set +e
actor_failure_output="$(MOTE_ACTOR_SHOW_FAIL=1 run_checker)"
actor_failure_status=$?
set -e
[ "$actor_failure_status" -eq 3 ]
printf '%s\n' "$actor_failure_output" | grep -q 'landing authority cannot be checked'

set +e
audit_duplicate_output="$(MOTE_AUDIT_DUPLICATE_CONTEXT=1 run_checker)"
audit_duplicate_status=$?
set -e
[ "$audit_duplicate_status" -eq 3 ]
printf '%s\n' "$audit_duplicate_output" | grep -q 'mote audit failed or did not bind'

# Successful-looking output followed by a failing status is still a failed query.
set +e
who_has_failure_output="$(MOTE_WHO_HAS_FAIL_AFTER_CLEAR=1 run_checker)"
who_has_failure_status=$?
set -e
[ "$who_has_failure_status" -eq 3 ]
printf '%s\n' "$who_has_failure_output" | grep -q 'who-has failed for candidate.txt'

# Git path quoting must not change the identity passed to who-has. A newline-bearing
# path is the control that distinguishes a NUL-delimited manifest from display text.
set +e
newline_reservation_output="$(MOTE_RESERVE_NEWLINE=1 run_checker)"
newline_reservation_status=$?
set -e
[ "$newline_reservation_status" -eq 3 ]
printf '%s\n' "$newline_reservation_output" | grep -q 'adversarial-holder'

# Human text that happens to contain the old clear sentinel is not authority. This path
# was chosen so the vulnerable parser saw "no live reservations" inside the path itself.
set +e
injectable_reservation_output="$(MOTE_RESERVE_INJECTABLE=1 run_checker)"
injectable_reservation_status=$?
set -e
[ "$injectable_reservation_status" -eq 3 ]
printf '%s\n' "$injectable_reservation_output" | grep -q 'adversarial-holder'

# A rename/modify merge can move the landing effect away from every path in the
# candidate-side diff. The conservative union must therefore query renamed.txt.
set +e
renamed_reservation_output="$(MOTE_RESERVE_RENAMED=1 run_checker)"
renamed_reservation_status=$?
set -e
[ "$renamed_reservation_status" -eq 3 ]
printf '%s\n' "$renamed_reservation_output" | grep -q 'reserved path renamed.txt'

set +e
rename_source_reservation_output="$(MOTE_RESERVE_RENAME_SOURCE=1 run_checker)"
rename_source_reservation_status=$?
set -e
[ "$rename_source_reservation_status" -eq 3 ]
printf '%s\n' "$rename_source_reservation_output" | grep -q 'reserved path pure-rename-source.txt'

# Reservations can arrive after the initial sweep. A final sweep over the exact
# same immutable manifest must observe the late hold.
set +e
late_reservation_output="$(MOTE_LATE_RESERVATION=1 run_checker)"
late_reservation_status=$?
set -e
[ "$late_reservation_status" -eq 3 ]
printf '%s\n' "$late_reservation_output" | grep -q 'reserved path candidate.txt'
printf '%s\n' "$late_reservation_output" | grep -q '(final)\|PRE-MERGE CHECK FAILED'

# Duplicate object keys are ambiguous data, including in an otherwise plausible
# reservation response.
set +e
duplicate_who_output="$(MOTE_WHO_HAS_DUPLICATE_KEY=1 run_checker)"
duplicate_who_status=$?
set -e
[ "$duplicate_who_status" -eq 3 ]
printf '%s\n' "$duplicate_who_output" | grep -q 'who-has failed for candidate.txt'

# The checker has no errexit by design, so every Git observation that contributes to a
# pass must guard its own status even if a failing command emitted plausible output.
set +e
branch_failure_output="$(GIT_BRANCH_FAIL_AFTER_OUTPUT=1 run_checker)"
branch_failure_status=$?
set -e
[ "$branch_failure_status" -eq 3 ]
printf '%s\n' "$branch_failure_output" | grep -q 'cannot determine the current branch'

# The OID can remain unchanged while HEAD becomes detached or moves to another
# same-tip branch. Final validation must retain the symbolic main attachment.
set +e
detached_final_output="$(GIT_DETACHED_ON_FINAL=1 run_checker)"
detached_final_status=$?
set -e
[ "$detached_final_status" -eq 3 ]
printf '%s\n' "$detached_final_output" | grep -q 'no longer symbolically attached to main'

set +e
other_branch_final_output="$(GIT_OTHER_BRANCH_ON_FINAL=1 run_checker)"
other_branch_final_status=$?
set -e
[ "$other_branch_final_status" -eq 3 ]
printf '%s\n' "$other_branch_final_output" | grep -q 'no longer symbolically attached to main'

set +e
diff_failure_output="$(GIT_DIFF_FAIL_AFTER_OUTPUT=1 run_checker)"
diff_failure_status=$?
set -e
[ "$diff_failure_status" -eq 3 ]
printf '%s\n' "$diff_failure_output" | grep -q 'cannot enumerate paths changed by'

# A successful `git diff -z` status does not prove that its byte stream ended at
# a record boundary. Both an entirely unterminated path and a valid prefix with a
# hidden reserved tail must fail before reservation/scope checks can pass.
for manifest_mode in unterminated_only valid_prefix_unterminated_tail; do
  set +e
  manifest_output="$(GIT_DIFF_MANIFEST_MODE="$manifest_mode" run_checker)"
  manifest_status=$?
  set -e
  [ "$manifest_status" -eq 3 ]
  printf '%s\n' "$manifest_output" | grep -q 'manifests are empty, malformed, unterminated, or non-UTF-8'
done

set +e
rev_list_failure_output="$(GIT_REV_LIST_FAIL_AFTER_OUTPUT=1 run_checker)"
rev_list_failure_status=$?
set -e
[ "$rev_list_failure_status" -eq 3 ]
printf '%s\n' "$rev_list_failure_output" | grep -q "could not enumerate main's first-parent history"

set +e
malformed_history_output="$(GIT_REV_LIST_MALFORMED=1 run_checker)"
malformed_history_status=$?
set -e
[ "$malformed_history_status" -eq 3 ]
printf '%s\n' "$malformed_history_output" | grep -q 'candidate or first-parent history validation failed'

set +e
shallow_output="$(GIT_FORCE_SHALLOW=1 run_checker)"
shallow_status=$?
set -e
[ "$shallow_status" -eq 3 ]
printf '%s\n' "$shallow_output" | grep -q 'repository history is shallow'

# Every downstream observation is bound to the first main receipt. A second
# independently sampled main must not quietly replace it after the gate log has
# already been verified.
set +e
advanced_main_output="$(GIT_MAIN_ADVANCE_AFTER_FIRST=1 run_checker)"
advanced_main_status=$?
set -e
[ "$advanced_main_status" -eq 3 ]
printf '%s\n' "$advanced_main_output" | grep -q 'main advanced or became unreadable'

# A global candidate can become a first-parent bypass after the initial scan
# without changing the row being landed. The final global reread must catch it.
set +e
late_global_output="$(MOTE_LIST_BLOCK_ON_SECOND=1 run_checker)"
late_global_status=$?
set -e
[ "$late_global_status" -eq 3 ]
printf '%s\n' "$late_global_output" | grep -q 'gate bypass:.*cand-late-bypass.*(final)'

# The newest eight are not a complete board read when the reducer says older unread posts
# remain. Rendering a page must preserve full bodies and pagination must fail closed.
set +e
unread_incomplete_output="$(MOTE_UNREAD_HAS_OLDER=1 run_checker)"
unread_incomplete_status=$?
set -e
[ "$unread_incomplete_status" -eq 3 ]
printf '%s\n' "$unread_incomplete_output" | grep -q 'unread board page is incomplete'
printf '%s\n' "$unread_incomplete_output" | grep -q 'do not merge'

unread_complete_output="$(MOTE_UNREAD_ONE=1 run_checker)"
printf '%s\n' "$unread_complete_output" | grep -q 'fresh full body'

set +e
unread_malformed_output="$(MOTE_UNREAD_MALFORMED=1 run_checker)"
unread_malformed_status=$?
set -e
[ "$unread_malformed_status" -eq 3 ]
printf '%s\n' "$unread_malformed_output" | grep -q 'mote discuss unread failed'

set +e
unread_duplicate_output="$(MOTE_UNREAD_DUPLICATE_PAGE_KEY=1 run_checker)"
unread_duplicate_status=$?
set -e
[ "$unread_duplicate_status" -eq 3 ]
printf '%s\n' "$unread_duplicate_output" | grep -q 'mote discuss unread failed'

for phase in pending abandoned superseded; do
  expect_phase_failure "$phase"
done

# A replacement ref can keep MAIN_OID's displayed identity while substituting a
# different parent chain that omits BYPASS_OID. Prove the mutation is live, then
# prove the checker ignores that caller-local view and still catches the pending
# first-parent candidate. This court kills removal of GIT_NO_REPLACE_OBJECTS.
MAIN_TREE="$(git -C "$REPO" rev-parse "$MAIN_OID^{tree}")"
REPLACEMENT_OID="$(printf 'replacement-history control\n' \
  | git -C "$REPO" commit-tree "$MAIN_TREE" -p "$BASE_OID")"
git -C "$REPO" replace "$MAIN_OID" "$REPLACEMENT_OID"
if env -u GIT_NO_REPLACE_OBJECTS git -C "$REPO" rev-list --first-parent "$MAIN_OID" \
    | grep -q "^$BYPASS_OID$"; then
  printf 'replacement-history control did not hide the bypass commit\n' >&2
  exit 1
fi
GIT_NO_REPLACE_OBJECTS=1 git -C "$REPO" rev-list --first-parent "$MAIN_OID" \
  | grep -q "^$BYPASS_OID$"
write_candidate_row cand-replacement-hidden-bypass "$BYPASS_OID" pending
set +e
replacement_output="$(run_checker)"
replacement_status=$?
set -e
[ "$replacement_status" -eq 3 ]
printf '%s\n' "$replacement_output" \
  | grep -q 'gate bypass:.*cand-replacement-hidden-bypass phase=pending'
git -C "$REPO" replace -d "$MAIN_OID" >/dev/null

# GIT_REPLACE_REF_BASE can move replacement authority out of refs/replace.
# Prove the custom namespace hides the same bypass, then prove the exported
# no-replacement boundary defeats it without knowing or enumerating that name.
CUSTOM_REPLACE_BASE=refs/premerge-test-replace
git -C "$REPO" update-ref "$CUSTOM_REPLACE_BASE/$MAIN_OID" "$REPLACEMENT_OID"
if env -u GIT_NO_REPLACE_OBJECTS GIT_REPLACE_REF_BASE="$CUSTOM_REPLACE_BASE" \
    git -C "$REPO" rev-list --first-parent "$MAIN_OID" \
    | grep -q "^$BYPASS_OID$"; then
  printf 'custom replacement-history control did not hide the bypass commit\n' >&2
  exit 1
fi
GIT_NO_REPLACE_OBJECTS=1 GIT_REPLACE_REF_BASE="$CUSTOM_REPLACE_BASE" \
  git -C "$REPO" rev-list --first-parent "$MAIN_OID" \
  | grep -q "^$BYPASS_OID$"
write_candidate_row cand-custom-replacement-hidden-bypass "$BYPASS_OID" pending
set +e
custom_replacement_output="$(GIT_REPLACE_REF_BASE="$CUSTOM_REPLACE_BASE" run_checker)"
custom_replacement_status=$?
set -e
[ "$custom_replacement_status" -eq 3 ]
printf '%s\n' "$custom_replacement_output" \
  | grep -q 'gate bypass:.*cand-custom-replacement-hidden-bypass phase=pending'
git -C "$REPO" update-ref -d "$CUSTOM_REPLACE_BASE/$MAIN_OID"

# Legacy info/grafts rewrites commit parents independently of replacement refs.
# Prove that even GIT_NO_REPLACE_OBJECTS cannot recover the stored history, then
# require an explicit fail-closed refusal instead of trusting the grafted view.
GRAFTS_PATH="$(git -C "$REPO" rev-parse --git-path info/grafts)"
case "$GRAFTS_PATH" in
  /*) ;;
  *) GRAFTS_PATH="$REPO/$GRAFTS_PATH" ;;
esac
printf '%s %s\n' "$MAIN_OID" "$BASE_OID" > "$GRAFTS_PATH"
if GIT_NO_REPLACE_OBJECTS=1 git -C "$REPO" rev-list --first-parent "$MAIN_OID" \
    | grep -q "^$BYPASS_OID$"; then
  printf 'legacy-graft control did not hide the bypass commit\n' >&2
  exit 1
fi
write_candidate_row cand-graft-hidden-bypass "$BYPASS_OID" pending
set +e
graft_output="$(run_checker)"
graft_status=$?
set -e
[ "$graft_status" -eq 3 ]
printf '%s\n' "$graft_output" \
  | grep -q 'legacy Git graft file exists at initial checkpoint'
rm -f -- "$GRAFTS_PATH"

# A pending candidate that is itself a multi-parent commit on main's first-parent
# line is still a direct gate bypass. Conversely, the side commit of an ordinary
# no-ff landing merge is not on that line and must remain a positive control.
write_candidate_row cand-merge-bypass "$MERGE_BYPASS_OID" pending
set +e
merge_bypass_output="$(run_checker)"
merge_bypass_status=$?
set -e
[ "$merge_bypass_status" -eq 3 ]
printf '%s\n' "$merge_bypass_output" | grep -q 'gate bypass:.*cand-merge-bypass phase=pending'

write_candidate_row cand-ordinary-side "$ORDINARY_MERGE_SIDE_OID" pending
ordinary_merge_output="$(run_checker)"
printf '%s\n' "$ordinary_merge_output" | grep -q 'Mechanical checks passed'

write_phase unknown
expect_validation_failure 'unknown candidate phase'

write_missing_phase
expect_validation_failure 'missing candidate phase'

# A terminal phase is not enough to prove reconciliation when the row has no stable identity.
# The former parser substituted `<missing>` and incorrectly exempted this direct first-parent commit.
write_missing_candidate_id
expect_validation_failure 'missing candidate id'

write_empty_candidate_id
expect_validation_failure 'empty candidate id'

write_missing_oid
expect_validation_failure 'missing candidate oid'

# A list parser must reject the whole snapshot, not keep plausible partial output.
# Under a mutation that skips invalid rows, the valid landed row would exempt the
# first-parent commit and the gate would falsely pass.
write_valid_landed_plus_malformed_identity
expect_validation_failure 'valid landed row beside malformed identity row'

write_malformed_oid
expect_validation_failure 'malformed candidate oid'

write_conflicting_oids
expect_validation_failure 'conflicting candidate commit identities'

write_duplicate_candidate_ids
expect_validation_failure 'duplicate candidate ids'

write_duplicate_phase_keys
expect_validation_failure 'duplicate phase keys'

write_duplicate_landed
duplicate_output="$(run_checker || true)"
printf '%s\n' "$duplicate_output" | grep -q 'FAIL: candidate identity ambiguity:.*has 2 rows'
printf '%s\n' "$duplicate_output" | grep -q 'PRE-MERGE CHECK FAILED'

write_phase landed
set +e
list_failure_output="$(MOTE_LIST_FAIL=1 run_checker)"
list_failure_status=$?
set -e
[ "$list_failure_status" -eq 3 ]
printf '%s\n' "$list_failure_output" | grep -q 'could not enumerate candidate commits'

set +e
malformed_output="$(MOTE_LIST_MALFORMED=1 run_checker)"
malformed_status=$?
set -e
[ "$malformed_status" -eq 3 ]
printf '%s\n' "$malformed_output" | grep -q 'candidate or first-parent history validation failed'

# The board read is a merge-gate input, not optional display. An unresolved actor or
# reducer failure must not render as the same empty output as "nothing unread".
write_phase landed
set +e
unread_failure_output="$(MOTE_UNREAD_FAIL=1 run_checker)"
unread_failure_status=$?
set -e
[ "$unread_failure_status" -eq 3 ]
printf '%s\n' "$unread_failure_output" | grep -q 'mote discuss unread failed'
printf '%s\n' "$unread_failure_output" | grep -q 'PRE-MERGE CHECK FAILED'

printf 'premerge-check court passed: identity, authority, receipts, observations, and reconciliation fail closed\n'
