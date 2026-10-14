#!/usr/bin/env bash
set -euo pipefail

# Runs against an explicitly selected, checksum-verified installed distribution.
repo=$(cd "$(dirname "$0")/../.." && pwd)
dist=${STABILITY_DIST:?Set STABILITY_DIST to the installed CLI distribution being audited}
expected=${STABILITY_SOURCE:?Set STABILITY_SOURCE to its exact source commit}
out=${1:-"$repo/docs/refactor/evidence/stability-audit-20261014/semantic-inventory.json"}
roots=${STABILITY_ROOTS:-"$repo/tools/stability-audit/roots.txt"}
python3 - "$dist" "$expected" <<'VERIFY'
import hashlib, json, pathlib, re, sys
root = pathlib.Path(sys.argv[1]).resolve()
m = json.loads((root / 'manifest.json').read_text())
assert m['schemaVersion'] == 'storymodel4s-cli-distribution/v1'
assert re.fullmatch('[0-9a-f]{40}', sys.argv[2]) and m['source'] == sys.argv[2], 'source mismatch'
assert m['scalaVersion'] == '3.7.4', 'compiler version mismatch'
seen = set()
for entry in m['files']:
    rel = pathlib.PurePosixPath(entry['path'])
    assert not rel.is_absolute() and '..' not in rel.parts and str(rel) not in seen
    seen.add(str(rel))
    path = root / rel
    assert path.resolve().is_relative_to(root), 'file escapes distribution'
    data = path.read_bytes()
    assert len(data) == entry['bytes'] and hashlib.sha256(data).hexdigest() == entry['sha256'], str(rel)
assert (root / 'classpath.txt').read_text().splitlines() == m['classpath']
assert m['classpath'] and all('lib/' + name in seen for name in m['classpath'])
assert {str(p.relative_to(root)) for p in (root / 'lib').glob('*.jar')} == {
    'lib/' + name for name in m['classpath']
}, 'unlisted or missing runtime JAR'
VERIFY
work=$(mktemp -d "${TMPDIR:-/tmp}/storymodel4s-stability-audit.XXXXXX")
trap 'rm -rf "$work"' EXIT
mkdir -p "$(dirname "$out")" "$work/tasty" "$work/classes"

runtime=$(awk -v base="$dist/lib" '{print base "/" $0}' "$dist/classpath.txt" | paste -sd: -)
cache="$HOME/Library/Caches/Coursier/v1"
one() { find "$cache" -type f -name "$1" -print -quit; }
compiler="$(one 'scala3-compiler_3-3.7.4.jar'):$(one 'scala3-tasty-inspector_3-3.7.4.jar'):$(one 'scala3-interfaces-3.7.4.jar'):$(one 'scala3-library_3-3.7.4.jar'):$(one 'tasty-core_3-3.7.4.jar'):$(one 'scala-library-2.13.16.jar'):$(one 'scala-asm-9.8.0-scala-1.jar'):$(one 'compiler-interface-1.10.7.jar'):$(one 'jline-reader-3.29.0.jar'):$(one 'jline-terminal-3.29.0.jar'):$(one 'jline-terminal-jni-3.29.0.jar')"
java_opts=(-Xmx2g -XX:ActiveProcessorCount=2)
# `jar` has no portable extraction-to option. Extract only project TASTy entries inside the
# ignored temporary directory, avoiding the third-party runtime jars in the installed classpath.
while IFS= read -r jarname; do
  jarfile="$dist/lib/$jarname"
  entries=()
  while IFS= read -r entry; do entries+=("$entry"); done < <(jar tf "$jarfile" | grep '^storymodel4s/.*\.tasty$' || true)
  if test "${#entries[@]}" -gt 0; then (cd "$work/tasty" && jar xf "$jarfile" "${entries[@]}"); fi
done < "$dist/classpath.txt"
tasty=()
while IFS= read -r entry; do tasty+=("$entry"); done < <(find "$work/tasty" -name '*.tasty' -print | sort)
if test -n "${STABILITY_EXTRA_TASTY:-}"; then
  while IFS= read -r entry; do tasty+=("$entry"); done < <(find "$STABILITY_EXTRA_TASTY" -name '*.tasty' -print | sort)
fi
test "${#tasty[@]}" -gt 0
java "${java_opts[@]}" -cp "$compiler" dotty.tools.dotc.Main -experimental -classpath "$runtime:$compiler" -d "$work/classes" "$repo/tools/stability-audit/src/StableApiAudit.scala"
java "${java_opts[@]}" -Daudit.source="$expected" -cp "$work/classes:$runtime:$compiler" storymodel4s.stabilityaudit.StableApiAudit "$roots" "$out" "${tasty[@]}"
python3 - "$dist" "$expected" "$roots" "$out" "$repo/tools/stability-audit/src/StableApiAudit.scala" "$repo/tools/stability-audit/run.sh" "${STABILITY_EXTRA_TASTY:-}" <<'RECEIPT'
import hashlib, json, pathlib, sys
def sha(p): return hashlib.sha256(pathlib.Path(p).read_bytes()).hexdigest()
dist, source, roots, out, tool, runner, extra = sys.argv[1:]
inventory = json.loads(pathlib.Path(out).read_text())
assert inventory['sourceSha'] == source and inventory['roots'] and not inventory['unresolved'], 'incomplete signature inventory'
receipt = {'sourceSha': source, 'distributionManifestSha256': sha(pathlib.Path(dist) / 'manifest.json'),
           'rootsSha256': sha(roots), 'inventorySha256': sha(out), 'toolSha256': sha(tool),
           'runnerSha256': sha(runner), 'tool': 'Scala 3.7.4 TASTy signature walker',
           'supplementalTasty': [{'path': str(p.relative_to(extra)), 'sha256': sha(p)}
                                for p in sorted(pathlib.Path(extra).rglob('*.tasty'))] if extra else []}
pathlib.Path(out + '.provenance.json').write_text(json.dumps(receipt, indent=2) + '\n')
RECEIPT
