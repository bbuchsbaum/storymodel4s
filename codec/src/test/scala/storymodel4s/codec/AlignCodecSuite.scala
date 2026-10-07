package storymodel4s.codec

import io.circe.Json
import munit.FunSuite
import scala.compiletime.testing.typeCheckErrors
import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.features.CanonicalDouble
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked

/** Contextual and adversarial laws for the gated HSMM wire artifact. */
class AlignCodecSuite extends FunSuite:

  private lazy val fixture = Fixture.build()
  private lazy val encoded =
    HsmmResultCodec.encode(fixture.result).fold(e => fail(e.message), identity)
  private lazy val json = Canonical.parse(encoded).toOption.get

  test("a real inferred result has a canonical contextual round trip") {
    val decoded = HsmmResultCodec.decode(encoded, fixture.recall, fixture.view)
    assertEquals(decoded, Right(fixture.result))
    assertEquals(decoded.flatMap(HsmmResultCodec.encode), Right(encoded))
    assert(encoded.contains(s"\"schemaVersion\":\"${HsmmResultCodec.SchemaVersion}\""))
    assert(encoded.contains("\"candidateAnchors\""))
    assert(encoded.contains("\"admissibilityEcho\""))
    assert(encoded.contains("\"viewFingerprint\""))
    assert(encoded.contains("\"recallChecksum\""))
    assert(encoded.contains("\"support\":{"))
    assert(!encoded.contains("supportWeight"), "the v3 numeric support leaked into v4")
  }

  test("a ranked provider-ineligible structural receipt survives contextual wire roundtrip") {
    import storymodel4s.features.{Coverage, Estimate}
    import storymodel4s.proposition.*
    val id = ConceptId.unsafe("event")
    val chart = ChartValidator
      .check(
        PropositionChart.unchecked(Some(id), Map(id -> Concept.predicate("arrive")), Vector.empty)
      )
      .toOption
      .get
    val evidence = PropositionEvidence.hand(chart)
    val view = fixture.view.copy(nodes = fixture.view.nodes.map(_.copy(evidence = Some(evidence))))
    val recall = RecallGraph
      .validated(
        fixture.recall.transcript,
        fixture.recall.atlas,
        fixture.recall.units.map(_.copy(evidence = Some(evidence))),
        fixture.recall.relations
      )
      .toOption
      .get
    val model = DefaultLocalCostModel(
      semantic = SemanticDistance.of((_, _) => 0.2),
      structural = StructuralDistance((_, _) => Estimate.Ineligible)
    )
    val result = GraphHsmm.infer(recall, view, fixture.candidates, model).toOption.get
    val cells = result.costs.values.toVector.flatMap(_.toVector.filter(!_._1.isExternal).map(_._2))
    assert(cells.nonEmpty)
    assert(cells.forall(!_.excluded))
    assert(cells.forall(!_.missingTerms.contains(CostTerm.Structural)))
    assert(
      cells.forall(_.reductions(CostTerm.Structural).observedEstimateCoverage == Coverage.empty)
    )
    val document = HsmmResultCodec.encode(result).toOption.get
    assert(
      document.contains("\"ineligible\":true"),
      "the provider outcome disappeared from the wire"
    )
    assertEquals(HsmmResultCodec.decode(document, recall, view), Right(result))
  }

  test("a view-only change is rejected by the mandatory fingerprint match") {
    val changed = fixture.view.copy(scoringLength = fixture.view.scoringLength + 1)
    HsmmResultCodec.decode(encoded, fixture.recall, changed) match
      case Left(HsmmCodecError.Rejected(AlignError.FingerprintMismatch(field, _, _))) =>
        assertEquals(field, "viewFingerprint")
      case other => fail(s"expected a view fingerprint rejection, got $other")
  }

  test("a different valid recall fails recall-checksum matching") {
    val original = fixture.recall.transcript.canonicalText
    val changedText = original.updated(0, original.head.toLower)
    val transcript = StorySource.fromText(changedText, Some("checksum foil")).toOption.get
    val atlas = SurfaceAnalyzer.analyze(transcript)
    val units = fixture.recall.units.zip(atlas.sentences).map { case (unit, sentence) =>
      unit.copy(
        span = SpanSet.one(SpanRef(Some(sentence.id), sentence.span)),
        text = atlas.text(sentence)
      )
    }
    val changed = RecallGraph
      .validated(transcript, atlas, units, fixture.recall.relations)
      .fold(errors => fail(s"checksum foil must be a valid recall: $errors"), identity)
    HsmmResultCodec.decode(encoded, changed, fixture.view) match
      case Left(HsmmCodecError.Rejected(AlignError.FingerprintMismatch(field, _, _))) =>
        assertEquals(field, "recallChecksum")
      case other => fail(s"expected a recall checksum rejection, got $other")
  }

  test("an edited unchecked recall cannot reach the contextual decoder") {
    val positive = typeCheckErrors("""
      import storymodel4s.align.SourceView
      import storymodel4s.codec.HsmmResultCodec
      import storymodel4s.core.StorySource
      import storymodel4s.recall.RecallSegmenter
      val source = StorySource.fromText("A remembered event.").toOption.get
      val checked = RecallSegmenter.segment(source)
      val view: SourceView = ???
      HsmmResultCodec.decode("{}", checked, view)
    """)
    val rejected = typeCheckErrors("""
      import storymodel4s.align.SourceView
      import storymodel4s.codec.HsmmResultCodec
      import storymodel4s.core.StorySource
      import storymodel4s.recall.RecallSegmenter
      val source = StorySource.fromText("A remembered event.").toOption.get
      val unchecked = RecallSegmenter.segment(source).copy()
      val view: SourceView = ???
      HsmmResultCodec.decode("{}", unchecked, view)
    """)
    assert(positive.isEmpty, positive.toString)
    assert(rejected.nonEmpty, rejected.toString)
  }

  test("an admissibility echo mutation is gate drift, never construction authority") {
    val changed = json.mapObject(
      _.add("admissibilityEcho", Json.fromString(Checksum.ofText("wrong echo").hex))
    )
    HsmmResultCodec.decodeJson(changed, fixture.recall, fixture.view) match
      case Left(HsmmCodecError.Rejected(AlignError.GateDrift(_, _))) => ()
      case other => fail(s"expected gate drift, got $other")
  }

  test("an anchored posterior key outside the nominated set is rejected") {
    val firstUnit = fixture.recall.ordered.head.id
    val candidates = fixture.result.candidateAnchors.updated(firstUnit, Vector.empty)
    val admissibility = fixture.recall.ordered.iterator.map { unit =>
      unit.id -> candidates(unit.id).iterator.map { ref =>
        ref -> ModeGate.assess(unit, fixture.view.node(ref).get, fixture.view)
      }.toMap
    }.toMap
    val withoutAnchor = updateFirstObjectInArray(json, "candidateAnchors") { candidate =>
      candidate.mapObject(_.add("anchors", Json.arr()))
    }
    val changed = withoutAnchor.mapObject(
      _.add(
        "admissibilityEcho",
        Json.fromString(AdmissibilityEcho.of(admissibility).checksum.hex)
      )
    )
    HsmmResultCodec.decodeJson(changed, fixture.recall, fixture.view) match
      case Left(HsmmCodecError.Rejected(AlignError.GateViolation(_, _, detail))) =>
        assert(detail.contains("not nominated"), detail)
      case other => fail(s"expected a nomination rejection, got $other")
  }

  test("a cost record whose mode contradicts its map key is rejected at the proof site") {
    val changed = updateFirstSourceCost(json) { cost =>
      val distorted = Json.obj(
        "type" -> Json.fromString("Distorted"),
        "facets" -> Json.arr(Json.fromString("Polarity"))
      )
      cost.mapObject(_.add("mode", distorted))
    }
    HsmmResultCodec.decodeJson(changed, fixture.recall, fixture.view) match
      case Left(HsmmCodecError.Rejected(AlignError.MalformedRecord(record, detail))) =>
        assert(record.nonEmpty && detail.nonEmpty)
      case other => fail(s"expected a state/cost record rejection, got $other")
  }

  test("a coverage whose counts break 0 <= evidenced <= members is refused at decode") {
    // StructuralCoverage is a checked type (bd-01M17ZNXY6AS1CMBQJRH3JMNVX): a count-violating
    // coverage can no longer be built, so the decoder refuses it as a wire failure. The level range
    // stays with AlignWire and still surfaces as a record rejection; both classes are pinned here.
    def withCoverage(level: Int, evidenced: Int, members: Int): Json =
      updateFirstSourceCost(json) { cost =>
        cost.mapObject(
          _.add(
            "sourceChartCoverage",
            Json.obj(
              "level" -> Json.fromInt(level),
              "membersWithEvidence" -> Json.fromInt(evidenced),
              "members" -> Json.fromInt(members)
            )
          )
        )
      }
    def countRefusal(r: Either[HsmmCodecError, ?]): Boolean = r match
      case Left(HsmmCodecError.Wire(e)) => e.message.contains("StructuralCoverage")
      case _                            => false
    val counts = HsmmResultCodec.decodeJson(withCoverage(0, 2, 1), fixture.recall, fixture.view)
    assert(countRefusal(counts), s"expected a StructuralCoverage decode refusal, got $counts")
    // Control: lawful counts reach past the coverage decoder, so the refusal above is about counts.
    val lawful = HsmmResultCodec.decodeJson(withCoverage(0, 1, 1), fixture.recall, fixture.view)
    assert(!countRefusal(lawful), s"lawful counts were refused as counts: $lawful")
    HsmmResultCodec.decodeJson(withCoverage(-1, 0, 1), fixture.recall, fixture.view) match
      case Left(HsmmCodecError.Rejected(AlignError.MalformedRecord(_, detail))) =>
        assert(detail.contains("sourceChartCoverage"), detail)
      case other => fail(s"expected the negative level to be a record rejection, got $other")
  }

  test("duplicate sparse entries are rejected before conversion to Map") {
    val changed = updateFirstObjectInArray(json, "posterior") { row =>
      row.mapObject { fields =>
        val mass = fields("mass").flatMap(_.asArray).getOrElse(Vector.empty)
        fields.add("mass", Json.fromValues(mass.headOption.toVector ++ mass))
      }
    }
    HsmmResultCodec.decodeJson(changed, fixture.recall, fixture.view) match
      case Left(HsmmCodecError.Rejected(AlignError.MalformedRecord(record, _))) =>
        assertEquals(record, "AlignmentRow.mass")
      case other => fail(s"expected a duplicate-key rejection, got $other")
  }

  test("the HSMM artifact has its own required schema version, refused with a typed error") {
    // hsmm/v3 is refused as UnsupportedSchema, whatever its body looks like: a v3 artifact's
    // numeric supportWeight cannot say which support state applied, so it is re-derived from its
    // inputs, never upgraded. The v3 body is judged by its tag, not by the first v4 field it lacks.
    val v3Tagged = json.mapObject(_.add("schemaVersion", Json.fromString("hsmm/v3")))
    val v3Body = mapCosts(v3Tagged) { cost =>
      cost.mapObject(
        _.remove("support").add("supportWeight", Json.fromString(CanonicalDouble.render(1.0)))
      )
    }
    Vector("hsmm/v3" -> v3Tagged, "hsmm/v3" -> v3Body).foreach { (version, document) =>
      assertEquals(
        HsmmResultCodec.decodeJson(document, fixture.recall, fixture.view),
        Left(
          HsmmCodecError.Wire(
            CodecError.UnsupportedSchema(version, Vector(HsmmResultCodec.SchemaVersion))
          )
        )
      )
      assertEquals(
        HsmmResultCodec.decode(Canonical.print(document), fixture.recall, fixture.view),
        Left(
          HsmmCodecError.Wire(
            CodecError.UnsupportedSchema(version, Vector(HsmmResultCodec.SchemaVersion))
          )
        )
      )
    }
    val v0 = json.mapObject(_.add("schemaVersion", Json.fromString("hsmm/v0")))
    assertEquals(
      HsmmResultCodec.decodeJson(v0, fixture.recall, fixture.view),
      Left(HsmmCodecError.Wire(CodecError.UnsupportedSchema("hsmm/v0", Vector("hsmm/v4"))))
    )
    // An untagged artifact is a wire error, not a guess at the current version.
    HsmmResultCodec.decodeJson(
      json.mapObject(_.remove("schemaVersion")),
      fixture.recall,
      fixture.view
    ) match
      case Left(HsmmCodecError.Wire(CodecError.Decode(_, _))) => ()
      case other => fail(s"expected an untagged-artifact wire error, got $other")
  }

  // ---- hsmm/v4 support -------------------------------------------------------------------------

  private def support(cost: Json): Json =
    cost.hcursor.downField("support").focus.getOrElse(fail("cost carries no support"))

  private def withSupport(document: Json, stateType: String)(f: Json => Json): Json =
    updateFirstCost(document, stateType)(cost => cost.mapObject(_.add("support", f(support(cost)))))

  private def terms(support: Json, field: String): Vector[CostTerm] =
    support.hcursor
      .downField(field)
      .focus
      .flatMap(_.asArray)
      .getOrElse(fail(s"support has no $field"))
      .map(term => CostTerm.valueOf(term.asString.getOrElse(fail("term is not a string"))))

  private def weights(support: Json): Map[CostTerm, Double] =
    support.hcursor
      .downField("eligibleWeights")
      .focus
      .flatMap(_.asArray)
      .getOrElse(fail("support has no eligibleWeights"))
      .map { entry =>
        val c = entry.hcursor
        CostTerm.valueOf(c.get[String]("term").fold(e => fail(e.message), identity)) ->
          CanonicalPrimitives
            .parseHexDouble(c.get[String]("weight").fold(e => fail(e.message), identity))
            .getOrElse(fail("weight is not a canonical double"))
      }
      .toMap

  private def assessedJson(
      measured: Set[CostTerm],
      eligible: Map[CostTerm, Double],
      share: Option[Double] = None
  ): Json =
    val derived = SupportAssessment
      .fromEvidence(measured, eligible.keySet, eligible)
      .fold(e => fail(e.message), identity) match
      case a: SupportAssessment.Assessed => a.share
      case other                         => fail(s"fixture basis derives $other")
    val order = (ts: Iterable[CostTerm]) => ts.toVector.sortBy(_.ordinal)
    Json.obj(
      "type" -> Json.fromString("Assessed"),
      "share" -> Json.fromString(CanonicalDouble.render(share.getOrElse(derived))),
      "measuredTerms" -> Json.fromValues(order(measured).map(t => Json.fromString(t.toString))),
      "eligibleTerms" -> Json.fromValues(
        order(eligible.keys).map(t => Json.fromString(t.toString))
      ),
      "eligibleWeights" -> Json.fromValues(order(eligible.keys).map { t =>
        Json.obj(
          "term" -> Json.fromString(t.toString),
          "weight" -> Json.fromString(CanonicalDouble.render(eligible(t)))
        )
      })
    )

  private def rejected(label: String, document: Json, record: String): Unit =
    HsmmResultCodec.decodeJson(document, fixture.recall, fixture.view) match
      case Left(HsmmCodecError.Rejected(AlignError.MalformedRecord(`record`, _))) => ()
      case other => fail(s"$label: expected a $record rejection, got $other")

  test("an assessed share is re-derived from its carried basis and compared bit for bit") {
    val source = support(firstCost(json, "Source"))
    val measured = terms(source, "measuredTerms").toSet
    val eligible = weights(source)
    // Control: re-encoding the carried basis through the test's own writer is accepted.
    assertEquals(
      HsmmResultCodec.decodeJson(
        withSupport(json, "Source")(_ => assessedJson(measured, eligible)),
        fixture.recall,
        fixture.view
      ),
      Right(fixture.result)
    )
    // THE ALL-MEASURED-BUT-0.5 ATTACK: a basis whose eligible population is exactly its measured
    // population derives exactly 1.0, so a carried 0.5 is refused rather than adopted.
    val complete = eligible.view.filterKeys(measured).toMap
    HsmmResultCodec.decodeJson(
      withSupport(json, "Source")(_ => assessedJson(measured, complete, Some(0.5))),
      fixture.recall,
      fixture.view
    ) match
      case Left(HsmmCodecError.Rejected(AlignError.MalformedRecord("SupportAssessment", detail))) =>
        // Human diagnostics use the backend's Double rendering (JS prints 1, JVM prints 1.0).
        // The wire above remains canonical IEEE-754 and the refusal still pins both values.
        assertEquals(
          detail,
          s"carried share ${0.5.toString} is not the share its basis derives (${1.0.toString})"
        )
      case other => fail(s"a caller-selected 0.5 over a complete measurement was accepted: $other")
    // One ULP is a different claim.
    val share = CanonicalPrimitives
      .parseHexDouble(source.hcursor.get[String]("share").fold(e => fail(e.message), identity))
      .getOrElse(fail("share is not a canonical double"))
    rejected(
      "a share one ULP off its basis",
      withSupport(json, "Source")(_ => assessedJson(measured, eligible, Some(math.nextUp(share)))),
      "SupportAssessment"
    )
    // A share on the wire is required: v4 carries it redundantly, never optionally.
    HsmmResultCodec.decodeJson(
      withSupport(json, "Source")(_.mapObject(_.remove("share"))),
      fixture.recall,
      fixture.view
    ) match
      case Left(HsmmCodecError.Wire(_)) => ()
      case other                        => fail(s"an Assessed support without its share: $other")
  }

  test(
    "the carried basis must be this record's basis and coherent"
  ) {
    val source = support(firstCost(json, "Source"))
    val measured = terms(source, "measuredTerms").toSet
    val eligible = weights(source)
    // A priced term dropped from the measured population, share recomputed so only the binding
    // to the record is wrong.
    rejected(
      "a priced term missing from measuredTerms",
      withSupport(json, "Source")(_ => assessedJson(measured - measured.head, eligible)),
      "CostBreakdown"
    )
    // Weights that do not cover the eligible population, and a non-finite or negative weight.
    rejected(
      "an eligible term without a weight",
      withSupport(json, "Source")(
        _.mapObject(o =>
          o.add(
            "eligibleWeights",
            Json.fromValues(o("eligibleWeights").flatMap(_.asArray).getOrElse(Vector.empty).drop(1))
          )
        )
      ),
      "CellSupportBasis"
    )
    Vector(Double.NaN, Double.PositiveInfinity, -1.0).foreach { bad =>
      rejected(
        s"eligible weight $bad",
        withSupport(json, "Source")(_ =>
          assessedJson(measured, eligible, Some(1.0)).mapObject { o =>
            val entries = o("eligibleWeights").flatMap(_.asArray).getOrElse(Vector.empty)
            o.add(
              "eligibleWeights",
              Json.fromValues(
                entries.head.mapObject(
                  _.add("weight", Json.fromString(CanonicalDouble.render(bad)))
                ) +:
                  entries.tail
              )
            )
          }
        ),
        "CellSupportBasis"
      )
    }
    rejected(
      "a duplicate measured term",
      withSupport(json, "Source")(
        _.mapObject(o =>
          o.add(
            "measuredTerms",
            Json.fromValues(
              o("measuredTerms").flatMap(_.asArray).toVector.flatMap(v => v.head +: v)
            )
          )
        )
      ),
      "SupportAssessment.measuredTerms"
    )
  }

  test("fields the schema does not define are refused, not ignored") {
    // A v3 supportWeight of 1.0 smuggled beside a v4 NotApplicable would be dropped by this
    // decoder and re-encoded away, but any other reader of the artifact would still see "fully
    // supported". Unknown fields are refused at the artifact, cost and basis levels.
    def wireRefused(label: String, document: Json, expected: String): Unit =
      HsmmResultCodec.decodeJson(document, fixture.recall, fixture.view) match
        case Left(HsmmCodecError.Wire(error)) =>
          assert(error.message.contains(expected), s"$label: ${error.message}")
        case other => fail(s"$label was accepted: $other")
    wireRefused(
      "a supportWeight beside NotApplicable",
      updateFirstCost(json, "External")(
        _.mapObject(_.add("supportWeight", Json.fromString(CanonicalDouble.render(1.0))))
      ),
      "cost carries unknown supportWeight"
    )
    wireRefused(
      "an unknown artifact field",
      json.mapObject(_.add("supportWeights", Json.arr())),
      "HSMM artifact carries unknown supportWeights"
    )
    wireRefused(
      "an unknown eligible-weight field",
      withSupport(json, "Source")(
        _.mapObject { o =>
          val entries = o("eligibleWeights").flatMap(_.asArray).getOrElse(Vector.empty)
          o.add(
            "eligibleWeights",
            Json.fromValues(entries.head.mapObject(_.add("share", Json.True)) +: entries.tail)
          )
        }
      ),
      "eligible weight carries unknown share"
    )
    wireRefused(
      "an unknown state-cost field",
      json.mapObject { root =>
        val units = root("costs").flatMap(_.asArray).getOrElse(Vector.empty)
        root.add(
          "costs",
          Json.fromValues(units.head.mapObject { u =>
            val costs = u("costs").flatMap(_.asArray).getOrElse(Vector.empty)
            u.add(
              "costs",
              Json.fromValues(costs.head.mapObject(_.add("support", Json.Null)) +: costs.tail)
            )
          } +: units.tail)
        )
      },
      "state cost carries unknown support"
    )
  }

  test("support variants cannot move between source, external and unreachable records") {
    val notApplicable = (reason: String) =>
      Json.obj("type" -> Json.fromString("NotApplicable"), "reason" -> Json.fromString(reason))
    rejected(
      "a source record claiming ExternalState",
      withSupport(json, "Source")(_ => notApplicable("ExternalState")),
      "CostBreakdown"
    )
    rejected(
      "a source record claiming Unreachable",
      withSupport(json, "Source")(_ => notApplicable("Unreachable")),
      "CostBreakdown"
    )
    val sourceSupport = support(firstCost(json, "Source"))
    rejected(
      "an external record carrying a measured share",
      withSupport(json, "External")(_ => sourceSupport),
      "CostBreakdown"
    )
    rejected(
      "an external record claiming Unreachable",
      withSupport(json, "External")(_ => notApplicable("Unreachable")),
      "CostBreakdown"
    )
    // An external state dressed as an unreachable exclusion is still an external key.
    val masquerade = updateFirstCost(json, "External")(
      _.mapObject(
        _.add("exclusion", Json.fromString("Unreachable"))
          .add("support", notApplicable("Unreachable"))
      )
    )
    rejected("an external key carrying an unreachable record", masquerade, "CostBreakdown")
  }

  test("variants that make no numeric claim refuse a share and every measurement field") {
    def wireRefused(label: String, next: Json, expected: String): Unit =
      HsmmResultCodec.decodeJson(
        withSupport(json, "External")(_ => next),
        fixture.recall,
        fixture.view
      ) match
        case Left(HsmmCodecError.Wire(error)) =>
          assert(error.message.contains(expected), s"$label: ${error.message}")
        case other => fail(s"$label was accepted: $other")
    val external = Json.obj(
      "type" -> Json.fromString("NotApplicable"),
      "reason" -> Json.fromString("ExternalState")
    )
    wireRefused(
      "NotApplicable with a share",
      external.mapObject(_.add("share", Json.fromString(CanonicalDouble.render(1.0)))),
      "cannot carry a numeric claim"
    )
    wireRefused(
      "NotApplicable with measured terms",
      external.mapObject(_.add("measuredTerms", Json.arr())),
      "unexpected measuredTerms"
    )
    wireRefused(
      "Unreachable with a basis",
      Json.obj(
        "type" -> Json.fromString("NotApplicable"),
        "reason" -> Json.fromString("Unreachable"),
        "eligibleTerms" -> Json.arr(),
        "eligibleWeights" -> Json.arr()
      ),
      "unexpected eligibleTerms, eligibleWeights"
    )
    wireRefused(
      "Unestablished with a share",
      Json.obj(
        "type" -> Json.fromString("Unestablished"),
        "reason" -> Json.fromString("EmptyEligibility"),
        "share" -> Json.fromString(CanonicalDouble.render(1.0)),
        "measuredTerms" -> Json.arr(),
        "eligibleTerms" -> Json.arr(),
        "eligibleWeights" -> Json.arr()
      ),
      "cannot carry a numeric claim"
    )
    // The external cells of a real artifact publish no number at all - on WOG they published 1.0.
    val externals = allCosts(json, "External")
    assert(externals.nonEmpty)
    externals.foreach(cost => assertEquals(support(cost), external))
  }

  test("zero eligible weight round-trips as derived Unestablished; its reason cannot be chosen") {
    val zero = CostWeights
      .of(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
      .fold(e => fail(e.message), identity)
    val result = GraphHsmm
      .infer(
        fixture.recall,
        fixture.view,
        fixture.candidates,
        DefaultLocalCostModel(weights = zero, semantic = SemanticDistance.lexicalJaccard)
      )
      .fold(e => fail(e.message), identity)
    val document = HsmmResultCodec.encode(result).fold(e => fail(e.message), identity)
    assert(document.contains("\"reason\":\"ZeroEligibleWeight\""), document)
    assertEquals(HsmmResultCodec.decode(document, fixture.recall, fixture.view), Right(result))
    val parsed = Canonical.parse(document).fold(e => fail(e.message), identity)
    rejected(
      "a caller-selected Unestablished reason",
      withSupport(parsed, "Source")(
        _.mapObject(_.add("reason", Json.fromString("EmptyEligibility")))
      ),
      "SupportAssessment"
    )
    rejected(
      "an Assessed tag over a zero-weight basis",
      withSupport(parsed, "Source")(
        _.mapObject(
          _.remove("reason")
            .add("type", Json.fromString("Assessed"))
            .add("share", Json.fromString(CanonicalDouble.render(1.0)))
        )
      ),
      "SupportAssessment"
    )
    // And the converse: an Unestablished tag over a basis that derives a share.
    rejected(
      "an Unestablished tag over an assessable basis",
      withSupport(json, "Source")(
        _.mapObject(
          _.remove("share")
            .add("type", Json.fromString("Unestablished"))
            .add("reason", Json.fromString("ZeroEligibleWeight"))
        )
      ),
      "SupportAssessment"
    )
  }

  test("there is no context-free Decoder[HsmmResult]") {
    val errors = typeCheckErrors("""
      import io.circe.Decoder
      import storymodel4s.align.HsmmResult
      import storymodel4s.codec.HsmmResultCodec.given
      summon[Decoder[HsmmResult]]
    """)
    assert(errors.nonEmpty)
  }

  test("generic HSMM encoding and both contextual decode doors refuse non-text support") {
    val bundle = SourceBundle
      .filmEdition(
        EditionId.unsafe("hsmm-film"),
        Checksum.ofText("picture"),
        0L,
        100L,
        RationalTimebase.Millisecond
      )
      .toOption
      .get
    val anchors = EvidenceSupport
      .of(
        bundle,
        Vector(
          EvidenceAnchor.MediaPoint(
            bundle.id,
            bundle.streams.head.id,
            PlaybackInstant.on(bundle.primaryAxis, 25L).toOption.get
          )
        )
      )
      .toOption
      .get
    val pointNodes = fixture.view.nodes.map(
      _.copy(support = TypedSupport.Anchored(anchors), scoringPosition = None)
    )
    val unused =
      pointNodes.head.copy(ref = SourceNodeRef.Situation(SituationId.unsafe("unnominated-point")))
    val view = fixture.view.copy(nodes = pointNodes :+ unused)
    val old = fixture.result
    val result = HsmmResult
      .validated(
        fixture.recall,
        view,
        old.candidateAnchors,
        old.posterior,
        old.flow,
        old.viterbi,
        old.logLikelihood,
        old.costs,
        old.refinementPasses
      )
      .toOption
      .get
    assertEquals(result.sourceSupport.keySet, view.nodes.map(_.ref).toSet)
    assert(result.sourceSupport.values.forall(_ == TypedSupport.Anchored(anchors)))
    assert(!old.candidateAnchors.values.flatten.toSet.contains(unused.ref))
    assertEquals(result.sourceSupport.get(unused.ref), Some(TypedSupport.Anchored(anchors)))
    assertEquals(HsmmResultCodec.encode(result), Left(HsmmCodecError.UnsupportedSupport))
    assertEquals(HsmmResultCodec.toJson(result), Left(HsmmCodecError.UnsupportedSupport))
    assertEquals(
      HsmmResultCodec.decode(encoded, fixture.recall, view),
      Left(HsmmCodecError.UnsupportedSupport)
    )
    assertEquals(
      HsmmResultCodec.decodeJson(json, fixture.recall, view),
      Left(HsmmCodecError.UnsupportedSupport)
    )
  }

  test("checked encoding remains available but no generic Encoder HsmmResult exists") {
    assert(typeCheckErrors("""
      import storymodel4s.align.HsmmResult
      import storymodel4s.codec.{HsmmResultCodec, HsmmCodecError}
      def encode(r: HsmmResult): Either[HsmmCodecError, String] = HsmmResultCodec.encode(r)
    """).isEmpty)
    assert(typeCheckErrors("""
      import io.circe.Encoder
      import storymodel4s.align.HsmmResult
      import storymodel4s.codec.HsmmResultCodec.given
      summon[Encoder[HsmmResult]]
    """).nonEmpty)
  }

  test("result refuses duplicate inventory lookup disagreement and lookup-only nominations") {
    val old = fixture.result
    def rebuild(view: SourceView) = HsmmResult.validated(
      fixture.recall,
      view,
      old.candidateAnchors,
      old.posterior,
      old.flow,
      old.viterbi,
      old.logLikelihood,
      old.costs,
      old.refinementPasses
    )
    def wrapped(
        inventory: Vector[NodeSummary],
        lookup: SourceNodeRef => Option[NodeSummary]
    ): SourceView =
      new SourceView:
        val nodes = inventory
        def node(ref: SourceNodeRef) = lookup(ref)
        def adjacency(layer: RelationLayer) = fixture.view.adjacency(layer)
        val worldOrder = fixture.view.worldOrder
        val scoringLength = fixture.view.scoringLength
    assertEquals(rebuild(fixture.view), Right(old))
    val first = fixture.view.nodes.head
    assert(rebuild(wrapped(fixture.view.nodes :+ first, fixture.view.node)).isLeft)
    assert(
      rebuild(
        wrapped(
          fixture.view.nodes,
          ref =>
            fixture.view
              .node(ref)
              .map(_.copy(scoringPosition = None))
        )
      ).isLeft
    )
    val nominated = old.candidateAnchors.values.flatten.head
    assert(
      rebuild(wrapped(fixture.view.nodes.filterNot(_.ref == nominated), fixture.view.node)).isLeft
    )
  }

  test("text support with absent or noncanonical scoring cannot enter the v3 wire") {
    val old = fixture.result
    val positions: Vector[NodeSummary => Option[ScoringPosition]] = Vector(
      _ => None,
      node => Some(ScoringPosition.LegacyAnnotationText(node.support.textSpans.get)),
      _ => Some(ScoringPosition.CanonicalText(SpanSet.one(TextSpan.unsafe(0, 0))))
    )
    positions.foreach { position =>
      val view =
        fixture.view.copy(nodes =
          fixture.view.nodes.map(node => node.copy(scoringPosition = position(node)))
        )
      assert(!view.textWireCompatible)
      val result = HsmmResult
        .validated(
          fixture.recall,
          view,
          old.candidateAnchors,
          old.posterior,
          old.flow,
          old.viterbi,
          old.logLikelihood,
          old.costs,
          old.refinementPasses
        )
        .toOption
        .get
      assertEquals(HsmmResultCodec.encode(result), Left(HsmmCodecError.UnsupportedSupport))
    }
  }

  private def updateFirstObjectInArray(
      document: Json,
      field: String
  )(f: Json => Json): Json =
    document.mapObject { root =>
      val values = root(field).flatMap(_.asArray).getOrElse(Vector.empty)
      root.add(field, Json.fromValues(values.headOption.map(f).toVector ++ values.drop(1)))
    }

  private def updateFirstSourceCost(document: Json)(f: Json => Json): Json =
    updateFirstCost(document, "Source")(f)

  private def stateType(stateCost: Json): Either[io.circe.DecodingFailure, String] =
    stateCost.hcursor.downField("state").get[String]("type")

  private def allCosts(document: Json, kind: String): Vector[Json] =
    document.hcursor
      .downField("costs")
      .focus
      .flatMap(_.asArray)
      .getOrElse(Vector.empty)
      .flatMap(unit =>
        unit.hcursor.downField("costs").focus.flatMap(_.asArray).getOrElse(Vector.empty)
      )
      .collect {
        case stateCost if stateType(stateCost) == Right(kind) =>
          stateCost.hcursor.downField("cost").focus.getOrElse(fail("state cost has no cost"))
      }

  private def firstCost(document: Json, kind: String): Json =
    allCosts(document, kind).headOption.getOrElse(fail(s"no $kind cost in the artifact"))

  private def mapCosts(document: Json)(f: Json => Json): Json =
    document.mapObject { root =>
      val units = root("costs").flatMap(_.asArray).getOrElse(Vector.empty)
      root.add(
        "costs",
        Json.fromValues(units.map { unit =>
          unit.mapObject { unitFields =>
            val costs = unitFields("costs").flatMap(_.asArray).getOrElse(Vector.empty)
            unitFields.add(
              "costs",
              Json.fromValues(costs.map { stateCost =>
                stateCost.mapObject(fields =>
                  fields("cost").fold(fields)(c => fields.add("cost", f(c)))
                )
              })
            )
          }
        })
      )
    }

  /** Rewrite the FIRST cost of `kind` in the whole artifact (one cell, not one per unit). */
  private def updateFirstCost(document: Json, kind: String)(f: Json => Json): Json =
    document.mapObject { root =>
      val units = root("costs").flatMap(_.asArray).getOrElse(Vector.empty)
      var changed = false
      val updated = units.map { unit =>
        unit.mapObject { unitFields =>
          val costs = unitFields("costs").flatMap(_.asArray).getOrElse(Vector.empty)
          val rewritten = costs.map { stateCost =>
            if !changed && stateType(stateCost) == Right(kind) then
              changed = true
              stateCost.mapObject { fields =>
                fields("cost").fold(fields)(cost => fields.add("cost", f(cost)))
              }
            else stateCost
          }
          unitFields.add("costs", Json.fromValues(rewritten))
        }
      }
      root.add("costs", Json.fromValues(updated))
    }

  private final case class Fixture(
      recall: RecallGraph[Checked],
      view: InMemorySourceView,
      candidates: Candidates,
      result: HsmmResult
  )

  private object Fixture:
    def build(): Fixture =
      val source = StorySource.fromText("Anna arrived. Bob left.").toOption.get
      val sourceAtlas = SurfaceAnalyzer.analyze(source)
      val sourceSentences = sourceAtlas.sentences
      val firstRef = SourceNodeRef.Situation(SituationId.unsafe("arrive"))
      val secondRef = SourceNodeRef.Situation(SituationId.unsafe("leave"))

      def support(index: Int): SpanSet =
        val sentence = sourceSentences(index)
        SpanSet.one(SpanRef(Some(sentence.id), sentence.span))

      val view = InMemorySourceView(
        Vector(
          NodeSummary(
            firstRef,
            0,
            None,
            0,
            support(0),
            Some("arrive"),
            Vector(ParticipantSummary(SketchRole.Agent, "Anna", Set("anna"))),
            ContextTag.NarratedWorld,
            PolarityTag.Positive,
            ModalityTag.Asserted,
            Vector.empty,
            Set("anna", "arrive")
          ),
          NodeSummary(
            secondRef,
            0,
            None,
            1,
            support(1),
            Some("leave"),
            Vector(ParticipantSummary(SketchRole.Agent, "Bob", Set("bob"))),
            ContextTag.NarratedWorld,
            PolarityTag.Positive,
            ModalityTag.Asserted,
            Vector.empty,
            Set("bob", "leave")
          )
        ),
        Map(
          RelationLayer.DiscourseSuccession -> Vector((firstRef, secondRef, 1.0)),
          RelationLayer.WorldTime -> Vector((firstRef, secondRef, 1.0))
        ),
        Some(Map(firstRef -> 0, secondRef -> 1)),
        source.canonicalText.length
      )

      val transcript = StorySource.fromText("Anna arrived. Bob left.").toOption.get
      val recallAtlas = SurfaceAnalyzer.analyze(transcript)
      val units = recallAtlas.sentences.zipWithIndex.map { (sentence, index) =>
        val (name, predicate) = if index == 0 then ("Anna", "arrive") else ("Bob", "leave")
        RecallUnit(
          RecallUnitId.unsafe(s"u$index"),
          index,
          SpanSet.one(SpanRef(Some(sentence.id), sentence.span)),
          recallAtlas.text(sentence),
          DiscourseFunction.EpisodicAssertion,
          ExpressedUncertainty.Unmarked,
          PropositionSketch(
            Some(predicate),
            Vector(SketchParticipant(SketchRole.Agent, None, name)),
            PolarityTag.Positive,
            ModalityTag.Asserted,
            Vector.empty,
            Vector.empty,
            Vector.empty,
            Set(name.toLowerCase, predicate)
          ),
          None
        )
      }
      val recall = RecallGraph
        .validated(transcript, recallAtlas, units, RecallRelations.empty)
        .toOption
        .get
      val candidates = Candidates.of(
        Map(units(0).id -> Vector(firstRef), units(1).id -> Vector(secondRef))
      )
      val costModel = DefaultLocalCostModel(semantic = SemanticDistance.lexicalJaccard)
      val result = GraphHsmm
        .infer(recall, view, candidates, costModel)
        .fold(error => throw new IllegalStateException(error.message), identity)
      Fixture(recall, view, candidates, result)
