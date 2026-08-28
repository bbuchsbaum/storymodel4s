package storymodel4s.laws

import cats.data.NonEmptyVector
import org.scalacheck.{Arbitrary, Gen}
import storymodel4s.core.*
import storymodel4s.core.NarrativeKind.*
import storymodel4s.features.{FeatureAddress, FeatureTarget}
import storymodel4s.proposition.ParticipantRole
import storymodel4s.story.*
import storymodel4s.recall.{RecallEntityId, RecallRef, RecallTemporalRelation, RecallUnitId}
import storymodel4s.align.{AlignRef, AlignState, ExternalState, SourceNodeRef}

/** Generators for the address protocol and every module's typed reference. */
object AddressGens:
  private val id: Gen[String] = CoreGens.idString

  /** Any string, including separators, escapes, spaces, and non-ASCII; no lone surrogates. */
  val anyPart: Gen[String] = Gen.oneOf(
    Gen.asciiStr,
    Gen
      .listOf(Gen.chooseNum(0x20, 0xffff).map(_.toChar).suchThat(c => !Character.isSurrogate(c)))
      .map(_.mkString),
    Gen.oneOf("", "/", "%", "%2F", "a/b", "tag:kind", "x y", "é/ß%")
  )

  val moduleTag: Gen[ModuleTag] =
    for
      h <- Gen.alphaLowerChar
      t <- Gen.listOf(Gen.oneOf(Gen.alphaLowerChar, Gen.numChar, Gen.const('-'))).map(_.take(31))
    yield ModuleTag.unsafe((h :: t).mkString)

  val addressKind: Gen[AddressKind] = moduleTag.map(t => AddressKind.unsafe(t.value))

  val addressKey: Gen[AddressKey] =
    Gen
      .nonEmptyListOf(anyPart)
      .map(ps => AddressKey.of(NonEmptyVector.fromVectorUnsafe(ps.toVector)))

  val address: Gen[Address] =
    for
      t <- moduleTag
      k <- addressKind
      key <- addressKey
    yield Address(t, k, key)

  val tokenRange: Gen[TokenRange] =
    for
      a <- Gen.chooseNum(0, 1000)
      b <- Gen.chooseNum(0, 1000)
    yield TokenRange.unsafe(math.min(a, b), math.max(a, b))

  val coreRef: Gen[CoreRef] = Gen.oneOf(
    id.map(s => CoreRef.Story(StoryId.unsafe(s))),
    id.map(s => CoreRef.SurfaceUnit(SurfaceUnitId.unsafe(s))),
    tokenRange.map(CoreRef.Tokens.apply),
    CoreGens.spanSet.map(CoreRef.Spans.apply),
    id.map(s => CoreRef.Claim(ClaimId.unsafe(s))),
    id.map(s => CoreRef.Evidence(EvidenceId.unsafe(s)))
  )

  private val sit: Gen[SituationId] = id.map(SituationId.unsafe)
  private val seg: Gen[SegmentId] = id.map(SegmentId.unsafe)
  private val ent: Gen[EntityId] = id.map(EntityId.unsafe)
  private val ctx: Gen[ContextId] = id.map(ContextId.unsafe)

  val participantRole: Gen[ParticipantRole] =
    import ParticipantRole.*
    Gen.frequency(
      14 -> Gen.oneOf(
        Agent,
        Patient,
        Theme,
        Experiencer,
        Stimulus,
        Instrument,
        Beneficiary,
        Source,
        Destination,
        Location,
        Time,
        Manner,
        Cause,
        Result
      ),
      2 -> (for
        ns <- anyPart
        l <- anyPart
      yield Custom(ns, l))
    )

  val entityRelation: Gen[EntityRelation] =
    Gen.frequency(
      3 -> Gen.oneOf(EntityRelation.MemberOf, EntityRelation.PartOf, EntityRelation.SameAs),
      1 -> (for
        ns <- anyPart
        l <- anyPart
      yield EntityRelation.Custom(ns, l))
    )

  val narrativeMember: Gen[NarrativeMember] =
    Gen.oneOf(sit.map(NarrativeMember.Situation.apply), seg.map(NarrativeMember.Segment.apply))

  val storyRef: Gen[StoryRef] = Gen.oneOf(
    sit.map(StoryRef.Situation.apply),
    seg.map(StoryRef.Segment.apply),
    ent.map(StoryRef.Entity.apply),
    ctx.map(StoryRef.Context.apply),
    for
      s <- sit
      r <- participantRole
      e <- ent
    yield StoryRef.Participant(s, r, e),
    for
      f <- sit
      r <- Gen.oneOf(TemporalRelation.values.toSeq)
      t <- sit
      c <- ctx
    yield StoryRef.Temporal(f, r, t, c),
    for
      f <- sit
      r <- Gen.oneOf(CausalRelation.values.toSeq)
      t <- sit
    yield StoryRef.Causal(f, r, t),
    for
      f <- sit
      r <- Gen.oneOf(GoalRelation.values.toSeq)
      t <- sit
    yield StoryRef.Goal(f, r, t),
    for
      f <- sit
      r <- Gen.oneOf(StateChangeKind.values.toSeq)
      t <- sit
    yield StoryRef.StateChange(f, r, t),
    for
      f <- sit
      r <- Gen.oneOf(NarrativeReference.values.toSeq)
      t <- sit
    yield StoryRef.Reference(f, r, t),
    for
      m <- narrativeMember
      p <- seg
      k <- Gen.oneOf(HierarchyKind.values.toSeq)
    yield StoryRef.Containment(m, p, k),
    for
      f <- ent
      r <- entityRelation
      t <- ent
    yield StoryRef.EntityLink(f, r, t)
  )

  val featureTarget: Gen[FeatureTarget] = Gen.oneOf(
    Gen.chooseNum(0, 5000).map(i => FeatureTarget.Token(TokenIndex.unsafe(i))),
    id.map(s => FeatureTarget.Sentence(SurfaceUnitId.unsafe(s))),
    sit.map(FeatureTarget.Situation.apply),
    seg.map(FeatureTarget.Segment.apply),
    id.map(s => FeatureTarget.Boundary(SurfaceUnitId.unsafe(s))),
    id.map(s => FeatureTarget.Turn(TurnId.unsafe(s))),
    tokenRange.map(FeatureTarget.Window.apply),
    id.map(s => FeatureTarget.SurfaceUnit(SurfaceUnitId.unsafe(s)))
  )

  val featureAddress: Gen[FeatureAddress] = Gen.oneOf(
    id.map(s => FeatureAddress.Space(FeatureSpaceId.unsafe(s))),
    for
      s <- id
      t <- featureTarget
    yield FeatureAddress.Observation(FeatureSpaceId.unsafe(s), t),
    Gen.alphaNumStr.map(s => FeatureAddress.Derivation(Checksum.ofText(s)))
  )

  private val runit: Gen[RecallUnitId] = id.map(RecallUnitId.unsafe)

  val recallRef: Gen[RecallRef] = Gen.oneOf(
    runit.map(RecallRef.Unit.apply),
    id.map(s => RecallRef.Entity(RecallEntityId.unsafe(s))),
    for
      f <- runit
      r <- Gen.oneOf(RecallTemporalRelation.values.toSeq)
      t <- runit
    yield RecallRef.Temporal(f, r, t),
    for
      f <- runit
      t <- runit
    yield RecallRef.Causal(f, t),
    for
      f <- runit
      t <- runit
    yield RecallRef.Elaboration(f, t)
  )

  val alignState: Gen[AlignState] = Gen.oneOf(
    sit.map(s => AlignState.Source(SourceNodeRef.Situation(s))),
    seg.map(s => AlignState.Source(SourceNodeRef.Segment(s))),
    Gen.oneOf(ExternalState.values.toSeq).map(AlignState.External.apply)
  )

  val alignRef: Gen[AlignRef] = Gen.oneOf(
    for
      u <- runit
      s <- alignState
    yield AlignRef.Cell(u, s),
    for
      f <- runit
      t <- runit
      a <- alignState
      b <- alignState
    yield AlignRef.Transition(f, t, a, b)
  )

  given Arbitrary[Address] = Arbitrary(address)
  given Arbitrary[RecallRef] = Arbitrary(recallRef)
  given Arbitrary[AlignRef] = Arbitrary(alignRef)
  given Arbitrary[CoreRef] = Arbitrary(coreRef)
  given Arbitrary[StoryRef] = Arbitrary(storyRef)
  given Arbitrary[FeatureAddress] = Arbitrary(featureAddress)
