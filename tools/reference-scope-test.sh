#!/usr/bin/env bash

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPT_UNDER_TEST="${REFERENCE_SCOPE_SCRIPT:-$ROOT/tools/reference-scope.sh}"
TMP_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/reference-scope-test.XXXXXX")"
trap 'rm -rf "$TMP_ROOT"' EXIT

assertions=0

fail() {
  printf 'FAIL: %b\n' "$*" >&2
  exit 1
}

assert_status() {
  expected="$1"
  label="$2"
  [ "$scope_status" -eq "$expected" ] \
    || fail "$label: expected exit $expected, got $scope_status\n$scope_output"
  assertions=$((assertions + 1))
}

assert_contains() {
  needle="$1"
  label="$2"
  printf '%s\n' "$scope_output" | grep -Fq "$needle" \
    || fail "$label: missing '$needle'\n$scope_output"
  assertions=$((assertions + 1))
}

assert_not_contains() {
  needle="$1"
  label="$2"
  if printf '%s\n' "$scope_output" | grep -Fq "$needle"; then
    fail "$label: unexpectedly found '$needle'\n$scope_output"
  fi
  assertions=$((assertions + 1))
}

write_build() {
  repo="$1"
  printf '%s\n' \
    'lazy val core = crossProject(JVMPlatform).in(file("core"))' \
    'lazy val features = crossProject(JVMPlatform).in(file("features"))' \
    'lazy val embedBench = project.in(file("embed-bench"))' \
    'lazy val embedGrakern = project.in(file("embed-grakern"))' \
    'lazy val embedOnnx = project.in(file("embed-onnx"))' \
    'val jvmOnlyModules = List("embedGrakern", "embedOnnx", "embedBench")' \
    > "$repo/build.sbt"
}

new_repo() {
  name="$1"
  repo="$TMP_ROOT/$name"
  mkdir -p "$repo"
  git -C "$repo" init -q
  git -C "$repo" config user.name reference-scope-test
  git -C "$repo" config user.email reference-scope-test@example.invalid
  write_build "$repo"
  git -C "$repo" add build.sbt
  git -C "$repo" commit -qm base
}

commit_all() {
  repo="$1"
  message="$2"
  git -C "$repo" add -A
  git -C "$repo" commit -qm "$message"
}

run_scope() {
  repo="$1"
  base="$2"
  head="$3"
  set +e
  scope_output="$(cd "$repo" && bash "$SCRIPT_UNDER_TEST" "$base" "$head" 2>&1)"
  scope_status=$?
  set -e
}

new_repo docs-main
base="$(git -C "$repo" rev-parse HEAD)"
mkdir -p "$repo/docs-site/examples"
printf '%s\n' '@main def inspect(): Unit = println("ok")' > "$repo/docs-site/examples/Inspect.scala"
commit_all "$repo" docs-main
head="$(git -C "$repo" rev-parse HEAD)"
run_scope "$repo" "$base" "$head"
assert_status 0 "@main-only documentation"
assert_contains "no types defined in changed files" "@main-only documentation"

new_repo docs-object
base="$(git -C "$repo" rev-parse HEAD)"
mkdir -p "$repo/docs-site/src/main/scala"
printf '%s\n' 'object DocumentationExample' > "$repo/docs-site/src/main/scala/DocumentationExample.scala"
commit_all "$repo" docs-object
head="$(git -C "$repo" rev-parse HEAD)"
run_scope "$repo" "$base" "$head"
assert_status 0 "object-defining documentation"
assert_contains "no types defined in changed files" "object-defining documentation"
assert_not_contains "docsSite" "object-defining documentation"

new_repo mixed
base="$(git -C "$repo" rev-parse HEAD)"
mkdir -p "$repo/core/src/main/scala" "$repo/docs-site/src/main/scala"
printf '%s\n' 'final case class LibraryThing(value: Int)' > "$repo/core/src/main/scala/LibraryThing.scala"
printf '%s\n' 'object LibraryThing' > "$repo/docs-site/src/main/scala/LibraryThing.scala"
commit_all "$repo" mixed
head="$(git -C "$repo" rev-parse HEAD)"
run_scope "$repo" "$base" "$head"
assert_status 0 "mixed module and documentation change"
assert_contains "  LibraryThing" "mixed module and documentation change"
assert_contains "  core" "mixed module and documentation change"
assert_contains 'sbt -batch "coreJVM/test"' "mixed module and documentation change"
assert_not_contains "docsSite" "mixed module and documentation change"

new_repo no-declaration
base="$(git -C "$repo" rev-parse HEAD)"
mkdir -p "$repo/core/src/main/scala"
printf '%s\n' 'def lowerCaseDefinition: Int = 1' > "$repo/core/src/main/scala/lower.scala"
commit_all "$repo" no-declaration
head="$(git -C "$repo" rev-parse HEAD)"
run_scope "$repo" "$base" "$head"
assert_status 0 "module source without uppercase declaration"
assert_contains "no types defined in changed files" "module source without uppercase declaration"

new_repo deleted-declaration-free-source
mkdir -p "$repo/core/src/main/scala"
printf '%s\n' 'def removedHelper: Int = 1' > "$repo/core/src/main/scala/removed.scala"
commit_all "$repo" add-source
base="$(git -C "$repo" rev-parse HEAD)"
rm "$repo/core/src/main/scala/removed.scala"
commit_all "$repo" delete-source
head="$(git -C "$repo" rev-parse HEAD)"
run_scope "$repo" "$base" "$head"
assert_status 0 "deleted declaration-free module source"
assert_contains "no types defined in changed files" "deleted declaration-free module source"

new_repo deleted-declaration
mkdir -p "$repo/core/src/main/scala"
printf '%s\n' 'object RemovedType' > "$repo/core/src/main/scala/RemovedType.scala"
commit_all "$repo" add-source
base="$(git -C "$repo" rev-parse HEAD)"
rm "$repo/core/src/main/scala/RemovedType.scala"
commit_all "$repo" delete-source
head="$(git -C "$repo" rev-parse HEAD)"
run_scope "$repo" "$base" "$head"
assert_status 3 "deleted unreferenced declaration"
assert_contains "  RemovedType" "deleted unreferenced declaration"
assert_contains "EMPTY SCOPE -- REFUSING TO EMIT A GATE COMMAND." "deleted unreferenced declaration"

new_repo deleted-consumed-declaration
mkdir -p "$repo/core/src/main/scala" "$repo/features/src/main/scala"
printf '%s\n' 'final case class RemovedApi(value: Int)' > "$repo/core/src/main/scala/RemovedApi.scala"
printf '%s\n' 'object Consumer { def use(x: RemovedApi): Int = x.value }' \
  > "$repo/features/src/main/scala/Consumer.scala"
commit_all "$repo" add-consumed-source
base="$(git -C "$repo" rev-parse HEAD)"
rm "$repo/core/src/main/scala/RemovedApi.scala"
commit_all "$repo" delete-consumed-source
head="$(git -C "$repo" rev-parse HEAD)"
run_scope "$repo" "$base" "$head"
assert_status 0 "deleted declaration with surviving consumer"
assert_contains "  RemovedApi" "deleted declaration with surviving consumer"
assert_contains "  features" "deleted declaration with surviving consumer"
assert_contains 'sbt -batch "featuresJVM/test"' "deleted declaration with surviving consumer"

new_repo removed-declaration-from-modified-source
mkdir -p "$repo/core/src/main/scala" "$repo/features/src/main/scala"
printf '%s\n' 'object RemovedMember\nobject KeptMember' > "$repo/core/src/main/scala/Members.scala"
printf '%s\n' 'object Consumer { val value: RemovedMember.type = RemovedMember }' \
  > "$repo/features/src/main/scala/Consumer.scala"
commit_all "$repo" add-members
base="$(git -C "$repo" rev-parse HEAD)"
printf '%s\n' 'object KeptMember' > "$repo/core/src/main/scala/Members.scala"
commit_all "$repo" remove-one-member
head="$(git -C "$repo" rev-parse HEAD)"
run_scope "$repo" "$base" "$head"
assert_status 0 "declaration removed from modified source"
assert_contains "  RemovedMember" "declaration removed from modified source"
assert_contains "  features" "declaration removed from modified source"
assert_contains 'sbt -batch "coreJVM/test; featuresJVM/test"' "declaration removed from modified source"

new_repo qualified-private-declaration
mkdir -p "$repo/core/src/main/scala" "$repo/features/src/main/scala"
printf '%s\n' 'private[storymodel4s] final class InternalApi(val value: Int)' \
  > "$repo/core/src/main/scala/InternalApi.scala"
printf '%s\n' 'object Consumer { def use(x: InternalApi): Int = x.value }' \
  > "$repo/features/src/main/scala/Consumer.scala"
commit_all "$repo" add-qualified-private-api
base="$(git -C "$repo" rev-parse HEAD)"
printf '%s\n' \
  'private[storymodel4s] final class InternalApi(val value: Int):' \
  '  def doubled: Int = value * 2' \
  > "$repo/core/src/main/scala/InternalApi.scala"
commit_all "$repo" modify-qualified-private-api
head="$(git -C "$repo" rev-parse HEAD)"
run_scope "$repo" "$base" "$head"
assert_status 0 "qualified-private modified declaration"
assert_contains "  InternalApi" "qualified-private modified declaration"
assert_contains "  features" "qualified-private modified declaration"
assert_contains 'sbt -batch "coreJVM/test; featuresJVM/test"' "qualified-private modified declaration"

new_repo abstract-declaration
mkdir -p "$repo/core/src/main/scala" "$repo/features/src/main/scala"
printf '%s\n' 'abstract class PublicApi { def value: Int }' \
  > "$repo/core/src/main/scala/PublicApi.scala"
printf '%s\n' 'object Consumer { def use(x: PublicApi): Int = x.value }' \
  > "$repo/features/src/main/scala/Consumer.scala"
commit_all "$repo" add-abstract-api
base="$(git -C "$repo" rev-parse HEAD)"
printf '%s\n' 'abstract class PublicApi { def value: Int; def doubled: Int = value * 2 }' \
  > "$repo/core/src/main/scala/PublicApi.scala"
commit_all "$repo" modify-abstract-api
head="$(git -C "$repo" rev-parse HEAD)"
run_scope "$repo" "$base" "$head"
assert_status 0 "abstract modified declaration"
assert_contains "  PublicApi" "abstract modified declaration"
assert_contains "  features" "abstract modified declaration"
assert_contains 'sbt -batch "coreJVM/test; featuresJVM/test"' "abstract modified declaration"

new_repo open-declaration
mkdir -p "$repo/core/src/main/scala" "$repo/features/src/main/scala"
printf '%s\n' 'open class OpenApi(val value: Int)' > "$repo/core/src/main/scala/OpenApi.scala"
printf '%s\n' 'object Consumer { def use(x: OpenApi): Int = x.value }' \
  > "$repo/features/src/main/scala/Consumer.scala"
commit_all "$repo" add-open-api
base="$(git -C "$repo" rev-parse HEAD)"
printf '%s\n' 'open class OpenApi(val value: Int) { def doubled: Int = value * 2 }' \
  > "$repo/core/src/main/scala/OpenApi.scala"
commit_all "$repo" modify-open-api
head="$(git -C "$repo" rev-parse HEAD)"
run_scope "$repo" "$base" "$head"
assert_status 0 "open modified declaration"
assert_contains "  OpenApi" "open modified declaration"
assert_contains "  features" "open modified declaration"
assert_contains 'sbt -batch "coreJVM/test; featuresJVM/test"' "open modified declaration"

new_repo transparent-declarations
mkdir -p "$repo/core/src/main/scala" "$repo/features/src/main/scala"
printf '%s\n' \
  'transparent trait TransparentApi:' \
  '  def value: Int' \
  'transparent class TransparentClassApi(val value: Int)' \
  > "$repo/core/src/main/scala/TransparentApi.scala"
printf '%s\n' \
  'object Consumer:' \
  '  def traitValue(x: TransparentApi): Int = x.value' \
  '  def classValue(x: TransparentClassApi): Int = x.value' \
  > "$repo/features/src/main/scala/Consumer.scala"
commit_all "$repo" add-transparent-apis
base="$(git -C "$repo" rev-parse HEAD)"
printf '%s\n' \
  'transparent trait TransparentApi:' \
  '  def value: Int' \
  '  def doubled: Int = value * 2' \
  'transparent class TransparentClassApi(val value: Int):' \
  '  def doubled: Int = value * 2' \
  > "$repo/core/src/main/scala/TransparentApi.scala"
commit_all "$repo" modify-transparent-apis
head="$(git -C "$repo" rev-parse HEAD)"
run_scope "$repo" "$base" "$head"
assert_status 0 "transparent modified declarations"
assert_contains "  TransparentApi" "transparent modified declarations"
assert_contains "  TransparentClassApi" "transparent modified declarations"
assert_contains "  features" "transparent modified declarations"
assert_contains 'sbt -batch "coreJVM/test; featuresJVM/test"' "transparent modified declarations"

new_repo implicit-class-declaration
mkdir -p "$repo/core/src/main/scala" "$repo/features/src/main/scala"
printf '%s\n' \
  'implicit class ImplicitApi(val value: Int):' \
  '  def doubled: Int = value * 2' \
  > "$repo/core/src/main/scala/ImplicitApi.scala"
printf '%s\n' 'object Consumer { def use(x: ImplicitApi): Int = x.value }' \
  > "$repo/features/src/main/scala/Consumer.scala"
commit_all "$repo" add-implicit-class-api
base="$(git -C "$repo" rev-parse HEAD)"
printf '%s\n' \
  'implicit class ImplicitApi(val value: Int):' \
  '  def doubled: Int = value * 2' \
  '  def tripled: Int = value * 3' \
  > "$repo/core/src/main/scala/ImplicitApi.scala"
commit_all "$repo" modify-implicit-class-api
head="$(git -C "$repo" rev-parse HEAD)"
run_scope "$repo" "$base" "$head"
assert_status 0 "implicit-class modified declaration"
assert_contains "  ImplicitApi" "implicit-class modified declaration"
assert_contains "  features" "implicit-class modified declaration"
assert_contains 'sbt -batch "coreJVM/test; featuresJVM/test"' "implicit-class modified declaration"

new_repo infix-type-declaration
mkdir -p "$repo/core/src/main/scala" "$repo/features/src/main/scala"
printf '%s\n' 'infix type ScopeOr[A, B] = Either[A, B]' \
  > "$repo/core/src/main/scala/ScopeOr.scala"
printf '%s\n' 'object Consumer { def use(x: String ScopeOr Int): String ScopeOr Int = x }' \
  > "$repo/features/src/main/scala/Consumer.scala"
commit_all "$repo" add-infix-type-api
base="$(git -C "$repo" rev-parse HEAD)"
printf '%s\n' 'infix type ScopeOr[A, B] = Either[A, B] | Tuple2[A, B]' \
  > "$repo/core/src/main/scala/ScopeOr.scala"
commit_all "$repo" modify-infix-type-api
head="$(git -C "$repo" rev-parse HEAD)"
run_scope "$repo" "$base" "$head"
assert_status 0 "infix-type modified declaration"
assert_contains "  ScopeOr" "infix-type modified declaration"
assert_contains "  features" "infix-type modified declaration"
assert_contains 'sbt -batch "coreJVM/test; featuresJVM/test"' "infix-type modified declaration"

new_repo unreferenced
base="$(git -C "$repo" rev-parse HEAD)"
mkdir -p "$repo/core/src/main/scala"
printf '%s\n' 'object CandidateOnlyType' > "$repo/core/src/main/scala/CandidateOnlyType.scala"
commit_all "$repo" candidate-only
head="$(git -C "$repo" rev-parse HEAD)"
git -C "$repo" checkout -q "$base"
run_scope "$repo" "$base" "$head"
assert_status 3 "candidate type absent from checkout"
assert_contains "EMPTY SCOPE -- REFUSING TO EMIT A GATE COMMAND." "candidate type absent from checkout"

new_repo jvm-only
base="$(git -C "$repo" rev-parse HEAD)"
for module in embed-bench embed-grakern embed-onnx; do
  mkdir -p "$repo/$module/src/main/scala"
done
printf '%s\n' 'object BenchOnlyType' > "$repo/embed-bench/src/main/scala/BenchOnlyType.scala"
printf '%s\n' 'object GrakernOnlyType' > "$repo/embed-grakern/src/main/scala/GrakernOnlyType.scala"
printf '%s\n' 'object OnnxOnlyType' > "$repo/embed-onnx/src/main/scala/OnnxOnlyType.scala"
commit_all "$repo" jvm-only
head="$(git -C "$repo" rev-parse HEAD)"
run_scope "$repo" "$base" "$head"
assert_status 0 "JVM-only project mapping"
assert_contains 'sbt -batch "embedBench/test; embedGrakern/test; embedOnnx/test"' "JVM-only project mapping"
assert_not_contains "embedBenchJVM" "JVM-only project mapping"
assert_not_contains "embedGrakernJVM" "JVM-only project mapping"
assert_not_contains "embedOnnxJVM" "JVM-only project mapping"

new_repo jvm-platform-consumer
mkdir -p "$repo/features/.jvm/src/test/scala"
printf '%s\n' 'object JvmConsumer { def use(x: SharedApi): Int = x.value }' \
  > "$repo/features/.jvm/src/test/scala/JvmConsumer.scala"
commit_all "$repo" add-jvm-consumer
base="$(git -C "$repo" rev-parse HEAD)"
mkdir -p "$repo/core/src/main/scala"
printf '%s\n' 'final case class SharedApi(value: Int)' > "$repo/core/src/main/scala/SharedApi.scala"
commit_all "$repo" add-shared-api
head="$(git -C "$repo" rev-parse HEAD)"
run_scope "$repo" "$base" "$head"
assert_status 0 "shared declaration with JVM-platform consumer"
assert_contains "  SharedApi" "shared declaration with JVM-platform consumer"
assert_contains "  features" "shared declaration with JVM-platform consumer"
assert_contains 'sbt -batch "coreJVM/test; featuresJVM/test"' "shared declaration with JVM-platform consumer"

new_repo jvm-platform-declaration
mkdir -p "$repo/features/src/main/scala"
printf '%s\n' 'object SharedConsumer { def use(x: JvmApi): Int = x.value }' \
  > "$repo/features/src/main/scala/SharedConsumer.scala"
commit_all "$repo" add-shared-consumer
base="$(git -C "$repo" rev-parse HEAD)"
mkdir -p "$repo/core/.jvm/src/main/scala"
printf '%s\n' 'final case class JvmApi(value: Int)' > "$repo/core/.jvm/src/main/scala/JvmApi.scala"
commit_all "$repo" add-jvm-api
head="$(git -C "$repo" rev-parse HEAD)"
run_scope "$repo" "$base" "$head"
assert_status 0 "JVM-platform declaration"
assert_contains "  JvmApi" "JVM-platform declaration"
assert_contains "  features" "JVM-platform declaration"
assert_contains 'sbt -batch "coreJVM/test; featuresJVM/test"' "JVM-platform declaration"

for platform in js native; do
  new_repo "unsupported-$platform-platform"
  base="$(git -C "$repo" rev-parse HEAD)"
  mkdir -p "$repo/core/.$platform/src/main/scala"
  printf '%s\n' "object ${platform}OnlyApi" > "$repo/core/.$platform/src/main/scala/PlatformApi.scala"
  commit_all "$repo" "add-$platform-api"
  head="$(git -C "$repo" rev-parse HEAD)"
  run_scope "$repo" "$base" "$head"
  assert_status 4 "unsupported .$platform platform source"
  assert_contains "unsupported changed platform source for JVM-only gate:" "unsupported .$platform platform source"
  assert_contains "core/.$platform/src/main/scala/PlatformApi.scala" "unsupported .$platform platform source"
done

for platform in js native; do
  new_repo "shared-api-with-$platform-consumer"
  mkdir -p "$repo/core/src/main/scala" "$repo/features/.$platform/src/test/scala"
  printf '%s\n' 'final case class SharedPlatformApi(value: Int)' \
    > "$repo/core/src/main/scala/SharedPlatformApi.scala"
  printf '%s\n' \
    'object PlatformConsumer { def use(x: SharedPlatformApi): Int = x.value }' \
    > "$repo/features/.$platform/src/test/scala/PlatformConsumer.scala"
  commit_all "$repo" "add-shared-api-and-$platform-consumer"
  base="$(git -C "$repo" rev-parse HEAD)"
  printf '%s\n' \
    'final case class SharedPlatformApi(value: Int) { def doubled: Int = value * 2 }' \
    > "$repo/core/src/main/scala/SharedPlatformApi.scala"
  commit_all "$repo" "modify-shared-api-with-$platform-consumer"
  head="$(git -C "$repo" rev-parse HEAD)"
  run_scope "$repo" "$base" "$head"
  assert_status 4 "shared declaration with .$platform-only consumer"
  assert_contains \
    "unsupported platform-specific consumer for JVM-only gate:" \
    "shared declaration with .$platform-only consumer"
  assert_contains \
    "features: features/.$platform/src/test/scala/PlatformConsumer.scala" \
    "shared declaration with .$platform-only consumer"
  assert_contains \
    "refusing partial scope" \
    "shared declaration with .$platform-only consumer"
  assert_not_contains \
    "gate command (JVM; paste it, do not retype the module names):" \
    "shared declaration with .$platform-only consumer"
  assert_not_contains \
    'sbt -batch' \
    "shared declaration with .$platform-only consumer"
done

echo "PASS: reference-scope regression court ($assertions assertions)"
