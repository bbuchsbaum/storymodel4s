package outsideaudit
import storymodel4s.embed.*
object TruncationProbe:
 def main(args: Array[String]): Unit =
  val provider = ProviderFingerprint.of("model", "tokenizer", "implementation", "runtime")
  for policy <- Vector(TruncationPolicy.KeepHead(-1), TruncationPolicy.KeepTail(-1), TruncationPolicy.KeepHead(0), TruncationPolicy.KeepTail(0), TruncationPolicy.KeepHead(1)) do
   val result = EmbeddingSpace.of(provider, Role.Document, SemanticView.Surface, None, Dimension.unsafe(4), Normalization.L2, policy)
   println(s"${policy.render}: admitted=${result.isRight}; retained=${result.toOption.map(_.truncation.render)}")
