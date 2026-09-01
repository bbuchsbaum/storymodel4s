#!/usr/bin/env bash
# premerge-check.sh CANDIDATE_ID COMMIT [GATE_LOG]
#   bash tools/premerge-check.sh cand-XXXX 1a2b3c4 /tmp/gate.log
#
# The chief's merge gate, mechanised. It exits nonzero on any doubt. The checks
# deliberately bind one immutable main snapshot, one candidate object, one clean
# merge-result tree, one Mote store/repository, and the exact candidate scope.
set -uo pipefail

# Git replacement refs (including caller-selected replacement namespaces) can
# preserve an object's displayed OID while changing the history and tree that
# ordinary Git commands observe. A merge gate must inspect the stored object
# graph, never a caller-local replacement view of it.
export GIT_NO_REPLACE_OBJECTS=1

CAND="${1:?usage: premerge-check.sh CANDIDATE_ID COMMIT [GATE_LOG]}"
COMMIT="${2:?usage: premerge-check.sh CANDIDATE_ID COMMIT [GATE_LOG]}"
GATE_LOG="${3:-}"
fail=0
gate_digest=""
actor=""
object_format=""
oid_pattern=""
receipt_main=""
receipt_head=""
repository_id=""
store_id=""
base=""
merged_tree=""
candidate_json=""
candidate_paths_tmp=""
effect_paths_tmp=""
paths_tmp=""

note() { printf '  %s\n' "$1"; }
bad() { printf 'FAIL: %s\n' "$1" >&2; fail=1; }

check_no_legacy_grafts() {
  local label="$1"
  local path=""
  if ! path="$(git rev-parse --git-path info/grafts 2>/dev/null)" || [ -z "$path" ]; then
    bad "cannot locate the legacy Git graft file at $label checkpoint"
    return 1
  fi
  if [ -e "$path" ]; then
    bad "legacy Git graft file exists at $label checkpoint: $path"
    return 1
  fi
  note "no legacy Git graft file at $label checkpoint"
  return 0
}

cleanup_file() {
  local path="$1"
  local label="$2"
  if [ -n "$path" ] && ! rm -f -- "$path"; then
    bad "could not remove $label"
  fi
}

# Parse one Mote audit snapshot with duplicate-key rejection. With expected
# repository/store arguments, also prove that the context has not changed.
audit_context() {
  python3 -c 'import json,sys
def strict_object(pairs):
    result={}
    for key,value in pairs:
        if key in result:
            raise ValueError(f"duplicate JSON key: {key}")
        result[key]=value
    return result
d=json.load(sys.stdin,object_pairs_hook=strict_object)
expected_target,expected_repo,expected_store=sys.argv[1:]
if not isinstance(d,dict) or not isinstance(d.get("context"),dict):
    raise ValueError("missing audit context")
context=d["context"]
if context.get("target_ref") != "main" or context.get("target_oid") != expected_target:
    raise ValueError("audit target is not the bound main snapshot")
repo=context.get("repository_id")
store=context.get("store_id")
for value in (repo,store):
    if not isinstance(value,str) or not value or any(ord(ch) < 32 or ord(ch) == 127 for ch in value):
        raise ValueError("invalid audit repository/store identity")
if expected_repo and repo != expected_repo:
    raise ValueError("landing repository changed during gate")
if expected_store and store != expected_store:
    raise ValueError("Mote store changed during gate")
print(repo + "\t" + store)' "$1" "${2:-}" "${3:-}"
}

# Validate a complete candidate snapshot. Besides row, object, phase, authority,
# and gate evidence, bind the candidate identity to this Mote store/repository and
# prove that its declared policy paths cover every conservative landing-effect path.
candidate_state() {
  python3 -c 'import json,pathlib,sys
def strict_object(pairs):
    result={}
    for key,value in pairs:
        if key in result:
            raise ValueError(f"duplicate JSON key: {key}")
        result[key]=value
    return result
def read_manifest(path):
    data=pathlib.Path(path).read_bytes()
    if not data or not data.endswith(b"\0"):
        raise ValueError("path manifest is empty or not NUL terminated")
    raw=data[:-1].split(b"\0")
    if not raw or any(not item for item in raw):
        raise ValueError("path manifest contains an empty entry")
    paths=[item.decode("utf-8") for item in raw]
    if len(paths) != len(set(paths)):
        raise ValueError("path manifest contains duplicate entries")
    return paths
def valid_scope(path):
    if not isinstance(path,str) or not path or path.startswith("/") or "\0" in path:
        return False
    parts=path.split("/")
    return all(part not in {"", ".", ".."} for part in parts)
def covers(scope,path):
    return path == scope or path.startswith(scope + "/")
d=json.load(sys.stdin,object_pairs_hook=strict_object)
(expected_candidate,expected_commit,actor,required_digest,expected_repo,
 expected_store,expected_format,manifest_path)=sys.argv[1:]
if d.get("candidate_id") != expected_candidate:
    raise ValueError("candidate id does not match requested row")
identity=d.get("identity")
if not isinstance(identity,dict) or identity.get("commit_oid") != expected_commit:
    raise ValueError("candidate commit does not match requested object")
for field,expected in (("landing_repository_id",expected_repo),("store_id",expected_store),("object_format",expected_format)):
    if identity.get(field) != expected:
        raise ValueError(f"candidate identity has wrong {field}")
phase=d.get("phase")
if not isinstance(phase,dict) or phase.get("value") != "pending":
    raise ValueError("candidate is not in pending phase")
policy=d.get("policy")
if not isinstance(policy,dict):
    raise ValueError("candidate policy is missing")
scopes=policy.get("paths")
if not isinstance(scopes,list) or not scopes or not all(valid_scope(path) for path in scopes):
    raise ValueError("candidate policy paths are invalid")
if len(scopes) != len(set(scopes)):
    raise ValueError("candidate policy paths contain duplicates")
changed=read_manifest(manifest_path)
uncovered=[path for path in changed if not any(covers(scope,path) for scope in scopes)]
if uncovered:
    raise ValueError("candidate policy does not cover every landing-effect path")
landability=d.get("landability")
if not isinstance(landability,dict) or not isinstance(landability.get("landable"),bool):
    raise ValueError("missing typed landability")
codes=landability.get("reason_codes",[])
reasons=landability.get("reasons",[])
if not isinstance(codes,list) or not all(isinstance(x,str) for x in codes):
    raise ValueError("invalid reason_codes")
if not isinstance(reasons,list) or not all(isinstance(x,dict) for x in reasons):
    raise ValueError("invalid reasons")
if landability["landable"]:
    if codes or reasons:
        raise ValueError("landable result carries blockers")
    authorization=d.get("authorization")
    if not isinstance(authorization,dict) or authorization.get("status") != "granted":
        raise ValueError("landable result lacks a granted authorization")
    grantees=authorization.get("grantees")
    if not isinstance(grantees,list) or not grantees or not all(isinstance(x,str) and x for x in grantees):
        raise ValueError("authorization has invalid grantees")
    if actor not in grantees:
        print("BLOCKED")
        print("  authorization_not_granted_to_actor: {}: grantees={}".format(actor,",".join(grantees)))
        raise SystemExit(0)
    if required_digest:
        evidence=d.get("evidence")
        if not isinstance(evidence,list):
            raise ValueError("candidate evidence is not an array")
        matches=[]
        for item in evidence:
            if not isinstance(item,dict):
                raise ValueError("candidate evidence item is not an object")
            payload=item.get("payload")
            if (item.get("name") == "chief-merged-tree-gate" and
                item.get("evidence_kind") == "external" and item.get("outcome") == "pass" and
                item.get("candidate_oid") == expected_commit and item.get("producer") == actor and
                isinstance(payload,dict) and payload.get("kind") == "external" and
                payload.get("digest") == required_digest and isinstance(item.get("refs"),list) and
                expected_commit in item["refs"]):
                matches.append(item)
        if len(matches) != 1:
            raise ValueError("missing or ambiguous candidate-bound chief gate evidence")
    print("LANDABLE")
else:
    if not codes and not reasons:
        raise ValueError("blocked result has no explanation")
    print("BLOCKED")
    for reason in reasons:
        code=reason.get("code","<missing-code>")
        subject=reason.get("subject","<missing-subject>")
        detail=reason.get("detail","<missing-detail>")
        print(f"  {code}: {subject}: {detail}")
    described={reason.get("code") for reason in reasons}
    for code in codes:
        if code not in described:
            print(f"  {code}: <no structured reason>")' \
    "$1" "$2" "$3" "${4:-}" "$5" "$6" "$7" "$8"
}

# Validate two complete Git -z manifests and create their stable union. Candidate
# diff paths catch what the proposal changed; landing-effect paths catch rename and
# merge interactions against the exact main receipt.
build_path_union() {
  python3 -c 'import pathlib,sys
def read_manifest(path,label):
    data=pathlib.Path(path).read_bytes()
    if not data or not data.endswith(b"\0"):
        raise ValueError(f"{label} manifest is empty or unterminated")
    values=data[:-1].split(b"\0")
    if not values or any(not value for value in values):
        raise ValueError(f"{label} manifest contains an empty path")
    for value in values:
        value.decode("utf-8")
    return values
candidate=read_manifest(sys.argv[1],"candidate")
effect=read_manifest(sys.argv[2],"landing effect")
union=[]
seen=set()
for value in candidate + effect:
    if value not in seen:
        seen.add(value)
        union.append(value)
pathlib.Path(sys.argv[3]).write_bytes(b"\0".join(union) + b"\0")
print(len(union))' "$1" "$2" "$3"
}

check_reservations() {
  local manifest="$1"
  local label="$2"
  local held=0
  local path_count=0
  local p=""
  local w=""
  while IFS= read -r -d '' p; do
    path_count=$((path_count + 1))
    if w="$(mote who-has "$p" --json 2>/dev/null \
      | python3 -c 'import json,sys
def strict_object(pairs):
    result={}
    for key,value in pairs:
        if key in result:
            raise ValueError(f"duplicate JSON key: {key}")
        result[key]=value
    return result
d=json.load(sys.stdin,object_pairs_hook=strict_object)
if not isinstance(d,list):
    raise ValueError("reservation result is not an array")
for row in d:
    if not isinstance(row,dict):
        raise ValueError("reservation row is not an object")
    for field in ("actor","entity","path","reservation_id"):
        if not isinstance(row.get(field),str) or not row[field]:
            raise ValueError("reservation row lacks typed identity")
if d:
    for row in d:
        print("{} actor={} issue={} path={!r}".format(row["reservation_id"],row["actor"],row["entity"],row["path"]))
else:
    print("CLEAR")')"; then
      if [ "$w" != "CLEAR" ]; then
        printf 'FAIL: reserved path %q: %s\n' "$p" "$w" >&2
        held=1
      fi
    else
      printf 'FAIL: who-has failed for %q: %s\n' "$p" "$w" >&2
      held=1
    fi
  done < "$manifest"
  if [ "$path_count" -eq 0 ]; then
    bad "$label path manifest contained no complete entries"
    return 1
  fi
  if [ "$held" -eq 0 ]; then
    note "$path_count path(s), none reserved ($label)"
    return 0
  fi
  fail=1
  return 1
}

# Validate one complete candidate-list snapshot against one immutable
# first-parent history. A terminal row exempts a commit only when its landing
# identity names this audited repository directly, or an out-of-band
# reconciliation carries an explicit object-availability bridge into it.
scan_candidate_history() {
  python3 -c 'import json,re,sys
def strict_object(pairs):
    result={}
    for key,value in pairs:
        if key in result:
            raise ValueError(f"duplicate JSON key: {key}")
        result[key]=value
    return result
def printable(value):
    return isinstance(value,str) and bool(value) and not any(ord(ch) < 32 or ord(ch) == 127 for ch in value)
def bridge_matches(row,identity,oid,phase,expected_repo,expected_format,oid_pattern,history_positions):
    if phase != "landed_out_of_band":
        return False
    reconciliation=row.get("reconciliation")
    if reconciliation is None:
        return False
    if not isinstance(reconciliation,dict):
        raise ValueError("candidate reconciliation must be an object")
    bridge=reconciliation.get("repository_bridge")
    if bridge is None:
        return False
    if not isinstance(bridge,dict):
        raise ValueError("candidate repository bridge must be an object")
    availability=bridge.get("object_availability")
    if not isinstance(availability,dict):
        raise ValueError("candidate repository bridge lacks object availability")
    target_oid=reconciliation.get("target_oid")
    candidate_position=history_positions.get(oid)
    target_position=history_positions.get(target_oid)
    if (reconciliation.get("target_ref") != "main" or
        not isinstance(target_oid,str) or oid_pattern.fullmatch(target_oid) is None or
        candidate_position is None or target_position is None or
        target_position > candidate_position):
        return False
    landing_op=identity.get("landing_repository_op_id")
    if (not printable(landing_op) or
        bridge.get("expect_landing_repository_op_id") != landing_op):
        return False
    parent_oids=identity.get("parent_oids")
    observed_parents=availability.get("observed_parent_oids")
    if (not isinstance(parent_oids,list) or not parent_oids or
        not all(isinstance(parent,str) and oid_pattern.fullmatch(parent) is not None for parent in parent_oids) or
        observed_parents != parent_oids):
        return False
    return (availability.get("candidate_oid") == oid and
            availability.get("repository_id") == expected_repo and
            availability.get("object_format") == expected_format and
            availability.get("object_available") is True)
(candidates_path,history_path,object_format,expected_main,
 expected_repo,expected_store)=sys.argv[1:]
length={"sha1":40,"sha256":64}.get(object_format)
if length is None:
    raise ValueError("unsupported Git object format")
oid_pattern=re.compile(rf"[0-9a-f]{{{length}}}")
history=[]
with open(history_path,encoding="utf-8") as handle:
    for line in handle:
        parts=line.strip().split()
        if not parts or any(oid_pattern.fullmatch(value) is None for value in parts):
            raise ValueError("invalid first-parent history row")
        history.append(parts)
if not history or history[0][0] != expected_main:
    raise ValueError("first-parent history is not bound to receipt main")
history_positions={parts[0]:index for index,parts in enumerate(history)}
with open(candidates_path,encoding="utf-8") as handle:
    data=json.load(handle,object_pairs_hook=strict_object)
rows=data if isinstance(data,list) else data.get("candidates",data.get("items")) if isinstance(data,dict) else None
if not isinstance(rows,list) or not rows:
    raise ValueError("candidate list must be a nonempty array")
allowed={"pending","landed","landed_out_of_band","superseded","abandoned"}
by_oid={}
seen_ids=set()
for row in rows:
    if not isinstance(row,dict):
        raise ValueError("candidate row must be an object")
    identity=row.get("identity")
    if not isinstance(identity,dict):
        raise ValueError("candidate identity must be an object")
    nested_oid=identity.get("commit_oid")
    top_oid=row.get("commit_oid")
    if nested_oid is not None and top_oid is not None and nested_oid != top_oid:
        raise ValueError("conflicting candidate commit identities")
    oid=nested_oid if nested_oid is not None else top_oid
    primary_id=row.get("candidate_id")
    legacy_id=row.get("id")
    if primary_id is not None and legacy_id is not None and primary_id != legacy_id:
        raise ValueError("conflicting candidate ids")
    candidate_id=primary_id if primary_id is not None else legacy_id
    phase=row.get("phase")
    if isinstance(phase,dict):
        phase=phase.get("value")
    landing_repo=identity.get("landing_repository_id")
    candidate_store=identity.get("store_id")
    candidate_format=identity.get("object_format")
    for value in (oid,candidate_id,phase,landing_repo,candidate_store,candidate_format):
        if not printable(value):
            raise ValueError("candidate identity, repository, store, format, and phase must be printable nonempty strings")
    candidate_length={"sha1":40,"sha256":64}.get(candidate_format)
    if candidate_length is None:
        raise ValueError("candidate row has unsupported object format")
    candidate_oid_pattern=re.compile(rf"[0-9a-f]{{{candidate_length}}}")
    if candidate_oid_pattern.fullmatch(oid) is None:
        raise ValueError("candidate commit oid has invalid syntax")
    if candidate_id in seen_ids:
        raise ValueError("duplicate candidate id")
    seen_ids.add(candidate_id)
    if phase not in allowed:
        raise ValueError("unknown candidate phase")
    if candidate_store != expected_store:
        raise ValueError("candidate row belongs to a different store")
    if landing_repo == expected_repo and candidate_format != object_format:
        raise ValueError("candidate row names this repository with the wrong object format")
    if candidate_format != object_format:
        continue
    bound=(landing_repo == expected_repo or
           bridge_matches(row,identity,oid,phase,expected_repo,object_format,oid_pattern,history_positions))
    by_oid.setdefault(oid,[]).append((candidate_id,phase,bound))
notes=[]
blocks=[]
for oid in (parts[0] for parts in history):
    matches=by_oid.get(oid,[])
    if not matches:
        continue
    if len(matches) != 1:
        blocks.append(f"candidate identity ambiguity: {oid[:7]} has {len(matches)} rows for one commit oid")
        continue
    candidate_id,phase,bound=matches[0]
    if phase in {"landed","landed_out_of_band"} and bound:
        notes.append(f"reconciled first-parent candidate: {oid[:7]} {candidate_id} ({phase})")
    elif phase in {"landed","landed_out_of_band"}:
        blocks.append(f"unbound terminal candidate: {oid[:7]} [{candidate_id} phase={phase}]")
    else:
        blocks.append(f"gate bypass: {oid[:7]} [{candidate_id} phase={phase}]")
if not notes and not blocks:
    notes.append(f"no proposed commit sits on bound main first-parent line ({len(rows)} candidates checked)")
for message in notes:
    print("NOTE " + message)
for message in blocks:
    print("BLOCK " + message)
sys.exit(4 if blocks else 0)' "$1" "$2" "$3" "$4" "$5" "$6"
}

echo "== 1. branch and immutable landing context =="
check_no_legacy_grafts "initial" || :
if BR="$(git symbolic-ref --quiet HEAD 2>/dev/null)"; then
  if [ "$BR" = "refs/heads/main" ]; then note "on main"; else bad "HEAD is '$BR', not refs/heads/main"; fi
else
  bad "cannot determine the current branch"
fi
if ! object_format="$(git rev-parse --show-object-format 2>/dev/null)"; then
  bad "cannot determine Git object format"
elif [ "$object_format" = "sha1" ]; then
  oid_pattern='^[0-9a-f]{40}$'
elif [ "$object_format" = "sha256" ]; then
  oid_pattern='^[0-9a-f]{64}$'
else
  bad "unsupported Git object format: $object_format"
fi
if ! receipt_main="$(git rev-parse --verify 'main^{commit}' 2>/dev/null)" \
    || [ -z "$oid_pattern" ] || ! [[ "$receipt_main" =~ $oid_pattern ]]; then
  receipt_main=""
  bad "cannot resolve main to one full lowercase commit identity"
fi
if ! receipt_head="$(git rev-parse --verify 'HEAD^{commit}' 2>/dev/null)" \
    || [ -z "$oid_pattern" ] || ! [[ "$receipt_head" =~ $oid_pattern ]]; then
  receipt_head=""
  bad "cannot resolve HEAD to one full lowercase commit identity"
elif [ -n "$receipt_main" ] && [ "$receipt_head" != "$receipt_main" ]; then
  bad "HEAD and main do not name the same starting commit"
fi
if [ -n "$receipt_main" ]; then
  audit_values=""
  if ! audit_values="$(mote audit --json --target-ref main --fail-on never 2>/dev/null \
      | audit_context "$receipt_main" 2>/dev/null)"; then
    bad "mote audit failed or did not bind this checkout, store, and main target"
  elif [[ "$audit_values" != *$'\t'* ]]; then
    bad "mote audit context parser returned an invalid identity"
  else
    repository_id="${audit_values%%$'\t'*}"
    store_id="${audit_values#*$'\t'}"
    note "bound main $receipt_main to repository $repository_id and store $store_id"
  fi
fi

echo "== 2. candidate object, clean merge tree, and exact landing paths =="
if [ -z "$oid_pattern" ] || ! [[ "$COMMIT" =~ $oid_pattern ]]; then
  bad "candidate commit must be one full lowercase $object_format object id"
elif ! commit_type="$(git cat-file -t "$COMMIT" 2>/dev/null)" || [ "$commit_type" != "commit" ]; then
  bad "$COMMIT is NOT a commit in this repository -- ask its author to push it or name their clone"
else
  note "$COMMIT present"
fi
if [ -n "$receipt_main" ] && [ -n "$oid_pattern" ] && [[ "$COMMIT" =~ $oid_pattern ]]; then
  if ! base="$(git merge-base "$receipt_main" "$COMMIT" 2>/dev/null)" \
      || ! [[ "$base" =~ $oid_pattern ]]; then
    base=""
    bad "cannot compute an exact merge-base for $COMMIT"
  fi
  if ! merged_tree="$(git merge-tree --write-tree "$receipt_main" "$COMMIT" 2>/dev/null)" \
      || ! [[ "$merged_tree" =~ $oid_pattern ]]; then
    merged_tree=""
    bad "cannot compute one exact clean merge-result tree for bound main plus candidate"
  elif ! merged_type="$(git cat-file -t "$merged_tree" 2>/dev/null)" || [ "$merged_type" != "tree" ]; then
    merged_tree=""
    bad "git merge-tree did not return an immutable tree object id"
  fi
fi
if [ -n "$base" ] && [ -n "$merged_tree" ]; then
  if ! candidate_paths_tmp="$(mktemp 2>/dev/null)"; then candidate_paths_tmp=""; fi
  if ! effect_paths_tmp="$(mktemp 2>/dev/null)"; then effect_paths_tmp=""; fi
  if ! paths_tmp="$(mktemp 2>/dev/null)"; then paths_tmp=""; fi
  if [ -z "$candidate_paths_tmp" ] || [ -z "$effect_paths_tmp" ] || [ -z "$paths_tmp" ]; then
    bad "could not allocate changed-path manifests"
  elif ! git diff --name-only -z --no-renames "$base..$COMMIT" > "$candidate_paths_tmp" 2>/dev/null; then
    bad "cannot enumerate paths changed by $COMMIT"
  elif ! git diff --name-only -z --no-renames "$receipt_main" "$merged_tree" > "$effect_paths_tmp" 2>/dev/null; then
    bad "cannot enumerate paths changed by the clean landing result"
  elif ! path_count="$(build_path_union "$candidate_paths_tmp" "$effect_paths_tmp" "$paths_tmp" 2>/dev/null)" \
      || ! [[ "$path_count" =~ ^[1-9][0-9]*$ ]]; then
    bad "changed-path manifests are empty, malformed, unterminated, or non-UTF-8"
  else
    note "$path_count conservative candidate/landing-effect path(s)"
  fi
fi

echo "== 3. candidate is pending, landable, authorized here, and scope-bound =="
if ! actor="$(mote actor show --json 2>/dev/null \
  | python3 -c 'import json,sys
def strict_object(pairs):
    result={}
    for key,value in pairs:
        if key in result:
            raise ValueError(f"duplicate JSON key: {key}")
        result[key]=value
    return result
d=json.load(sys.stdin,object_pairs_hook=strict_object)
actor=d.get("actor")
if not isinstance(actor,str) or not actor or "\t" in actor or "\n" in actor:
    raise ValueError("missing typed actor identity")
print(actor)')"; then
  actor=""
  bad "mote actor show failed or returned an invalid actor -- landing authority cannot be checked"
fi
if [ -z "$actor" ] || [ -z "$repository_id" ] || [ -z "$store_id" ] || [ -z "$paths_tmp" ] || [ ! -s "$paths_tmp" ]; then
  : # prerequisites already failed closed
elif ! candidate_json="$(mote candidate show "$CAND" --json 2>/dev/null)"; then
  bad "mote candidate show failed -- an empty blocker list and a failed command look identical"
elif ! out="$(printf '%s\n' "$candidate_json" \
  | candidate_state "$CAND" "$COMMIT" "$actor" "" "$repository_id" "$store_id" "$object_format" "$paths_tmp" 2>/dev/null)"; then
  bad "mote candidate show failed or returned invalid identity, scope, phase, or landability"
elif [ "$out" = "LANDABLE" ]; then
  note "no blockers; identity and policy scope match this landing"
elif [ "${out%%$'\n'*}" = "BLOCKED" ]; then
  bad "candidate is blocked:"
  if ! printf '%s\n' "$out" | sed -n '2,$p' >&2; then bad "could not render candidate blockers"; fi
else
  bad "candidate landability parser returned an unknown state"
fi

echo "== 4. no live reservation on any conservative landing path =="
if [ -n "$paths_tmp" ] && [ -s "$paths_tmp" ]; then
  check_reservations "$paths_tmp" "initial" || :
else
  bad "no validated landing-path manifest is available for reservation checks"
fi

echo "== 5. gate log has bound TEST TOTALS and exact command receipts =="
if [ -z "$GATE_LOG" ]; then
  bad "no gate log given -- pass one; 'I ran it and it looked fine' is not a receipt"
elif [ ! -f "$GATE_LOG" ]; then
  bad "gate log $GATE_LOG does not exist"
elif [ -z "$receipt_main" ] || [ -z "$merged_tree" ]; then
  bad "no immutable main/tree identity is available for the gate receipt"
else
  gate_summary=""
  if ! gate_summary="$(python3 -c 'import hashlib,pathlib,re,sys
data=pathlib.Path(sys.argv[1]).read_bytes()
text=data.decode("utf-8")
expected_main,expected_candidate,expected_tree=sys.argv[2:]
def require_exact(label,expected):
    values=re.findall(rf"(?m)^{label}=([^\r\n]+)$",text)
    if values != [expected]:
        raise ValueError(f"missing, duplicate, or mismatched {label}")
require_exact("GATE_MAIN",expected_main)
require_exact("GATE_CANDIDATE",expected_candidate)
require_exact("GATE_TREE",expected_tree)
mentions=[line for line in text.splitlines() if "Passed: Total" in line]
pattern=re.compile(r"^(?:\[[^]\r\n]+\]\s*)?Passed: Total ([0-9]+), Failed ([0-9]+), Errors ([0-9]+), Passed ([0-9]+)\s*$")
totals=[]
for line in mentions:
    match=pattern.fullmatch(line)
    if match is None:
        raise ValueError("malformed test totals receipt")
    total,failed,errors,passed=map(int,match.groups())
    if total != failed + errors + passed:
        raise ValueError("internally inconsistent test totals receipt")
    totals.append((total,failed,errors,passed))
exit_mentions=[line for line in text.splitlines() if "_EXIT=" in line]
exit_pattern=re.compile(r"^([A-Z][A-Z0-9_]*)_EXIT=([0-9]+)[ \t]*$")
receipts=[]
for line in exit_mentions:
    match=exit_pattern.fullmatch(line)
    if match is None:
        raise ValueError("malformed command-exit receipt")
    receipts.append(match.groups())
names=[name for name,_ in receipts]
if len(names) != len(set(names)):
    raise ValueError("duplicate command-exit receipt")
gate=[value for name,value in receipts if name == "GATE"]
if len(gate) != 1:
    raise ValueError("exactly one GATE_EXIT receipt is required")
nonzero=[f"{name}_EXIT={value}" for name,value in receipts if int(value) != 0]
rendered=",".join(nonzero) if nonzero else "-"
test_count=sum(x[0] for x in totals)
failed_count=sum(x[1] for x in totals)
error_count=sum(x[2] for x in totals)
if not totals:
    test_status="ZERO_TOTALS"
elif failed_count or error_count:
    test_status="FAILED"
elif test_count == 0:
    test_status="ZERO_TESTS"
else:
    test_status="PASS"
exit_status="FAILED" if nonzero else "PASS"
print(f"{test_status}\t{len(totals)}\t{test_count}\t{failed_count}\t{error_count}\t{exit_status}\t{len(receipts)}\t{rendered}\t{hashlib.sha256(data).hexdigest()}")' \
      "$GATE_LOG" "$receipt_main" "$COMMIT" "$merged_tree" 2>/dev/null)"; then
    bad "gate log could not be read or parsed"
  else
    IFS=$'\t' read -r test_status totals test_count failed errors exit_status exit_count nonzero_exits gate_digest <<< "$gate_summary"
    if ! [[ "$test_status" =~ ^(PASS|ZERO_TOTALS|FAILED|ZERO_TESTS)$ \
        && "$totals" =~ ^[0-9]+$ && "$test_count" =~ ^[0-9]+$ \
        && "$failed" =~ ^[0-9]+$ && "$errors" =~ ^[0-9]+$ \
        && "$exit_status" =~ ^(PASS|FAILED)$ && "$exit_count" =~ ^[0-9]+$ \
        && "$gate_digest" =~ ^[0-9a-f]{64}$ ]]; then
      bad "gate log parser returned an invalid summary"
    else
      case "$test_status" in
        ZERO_TOTALS) bad "gate log has ZERO 'Passed: Total' lines -- it measured your infrastructure, not the code" ;;
        FAILED) bad "gate log reports $failed failed and $errors errored test(s)" ;;
        ZERO_TESTS) bad "gate log reports zero executed tests" ;;
        PASS) note "$totals totals covering $test_count tests, 0 failed, 0 errors" ;;
      esac
      case "$exit_status" in
        FAILED) bad "gate log records nonzero command exit(s): $nonzero_exits" ;;
        PASS) note "$exit_count captured exit status(es), including exact GATE_EXIT=0" ;;
      esac
      if ! bound_state="$(printf '%s\n' "$candidate_json" \
        | candidate_state "$CAND" "$COMMIT" "$actor" "$gate_digest" "$repository_id" "$store_id" "$object_format" "$paths_tmp" 2>/dev/null)" \
          || [ "$bound_state" != "LANDABLE" ]; then
        bad "gate log digest is not bound to this candidate by one chief-merged-tree-gate evidence record"
      else
        note "gate digest $gate_digest is bound to candidate $CAND by $actor"
      fi
    fi
  fi
fi

echo "== 6. RE-READ THE BOARD NOW, not before the gate =="
echo "  unread since your cursor:"
if unread="$(mote discuss unread --json --page --limit 8 2>/dev/null \
  | python3 -c 'import json,sys
def strict_object(pairs):
    result={}
    for key,value in pairs:
        if key in result:
            raise ValueError(f"duplicate JSON key: {key}")
        result[key]=value
    return result
d=json.load(sys.stdin,object_pairs_hook=strict_object)
if not isinstance(d,dict) or not isinstance(d.get("posts"),list) or not isinstance(d.get("page"),dict):
    raise ValueError("invalid unread page")
posts=d["posts"]
page=d["page"]
count=page.get("count")
older=page.get("has_older")
newer=page.get("has_newer")
if not isinstance(count,int) or isinstance(count,bool) or count != len(posts) or not isinstance(older,bool) or not isinstance(newer,bool):
    raise ValueError("invalid unread pagination")
if newer:
    raise ValueError("newest unread window unexpectedly has newer posts")
print(("INCOMPLETE" if older else "COMPLETE") + f"\t{count}")
for post in posts:
    if not isinstance(post,dict):
        raise ValueError("invalid unread post")
    post_id=post.get("post_id")
    topic=post.get("topic")
    author=post.get("from")
    body=post.get("body")
    if not all(isinstance(x,str) and x for x in (post_id,topic,author)) or not isinstance(body,str):
        raise ValueError("invalid unread post fields")
    print(f"POST {post_id} topic={topic} from={author}")
    print(body)')"; then
  unread_header="${unread%%$'\n'*}"
  unread_payload=""
  if [[ "$unread" == *$'\n'* ]]; then unread_payload="${unread#*$'\n'}"; fi
  case "$unread_header" in
    COMPLETE$'\t'0) echo "    (nothing unread -- but you have still not looked at replies to your own posts)" ;;
    COMPLETE$'\t'*) if ! printf '    %s\n' "$unread_payload"; then bad "could not render unread board posts"; fi ;;
    INCOMPLETE$'\t'*)
      bad "unread board page is incomplete -- paginate or mark reviewed history before merging"
      if ! printf '    %s\n' "$unread_payload"; then bad "could not render the newest unread board page"; fi
      ;;
    *) bad "unread board parser returned an unknown state" ;;
  esac
else
  bad "mote discuss unread failed -- cannot establish that the board was re-read"
fi
echo "  READ THOSE. This check cannot judge them for you; it only refuses to let you skip looking."

echo "== 7. has anything reached bound main WITHOUT the merge gate? =="
cands_tmp=""
history_tmp=""
if ! cands_tmp="$(mktemp 2>/dev/null)"; then cands_tmp=""; fi
if ! history_tmp="$(mktemp 2>/dev/null)"; then history_tmp=""; fi
scan_ready=1
if ! check_no_legacy_grafts "history-scan"; then
  scan_ready=0
fi
if [ -z "$cands_tmp" ] || [ -z "$history_tmp" ]; then
  bad "could not allocate first-parent scan inputs"
  scan_ready=0
fi
shallow=""
if [ "$scan_ready" -eq 1 ] && ! shallow="$(git rev-parse --is-shallow-repository 2>/dev/null)"; then
  bad "cannot determine whether first-parent history is shallow"
  scan_ready=0
elif [ "$scan_ready" -eq 1 ] && [ "$shallow" != "false" ]; then
  bad "repository history is shallow -- a complete first-parent bypass scan is impossible"
  scan_ready=0
fi
if [ "$scan_ready" -eq 1 ] && ! mote candidate list --json > "$cands_tmp" 2>/dev/null; then
  bad "could not enumerate candidate commits -- cannot check for gate bypasses"
  scan_ready=0
elif [ "$scan_ready" -eq 1 ] && [ ! -s "$cands_tmp" ]; then
  bad "candidate enumeration returned no data"
  scan_ready=0
fi
if [ "$scan_ready" -eq 1 ] && ! git rev-list --first-parent --parents "$receipt_main" > "$history_tmp" 2>/dev/null; then
  bad "could not enumerate main's first-parent history"
  scan_ready=0
elif [ "$scan_ready" -eq 1 ] && [ ! -s "$history_tmp" ]; then
  bad "main's first-parent history is empty"
  scan_ready=0
fi
if [ "$scan_ready" -eq 1 ]; then
  scan_output="$(scan_candidate_history "$cands_tmp" "$history_tmp" "$object_format" \
    "$receipt_main" "$repository_id" "$store_id" 2>/dev/null)"
  scan_status=$?
  case "$scan_status" in
    0)
      while IFS= read -r line; do
        case "$line" in NOTE\ *) note "${line#NOTE }" ;; *) bad "first-parent parser returned an unknown success record" ;; esac
      done <<< "$scan_output"
      ;;
    4)
      while IFS= read -r line; do
        case "$line" in NOTE\ *) note "${line#NOTE }" ;; BLOCK\ *) bad "${line#BLOCK }" ;; *) bad "first-parent parser returned an unknown blocking record" ;; esac
      done <<< "$scan_output"
      ;;
    *) bad "candidate or first-parent history validation failed" ;;
  esac
fi

echo "== 8. final point-in-time revalidation =="
check_no_legacy_grafts "final" || :
final_candidate_json=""
final_state=""
if [ -z "$actor" ] || [ -z "$gate_digest" ] || [ -z "$paths_tmp" ]; then
  bad "candidate, gate, or path identity is unresolved at the final check"
elif ! final_candidate_json="$(mote candidate show "$CAND" --json 2>/dev/null)"; then
  bad "final mote candidate show failed -- landability may have changed during the gate"
elif ! final_state="$(printf '%s\n' "$final_candidate_json" \
  | candidate_state "$CAND" "$COMMIT" "$actor" "$gate_digest" "$repository_id" "$store_id" "$object_format" "$paths_tmp" 2>/dev/null)"; then
  bad "final candidate snapshot is invalid or no longer identity/scope/gate bound"
elif [ "$final_state" = "LANDABLE" ]; then
  note "candidate remains pending and landable for $actor"
elif [ "${final_state%%$'\n'*}" = "BLOCKED" ]; then
  bad "candidate became blocked during the gate:"
  if ! printf '%s\n' "$final_state" | sed -n '2,$p' >&2; then bad "could not render the final blocker list"; fi
else
  bad "final candidate landability parser returned an unknown state"
fi
if [ -n "$paths_tmp" ] && [ -s "$paths_tmp" ]; then
  check_reservations "$paths_tmp" "final" || :
else
  bad "no validated landing-path manifest is available for the final reservation check"
fi
final_main=""
final_head=""
if ! final_main="$(git rev-parse --verify 'main^{commit}' 2>/dev/null)" \
    || [ -z "$oid_pattern" ] || ! [[ "$final_main" =~ $oid_pattern ]] || [ "$final_main" != "$receipt_main" ]; then
  bad "main advanced or became unreadable after the gate receipt was fixed"
fi
if ! final_head="$(git rev-parse --verify 'HEAD^{commit}' 2>/dev/null)" \
    || [ -z "$oid_pattern" ] || ! [[ "$final_head" =~ $oid_pattern ]] || [ "$final_head" != "$receipt_main" ]; then
  bad "HEAD advanced, detached, or diverged after the gate receipt was fixed"
fi
if ! final_branch="$(git symbolic-ref --quiet HEAD 2>/dev/null)" || [ "$final_branch" != "refs/heads/main" ]; then
  bad "HEAD is no longer symbolically attached to main at final revalidation"
fi
if [ "$scan_ready" -ne 1 ] || [ -z "$cands_tmp" ] || [ -z "$history_tmp" ]; then
  bad "no validated global candidate-history inputs remain for final revalidation"
elif ! mote candidate list --json > "$cands_tmp" 2>/dev/null || [ ! -s "$cands_tmp" ]; then
  bad "final candidate enumeration failed -- bypass reconciliation may have changed"
else
  final_scan_output="$(scan_candidate_history "$cands_tmp" "$history_tmp" "$object_format" \
    "$receipt_main" "$repository_id" "$store_id" 2>/dev/null)"
  final_scan_status=$?
  case "$final_scan_status" in
    0)
      while IFS= read -r line; do
        case "$line" in NOTE\ *) : ;; *) bad "final first-parent parser returned an unknown success record" ;; esac
      done <<< "$final_scan_output"
      note "global candidate reconciliation remains bound to this repository at final revalidation"
      ;;
    4)
      while IFS= read -r line; do
        case "$line" in NOTE\ *) : ;; BLOCK\ *) bad "${line#BLOCK } (final)" ;; *) bad "final first-parent parser returned an unknown blocking record" ;; esac
      done <<< "$final_scan_output"
      ;;
    *) bad "final candidate or first-parent history validation failed" ;;
  esac
fi
if [ -n "$receipt_main" ] && [ -n "$repository_id" ] && [ -n "$store_id" ]; then
  if ! mote audit --json --target-ref main --fail-on never 2>/dev/null \
      | audit_context "$receipt_main" "$repository_id" "$store_id" >/dev/null 2>&1; then
    bad "final Mote audit no longer binds the same repository, store, and main target"
  else
    note "repository, store, main, candidate, reservations, and candidate history survived final revalidation"
  fi
fi
note "atomicity ceiling: these are ordered point-in-time reads, not a cross-Git/Mote transaction; merge immediately or rerun"

cleanup_file "$cands_tmp" "candidate scan input"
cleanup_file "$history_tmp" "history scan input"
cleanup_file "$candidate_paths_tmp" "candidate path manifest"
cleanup_file "$effect_paths_tmp" "landing-effect path manifest"
cleanup_file "$paths_tmp" "combined path manifest"

echo
if [ "$fail" -ne 0 ]; then
  echo "PRE-MERGE CHECK FAILED. Do not merge." >&2
  exit 3
fi
echo "Mechanical checks passed. The board re-read in step 6 is yours to judge."
