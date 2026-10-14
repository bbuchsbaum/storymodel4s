package externalledgerprobe

import storymodel4s.core.*
import storymodel4s.codec.JsonLines
import cats.data.NonEmptyVector
import scala.compiletime.testing.typeCheckErrors
import scala.deriving.Mirror

object ClaimLedgerProbe:
  def main(args: Array[String]): Unit =
    val a = ClaimId.unsafe("audit-a")
    val b = ClaimId.unsafe("audit-b")
    val ev = Evidence(EvidenceId.unsafe("audit-e"), None, Set.empty,
      Fingerprint.unsafe("rule:audit:1"), StageId.unsafe("audit-stage"))
    val meta = ClaimMeta.of(a, EpistemicStatus.Hypothesized, Credence.unmeasured,
      NonEmptyVector.one(ev), Provenance.deterministic("audit", Checksum.ofText("audit"))).toOption.get
    val valid = ClaimLedger.empty.add(meta).toOption.get
    assert(valid.size == 1 && valid.all == Vector(meta) && valid.lookup(a).contains(meta))
    assert(valid.add(meta).isLeft)
    assert(JsonLines.readClaims(JsonLines.claims(valid)).exists(_.all == Vector(meta)))
    println("positive_empty_add_duplicate_refusal_and_codec_roundtrip=true")
    println("direct_apply_compile_errors=" + typeCheckErrors("storymodel4s.core.ClaimLedger(Map.empty, Vector.empty)").size)
    println("direct_new_compile_errors=" + typeCheckErrors("new storymodel4s.core.ClaimLedger(Map.empty, Vector.empty)").size)
    println("copy_compile_errors=" + typeCheckErrors("""storymodel4s.core.ClaimLedger.empty.copy(order = Vector(storymodel4s.core.ClaimId.unsafe("ghost")))""").size)
    val mirror = summon[Mirror.ProductOf[ClaimLedger]]
    val ghost = mirror.fromProduct((Map.empty[ClaimId, ClaimMeta], Vector(a)))
    assert(ghost.size == 1 && ghost.all.isEmpty && ghost.lookup(a).isEmpty)
    println(s"mirror_ghost: size=${ghost.size}, all=${ghost.all.size}, lookup=${ghost.lookup(a).isDefined}")
    val hidden = ClaimLedger.fromProduct((Map(a -> meta), Vector.empty[ClaimId]))
    assert(hidden.size == 0 && hidden.all.isEmpty && hidden.lookup(a).contains(meta))
    val hiddenWire = JsonLines.claims(hidden)
    val hiddenReloaded = JsonLines.readClaims(hiddenWire).toOption.get
    assert(hiddenWire.isEmpty && hiddenReloaded.lookup(a).isEmpty)
    assert(hidden.add(meta).isLeft)
    println(s"fromProduct_hidden: size=${hidden.size}, all=${hidden.all.size}, lookup=${hidden.lookup(a).isDefined}, serializedBytes=${hiddenWire.length}, reloadLookup=${hiddenReloaded.lookup(a).isDefined}, addSameRefuses=${hidden.add(meta).isLeft}")
    val duplicated = mirror.fromProduct((Map(a -> meta), Vector(a, a)))
    assert(duplicated.size == 2 && duplicated.all == Vector(meta, meta))
    assert(JsonLines.readClaims(JsonLines.claims(duplicated)).isLeft)
    println("mirror_duplicate_order: size=2, all=2, codecReadRefuses=true")
    val miskeyed = ClaimLedger.fromProduct((Map(b -> meta), Vector(b)))
    assert(miskeyed.lookup(b).contains(meta) && miskeyed.lookup(a).isEmpty)
    val rekeyed = JsonLines.readClaims(JsonLines.claims(miskeyed)).toOption.get
    assert(rekeyed.lookup(a).contains(meta) && rekeyed.lookup(b).isEmpty)
    println("fromProduct_miskeyed: lookupKeyDiffersFromMetaId=true, codecRoundtripChangesKey=true")
    assert(ClaimMeta.of(a, EpistemicStatus.SurfaceExplicit, Credence.unmeasured,
      NonEmptyVector.one(ev), meta.provenance).isLeft)
    assert(Credence.of(Score.Raw(Double.NaN, ScorerId.unsafe("audit")), CredenceBasis.Uncalibrated).isLeft)
    assert(Credence.of(Score.Unmeasured, CredenceBasis.Calibrated(Probability.unsafe(0.5), CalibrationModelId.unsafe("audit"))).isLeft)
    println("claimmeta_span_law_and_credence_finite_score_basis_controls=true")
