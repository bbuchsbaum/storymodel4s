package storymodel4s.acquire

import cats.data.NonEmptyVector
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.*
import storymodel4s.core.*

class OrchestrateSuite extends ScalaCheckSuite:
  import Fixtures.*

  private def s(id: String, deps: String*) =
    StageSpec(StageId.unsafe(id), deps.map(StageId.unsafe).toSet, "1")
  private val specs = Vector(
    s("surface"),
    s("local", "surface"),
    s("document", "local"),
    s("features", "surface"),
    s("hierarchy", "document", "features")
  )
  private val config =
    BuildConfig(cfg, Map(StageId.unsafe("local") -> Fingerprint.unsafe("parser:v1")), fp)
  private val input = Checksum.ofText("story")

  test("topological order is deterministic and respects dependencies"):
    val order = StageDag.topologicalOrder(specs).toOption.get.map(_.value)
    assertEquals(order, Vector("surface", "features", "local", "document", "hierarchy"))
    assertEquals(StageDag.topologicalOrder(specs.reverse).toOption.get.map(_.value), order)

  test("cycles, unknown dependencies, and duplicates are rejected"):
    StageDag.topologicalOrder(Vector(s("a", "b"), s("b", "a"))) match
      case Left(PlanError.Cycle(path)) => assert(path.size >= 3)
      case other                       => fail(s"unexpected $other")
    assert(StageDag.topologicalOrder(Vector(s("a", "zz"))).isLeft)
    assert(StageDag.topologicalOrder(Vector(s("a"), s("a"))).isLeft)

  test("a fresh plan invalidates everything; a replay invalidates nothing"):
    val fresh = BuildPlan.from(specs, config, input, Map.empty).toOption.get
    assertEquals(fresh.invalidated.size, specs.size)
    val prev = fresh.stages.map(p => p.spec.id -> p.key).toMap
    val replay = BuildPlan.from(specs, config, input, prev).toOption.get
    assertEquals(replay.invalidated, Vector.empty)
    assertEquals(replay.cached.size, specs.size)

  test("changing a provider fingerprint invalidates that stage and its descendants only"):
    val prev = BuildPlan
      .from(specs, config, input, Map.empty)
      .toOption
      .get
      .stages
      .map(p => p.spec.id -> p.key)
      .toMap
    val changed = config.copy(providerFingerprints =
      Map(StageId.unsafe("local") -> Fingerprint.unsafe("parser:v2"))
    )
    val plan = BuildPlan.from(specs, changed, input, prev).toOption.get
    assertEquals(plan.invalidated.map(_.value).toSet, Set("local", "document", "hierarchy"))
    assertEquals(plan.cached.map(_.value).toSet, Set("surface", "features"))

  test("changing the source invalidates every stage"):
    val prev = BuildPlan
      .from(specs, config, input, Map.empty)
      .toOption
      .get
      .stages
      .map(p => p.spec.id -> p.key)
      .toMap
    val plan = BuildPlan.from(specs, config, Checksum.ofText("other"), prev).toOption.get
    assertEquals(plan.invalidated.size, specs.size)

  test("budget policy validates and supplies per-stage budgets"):
    assert(BudgetPolicy.of(0, 1000L, 1).isLeft)
    assert(BudgetPolicy.of(2, 0L, 1).isLeft)
    val custom = TaskBudget.unsafe(Some(1000L), 5L, 0)
    val p = BudgetPolicy.of(2, 1000L, 3, Map(StageId.unsafe("local") -> custom)).toOption.get
    assertEquals(p.budgetFor(StageId.unsafe("local")), custom)
    assertEquals(p.budgetFor(StageId.unsafe("surface")).timeoutMillis, 1000L)

  test("gate statuses combine to the worst outcome and keep every message"):
    val w = GateStatus.PassedWithWarnings(NonEmptyVector.one("w1"))
    val b = GateStatus.Blocked(NonEmptyVector.one("r1"), Vector("w2"))
    assertEquals(GateStatus.Passed.combine(w), w)
    val both = w.combine(b)
    assert(both.isBlocked)
    assertEquals(both.allWarnings, Vector("w1", "w2"))
    assertEquals(both.allReasons, Vector("r1"))
    assertEquals(GateStatus.all(Vector(GateStatus.Passed, GateStatus.Passed)), GateStatus.Passed)

  private val gate: Gen[GateStatus] = Gen.oneOf(
    Gen.const(GateStatus.Passed),
    Gen
      .nonEmptyListOf(Gen.alphaStr)
      .map(ws => GateStatus.PassedWithWarnings(NonEmptyVector.fromVectorUnsafe(ws.toVector))),
    for
      rs <- Gen.nonEmptyListOf(Gen.alphaStr)
      ws <- Gen.listOf(Gen.alphaStr)
    yield GateStatus.Blocked(NonEmptyVector.fromVectorUnsafe(rs.toVector), ws.toVector)
  )

  property("gate combination is associative with Passed as identity"):
    forAll(gate, gate, gate) { (a, b, c) =>
      (a.combine(b)).combine(c) == a.combine(b.combine(c)) &&
      GateStatus.Passed.combine(a) == a && a.combine(GateStatus.Passed) == a
    }
