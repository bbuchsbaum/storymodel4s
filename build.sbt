import org.typelevel.sbt.gha.JavaSpec

val Scala3           = "3.7.4"
val catsV            = "2.13.0"
val catsCollectionsV = "0.9.10"
val catsParseV       = "1.1.0"
val circeV           = "0.14.10"
val munitV           = "1.3.4"
val munitCheckV      = "1.3.0"
val disciplineMunitV = "2.0.0"
val scalaCheckV      = "1.19.0"

ThisBuild / tlBaseVersion    := "0.1"
ThisBuild / organization     := "io.github.canardlapin"
ThisBuild / organizationName := "Bradley Buchsbaum"
ThisBuild / startYear        := Some(2026)
ThisBuild / licenses         := Seq(License.Apache2)
ThisBuild / developers       := List(
  tlGitHubDev("canardlapin", "Bradley Buchsbaum")
)

ThisBuild / scalaVersion       := Scala3
ThisBuild / crossScalaVersions := Seq(Scala3)
ThisBuild / tlJdkRelease       := Some(11)
ThisBuild / githubWorkflowJavaVersions := Seq(
  JavaSpec.temurin("17"),
  JavaSpec.temurin("21")
)

lazy val commonSettings = Seq(
  scalacOptions ++= Seq(
    "-Wunused:all",
    "-Wvalue-discard",
    "-Wconf:msg=package scala contains object and package with same name.*caps:silent"
  ),
  libraryDependencies ++= Seq(
    "org.scalameta" %%% "munit"            % munitV      % Test,
    "org.scalameta" %%% "munit-scalacheck" % munitCheckV % Test
  ),
  Test / parallelExecution := false
)

def module(dir: String) =
  crossProject(JVMPlatform, JSPlatform, NativePlatform)
    .crossType(CrossType.Pure)
    .in(file(dir))
    .settings(commonSettings)
    .settings(name := s"storymodel4s-$dir")

lazy val root = tlCrossRootProject
  .aggregate(core, laws, amr, document, story, recall, align, interview, codec, fixtures)

/** Identity, spans, evidence, claims, credence, provenance, hashing. No I/O. */
lazy val core = module("core")
  .settings(
    libraryDependencies ++= Seq(
      "org.typelevel" %%% "cats-core"             % catsV,
      "org.typelevel" %%% "cats-collections-core" % catsCollectionsV
    )
  )

/** PENMAN syntax, checked AMR graphs, role canonicalization, isomorphism, alignment sidecar. */
lazy val amr = module("amr")
  .dependsOn(core)
  .settings(libraryDependencies += "org.typelevel" %%% "cats-parse" % catsParseV)

/** Narrative ontology: entities, situations, contexts, typed relation layers, hierarchy, trajectory. */
lazy val story = module("story")
  .dependsOn(core)

/** Disjoint-union mention graph, exact-coreference quotient, projection into narrative nodes. */
lazy val document = module("document")
  .dependsOn(core, amr, story)

/** Recall-side representation: idea units, discourse function, recall relations. */
lazy val recall = module("recall")
  .dependsOn(core, story)

/** Recall-to-source alignment: costs, unbalanced transport, graph-HSMM trajectories, signatures. */
lazy val align = module("align")
  .dependsOn(core, story, recall)

/** Autobiographical Interview: transcript atlas, detail atoms, memory addresses, derived scores. */
lazy val interview = module("interview")
  .dependsOn(core, story, recall, align)

/** Canonical JSON codecs for all artifacts (circe). */
lazy val codec = module("codec")
  .dependsOn(core, amr, story, recall, align, interview)
  .settings(
    libraryDependencies ++= Seq(
      "io.circe" %%% "circe-core"   % circeV,
      "io.circe" %%% "circe-parser" % circeV
    )
  )

/** Hand-authored reference fixtures: The War of the Ghosts, worked recall examples, interview example. */
lazy val fixtures = module("fixtures")
  .dependsOn(core, amr, document, story, recall, align, interview)

/** Published law suites and generators (Discipline). */
lazy val laws = module("laws")
  .dependsOn(core, amr, story, recall, align, interview)
  .settings(
    libraryDependencies ++= Seq(
      "org.scalameta"  %%% "munit"            % munitV,
      "org.typelevel"  %%% "discipline-munit" % disciplineMunitV,
      "org.typelevel"  %%% "cats-laws"        % catsV,
      "org.scalacheck" %%% "scalacheck"       % scalaCheckV
    )
  )

val allModules = List("core", "amr", "story", "document", "recall", "align", "interview", "codec", "fixtures", "laws")
val allPlatforms = List("JVM", "JS", "Native")

addCommandAlias(
  "compileAll",
  allModules.flatMap(m => allPlatforms.map(p => s"$m$p/compile")).mkString(";", ";", "")
)
addCommandAlias(
  "testAll",
  allModules.flatMap(m => allPlatforms.map(p => s"$m$p/test")).mkString(";", ";", "")
)
addCommandAlias("testJVM", allModules.map(m => s"${m}JVM/test").mkString(";", ";", ""))
addCommandAlias("checkAll", ";scalafmtCheckAll;scalafmtSbtCheck;compileAll;testAll")
