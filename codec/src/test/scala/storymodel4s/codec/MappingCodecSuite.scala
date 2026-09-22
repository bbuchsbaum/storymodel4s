package storymodel4s.codec

import munit.FunSuite
import io.circe.Json
import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.features.MissingReason
import storymodel4s.laws.MappingMiniature
import storymodel4s.recall.*

class MappingCodecSuite extends FunSuite:
  private lazy val miniature = MappingMiniature.record
  private def context(record: MappingResult): ExpectedMappingContext =
    ExpectedMappingContext(record.inventory, record.source)
  private def roundTrip(record: MappingResult, expected: ExpectedMappingContext): MappingResult =
    val encoded = MappingCodecs.encode(record)
    val decoded = MappingCodecs.decode(encoded, expected).fold(e => fail(e.message), identity)
    assertEquals(decoded.digest, record.digest)
    assertEquals(MappingCodecs.encode(decoded), encoded)
    decoded
  private def change(value: Json, key: String)(f: Json => Json): Json =
    value.mapObject(o => o.add(key, f(o(key).get)))
  private def first(value: Json, key: String)(f: Json => Json): Json = change(value, key)(j =>
    Json.fromValues(j.asArray.get.zipWithIndex.map((v, i) => if i == 0 then f(v) else v))
  )
  private def decode(
      value: Json,
      expected: ExpectedMappingContext
  ): Either[MappingCodecError, MappingResult] =
    MappingCodecs.decode(MappingJson.print(value), expected)
  private def refused(value: Either[MappingCodecError, ?], expected: MappingCodecError): Unit =
    assertEquals(value.left.toOption, Some(expected))
  test("miniature round-trips and retains p6") {
    val decoded = roundTrip(miniature, context(miniature))
    assertEquals(decoded.outcomes.size, 8)
    assert(
      decoded
        .outcome(RecallUnitId.unsafe("p6"))
        .get
        .processing
        .isInstanceOf[ProcessingStatus.Failed]
    )
    assertEquals(decoded.derivation, DerivationSource.NoDerivedValues)
  }
  test("historical text round-trips identically") {
    val record = MappingCodecFixture.record()
    val decoded = roundTrip(record, MappingCodecFixture.context())
    assert(
      decoded.outcomes.flatMap(_.links).exists(_.fidelity.isInstanceOf[FidelityStatus.Assessed])
    )
    decoded.outcomes.foreach { row =>
      assertEquals(
        row.measures.posterior.get.mass.map((s, p) => s -> java.lang.Double.doubleToLongBits(p)),
        MappingCodecFixture.result.posterior
          .row(row.unit)
          .get
          .mass
          .map((s, p) => s -> java.lang.Double.doubleToLongBits(p))
      )
    }
  }
  test("each unmixed result needs its own context") {
    val a = MappingCodecFixture.record()
    val b = MappingCodecFixture.record(MappingCodecFixture.variant)
    roundTrip(a, MappingCodecFixture.context())
    roundTrip(b, MappingCodecFixture.context(MappingCodecFixture.variant))
    assertEquals(
      MappingCodecs
        .decode(MappingCodecs.encode(a), MappingCodecFixture.context(MappingCodecFixture.variant))
        .left
        .toOption,
      Some(MappingCodecError.DigestMismatch("result_digest"))
    )
    assertEquals(
      MappingCodecs.decode(MappingCodecs.encode(b), MappingCodecFixture.context()).left.toOption,
      Some(MappingCodecError.DigestMismatch("result_digest"))
    )
  }
  test("posterior needs result context") {
    val record = MappingCodecFixture.record()
    assertEquals(
      MappingCodecs.decode(MappingCodecs.encode(record), context(record)).left.toOption,
      Some(MappingCodecError.ContextRequired("derivation_source"))
    )
  }
  test("Declared twin context refuses") {
    val twin = MappingCodecFixture.view.copy(nodes =
      MappingCodecFixture.view.nodes.map(
        _.copy(propositional = PropositionalScope.Undeclared(MissingReason.ProviderAbstained))
      )
    )
    val record = MappingCodecFixture.record()
    assertEquals(
      MappingCodecs
        .decode(MappingCodecs.encode(record), MappingCodecFixture.context(v = twin))
        .left
        .toOption,
      Some(MappingCodecError.Rejected(MappingRefusal.BindingMismatch("scopeDigest")))
    )
    val source = MappingCodecFixture.sourceFor(twin)
    roundTrip(
      MappingCodecFixture.record(v = twin, source = source),
      MappingCodecFixture.context(v = twin, source = source)
    )
  }

  test("Sherlock-shaped composed support round-trips") {
    val record = MappingCompositionFixture.record
    val decoded = roundTrip(record, context(record))
    assertEquals(
      decoded.source.order(
        MappingCompositionFixture.a.primaryAxis.id,
        MappingCompositionFixture.b.primaryAxis.id
      ),
      PartOrder.Before
    )
    assert(
      decoded.source.targets.exists(_.axisMembership.values.toSet.contains(BundleRole.Composition))
    )
  }
  test("ticks and rational denominator above 2^53 remain exact strings") {
    val record = MappingCompositionFixture.largeCoordinates
    val encoded = MappingCodecs.encode(record)
    assert(encoded.contains("\"tick\":\"9007199254740993\""))
    assert(encoded.contains("\"denominator\":\"9007199254740993\""))
    val decoded = roundTrip(record, context(record))
    val bundle = decoded.source.bundles.head.asInstanceOf[BundleEntry.Media].bundle
    assertEquals(
      bundle.primaryAxis.timebase.get.scale.denominator,
      MappingCompositionFixture.bigTick
    )
  }
  test("numeric tick refuses with NumericTick") {
    val record = MappingCompositionFixture.largeCoordinates
    val json = MappingCodecs.toJson(record)
    val changed = change(json, "source")(source =>
      first(source, "targets")(target =>
        change(target, "support_status")(physical =>
          change(physical, "source_support")(support =>
            first(support, "anchors")(
              _.mapObject(_.add("tick", Json.fromLong(MappingCompositionFixture.bigTick)))
            )
          )
        )
      )
    )
    refused(
      decode(changed, context(record)),
      MappingCodecError.NumericTick(
        "source.targets[0].support_status.source_support.anchors[0].tick"
      )
    )
  }
  test("absent timebase is tagged") {
    val record = MappingCompositionFixture.untimedTextAxis
    val json = MappingCodecs.toJson(record)
    val timebase =
      json.hcursor.downField("source").downField("axes").downArray.downField("timebase").focus.get
    assertEquals(
      timebase,
      Json.obj("status" -> Json.fromString("absent"), "reason" -> Json.fromString("not-supplied"))
    )
    roundTrip(record, context(record))
    val missing =
      change(json, "source")(source => first(source, "axes")(_.mapObject(_.remove("timebase"))))
    refused(decode(missing, context(record)), MappingCodecError.ValueMismatch("source"))
  }
  test("derived refuses with Reserved") {
    val json = MappingCodecs.toJson(miniature)
    val changed = first(json, "stage_assumption_receipts")(entry =>
      change(entry, "provenance")(_.mapObject(_.add("status", Json.fromString("derived"))))
    )
    refused(decode(changed, context(miniature)), MappingCodecError.Reserved("derived"))
  }
  test("calibrated refuses with Reserved") {
    val json = MappingCodecs.toJson(miniature)
    val changed = first(json, "outcomes")(row =>
      change(row, "decision")(value =>
        change(value, "value")(decision =>
          change(decision, "calibration")(
            _.mapObject(_.add("status", Json.fromString("calibrated")))
          )
        )
      )
    )
    refused(decode(changed, context(miniature)), MappingCodecError.Reserved("calibrated"))
    val compatibility = json.mapObject(
      _.add("measurement_compatibility", Json.fromString("CompatibleWithDeclaredPolicy"))
    )
    refused(
      decode(compatibility, context(miniature)),
      MappingCodecError.Reserved("measurement_compatibility")
    )
  }
  test("v0.2 refuses with UnsupportedSchema before parsing its body") {
    val json = Json.obj("schemaVersion" -> Json.fromString("mapping-record/v0.2"))
    refused(
      decode(json, context(miniature)),
      MappingCodecError.Wire(
        CodecError.UnsupportedSchema("mapping-record/v0.2", Vector(MappingCodecs.SchemaVersion))
      )
    )
  }
  test("foreign inventory refuses with DigestMismatch despite shared unit IDs") {
    val f = MappingCodecFixture
    val foreign = RecallInventory
      .of(
        f.recall,
        f.inventory.words.map(_.span),
        WordIdPolicy.inputArtifact(Checksum.ofText("foreign-input"))
      )
      .toOption
      .get
    assertEquals(foreign.units.map(_.id), f.inventory.units.map(_.id))
    assertEquals(foreign.segmentation, f.inventory.segmentation)
    refused(
      MappingCodecs.decode(MappingCodecs.encode(f.record()), f.context().copy(inventory = foreign)),
      MappingCodecError.DigestMismatch("inventory")
    )
  }
  test("unknown field refuses") {
    val json = MappingCodecs.toJson(miniature).mapObject(_.add("unverified_claim", Json.True))
    refused(decode(json, context(miniature)), MappingCodecError.ValueMismatch("canonical-record"))
  }
  test("unknown null fields refuse at the root and inside policies") {
    val json = MappingCodecs.toJson(miniature)
    val modified = Vector(
      json.mapObject(_.add("unexpected", Json.Null)),
      change(json, "policies")(_.mapObject(_.add("unexpected", Json.Null)))
    )
    modified.foreach { value =>
      val text = Canonical.printer.copy(dropNullValues = false).print(value)
      refused(
        MappingCodecs.decode(text, context(miniature)),
        MappingCodecError.ValueMismatch("canonical-record")
      )
    }
  }
  test("missing term_support refuses") {
    val json = first(MappingCodecs.toJson(miniature), "outcomes")(row =>
      first(row, "links")(_.mapObject(_.remove("term_support")))
    )
    decode(json, context(miniature)) match
      case Left(MappingCodecError.Wire(CodecError.Decode(path, _))) =>
        assertEquals(path, "$.term_support")
      case other => fail(s"expected required term_support, got $other")
  }
  test("not-computed is encoded explicitly and only equals ungated evidence") {
    val json = MappingCodecs.toJson(miniature)
    assertEquals(
      json.hcursor
        .downField("outcomes")
        .downArray
        .downField("links")
        .downArray
        .downField("term_support")
        .get[String]("status"),
      Right("not-computed")
    )
    val historical = MappingCodecFixture.record()
    val changed = first(MappingCodecs.toJson(historical), "outcomes")(row =>
      first(row, "links")(
        _.mapObject(
          _.add(
            "term_support",
            Json.obj(
              "status" -> Json.fromString("not-computed"),
              "reason" -> Json.fromString("NoCostBreakdown")
            )
          )
        )
      )
    )
    refused(
      decode(changed, MappingCodecFixture.context()),
      MappingCodecError.ValueMismatch("derived-link")
    )
  }
  test("historical round trip preserves mass bits and rejects rounded values") {
    val record = MappingCodecFixture.record()
    val changed = first(MappingCodecs.toJson(record), "outcomes")(row =>
      first(row, "mapping_links")(
        _.mapObject(_.add("raw_value", Json.fromString("0x0000000000000000")))
      )
    )
    refused(
      decode(changed, MappingCodecFixture.context()),
      MappingCodecError.ValueMismatch("mapping_links")
    )
  }
  test("match fields are mandatory") {
    val record = MappingCodecFixture.record()
    val changed = change(MappingCodecs.toJson(record), "derivation_source")(d =>
      change(d, "binding")(_.mapObject(_.remove("recall_checksum")))
    )
    decode(changed, MappingCodecFixture.context()) match
      case Left(MappingCodecError.Wire(CodecError.Decode(path, _))) =>
        assertEquals(path, "$.recall_checksum")
      case other => fail(s"expected required recall_checksum, got $other")
  }
  test("caller asserted receipt remains Unknown and round-trips exactly") {
    val receipt = StageReceipt
      .of(
        Stage.Projection,
        "caller-declared",
        Checksum.ofText("config"),
        Vector(Checksum.ofText("input")),
        ProviderStatus.NotApplicable("synthetic")
      )
      .toOption
      .get
    val entry = StageEntry
      .of(
        Stage.Projection,
        StageProvenance.unknown(UnknownProvenanceReason.CallerSuppliedDecision, Some(receipt))
      )
      .toOption
      .get
    val ledger = StageLedger
      .of(
        (miniature.ledger.entries.filterNot(_.stage == Stage.Projection) :+ entry)
          .sortBy(e => (e.stage.ordinal, e.id.digest.hex))
      )
      .toOption
      .get
    val record = MappingResult
      .checked(
        miniature.inventory,
        miniature.source,
        miniature.policies,
        miniature.roles,
        ledger,
        miniature.outcomes
      )
      .toOption
      .get
    val restored = roundTrip(record, context(record))
    val provenance =
      restored.ledger.at(Stage.Projection).head.provenance.asInstanceOf[StageProvenance.Unknown]
    assertEquals(provenance.reason, UnknownProvenanceReason.CallerSuppliedDecision)
    assertEquals(provenance.asserted.get.policy, "caller-declared")
  }
  test("Unicode and unmatched UTF16 code units survive the ASCII wire") {
    def withLabel(label: String): MappingResult =
      val p = miniature.policies
      val policies = MappingPolicies
        .of(
          InferencePolicy.Unspecified(label),
          p.context,
          p.candidate,
          p.referencePrior,
          p.decision,
          p.universe
        )
        .toOption
        .get
      MappingResult
        .checked(
          miniature.inventory,
          miniature.source,
          policies,
          miniature.roles,
          miniature.ledger,
          miniature.outcomes
        )
        .toOption
        .get
    val labels = Vector("a😀é", "a\ud800", "a\ud801", "a\udc00", """a"\{},[]:b""")
    val records = labels.map(withLabel)
    assertEquals(records.map(_.digest).distinct.size, labels.size)
    records.zip(labels).foreach { (record, label) =>
      val encoded = MappingCodecs.encode(record)
      assert(encoded.forall(_.toInt < 128))
      val decoded = roundTrip(record, context(record))
      assertEquals(decoded.policies.inference, InferencePolicy.Unspecified(label))
    }
  }

  test("normalized and transport measures retain their distinct values and semantics") {
    val row = miniature.outcomes.head
    val stages = row.stages.get
    val prior = ReferencePriorId.unsafe("synthetic-prior")
    val target = row.links.head.destination
    val external = Destination.External(ExternalState.Intrusion)
    val normalized = NormalizedScoreMass
      .of(
        miniature.policies.universe.id,
        prior,
        0.75,
        Map(target -> 0.6, external -> 0.4),
        stages.inference
      )
      .toOption
      .get
    val transport = TransportMass.of(7.0, Map(target -> 2.0), stages.inference).toOption.get
    val measures =
      UnitMeasures.of(row.measures.raw, Some(normalized), Some(transport), None).toOption.get
    val candidates =
      CandidateSetId.of(stages.candidates, row.unit, row.measures.raw.head.values.keySet)
    val links =
      measures.destinations.toVector.sorted.map(MappingLink.ungated(_, stages, candidates))
    val changed = UnitOutcome
      .computed(
        row.unit,
        measures,
        links,
        row.decision.get.basis,
        DecisionRequest.RawArgmax,
        stages
      )
      .toOption
      .get
    val p = miniature.policies
    val policies = MappingPolicies
      .of(
        p.inference,
        p.context,
        p.candidate,
        ReferencePrior.Declared(prior),
        p.decision,
        p.universe
      )
      .toOption
      .get
    val record = MappingResult
      .checked(
        miniature.inventory,
        miniature.source,
        policies,
        miniature.roles,
        miniature.ledger,
        miniature.outcomes.updated(0, changed)
      )
      .toOption
      .get
    val decoded = roundTrip(record, context(record)).outcomes.head
    assertEquals(decoded.measures.normalized.get.mass, Map(target -> 0.6, external -> 0.4))
    assertEquals(decoded.measures.transport.get.rowBudget, 7.0)
    assertEquals(decoded.measures.transport.get.mass, Map(target -> 2.0))
    assertEquals(decoded.measures.posterior, None)
  }
  private def linkOnly: MappingResult =
    val base = MappingCodecFixture.record()
    val outcomes = base.outcomes.map { row =>
      val raw = RawScores
        .of(
          "supplied",
          ScoreDirection.HigherIsBetter,
          "synthetic",
          row.measures.posterior.get.mass.map((s, p) => Destination.of(s) -> p),
          row.measures.raw.head.stage
        )
        .toOption
        .get
      val measures = UnitMeasures.of(Vector(raw), None, None, None).toOption.get
      UnitOutcome
        .computed(
          row.unit,
          measures,
          row.links,
          DecisionBasis.of(MeasureKind.RawScore, Some("supplied")).toOption.get,
          DecisionRequest.RawArgmax,
          row.stages.get
        )
        .toOption
        .get
    }
    MappingResult
      .checked(base.inventory, base.source, base.policies, base.roles, base.ledger, outcomes)
      .toOption
      .get
  test("assessed link evidence needs context without derived measures") {
    val record = linkOnly
    assert(
      record.outcomes.flatMap(_.links).exists(_.fidelity.isInstanceOf[FidelityStatus.Assessed])
    )
    assert(record.outcomes.forall(_.measures.posterior.isEmpty))
    roundTrip(record, MappingCodecFixture.context())
    refused(
      MappingCodecs.decode(MappingCodecs.encode(record), context(record)),
      MappingCodecError.ContextRequired("derivation_source")
    )
  }
  test("evaluated term support needs result context even if the header is demoted") {
    val record = linkOnly
    assert(
      record.outcomes
        .flatMap(_.links)
        .exists(_.termSupport.isInstanceOf[TermSupportStatus.Evaluated])
    )
    val changed = MappingCodecs
      .toJson(record)
      .mapObject(_.add("derivation_source", Json.obj("status" -> Json.fromString("none"))))
    refused(decode(changed, context(record)), MappingCodecError.ContextRequired("link"))
  }
  test("empty external decode and explicit abstention retain their original requests") {
    val row = miniature.outcomes.head
    val stages = row.stages.get
    val raw = RawScores
      .of("empty", ScoreDirection.HigherIsBetter, "synthetic", Map.empty, stages.inference)
      .toOption
      .get
    val measures = UnitMeasures.of(Vector(raw), None, None, None).toOption.get
    val basis = DecisionBasis.of(MeasureKind.RawScore, Some("empty")).toOption.get
    val policy = DecisionPolicyId.unsafe("synthetic-empty-decode")
    val requests = Vector(
      DecisionRequest.ExternalDecode(row.links.head.destination, policy),
      DecisionRequest.Abstain(policy, "explicit refusal")
    )
    val records = requests.map { request =>
      val outcome =
        UnitOutcome.computed(row.unit, measures, Vector.empty, basis, request, stages).toOption.get
      MappingResult
        .checked(
          miniature.inventory,
          miniature.source,
          miniature.policies,
          miniature.roles,
          miniature.ledger,
          miniature.outcomes.updated(0, outcome)
        )
        .toOption
        .get
    }
    assertEquals(records.map(_.digest).distinct.size, 2)
    records.zip(requests).foreach { (record, request) =>
      val decoded = roundTrip(record, context(record)).outcomes.head
      assertEquals(decoded.decision.get.request, request)
      assertEquals(decoded.decision.get.chosen, None)
    }
  }
  test("inventory body tampering refuses even with its original digest") {
    val changed = change(MappingCodecs.toJson(miniature), "inventory")(i =>
      first(i, "units")(_.mapObject(_.add("forged", Json.True)))
    )
    refused(decode(changed, context(miniature)), MappingCodecError.ValueMismatch("inventory"))
  }
  test("source body tampering refuses even with its original digest") {
    val changed =
      change(MappingCodecs.toJson(miniature), "source")(_.mapObject(_.add("forged", Json.True)))
    refused(decode(changed, context(miniature)), MappingCodecError.ValueMismatch("source"))
  }
  test("changing an ungated candidate identity refuses") {
    val changed = first(MappingCodecs.toJson(miniature), "outcomes")(row =>
      first(row, "links")(
        _.mapObject(
          _.add("candidate_set_id", Json.fromString(Checksum.ofText("foreign-candidate").hex))
        )
      )
    )
    refused(decode(changed, context(miniature)), MappingCodecError.ValueMismatch("link"))
  }

  test("distorted posterior keys retain all facets") {
    val f = MappingCodecFixture
    val recalled = RecallGraph
      .validated(
        f.recall.transcript,
        f.recall.atlas,
        f.recall.units.map(u =>
          u.copy(proposition = u.proposition.copy(polarity = PolarityTag.Negative))
        ),
        f.recall.relations
      )
      .toOption
      .get
    val inventory = f.inventoryFor(recalled)
    val result = GraphHsmm
      .infer(
        recalled,
        f.view,
        Candidates.of(recalled.ordered.zip(f.refs).map((u, r) => u.id -> Vector(r)).toMap),
        DefaultLocalCostModel(semantic = SemanticDistance.lexicalJaccard)
      )
      .toOption
      .get
    assert(result.posterior.rows.exists(_.mass.keys.exists(_.isInstanceOf[AlignState.Distorted])))
    val record = HistoricalMapping
      .of(result, recalled, f.view, inventory, f.source, HistoricalDecode.ArgmaxOnly)
      .toOption
      .get
    val decoded = roundTrip(
      record,
      ExpectedMappingContext(inventory, f.source, Some(DerivationContext(result, recalled, f.view)))
    )
    assertEquals(
      decoded.outcomes.map(_.measures.posterior.get.mass),
      record.outcomes.map(_.measures.posterior.get.mass)
    )
    import HsmmResultCodec.given
    val encodedStates = MappingCodecs
      .toJson(record)
      .hcursor
      .get[Vector[Json]]("outcomes")
      .toOption
      .get
      .flatMap { row =>
        row.hcursor
          .get[Vector[Json]]("mapping_links")
          .toOption
          .get
          .filter(_.hcursor.get[String]("measure_kind").toOption.contains("ModelPosterior"))
          .map(_.hcursor.downField("state").downField("value").focus.get)
      }
      .toSet
    val expectedStates = result.posterior.rows
      .flatMap(_.mass.keys)
      .map(summon[io.circe.Encoder[AlignState]].apply)
      .toSet
    assertEquals(encodedStates, expectedStates)

  }
  test("duplicate object keys refuse before parser normalization can erase them") {
    val encoded = MappingCodecs.encode(miniature)
    val examples = Vector(
      encoded.replace(
        "\"schemaVersion\":",
        "\"schemaVersion\":\"mapping-record/v0.1\",\"schemaVersion\":"
      ),
      encoded.replace(
        "\"schemaVersion\":",
        "\"schemaVersion\":\"mapping-record/v0.2\",\"schemaVersion\":"
      ),
      encoded.replace("\"policies\":{", "\"policies\":{\"context_policy_id\":null,"),
      encoded.replace(
        "\"schemaVersion\":",
        "\"schema\\u0056ersion\":\"mapping-record/v0.1\",\"schemaVersion\":"
      )
    )
    examples.foreach { text =>
      assertNotEquals(text, encoded)
      MappingCodecs.decode(text, context(miniature)) match
        case Left(MappingCodecError.Wire(CodecError.Decode("$", detail))) =>
          assert(detail.startsWith("duplicate object key:"))
        case other => fail(s"expected duplicate-key refusal, got ${other.left.toOption}")
    }
  }
  test("the same key in different nested objects remains legal") {
    val encoded = MappingCodecs.encode(miniature)
    assert(encoded.sliding("\"status\"".length).count(_ == "\"status\"") > 10)
    roundTrip(miniature, context(miniature))
  }
