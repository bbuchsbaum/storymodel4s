package outsideaudit
import storymodel4s.align.*
object CandidateProbe:
 def main(args: Array[String]): Unit =
  import AnnaFixture.{candidates,costModel,recall,view}
  val original = candidates.byUnit.values.find(_.nominations.nonEmpty).get
  val supplied = CandidateSet(original.nominations, abstained=true)
  val copied = original.copy(abstained=true)
  val mirrored = summon[scala.deriving.Mirror.ProductOf[CandidateSet]].fromProduct((original.nominations,true))
  assert(supplied == copied && supplied == mirrored)
  println(s"public apply/copy/Mirror accept nonempty nominations with abstained=${supplied.abstained}")
  val altered = Candidates(candidates.byUnit.view.mapValues(_.copy(abstained=true)).toMap)
  val result = LocalEvidence.compute(recall,view,altered,costModel,gate=true)
  println(s"historical compute accepted=${result.isRight} retains contradictory flag=${result.exists(_.candidates.exists(s => s.abstained && s.nominations.nonEmpty))} provenanceUnattested=${result.exists(_.provenance == CandidateProvenance.Unattested)}")
