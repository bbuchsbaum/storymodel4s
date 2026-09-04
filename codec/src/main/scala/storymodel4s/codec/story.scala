package storymodel4s.codec

import cats.data.NonEmptyVector
import io.circe.{Decoder, DecodingFailure, Encoder, Json}
import io.circe.syntax.*
import storymodel4s.core.*
import storymodel4s.core.NarrativeKind.{EntityK, SituationK}
import storymodel4s.features.{
  Estimate,
  FeatureRef,
  FeatureSpace,
  SidecarManifest,
  WorldTimeTransition
}
import storymodel4s.proposition.ParticipantRole
import storymodel4s.story.*
import CanonicalPrimitives.{*, given}
import CoreCodecs.given
import FeatureCodecs.given
import PropositionCodecs.given

/** Codecs for `storymodel4s.story` and the `StoryModel` artifact.
  *
  * Status is never on the wire: decoding yields `StoryModel[Draft]` and consumers revalidate. The
  * content checksum is computed over the same canonical text, so `Draft` and `Validated` of the
  * same content hash equal.
  */
object StoryCodecs:
  // ---- nodes ----------------------------------------------------------------------------
  given Encoder[EntityType] = Encoder.instance {
    case EntityType.Custom(ns, l) =>
      Json.obj("type" -> "Custom".asJson, "namespace" -> ns.asJson, "label" -> l.asJson)
    case t => t.toString.asJson
  }
  given Decoder[EntityType] = Decoder.instance { c =>
    c.value.asString match
      case Some(s) =>
        Vector(
          EntityType.Person,
          EntityType.Group,
          EntityType.Object,
          EntityType.Location,
          EntityType.Abstract
        )
          .find(v => v.toString == s)
          .toRight(DecodingFailure(s"unknown EntityType $s", c.history))
      case None =>
        for
          ns <- field[String](c, "namespace")
          l <- field[String](c, "label")
        yield EntityType.Custom(ns, l)
  }

  given Encoder[ScopedAttribute] = Encoder.instance { a =>
    Json.obj(
      "context" -> a.context.asJson,
      "key" -> a.key.asJson,
      "value" -> a.value.asJson,
      "meta" -> a.meta.asJson
    )
  }
  given Decoder[ScopedAttribute] = Decoder.instance { c =>
    for
      ctx <- field[ContextId](c, "context")
      k <- field[String](c, "key")
      v <- field[String](c, "value")
      m <- field[ClaimMeta](c, "meta")
    yield ScopedAttribute(ctx, k, v, m)
  }

  private def nev[A](
      c: io.circe.HCursor,
      what: String,
      v: Vector[A]
  ): Decoder.Result[NonEmptyVector[A]] =
    NonEmptyVector
      .fromVector(v)
      .toRight(DecodingFailure(s"$what requires at least one element", c.history))

  given Encoder[EntityNode] = Encoder.instance { e =>
    Json.obj(
      "id" -> e.id.asJson,
      "label" -> e.label.asJson,
      "entityType" -> e.entityType.asJson,
      "mentions" -> e.mentions.toVector.asJson,
      "attributes" -> e.attributes.asJson,
      "support" -> e.support.asJson,
      "meta" -> e.meta.asJson
    )
  }
  given Decoder[EntityNode] = Decoder.instance { c =>
    for
      id <- field[EntityId](c, "id")
      l <- field[Resolved[String]](c, "label")
      t <- field[EntityType](c, "entityType")
      ms <- field[Vector[MentionId[EntityK]]](c, "mentions")
      mn <- nev(c, "entity mentions", ms)
      at <- field[Vector[ScopedAttribute]](c, "attributes")
      su <- field[SpanSet](c, "support")
      me <- field[ClaimMeta](c, "meta")
    yield EntityNode(id, l, t, mn, at, su, me)
  }

  given Encoder[Predicate] = Encoder.instance(p =>
    obj("lemma" -> p.lemma.asJson, "frame" -> opt(p.frame), "gloss" -> p.gloss.asJson)
  )
  given Decoder[Predicate] = Decoder.instance { c =>
    for
      l <- field[String](c, "lemma")
      f <- field[Option[String]](c, "frame")
      g <- field[String](c, "gloss")
    yield Predicate(l, f, g)
  }

  given Encoder[storymodel4s.story.Polarity] = enumEncoder(_.toString)
  given Decoder[storymodel4s.story.Polarity] =
    enumDecoder("Polarity", storymodel4s.story.Polarity.values, _.toString)
  given Encoder[Modality] = enumEncoder(_.toString)
  given Decoder[Modality] = enumDecoder("Modality", Modality.values, _.toString)
  given Encoder[Aspect] = enumEncoder(_.toString)
  given Decoder[Aspect] = enumDecoder("Aspect", Aspect.values, _.toString)

  given Encoder[EventNode] = Encoder.instance { n =>
    obj(
      "id" -> n.id.asJson,
      "predicate" -> n.predicate.asJson,
      "description" -> n.description.asJson,
      "context" -> n.context.asJson,
      "polarity" -> n.polarity.asJson,
      "modality" -> n.modality.asJson,
      "aspect" -> opt(n.aspect),
      "support" -> n.support.asJson,
      "mentions" -> n.mentions.toVector.asJson,
      "meta" -> n.meta.asJson
    )
  }
  given Decoder[EventNode] = Decoder.instance { c =>
    for
      id <- field[SituationId](c, "id")
      p <- field[Predicate](c, "predicate")
      d <- field[String](c, "description")
      ctx <- field[ContextId](c, "context")
      po <- field[storymodel4s.story.Polarity](c, "polarity")
      mo <- field[Modality](c, "modality")
      as <- field[Option[Aspect]](c, "aspect")
      su <- field[SpanSet](c, "support")
      ms <- field[Vector[MentionId[SituationK]]](c, "mentions")
      mn <- nev(c, "situation mentions", ms)
      me <- field[ClaimMeta](c, "meta")
    yield EventNode(id, p, d, ctx, po, mo, as, su, mn, me)
  }

  given Encoder[StateNode] = Encoder.instance { n =>
    Json.obj(
      "id" -> n.id.asJson,
      "predicate" -> n.predicate.asJson,
      "description" -> n.description.asJson,
      "context" -> n.context.asJson,
      "polarity" -> n.polarity.asJson,
      "modality" -> n.modality.asJson,
      "support" -> n.support.asJson,
      "mentions" -> n.mentions.toVector.asJson,
      "meta" -> n.meta.asJson
    )
  }
  given Decoder[StateNode] = Decoder.instance { c =>
    for
      id <- field[SituationId](c, "id")
      p <- field[Predicate](c, "predicate")
      d <- field[String](c, "description")
      ctx <- field[ContextId](c, "context")
      po <- field[storymodel4s.story.Polarity](c, "polarity")
      mo <- field[Modality](c, "modality")
      su <- field[SpanSet](c, "support")
      ms <- field[Vector[MentionId[SituationK]]](c, "mentions")
      mn <- nev(c, "situation mentions", ms)
      me <- field[ClaimMeta](c, "meta")
    yield StateNode(id, p, d, ctx, po, mo, su, mn, me)
  }

  given Encoder[SituationNode] = Encoder.instance {
    case SituationNode.Event(n) => Json.obj("type" -> "Event".asJson, "node" -> n.asJson)
    case SituationNode.State(n) => Json.obj("type" -> "State".asJson, "node" -> n.asJson)
  }
  given Decoder[SituationNode] = Decoder.instance { c =>
    field[String](c, "type").flatMap {
      case "Event" => field[EventNode](c, "node").map(SituationNode.Event.apply)
      case "State" => field[StateNode](c, "node").map(SituationNode.State.apply)
      case o       => Left(DecodingFailure(s"unknown SituationNode $o", c.history))
    }
  }

  given Encoder[SegmentKind] = enumEncoder(_.toString)
  given Decoder[SegmentKind] = enumDecoder("SegmentKind", SegmentKind.values, _.toString)

  given Encoder[SummaryGap] = Encoder.instance(_.render.asJson)
  given Decoder[SummaryGap] = Decoder[String].emap {
    case "not-proposed" => Right(SummaryGap.NotProposed)
    case "not-accepted" => Right(SummaryGap.NotAccepted)
    case "not-emitted"  => Right(SummaryGap.NotEmitted)
    case o              => Left(s"unknown SummaryGap $o")
  }

  /** Why an absent summary is a tagged object and not an absent field: as with [[ContextHolder]], a
    * summary the compiler could not derive and a summary a writer left out must not share a wire
    * shape.
    */
  given Encoder[SegmentSummary] = Encoder.instance {
    case SegmentSummary.Stated(r) =>
      Json.obj("summary" -> "stated".asJson, "value" -> r.asJson)
    case SegmentSummary.Unsummarized(gap) =>
      Json.obj("summary" -> "unsummarized".asJson, "gap" -> gap.asJson)
  }
  given Decoder[SegmentSummary] = Decoder.instance { c =>
    field[String](c, "summary").flatMap {
      case "stated"       => field[Resolved[String]](c, "value").map(SegmentSummary.Stated(_))
      case "unsummarized" => field[SummaryGap](c, "gap").map(SegmentSummary.Unsummarized(_))
      case o              => Left(DecodingFailure(s"unknown SegmentSummary tag $o", c.history))
    }
  }

  given Encoder[SegmentNode] = Encoder.instance { s =>
    Json.obj(
      "id" -> s.id.asJson,
      "kind" -> s.kind.asJson,
      "level" -> s.level.asJson,
      "meta" -> s.meta.asJson,
      "summary" -> s.summary.asJson,
      "support" -> s.support.asJson
    )
  }
  given Decoder[SegmentNode] = Decoder.instance { c =>
    for
      id <- field[SegmentId](c, "id")
      k <- field[SegmentKind](c, "kind")
      l <- field[Int](c, "level")
      m <- field[ClaimMeta](c, "meta")
      s <- field[SegmentSummary](c, "summary")
      su <- field[SpanSet](c, "support")
    yield SegmentNode(id, k, l, m, s, su)
  }

  given Encoder[HolderGap] = Encoder.instance(_.render.asJson)
  given Decoder[HolderGap] = Decoder[String].emap {
    case "no-candidate"         => Right(HolderGap.NoCandidate)
    case "several-candidates"   => Right(HolderGap.SeveralCandidates)
    case "unresolved-candidate" => Right(HolderGap.UnresolvedCandidate)
    case o                      => Left(s"unknown HolderGap $o")
  }

  /** Why an unattributed holder is a tagged object and not an absent field: a missing `holder` and
    * a holder the compiler could not derive must not share a wire shape, or every reader downstream
    * inherits the conflation the type was introduced to remove.
    */
  given Encoder[ContextHolder] = Encoder.instance {
    case ContextHolder.Named(e) =>
      Json.obj("holder" -> "named".asJson, "entity" -> e.asJson)
    case ContextHolder.Unattributed(gap) =>
      Json.obj("holder" -> "unattributed".asJson, "gap" -> gap.asJson)
  }
  given Decoder[ContextHolder] = Decoder.instance { c =>
    field[String](c, "holder").flatMap {
      case "named"        => field[EntityId](c, "entity").map(ContextHolder.Named(_))
      case "unattributed" => field[HolderGap](c, "gap").map(ContextHolder.Unattributed(_))
      case o              => Left(DecodingFailure(s"unknown ContextHolder $o", c.history))
    }
  }

  given Encoder[ContextKind] = Encoder.instance { k =>
    k.heldBy match
      case Some(h) => Json.obj("type" -> k.productPrefix.asJson, "holder" -> h.asJson)
      case None    => k.toString.asJson
  }
  given Decoder[ContextKind] = Decoder.instance { c =>
    c.value.asString match
      case Some("NarratedWorld")  => Right(ContextKind.NarratedWorld)
      case Some("Hypothetical")   => Right(ContextKind.Hypothetical)
      case Some("Counterfactual") => Right(ContextKind.Counterfactual)
      case Some(o)                => Left(DecodingFailure(s"unknown ContextKind $o", c.history))
      case None                   =>
        for
          t <- field[String](c, "type")
          h <- field[ContextHolder](c, "holder")
          k <- t match
            case "Speech"      => Right(ContextKind.Speech(h))
            case "Belief"      => Right(ContextKind.Belief(h))
            case "Desire"      => Right(ContextKind.Desire(h))
            case "Intention"   => Right(ContextKind.Intention(h))
            case "Memory"      => Right(ContextKind.Memory(h))
            case "Imagination" => Right(ContextKind.Imagination(h))
            case o             => Left(DecodingFailure(s"unknown ContextKind $o", c.history))
        yield k
  }

  given Encoder[ContextFrame] = Encoder.instance { f =>
    obj(
      "id" -> f.id.asJson,
      "parent" -> opt(f.parent),
      "kind" -> f.kind.asJson,
      "support" -> f.support.asJson,
      "meta" -> f.meta.asJson
    )
  }
  given Decoder[ContextFrame] = Decoder.instance { c =>
    for
      id <- field[ContextId](c, "id")
      p <- field[Option[ContextId]](c, "parent")
      k <- field[ContextKind](c, "kind")
      su <- field[SpanSet](c, "support")
      m <- field[ClaimMeta](c, "meta")
    yield ContextFrame(id, p, k, su, m)
  }

  given Encoder[DescriptorKind] = enumEncoder(_.toString)
  given Decoder[DescriptorKind] = enumDecoder("DescriptorKind", DescriptorKind.values, _.toString)

  given Encoder[DescriptorClaim] = Encoder.instance { d =>
    Json.obj(
      "target" -> d.target.asJson,
      "kind" -> d.kind.asJson,
      "text" -> d.text.asJson,
      "meta" -> d.meta.asJson
    )
  }
  given Decoder[DescriptorClaim] = Decoder.instance { c =>
    for
      t <- field[SegmentId](c, "target")
      k <- field[DescriptorKind](c, "kind")
      x <- field[String](c, "text")
      m <- field[ClaimMeta](c, "meta")
    yield DescriptorClaim(t, k, x, m)
  }

  given Encoder[HypothesisClaim] =
    Encoder.instance(h => Json.obj("subject" -> h.subject.asJson, "reading" -> h.reading.asJson))
  given Decoder[HypothesisClaim] = Decoder.instance { c =>
    for
      s <- field[SituationId](c, "subject")
      r <- field[Resolved[String]](c, "reading")
    yield HypothesisClaim(s, r)
  }

  // ---- relations ------------------------------------------------------------------------
  given Encoder[ParticipantEdge] = Encoder.instance { e =>
    Json.obj(
      "situation" -> e.situation.asJson,
      "role" -> e.role.asJson,
      "entity" -> e.entity.asJson,
      "meta" -> e.meta.asJson
    )
  }
  given Decoder[ParticipantEdge] = Decoder.instance { c =>
    for
      s <- field[SituationId](c, "situation")
      r <- field[ParticipantRole](c, "role")
      e <- field[EntityId](c, "entity")
      m <- field[ClaimMeta](c, "meta")
    yield ParticipantEdge(s, r, e, m)
  }

  given Encoder[TemporalRelation] = enumEncoder(_.toString)
  given Decoder[TemporalRelation] =
    enumDecoder("TemporalRelation", TemporalRelation.values, _.toString)

  given Encoder[TemporalEdge] = Encoder.instance { e =>
    Json.obj(
      "from" -> e.from.asJson,
      "relation" -> e.relation.asJson,
      "to" -> e.to.asJson,
      "context" -> e.context.asJson,
      "meta" -> e.meta.asJson
    )
  }
  given Decoder[TemporalEdge] = Decoder.instance { c =>
    for
      f <- field[SituationId](c, "from")
      r <- field[TemporalRelation](c, "relation")
      t <- field[SituationId](c, "to")
      ctx <- field[ContextId](c, "context")
      m <- field[ClaimMeta](c, "meta")
    yield TemporalEdge(f, r, t, ctx, m)
  }

  given Encoder[CausalRelation] = enumEncoder(_.toString)
  given Decoder[CausalRelation] = enumDecoder("CausalRelation", CausalRelation.values, _.toString)
  given Encoder[CausalEdge] = Encoder.instance { e =>
    Json.obj(
      "cause" -> e.cause.asJson,
      "relation" -> e.relation.asJson,
      "effect" -> e.effect.asJson,
      "meta" -> e.meta.asJson
    )
  }
  given Decoder[CausalEdge] = Decoder.instance { c =>
    for
      a <- field[SituationId](c, "cause")
      r <- field[CausalRelation](c, "relation")
      b <- field[SituationId](c, "effect")
      m <- field[ClaimMeta](c, "meta")
    yield CausalEdge(a, r, b, m)
  }

  given Encoder[GoalRelation] = enumEncoder(_.toString)
  given Decoder[GoalRelation] = enumDecoder("GoalRelation", GoalRelation.values, _.toString)
  given Encoder[GoalEdge] = Encoder.instance { e =>
    Json.obj(
      "from" -> e.from.asJson,
      "relation" -> e.relation.asJson,
      "to" -> e.to.asJson,
      "meta" -> e.meta.asJson
    )
  }
  given Decoder[GoalEdge] = Decoder.instance { c =>
    for
      a <- field[SituationId](c, "from")
      r <- field[GoalRelation](c, "relation")
      b <- field[SituationId](c, "to")
      m <- field[ClaimMeta](c, "meta")
    yield GoalEdge(a, r, b, m)
  }

  given Encoder[StateChangeKind] = enumEncoder(_.toString)
  given Decoder[StateChangeKind] =
    enumDecoder("StateChangeKind", StateChangeKind.values, _.toString)
  given Encoder[StateChangeEdge] = Encoder.instance { e =>
    Json.obj(
      "event" -> e.event.asJson,
      "change" -> e.change.asJson,
      "state" -> e.state.asJson,
      "meta" -> e.meta.asJson
    )
  }
  given Decoder[StateChangeEdge] = Decoder.instance { c =>
    for
      a <- field[SituationId](c, "event")
      k <- field[StateChangeKind](c, "change")
      b <- field[SituationId](c, "state")
      m <- field[ClaimMeta](c, "meta")
    yield StateChangeEdge(a, k, b, m)
  }

  given Encoder[NarrativeReference] = enumEncoder(_.toString)
  given Decoder[NarrativeReference] =
    enumDecoder("NarrativeReference", NarrativeReference.values, _.toString)
  given Encoder[ReferenceEdge] = Encoder.instance { e =>
    Json.obj(
      "from" -> e.from.asJson,
      "mode" -> e.mode.asJson,
      "to" -> e.to.asJson,
      "meta" -> e.meta.asJson
    )
  }
  given Decoder[ReferenceEdge] = Decoder.instance { c =>
    for
      a <- field[SituationId](c, "from")
      r <- field[NarrativeReference](c, "mode")
      b <- field[SituationId](c, "to")
      m <- field[ClaimMeta](c, "meta")
    yield ReferenceEdge(a, r, b, m)
  }

  given Encoder[NarrativeMember] = Encoder.instance {
    case NarrativeMember.Situation(id) => Json.obj("type" -> "Situation".asJson, "id" -> id.asJson)
    case NarrativeMember.Segment(id)   => Json.obj("type" -> "Segment".asJson, "id" -> id.asJson)
  }
  given Decoder[NarrativeMember] = Decoder.instance { c =>
    field[String](c, "type").flatMap {
      case "Situation" => field[SituationId](c, "id").map(NarrativeMember.Situation.apply)
      case "Segment"   => field[SegmentId](c, "id").map(NarrativeMember.Segment.apply)
      case o           => Left(DecodingFailure(s"unknown NarrativeMember $o", c.history))
    }
  }

  given Encoder[HierarchyKind] = enumEncoder(_.toString)
  given Decoder[HierarchyKind] = enumDecoder("HierarchyKind", HierarchyKind.values, _.toString)
  given Encoder[ContainmentEdge] = Encoder.instance { e =>
    Json.obj(
      "member" -> e.member.asJson,
      "parent" -> e.parent.asJson,
      "kind" -> e.kind.asJson,
      "weight" -> e.weight.asJson,
      "meta" -> e.meta.asJson
    )
  }
  given Decoder[ContainmentEdge] = Decoder.instance { c =>
    for
      m <- field[NarrativeMember](c, "member")
      p <- field[SegmentId](c, "parent")
      k <- field[HierarchyKind](c, "kind")
      w <- field[Double](c, "weight")
      me <- field[ClaimMeta](c, "meta")
    yield ContainmentEdge(m, p, k, w, me)
  }

  given Encoder[EntityRelation] = Encoder.instance {
    case EntityRelation.Custom(ns, l) =>
      Json.obj("type" -> "Custom".asJson, "namespace" -> ns.asJson, "label" -> l.asJson)
    case r => r.toString.asJson
  }
  given Decoder[EntityRelation] = Decoder.instance { c =>
    c.value.asString match
      case Some(s) =>
        Vector(EntityRelation.MemberOf, EntityRelation.PartOf, EntityRelation.SameAs)
          .find(v => v.toString == s)
          .toRight(DecodingFailure(s"unknown EntityRelation $s", c.history))
      case None =>
        for
          ns <- field[String](c, "namespace")
          l <- field[String](c, "label")
        yield EntityRelation.Custom(ns, l)
  }
  given Encoder[EntityEdge] = Encoder.instance { e =>
    Json.obj(
      "from" -> e.from.asJson,
      "relation" -> e.relation.asJson,
      "to" -> e.to.asJson,
      "meta" -> e.meta.asJson
    )
  }
  given Decoder[EntityEdge] = Decoder.instance { c =>
    for
      a <- field[EntityId](c, "from")
      r <- field[EntityRelation](c, "relation")
      b <- field[EntityId](c, "to")
      m <- field[ClaimMeta](c, "meta")
    yield EntityEdge(a, r, b, m)
  }

  given Encoder[CircumstanceKind] = Encoder.instance(_.toString.asJson)
  given Decoder[CircumstanceKind] = Decoder.decodeString.emap(raw =>
    Vector(CircumstanceKind.Time, CircumstanceKind.Manner)
      .find(_.toString == raw)
      .toRight(s"unknown CircumstanceKind $raw")
  )

  given Encoder[CircumstanceEdge] = Encoder.instance { e =>
    Json.obj(
      "situation" -> e.situation.asJson,
      "kind" -> e.kind.asJson,
      "label" -> e.label.asJson,
      "support" -> e.support.asJson,
      "meta" -> e.meta.asJson
    )
  }
  given Decoder[CircumstanceEdge] = Decoder.instance { c =>
    for
      s <- field[SituationId](c, "situation")
      k <- field[CircumstanceKind](c, "kind")
      l <- field[String](c, "label")
      sp <- field[SpanSet](c, "support")
      m <- field[ClaimMeta](c, "meta")
    yield CircumstanceEdge(s, k, l, sp, m)
  }

  given Encoder[RelationLayers] = Encoder.instance { r =>
    Json.obj(
      "participants" -> r.participants.asJson,
      "temporal" -> r.temporal.asJson,
      "causal" -> r.causal.asJson,
      "goals" -> r.goals.asJson,
      "stateChanges" -> r.stateChanges.asJson,
      "references" -> r.references.asJson,
      "entityRelations" -> r.entityRelations.asJson,
      "circumstances" -> r.circumstances.asJson
    )
  }
  given Decoder[RelationLayers] = Decoder.instance { c =>
    for
      p <- field[Vector[ParticipantEdge]](c, "participants")
      t <- field[Vector[TemporalEdge]](c, "temporal")
      ca <- field[Vector[CausalEdge]](c, "causal")
      g <- field[Vector[GoalEdge]](c, "goals")
      s <- field[Vector[StateChangeEdge]](c, "stateChanges")
      r <- field[Vector[ReferenceEdge]](c, "references")
      e <- field[Vector[EntityEdge]](c, "entityRelations")
      ci <- field[Vector[CircumstanceEdge]](c, "circumstances")
    yield RelationLayers(p, t, ca, g, s, r, e, ci)
  }

  given Encoder[NarrativeGraph] = Encoder.instance { g =>
    Json.obj(
      "entities" -> g.entities.asJson,
      "situations" -> g.situations.asJson,
      "segments" -> g.segments.asJson,
      "contexts" -> g.contexts.asJson,
      "relations" -> g.relations.asJson
    )
  }
  given Decoder[NarrativeGraph] = Decoder.instance { c =>
    for
      e <- field[Map[EntityId, EntityNode]](c, "entities")
      s <- field[Map[SituationId, SituationNode]](c, "situations")
      g <- field[Map[SegmentId, SegmentNode]](c, "segments")
      x <- field[Map[ContextId, ContextFrame]](c, "contexts")
      r <- field[RelationLayers](c, "relations")
    yield NarrativeGraph(e, s, g, x, r)
  }

  // ---- hierarchy and trajectory ---------------------------------------------------------
  given Encoder[BoundaryBelief] = Encoder.instance { b =>
    obj(
      "afterUnit" -> b.afterUnit.asJson,
      "level" -> b.level.asJson,
      "rawScore" -> b.rawScore.asJson,
      "calibrated" -> opt(b.calibrated),
      "evidence" -> b.evidence.toVector.asJson
    )
  }
  given Decoder[BoundaryBelief] = Decoder.instance { c =>
    for
      u <- field[SurfaceUnitId](c, "afterUnit")
      l <- field[Int](c, "level")
      r <- field[Double](c, "rawScore")
      p <- field[Option[Probability]](c, "calibrated")
      ev <- field[Vector[Evidence]](c, "evidence")
      en <- nev(c, "boundary evidence", ev)
    yield BoundaryBelief(u, l, r, p, en)
  }

  given Encoder[NarrativeHierarchy] = Encoder.instance(h =>
    Json.obj("containment" -> h.containment.asJson, "boundaryBeliefs" -> h.boundaryBeliefs.asJson)
  )
  given Decoder[NarrativeHierarchy] = Decoder.instance { c =>
    for
      co <- field[Vector[ContainmentEdge]](c, "containment")
      bb <- field[Vector[BoundaryBelief]](c, "boundaryBeliefs")
    yield NarrativeHierarchy(co, bb)
  }

  given Encoder[FlowStep] = Encoder.instance { s =>
    obj(
      "from" -> s.from.asJson,
      "to" -> s.to.asJson,
      "featureChanges" -> s.featureChanges.asJson,
      "entityTurnover" -> s.entityTurnover.asJson,
      "locationChange" -> opt(s.locationChange),
      "contextChange" -> s.contextChange.asJson,
      "worldTime" -> s.worldTime.asJson,
      "worldTimeContext" -> opt(s.worldTimeContext),
      "boundaryBeliefs" -> s.boundaryBeliefs.asJson
    )
  }
  given Decoder[FlowStep] = Decoder.instance { c =>
    for
      f <- field[SituationId](c, "from")
      t <- field[SituationId](c, "to")
      fc <- field[Map[FeatureSpaceId, Estimate[Double]]](c, "featureChanges")
      et <- field[Estimate[Double]](c, "entityTurnover")
      lc <- field[Option[Boolean]](c, "locationChange")
      cc <- field[Boolean](c, "contextChange")
      wt <- field[Resolved[WorldTimeTransition]](c, "worldTime")
      wc <- field[Option[ContextId]](c, "worldTimeContext")
      bb <- field[Vector[BoundaryBelief]](c, "boundaryBeliefs")
    yield FlowStep(f, t, fc, et, lc, cc, wt, wc, bb)
  }

  given Encoder[DiscourseTrajectory] = Encoder.instance(t => Json.obj("steps" -> t.steps.asJson))
  given Decoder[DiscourseTrajectory] =
    Decoder.instance(c => field[Vector[FlowStep]](c, "steps").map(DiscourseTrajectory.apply))

  given Encoder[SensoryModality] = enumEncoder(_.toString)
  given Decoder[SensoryModality] =
    enumDecoder("SensoryModality", SensoryModality.values, _.toString)
  given Encoder[SensoryProfileKind] = enumEncoder(_.toString)
  given Decoder[SensoryProfileKind] =
    enumDecoder("SensoryProfileKind", SensoryProfileKind.values, _.toString)

  given Encoder[SensoryProfile] = Encoder.instance { p =>
    Json.obj(
      "kind" -> p.kind.asJson,
      "scores" -> p.scores.toVector
        .sortBy(_._1.ordinal)
        .map((m, e) => Json.obj("modality" -> m.asJson, "estimate" -> e.asJson))
        .asJson
    )
  }
  given Decoder[SensoryProfile] = Decoder.instance { c =>
    val score: Decoder[(SensoryModality, Estimate[Double])] = Decoder.instance { sc =>
      for
        m <- sc.downField("modality").as[SensoryModality]
        e <- sc.downField("estimate").as[Estimate[Double]]
      yield (m, e)
    }
    for
      k <- field[SensoryProfileKind](c, "kind")
      s <- c
        .downField("scores")
        .as[Vector[(SensoryModality, Estimate[Double])]](using Decoder.decodeVector(using score))
    yield SensoryProfile(k, s.toMap)
  }

/** The `StoryModel` artifact: encoding, decoding to `Draft`, and the content checksum. */
object StoryModelCodec:
  import StoryCodecs.given

  private given Encoder[FeatureSpace[?]] = FeatureCodecs.spaceExistentialEncoder
  private given Decoder[FeatureSpace[?]] = FeatureCodecs.spaceExistentialDecoder

  /** Status is a phantom and is not written; every other field is. */
  given modelEncoder[S <: ModelStatus]: Encoder[StoryModel[S]] = Encoder.instance { m =>
    obj(
      "schemaVersion" -> m.schemaVersion.asJson,
      "source" -> m.source.asJson,
      "atlas" -> CoreCodecs.atlasUnitsEncoder(m.atlas),
      "graph" -> m.graph.asJson,
      "hierarchy" -> m.hierarchy.asJson,
      "trajectory" -> m.trajectory.asJson,
      "featureSpaces" -> m.featureSpaces.asJson,
      "sidecars" -> m.sidecars.asJson,
      "featureRefs" -> m.featureRefs.asJson,
      "descriptors" -> m.descriptors.asJson,
      "hypotheses" -> m.hypotheses.asJson,
      "sensoryProfiles" -> m.sensoryProfiles.asJson,
      "receipt" -> opt(m.receipt)
    )
  }

  /** Decoding yields a draft; the atlas is checked against the source (same checksums) and the
    * consumer revalidates with `StoryValidator` to recover `Validated`.
    */
  given draftDecoder: Decoder[StoryModel[ModelStatus.Draft]] = Decoder.instance { c =>
    for
      sv <- SchemaVersions.check(c)
      source <- field[StorySource](c, "source")
      atlas <- c
        .downField("atlas")
        .success
        .toRight(DecodingFailure("missing atlas", c.history))
        .flatMap(ac => CoreCodecs.decodeAtlasUnits(ac, source))
      graph <- field[NarrativeGraph](c, "graph")
      hierarchy <- field[NarrativeHierarchy](c, "hierarchy")
      trajectory <- field[DiscourseTrajectory](c, "trajectory")
      spaces <- field[Map[FeatureSpaceId, FeatureSpace[?]]](c, "featureSpaces")
      sidecars <- field[Map[FeatureSpaceId, SidecarManifest]](c, "sidecars")
      refs <- field[Vector[FeatureRef]](c, "featureRefs")
      descriptors <- field[Vector[DescriptorClaim]](c, "descriptors")
      hypotheses <- field[Vector[HypothesisClaim]](c, "hypotheses")
      sensory <- field[Map[SituationId, Vector[SensoryProfile]]](c, "sensoryProfiles")
      receipt <- field[Option[BuildReceipt]](c, "receipt")
    yield StoryModel.draft(
      source,
      atlas,
      graph,
      hierarchy,
      trajectory,
      spaces,
      sidecars,
      refs,
      descriptors,
      hypotheses,
      sensory,
      receipt,
      sv
    )
  }

  def encode[S <: ModelStatus](m: StoryModel[S]): String = Canonical.encode(m)

  def decode(text: String): Either[CodecError, StoryModel[ModelStatus.Draft]] =
    Canonical.decode[StoryModel[ModelStatus.Draft]](text)

  /** SHA-256 of the canonical text. Status is not part of the text, so `Draft`, `Validated`, and
    * `Adjudicated` models with identical content have identical checksums. Vectors are excluded by
    * construction (only sidecar manifests are inline).
    */
  def contentChecksum[S <: ModelStatus](m: StoryModel[S]): Checksum = Canonical.checksum(m)
