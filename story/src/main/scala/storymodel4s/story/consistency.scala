package storymodel4s.story

import storymodel4s.core.*

/** Narrative-consistency rules beyond the structural laws: the failure modes design record §27.2
  * prohibits for *The War of the Ghosts* — a retelling duplicated as a second occurrence, reported
  * content promoted to narrated-world fact, and a causal edge marked explicit without any causal
  * cue in its evidence. These are advisory (`Warning`) except where the model is
  * self-contradictory.
  *
  * Why a separate checker: the structural validator decides admissibility of a graph; these rules
  * judge whether an admissible graph is a plausible *reading* of the text, which acquisition may
  * legitimately get wrong and a resolver must be able to see.
  */
object NarrativeConsistency:

  /** Cue lemmas whose presence in the evidence licenses a surface-explicit causal claim. */
  val CausalCues: Set[String] = Set(
    "because",
    "so",
    "therefore",
    "since",
    "thus",
    "hence",
    "cause",
    "caused",
    "causes",
    "made",
    "result",
    "consequently",
    "why",
    "for"
  )

  private def tokensIn(atlas: SurfaceAtlas, spans: SpanSet): Vector[String] =
    spans.refs.toVector.flatMap { r =>
      atlas.unitsOverlapping(r.span, SurfaceUnitKind.Token).map(t => TextNorm.lower(atlas.text(t)))
    }

  def check(m: TextModel[?]): Vector[Violation] = checkParts(m.model, Some(m))

  /** These rules use graph/status/context only and also govern non-text models. */
  private[story] def checkGeneral(m: StoryModel[?]): Vector[Violation] = checkParts(m, None)

  private def checkParts(m: StoryModel[?], text: Option[TextModel[?]]): Vector[Violation] =
    val g = m.graph
    val out = Vector.newBuilder[Violation]
    def warn(law: String, path: String, reason: String): Unit =
      out += Violation(law, Severity.Warning, path, reason)
    def err(law: String, path: String, reason: String): Unit =
      out += Violation(law, Severity.Error, path, reason)

    val root = g.rootContext
    def isRoot(s: SituationNode): Boolean = root.contains(s.context)
    def roleSet(id: SituationId): Set[(ParticipantRole, EntityId)] =
      g.participantsOf.getOrElse(id, Vector.empty).toSet

    // 1. A retrospective or prospective reference must not connect two occurrences of the same
    //    predicate in the same context: the referring side is a retelling, so it belongs in a
    //    speech/belief context or must differ in predicate.
    g.relations.references.zipWithIndex.foreach { (r, i) =>
      (g.situations.get(r.from), g.situations.get(r.to)) match
        case (Some(a), Some(b))
            if (r.mode == NarrativeReference.Retrospective || r.mode == NarrativeReference.Prospective)
              && a.context == b.context && a.predicate.lemma == b.predicate.lemma
              && a.modality == Modality.Asserted && b.modality == Modality.Asserted =>
          warn(
            "no-duplicate-occurrence-via-retrospective-reference",
            s"references/$i",
            s"${a.id.value} refers ${r.mode} to ${b.id.value} but both are asserted `${a.predicate.lemma}` in the same context: a retelling duplicated as an occurrence"
          )
        case _ => ()
    }

    // Repeated events with the same predicate and participants (the warriors "go" several times)
    // are ordinary; without a reference edge there is no structural evidence of duplication, so
    // no rule fires on them. Duplication is only diagnosable through the reference (rule 1) or
    // through the speech-scoped twin (rule 2).
    val rootAsserted = m.discourseOrder
      .flatMap(g.situations.get)
      .filter(s => isRoot(s) && s.modality == Modality.Asserted && s.isEvent)

    // 2. Reported content must not sit in the narrated world. A root situation with `Reported`
    //    modality is an error; a root situation that duplicates a speech-scoped situation
    //    (same predicate, same participants, overlapping support) without a reference to it is a
    //    promoted report.
    g.situations.values.toVector.sortBy(_.id).foreach { s =>
      if isRoot(s) && s.modality == Modality.Reported then
        err(
          "reported-content-not-root-without-root-claim",
          s"situations/${s.id.value}",
          "reported modality in the narrated-world context"
        )
    }
    val speechScoped = g.situations.values.toVector.filter { s =>
      g.contexts
        .get(s.context)
        .exists(_.kind match
          case ContextKind.Speech(_) => true
          case _                     => false)
    }
    text.foreach { _ =>
      rootAsserted.foreach { s =>
        speechScoped
          .filter(t =>
            t.predicate.lemma == s.predicate.lemma && roleSet(t.id) == roleSet(s.id) &&
              roleSet(s.id).nonEmpty && s.support.textSpans
                .exists(a => t.support.textSpans.exists(a.overlaps)) &&
              !g.referencesOut.getOrElse(s.id, Vector.empty).exists(_.to == t.id) &&
              !g.referencesOut.getOrElse(t.id, Vector.empty).exists(_.to == s.id)
          )
          .foreach(t =>
            warn(
              "reported-content-not-root-without-root-claim",
              s"situations/${s.id.value}",
              s"narrated-world `${s.predicate.lemma}` duplicates speech-scoped ${t.id.value} on the same span with no reference: reported content promoted to fact"
            )
          )
      }

    }

    // 3. A surface-explicit causal edge needs a causal cue in its evidence spans.
    text.foreach { witnessed =>
      g.relations.causal.zipWithIndex.foreach { (c, i) =>
        if c.meta.status == EpistemicStatus.SurfaceExplicit then
          val toks =
            c.meta.evidence.toVector.flatMap(_.spans).flatMap(ss => tokensIn(witnessed.atlas, ss))
          if !toks.exists(CausalCues.contains) then
            err(
              "explicit-causal-requires-span-with-causal-cue",
              s"causal/$i",
              s"${c.cause.value} ${c.relation} ${c.effect.value} is SurfaceExplicit but its evidence contains no causal cue"
            )
      }

    }

    // 4. A hypothesis must be about a hypothesized subject, not an explicit one.
    m.hypotheses.zipWithIndex.foreach { (hyp, i) =>
      g.situations.get(hyp.subject).foreach { s =>
        if s.meta.status == EpistemicStatus.SurfaceExplicit then
          warn(
            "hypothesis-subject-explicit",
            s"hypotheses/$i",
            s"${s.id.value} is SurfaceExplicit yet has competing readings"
          )
      }
    }

    out.result().sortBy(v => (v.law, v.path, v.reason))

  extension (a: SpanSet)
    private def overlaps(b: SpanSet): Boolean =
      a.refs.toVector.exists(x => b.refs.toVector.exists(y => x.span.overlaps(y.span)))
