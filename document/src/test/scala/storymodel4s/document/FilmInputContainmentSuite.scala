package storymodel4s.document

import munit.FunSuite
import storymodel4s.core.*

/** Exact interval/point containment courts using the admitted mechanical A/B fixture. */
class FilmInputContainmentSuite extends FunSuite:
  import FilmInputFixtures.*
  private val refs = Vector(
    ChartNodeRef(a, evidenceA.chart.conceptIds.head),
    ChartNodeRef(b, evidenceB.chart.conceptIds.head)
  )
  private def check(
      sentences: Vector[SurfaceUnitId],
      refs0: Vector[ChartNodeRef],
      support0: EvidenceSupport
  ) =
    FilmCompilerInput.of(
      prepared,
      associations,
      expected,
      Vector(scope(sentences, refs0, support0))
    )
  private def point(at: Long) = EvidenceSupport
    .of(
      bundle,
      Vector(
        EvidenceAnchor
          .MediaPoint(bundle.id, stream, PlaybackInstant.on(bundle.primaryAxis, at).toOption.get)
      )
    )
    .toOption
    .get

  test("adjacent unit union accepts a covered interval while an omitted-unit interval is refused") {
    assert(check(Vector(a, b), refs, support(50, 150)).isRight)
    assert(check(Vector(a), Vector(refs.head), support(50, 150)).isLeft)
  }
  test("half-open endpoint point is refused while interior point is accepted") {
    assert(check(Vector(a), Vector(refs.head), point(99)).isRight)
    assert(check(Vector(a), Vector(refs.head), point(100)).isLeft)
  }
  test("support on B cannot be licensed by A with matching primary axis") {
    assert(check(Vector(a), Vector(refs.head), support(100, 150)).isLeft)
  }
  test("unsupported anchor kind is refused before containment") {
    val shot = EvidenceSupport
      .of(
        bundle,
        Vector(
          EvidenceAnchor.Shot(
            bundle.id,
            stream,
            ShotId.unsafe("s"),
            PlaybackInterval.on(bundle.primaryAxis, 0, 10).toOption.get
          )
        )
      )
      .toOption
      .get
    assert(check(Vector(a), Vector(refs.head), shot).isLeft)
  }

  private def checkRows(changedRows: Vector[FilmSourceRecord], attempted: EvidenceSupport) =
    val derivation = FilmProposalSurface
      .expectedDerivation(bundle, changedRows, Vector.empty, prepared.derivation.outputChecksum)
      .toOption
      .get
    val changed =
      FilmProposalSurface.derive(bundle, changedRows, Vector.empty, derivation).toOption.get
    assertEquals(changed.origins.map(_.sentence), Vector(a, b))
    val bindings = expected.zip(changedRows).map { case (binding, row) =>
      binding.copy(preparedIdentity = changed.identity, originRecordIdentity = row.identity)
    }
    val charts =
      associations.zip(bindings).map((association, binding) => association.copy(binding = binding))
    FilmCompilerInput.of(changed, charts, bindings, Vector(scope(Vector(a, b), refs, attempted)))

  test("a gap between declared unit envelopes is not filled by their hull") {
    val changed = rows.updated(1, rows(1).copy(support = support(150, 200)))
    assert(checkRows(changed, support(160, 190)).isRight)
    assert(
      checkRows(changed, support(50, 160)).left.toOption.get.toString
        .contains("attempt/containment")
    )
  }

  test("an explicit point permits that point but cannot license an interval") {
    val changed = rows.map(_.copy(support = point(50)))
    assert(checkRows(changed, point(50)).isRight)
    assert(checkRows(changed, point(51)).isLeft)
    assert(checkRows(changed, support(50, 51)).isLeft)
  }

  test("mixed interval and point support preserves the isolated point and gap") {
    val changed = rows.updated(1, rows(1).copy(support = point(150)))
    assert(checkRows(changed, point(150)).isRight)
    assert(checkRows(changed, point(149)).isLeft)
    assert(checkRows(changed, support(90, 150)).isLeft)
  }

  test("unsupported source record support is refused even without compiler attempts") {
    val shot = EvidenceSupport
      .of(
        bundle,
        Vector(
          EvidenceAnchor.Shot(
            bundle.id,
            stream,
            ShotId.unsafe("s"),
            PlaybackInterval.on(bundle.primaryAxis, 0, 10).toOption.get
          )
        )
      )
      .toOption
      .get
    val changed = rows.updated(0, rows.head.copy(support = shot))
    val derivation = FilmProposalSurface
      .expectedDerivation(bundle, changed, Vector.empty, prepared.derivation.outputChecksum)
      .toOption
      .get
    assert(
      FilmProposalSurface
        .derive(bundle, changed, Vector.empty, derivation)
        .left
        .toOption
        .get
        .toString
        .contains("film-input/support")
    )
  }
