import cats.kernel.Hash
import grakern.core.*
import grakern.engine.WLCompiler
import grakern.graph4s.GraphSample
import grakern.standard.wl.*
import graph4s.{Graph, Link}

/** Exercises the staged graph adapter, WL engine and Gale-backed numerical result together. */
object UpstreamBinaryConsumer:
  given Hash[Int] = Hash.fromUniversalHashCode
  given Hash[String] = Hash.fromUniversalHashCode

  def main(args: Array[String]): Unit =
    val topology = Graph.fromEdges(Link(1, 2)).toOption.get
    def sample(key: String, label: String) =
      GraphSample.build(SampleKey(key), topology)(_ => label, _ => 1)
    val training = Vector(sample("carbon", "C"), sample("oxygen", "O"))
    val refinement = WLRefinement
      .create(1, Projection.of[String, String](identity), Projection.of[Int, Int](identity),
        WLCodecs[String, Int](None, None))
      .toOption.get
    val kernel = (refinement.subtree + refinement.optimalAssignment).normalized
    val compiled = WLCompiler.plan(training, kernel).run().toOption.get
    assert(math.abs(compiled.kernel(0, 0).get - 1.0) < 1e-12)
    assert(math.abs(compiled.kernel(1, 1).get - 1.0) < 1e-12)
    assert(compiled.kernel(0, 1).get == 0.0)
    assert(compiled.kernel(1, 0).get == 0.0)
    val cross = compiled.cross(Vector(sample("query-carbon", "C"))).toOption.get
    assert(math.abs(cross.cross.values(0, 0) - 1.0) < 1e-12)
    assert(cross.cross.values(0, 1) == 0.0)
    println("PASS: six binary-consumer assertions across graph4s, grakern and Gale")
