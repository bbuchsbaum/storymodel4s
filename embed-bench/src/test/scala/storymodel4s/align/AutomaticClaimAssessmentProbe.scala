package storymodel4s.align

import cats.data.NonEmptyVector
import io.circe.{Decoder, Json}
import io.circe.parser.parse
import io.circe.syntax.*
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths, StandardOpenOption}
import storymodel4s.codec.CoreCodecs.given
import storymodel4s.codec.MappingCodecs
import storymodel4s.core.*
import storymodel4s.recall.*

/** Consume local, attributed model proposals against fixed experimental claims and candidates.
  * Original recall/media coordinates remain provenance; this is not production localization.
  */
object AutomaticClaimAssessmentProbe:
  private def field[A: Decoder](j: Json, name: String): A =
    j.hcursor.get[A](name).fold(throw _, identity)
  private def json(path: Path): Json = parse(Files.readString(path, UTF_8)).fold(throw _, identity)
  private def hash(path: Path): String = Checksum.ofBytes(Files.readAllBytes(path)).hex

  /** Bind the actual consumed file to this frozen experiment, including its absolute path. */
  private[align] def checkedInput(path: Path, freeze: Json): Json =
    val absolute = path.toAbsolutePath.normalize()
    require(
      field[Map[String, String]](freeze, "inputs").get(absolute.toString).contains(hash(absolute)),
      "consumed input is not bound to this frozen experiment"
    )
    json(absolute)

  private val arms = Vector("audio", "visual", "audio_visual")
  private val reasons = Map(
    "source_unavailable" -> ClaimAssessmentUnknown.SourceUnavailable,
    "partial_evidence" -> ClaimAssessmentUnknown.SourcePartial,
    "reference_unresolved" -> ClaimAssessmentUnknown.ReferenceUnresolved,
    "context_unresolved" -> ClaimAssessmentUnknown.ContextUnresolved,
    "comparison_unavailable" -> ClaimAssessmentUnknown.SemanticComparisonUnavailable
  )
  private def key(j: Json): (String, String) =
    field[String](j, "question") -> field[String](j, "arm")
  private def questionId(j: Json) = ClaimId.unsafe(field[String](j, "id"))
  private def unitId(j: Json) = RecallUnitId.unsafe("supplied:" + field[String](j, "id"))
  private def event(j: Json): SourceNodeRef = SourceNodeRef.Situation(
    SituationId.unsafe(
      "experimental-target:" + field[Vector[Int]](j, "sourceRows").sorted.mkString("-")
    )
  )
  private def origin(id: String): SourceNodeRef =
    SourceNodeRef.Situation(SituationId.unsafe("text-evidence:" + id))

  /** Exact UTF-16 offsets bind source text; they establish no semantic relation. */
  private[align] def checkedQuote(text: String, quote: String, start: Int, end: Int): Unit =
    require(
      quote.trim.nonEmpty && start >= 0 && end > start && end <= text.length,
      "invalid quotation extent"
    )
    require(text.substring(start, end) == quote, "quotation differs from its owned text")
    require(
      text.indexOf(quote) == start && text.indexOf(quote, start + 1) < 0,
      "quotation has ambiguous occurrence"
    )

  /** Recheck the normalized proposal against the raw model response and allowed request items. */
  private[align] def checkedProposal(request: Json, output: Json): Option[Json] =
    require(key(request) == key(output), "foreign question or arm")
    require(
      field[String](output, "requestIdentity") == field[String](request, "identity"),
      "foreign request identity"
    )
    val processing = field[String](output, "processing")
    if processing != "valid" then
      require(
        Set("oversized_input", "output_limit_or_unknown", "invalid_proposal", "provider_failure")(
          processing
        ),
        "unknown processing state"
      )
      require(
        output.hcursor.downField("proposal").focus.contains(Json.Null),
        "failed processing cannot carry an accepted proposal"
      )
      None
    else
      val p = field[Json](output, "proposal")
      val raw = parse(field[String](output, "raw")).fold(throw _, identity)
      require(
        field[String](p, "question") == key(request)._1 &&
          field[String](raw, "claim_id") == Checksum.ofText(key(request)._1).hex,
        "foreign claim"
      )
      val rs = field[Vector[String]](p, "unresolved")
      require(
        rs.distinct.size == rs.size && rs.forall(reasons.contains) &&
          rs.toSet == field[Vector[String]](raw, "unresolved").toSet,
        "unresolved reasons differ"
      )
      val entries = field[Vector[Json]](request, "items")
      val items = entries.map(i => field[String](i, "id") -> field[String](i, "text")).toMap
      require(entries.size == items.size, "duplicate request evidence")
      Vector("support", "counter").foreach { side =>
        val links = field[Vector[Json]](p, side)
        val sourceLinks = field[Vector[Json]](raw, side)
        require(
          links.map(l => (field[String](l, "evidence"), field[String](l, "quote"))) ==
            sourceLinks.map(l => (field[String](l, "evidence_id"), field[String](l, "quote"))),
          "normalized links differ from raw proposal"
        )
        require(links.distinct.size == links.size, "duplicate quotation")
        links.foreach { l =>
          val id = field[String](l, "evidence")
          require(items.contains(id), "citation borrowed from another arm or question")
          checkedQuote(
            items(id),
            field[String](l, "quote"),
            field[Int](l, "start"),
            field[Int](l, "end")
          )
        }
      }
      require(
        rs.nonEmpty || field[Vector[Json]](p, "support").nonEmpty ||
          field[Vector[Json]](p, "counter").nonEmpty,
        "unanswered claim lacks a reason"
      )
      Some(p)

  def main(args: Array[String]): Unit =
    require(args.length == 2, "experiment-directory output.json")
    val root = Paths.get(args(0))
    val freeze = json(root.resolve("freeze.json"))
    field[Map[String, String]](freeze, "inputs").foreach { (path, digest) =>
      require(hash(Paths.get(path)) == digest, "frozen input changed")
    }
    val execution = json(root.resolve("execution.json"))
    require(
      field[String](execution, "freezeSha256") == hash(root.resolve("freeze.json")) &&
        field[String](execution, "outputsSha256") == hash(root.resolve("outputs.jsonl")),
      "execution content differs"
    )
    val requestRows =
      field[Vector[Json]](checkedInput(root.resolve("requests.json"), freeze), "requests")
    val qs = field[Vector[Json]](checkedInput(root.resolve("questions.json"), freeze), "questions")
    require(
      qs.map(questionId).distinct.size == qs.size && qs.nonEmpty,
      "duplicate or empty questions"
    )
    val outputs = Files
      .readString(root.resolve("outputs.jsonl"), UTF_8)
      .linesIterator
      .filter(_.nonEmpty)
      .map(s => parse(s).fold(throw _, identity))
      .toVector
    val expected = qs.flatMap(q => arms.map(a => field[String](q, "id") -> a)).toSet
    require(
      requestRows.map(key).toSet == expected && requestRows.size == expected.size,
      "request population differs"
    )
    require(
      outputs.map(key).toSet == expected && outputs.size == expected.size &&
        field[Int](execution, "requested") == expected.size &&
        field[Int](execution, "observed") == expected.size,
      "output population differs"
    )
    val requests = requestRows.map(r => key(r) -> r).toMap
    val byOutput = outputs.map(r => key(r) -> r).toMap
    val proposals = outputs.map(o => key(o) -> checkedProposal(requests(key(o)), o)).toMap
    val texts = qs.map(field[String](_, "claim"))
    require(texts.forall(_.trim.nonEmpty), "empty supplied claim")
    val transcript = StorySource.fromText(texts.mkString("\n")).toOption.get
    val offsets = texts.scanLeft(0)((n, s) => n + s.length + 1).init
    val units = qs.zipWithIndex.map { (q, i) =>
      RecallUnit(
        unitId(q),
        i,
        SpanSet.one(TextSpan.unsafe(offsets(i), offsets(i) + texts(i).length)),
        texts(i),
        DiscourseFunction.EpisodicAssertion,
        ExpressedUncertainty.Unmarked,
        PropositionSketch.empty,
        None
      )
    }
    val recall = RecallGraph
      .validated(transcript, SurfaceAnalyzer.analyze(transcript), units, RecallRelations.empty)
      .toOption
      .get
    val inventory = RecallInventory
      .of(recall, Vector.empty, WordIdPolicy.inputArtifact(transcript.canonicalChecksum))
      .toOption
      .get
    val itemRows = requestRows
      .flatMap(field[Vector[Json]](_, "items"))
      .groupBy(field[String](_, "id"))
      .toVector
      .sortBy(_._1)
    require(
      itemRows.nonEmpty && itemRows.forall((_, rows) => rows.distinct.size == 1),
      "source item identity has inconsistent content"
    )
    val items = itemRows.map((id, rows) => id -> rows.head)
    val itemText = items.map((_, j) => field[String](j, "text"))
    require(itemText.forall(_.trim.nonEmpty), "empty source item")
    val sourceText = itemText.mkString("\n")
    val sourceOffsets = itemText.scanLeft(0)((n, s) => n + s.length + 1).init
    val starts = items.zip(sourceOffsets).map((entry, offset) => entry._1 -> offset).toMap
    val spans = items.zipWithIndex.map { case ((id, _), i) =>
      origin(id) -> SpanSet.one(
        TextSpan.unsafe(sourceOffsets(i), sourceOffsets(i) + itemText(i).length)
      )
    }.toMap
    val events = qs.map(event).distinct
    val whole = SpanSet.one(TextSpan.unsafe(0, sourceText.length))
    val support = spans ++ events.map(_ -> whole)
    val refs = events ++ items.map((id, _) => origin(id))
    val nodes = refs.zipWithIndex.map { (ref, i) =>
      NodeSummary(
        ref,
        0,
        None,
        i,
        support(ref),
        None,
        Vector.empty,
        if events.contains(ref) then ContextTag.Hypothetical else ContextTag.Speech,
        PolarityTag.Unknown,
        ModalityTag.Unknown,
        Vector.empty,
        Set.empty
      )
    }
    val view = InMemorySourceView(nodes, Map.empty, None, sourceText.length)
    val source = SourceRepresentation
      .of(
        view,
        NonEmptyVector.one(BundleEntry.text(Checksum.ofText(sourceText))),
        None,
        support.map((ref, s) => ref -> SourceSupportStatus.located(TypedSupport.Text(s)))
      )
      .toOption
      .get
    val universe = DeclaredUniverse.of(refs, TargetGrain.SingleLevel(0)).toOption.get
    val policies = MappingPolicies
      .of(
        InferencePolicy.Unspecified("supplied experimental candidates"),
        ContextPolicy.Unspecified("model assertions retain source context in the frozen manifest"),
        CandidatePolicy.Unknown("authored fixed candidates; no localization run"),
        ReferencePrior.NotApplicable("no probability"),
        DecisionPolicy.NotApplicable("fixed candidate"),
        universe
      )
      .toOption
      .get
    val roles = UnitRoles
      .of(
        AnalysisGrain.InferenceUnit(inventory.segmentation),
        AnalysisGrain.InferenceUnit(inventory.segmentation),
        AnalysisGrain.Targets(TargetGrain.SingleLevel(0))
      )
      .toOption
      .get
    val ledger = StageLedger
      .of(
        Stage.values.toVector.map(s =>
          StageEntry
            .of(s, StageProvenance.unknown(UnknownProvenanceReason.NotRun, None))
            .toOption
            .get
        )
      )
      .toOption
      .get
    val stages = UnitStageRefs
      .of(
        ledger,
        ledger.at(Stage.Inference).head.id,
        ledger.at(Stage.Candidates).head.id,
        ledger.at(Stage.Decision).head.id
      )
      .toOption
      .get
    val basis = DecisionBasis.of(MeasureKind.RawScore, Some("fixed-candidate")).toOption.get
    val outcomes = qs.zip(units).map { (q, u) =>
      val dest: Destination = Destination.Target(event(q))
      val raw = RawScores
        .of(
          "fixed-candidate",
          ScoreDirection.HigherIsBetter,
          "authored candidate marker, not a measured score",
          Map(dest -> 1.0),
          ledger.at(Stage.Scoring).head.id
        )
        .toOption
        .get
      val measures = UnitMeasures.of(Vector(raw), None, None, None).toOption.get
      val candidates = CandidateSetId.of(stages.candidates, u.id, Set(dest))
      UnitOutcome
        .computed(
          u.id,
          measures,
          Vector(MappingLink.ungated(dest, stages, candidates)),
          basis,
          DecisionRequest.RawArgmax,
          stages
        )
        .toOption
        .get
    }
    val mapping =
      MappingResult.checked(inventory, source, policies, roles, ledger, outcomes).toOption.get
    val questions = qs.map(q =>
      ClaimAssessmentQuestion(
        questionId(q),
        unitId(q),
        event(q),
        Facet
          .parse(field[String](q, "facet"))
          .getOrElse(throw new IllegalArgumentException("unknown facet"))
      )
    )
    val procedure = Fingerprint.unsafe("local-claim-proposal:" + hash(root.resolve("freeze.json")))
    val receipts = arms.map { arm =>
      val citations = Vector.newBuilder[ClaimSourceCitation]
      val submissions = qs.zip(units).map { (q, unit) =>
        val id = field[String](q, "id")
        val proposal = proposals(id -> arm)
        def links(side: String): Set[EvidenceId] = proposal.toVector
          .flatMap(field[Vector[Json]](_, side))
          .zipWithIndex
          .map { (link, i) =>
            val evidence = field[String](link, "evidence")
            val citation = EvidenceId.unsafe(s"$arm:$id:$side:$i")
            val span = SpanSet.one(
              TextSpan.unsafe(
                starts(evidence) + field[Int](link, "start"),
                starts(evidence) + field[Int](link, "end")
              )
            )
            citations += ClaimSourceCitation(citation, origin(evidence), TypedSupport.Text(span))
            citation
          }
          .toSet
        val unknown = proposal.fold(Set(ClaimAssessmentUnknown.SemanticComparisonUnavailable))(p =>
          field[Vector[String]](p, "unresolved").map(reasons).toSet
        )
        ClaimAssessmentSubmission(
          questionId(q),
          RecallClaimState.Expressed,
          unit.span,
          Some(unit.span),
          None,
          links("support"),
          links("counter"),
          unknown,
          ClaimAssessorKind.AgentProposal,
          procedure
        )
      }
      val receipt = ClaimAssessmentReceipt
        .checked(mapping, questions, citations.result(), submissions)
        .fold(e => throw new IllegalArgumentException(e.toString), identity)
      arm -> receipt
    }
    require(
      receipts.map(_._2.mappingDigest).distinct.size == 1 &&
        receipts.map(_._2.populationDigest).distinct.size == 1,
      "arm changed mapping or question population"
    )
    val rows = receipts.flatMap { (arm, receipt) =>
      qs.zip(receipt.submissions).map { (q, s) =>
        val id = field[String](q, "id")
        Json.obj(
          "question" -> q,
          "arm" -> arm.asJson,
          "status" -> receipt.status(s.question).get.toString.asJson,
          "processing" -> field[String](byOutput(id -> arm), "processing").asJson,
          "supporting" -> s.supporting.toVector.map(_.value).sorted.asJson,
          "counterevidence" -> s.counterevidence.toVector.map(_.value).sorted.asJson,
          "unresolved" -> s.unresolved.toVector.map(_.toString).sorted.asJson,
          "proposal" -> proposals(id -> arm).asJson
        )
      }
    }
    val report = Json.obj(
      "qualification" -> "Attributed local model proposals over supplied focal claims and authored fixed candidates; not automatic extraction, production localization, or semantic truth certification".asJson,
      "freezeSha256" -> hash(root.resolve("freeze.json")).asJson,
      "mapping" -> MappingCodecs.toJson(mapping),
      "mappingDigest" -> mapping.digest.hex.asJson,
      "populationDigest" -> receipts.head._2.populationDigest.hex.asJson,
      "citations" -> receipts.map { (arm, r) =>
        Json.obj(
          "arm" -> arm.asJson,
          "items" -> r.citations
            .map(c =>
              Json.obj(
                "id" -> c.id.value.asJson,
                "origin" -> c.origin.key.asJson,
                "support" -> c.support.asJson
              )
            )
            .asJson
        )
      }.asJson,
      "rows" -> rows.asJson
    )
    val _ = Files.writeString(
      Paths.get(args(1)),
      report.spaces2 + "\n",
      UTF_8,
      StandardOpenOption.CREATE_NEW
    )
    println(s"Built ${receipts.size} receipts; ${questions.size} fixed questions per arm")
