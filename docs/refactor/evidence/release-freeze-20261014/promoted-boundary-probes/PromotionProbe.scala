package externalpromotionprobe
import storymodel4s.core.*
import storymodel4s.align.*
import storymodel4s.embed.*
import storymodel4s.media.*
import scala.deriving.Mirror

object PromotionProbe:
  def main(args: Array[String]): Unit =
    val hash = Checksum.ofText("synthetic-probe")
    val lawfulPin = ModelPin.of("synthetic", "rev", Map("config.json" -> hash, "weights.safetensors" -> hash)).toOption.get
    assert(lawfulPin.configSha256 == hash)
    assert(ModelPin.of("synthetic", "rev", Map.empty).isLeft)
    val brokenPin = lawfulPin.copy(files = Map.empty)
    val pinRead = scala.util.Try(brokenPin.configSha256)
    assert(pinRead.failed.toOption.exists(_.isInstanceOf[NoSuchElementException]))
    println("MODEL_PIN: checked factory rejects missing config; public copy admits it; configSha256 throws " + pinRead.failed.get.getClass.getName)

    val model = TransitionModel(Map(TransitionKind.ExternalIn -> Double.NaN))
    assert(HsmmConfig.of(transitions = model).isLeft)
    val lawfulUse = LayerUse.of(TransitionModel(Map.empty))
    assert(lawfulUse.externalIn == 0.5 && lawfulUse.render.nonEmpty)
    val brokenUse = LayerUse.of(model)
    assert(brokenUse.externalIn.isNaN)
    val rendered = scala.util.Try(brokenUse.render)
    assert(rendered.isFailure)
    println("LAYER_USE: config refuses NaN; public LayerUse.of admits NaN externalIn; render throws " + rendered.failed.get.getClass.getName)

    val keyId = KeyId.unsafe("synthetic-key")
    val sensitive = SensitiveDigest.compute(keyId, Array[Byte](1,2,3), "synthetic-material").toOption.get
    val plain = summon[Mirror.ProductOf[ReceiptDigest.Plain]].fromProduct(Tuple1(hash))
    val keyed = summon[Mirror.ProductOf[ReceiptDigest.Keyed]].fromProduct(Tuple1(sensitive))
    val withheld = summon[Mirror.ProductOf[ReceiptDigest.Withheld]].fromProduct((keyId, hash))
    assert(plain.kind == DigestKind.Plain && keyed.kind == DigestKind.Keyed && withheld.kind == DigestKind.Withheld)
    assert(withheld.checksum == hash)
    println("RECEIPT_DIGEST: all three external Mirror constructors compile and run; Withheld minted without AttemptReceipt")

    val id = RequestId.unsafe("synthetic-request")
    val publicKeyed = ItemDigest.of(id, Sensitivity.Public, keyed).toOption.get
    val base = ProviderCall("probe", "synthetic", "v1", None, hash, hash, Map.empty, None, false)
    val receipt = EmbeddingReceipt.of(base, Vector(publicKeyed), keyed).toOption.get
    assert(!receipt.isKeyConsistent)
    val publicPlain = ItemDigest.of(id, Sensitivity.Public, plain).toOption.get
    assert(EmbeddingReceipt.of(base, Vector(publicPlain), plain).toOption.get.isKeyConsistent)
    assert(AttemptReceipt.of(Vector(receipt.call), Vector(receipt), Vector.empty, Vector.empty, Vector.empty, Vector(id -> Sensitivity.Public), SensitiveKeyProvider.none).isLeft)
    println("EMBEDDING_RECEIPT: checked factories admit Public+Keyed item and return isKeyConsistent=false; enclosing AttemptReceipt refuses")
    println("PROBES_PASSED=4 POSITIVE_CONTROLS_PASSED=4")
