import org.typelevel.sbt.gha.JavaSpec

val Scala3 = "3.7.4"
val catsV = "2.13.0"
val catsCollectionsV = "0.9.10"
val catsParseV = "1.1.0"
val circeV = "0.14.10"
val munitV = "1.3.4"
val munitCheckV = "1.3.0"
val disciplineMunitV = "2.0.0"
val scalaCheckV = "1.19.0"
val onnxRuntimeV = "1.29.0"
val djlV = "0.36.0"

ThisBuild / tlBaseVersion := "0.1"
ThisBuild / organization := "io.github.canardlapin"
ThisBuild / organizationName := "Bradley Buchsbaum"
ThisBuild / startYear := Some(2026)
ThisBuild / licenses := Seq(License.Apache2)
ThisBuild / developers := List(
  tlGitHubDev("canardlapin", "Bradley Buchsbaum")
)

ThisBuild / scalaVersion := Scala3
ThisBuild / crossScalaVersions := Seq(Scala3)
ThisBuild / tlJdkRelease := Some(11)
ThisBuild / githubWorkflowJavaVersions := Seq(
  JavaSpec.temurin("17"),
  JavaSpec.temurin("21")
)

lazy val commonSettings = Seq(
  scalacOptions ++= Seq(
    "-Wunused:all",
    "-Wconf:msg=package scala contains object and package with same name.*caps:silent"
  ),
  libraryDependencies ++= Seq(
    "org.scalameta" %%% "munit" % munitV % Test,
    "org.scalameta" %%% "munit-scalacheck" % munitCheckV % Test
  ),
  Test / parallelExecution := false
)

def moduleSettings(dir: String) = commonSettings ++ Seq(name := s"storymodel4s-$dir")

// Dependency structure (design record §99):
//
//   core
//    ↑
//   proposition ─────────────── canonical local semantic contract
//    ↑          ↑
//   amr-interop  acquire ────── AMR adapter; proposal/critic/resolution protocol
//         ↖      ↑
//          document ──────────── mention graph, coreference quotient, projection
//              ↑
//   story / recall / interview
//              ↑
//            align
//
// JVM-only provider adapters (parser services, LLM agents, embeddings) and the CLI are
// separate sbt projects added in M1; nothing portable may depend on them.

lazy val root = tlCrossRootProject
  .aggregate(
    core,
    proposition,
    amrInterop,
    features,
    acquire,
    story,
    document,
    recall,
    align,
    interview,
    embedCore,
    view,
    codec,
    fixtures,
    laws,
    providerParser,
    embedGrakern,
    embedOnnx,
    embedBench,
    media
  )

/** Identity, spans, evidence, claims, credence, provenance, hashing. No I/O. */
lazy val core = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("core"))
  .settings(moduleSettings("core"))
  .settings(
    libraryDependencies ++= Seq(
      "org.typelevel" %%% "cats-core" % catsV,
      "org.typelevel" %%% "cats-collections-core" % catsCollectionsV
    )
  )

/** Canonical local semantic contract: partial, evidence-backed propositional charts. */
lazy val proposition = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("proposition"))
  .settings(moduleSettings("proposition"))
  .dependsOn(core)

/** Standards-compatible AMR adapter: PENMAN syntax, checked AMR graphs, chart conversion. */
lazy val amrInterop = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("amr-interop"))
  .settings(moduleSettings("amr-interop"))
  .dependsOn(core, proposition)
  .settings(libraryDependencies += "org.typelevel" %%% "cats-parse" % catsParseV)

/** Aligned feature tracks: spaces, estimates, coverage, windows, reducers, derivation recipes. */
lazy val features = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("features"))
  .settings(moduleSettings("features"))
  .dependsOn(core)

/** Autonomous acquisition protocol: task packets, proposal-only agents, critics, resolution. */
lazy val acquire = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("acquire"))
  .settings(moduleSettings("acquire"))
  .dependsOn(core, proposition)

/** Narrative ontology: entities, situations, contexts, typed relation layers, hierarchy,
  * trajectory.
  */
lazy val story = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("story"))
  .settings(moduleSettings("story"))
  .dependsOn(core, proposition, features)

/** Mention graph (disjoint union of charts), exact-coreference quotient, projection into narrative
  * nodes.
  */
lazy val document = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("document"))
  .settings(moduleSettings("document"))
  .dependsOn(core, proposition, features, acquire, story)

/** Recall-side representation: idea units, discourse function, recall relations. */
lazy val recall = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("recall"))
  .settings(moduleSettings("recall"))
  .dependsOn(core, proposition, features, story)

/** Recall-to-source alignment: costs, unbalanced transport, graph-HSMM trajectories, signatures. */
lazy val align = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("align"))
  .settings(moduleSettings("align"))
  .dependsOn(core, proposition, features, story, recall)

/** Autobiographical Interview: transcript atlas, detail atoms, memory addresses, derived scores. */
lazy val interview = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("interview"))
  .settings(moduleSettings("interview"))
  .dependsOn(core, proposition, features, story, recall, align, embedCore)

/** Portable embedding contract (ADR 0001): identity, batch/result algebra, validated vectors, free
  * baselines, cache and privacy types. Providers are JVM-only adapters.
  */
lazy val embedCore = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("embed-core"))
  .settings(moduleSettings("embed-core"))
  .dependsOn(core, features, acquire)

/** JVM-only AMR parser-provider contract: atlas-bound inputs, receipted JSON transport,
  * deterministic admission, cache replay, and failure courts. Model runtimes remain external.
  */
lazy val providerParser = project
  .in(file("provider-parser"))
  .settings(commonSettings)
  .settings(
    name := "storymodel4s-provider-parser",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-core" % catsV,
      "io.circe" %% "circe-core" % circeV,
      "io.circe" %% "circe-parser" % circeV
    )
  )
  .dependsOn(core.jvm, proposition.jvm, amrInterop.jvm, acquire.jvm)

// grakern (in-house graph-kernel calculus) is consumed as an immutable source pin. grakern has no
// published artifacts and, at this revision, no git remote: until it is pushed, builds MUST supply
// the local override `-Dstorymodel4s.grakern.build=/path/to/grakern` (or the environment variable
// STORYMODEL4S_GRAKERN_BUILD). grakern's own build pins graph4s and gale by SHA from GitHub.
lazy val grakernRevision = "0329c43c88a0b71e9aa4456723bb16bac2fa3841"
lazy val grakernBuild =
  sys.props
    .get("storymodel4s.grakern.build")
    .orElse(sys.env.get("STORYMODEL4S_GRAKERN_BUILD"))
    .map(p => file(p).getCanonicalFile.toURI)
    .getOrElse(uri(s"https://github.com/canardlapin/grakern.git#$grakernRevision"))
lazy val grakernCoreJVM = ProjectRef(grakernBuild, "coreJVM")
lazy val grakernStandardJVM = ProjectRef(grakernBuild, "standardJVM")
lazy val grakernGraph4sAdapterJVM = ProjectRef(grakernBuild, "graph4sAdapterJVM")
lazy val grakernEngineJVM = ProjectRef(grakernBuild, "engineJVM")

/** JVM-only structural embedding channel (ADR 0001 §D4c): proposition charts reified into grakern
  * labelled neighbourhoods; WL subtree + optimal-assignment kernels supply `d_wl`. No grakern or
  * graph4s type crosses into portable modules.
  */
lazy val embedGrakern = project
  .in(file("embed-grakern"))
  .settings(commonSettings)
  .settings(
    name := "storymodel4s-embed-grakern",
    Compile / sourceGenerators += Def.task {
      val file =
        (Compile / sourceManaged).value / "storymodel4s" / "embed" / "grakern" / "GrakernPin.scala"
      IO.write(
        file,
        s"""package storymodel4s.embed.grakern
           |
           |/** The immutable grakern revision this build compiles against (generated from build.sbt). */
           |object GrakernPin:
           |  val revision: String = "$grakernRevision"
           |""".stripMargin
      )
      Seq(file)
    }.taskValue
  )
  .dependsOn(embedCore.jvm, proposition.jvm, align.jvm % "compile->compile;test->test")
  .dependsOn(grakernCoreJVM, grakernStandardJVM, grakernGraph4sAdapterJVM, grakernEngineJVM)

/** JVM-only, local neural sentence embedding channel. Pinned model and tokenizer checksums define
  * one reviewed geometry; no ONNX or DJL type crosses into portable modules.
  */
lazy val embedOnnx = project
  .in(file("embed-onnx"))
  .settings(commonSettings)
  .settings(
    name := "storymodel4s-embed-onnx",
    libraryDependencies ++= Seq(
      "com.microsoft.onnxruntime" % "onnxruntime" % onnxRuntimeV,
      "ai.djl.huggingface" % "tokenizers" % djlV
    ),
    Test / fork := true,
    Test / envVars += "ORT_DISABLE_TELEMETRY" -> "1"
  )
  .dependsOn(embedCore.jvm)

/** JVM-only evaluation harness (M1 W4(10), ADR 0001 §D7): scores gated alignments against
  * adjudicated gold from frozen sets verified by manifest checksum, or against diagnostic material
  * that can never be labelled calibrated. No portable module depends on it.
  */
lazy val embedBench = project
  .in(file("embed-bench"))
  .settings(commonSettings)
  .settings(
    name := "storymodel4s-embed-bench",
    Test / fork := true,
    Test / envVars += "ORT_DISABLE_TELEMETRY" -> "1"
  )
  .dependsOn(embedCore.jvm, embedOnnx, embedGrakern, align.jvm, fixtures.jvm, laws.jvm % Test)

/** JVM-only media acquisition adapter (ADR 0007 §4, amendment 2026-09-01): exact-byte fixture
  * manifests, an ffprobe packet-index ingest that yields `Draft`-authority records only, and the
  * first acquisition court over project-authored F0 media. It is the only module allowed to
  * spawn a process. No portable module depends on it.
  */
lazy val media = project
  .in(file("media"))
  .settings(commonSettings)
  .settings(
    name := "storymodel4s-media",
    libraryDependencies ++= Seq(
      "io.circe" %% "circe-core" % circeV,
      "io.circe" %% "circe-parser" % circeV
    ),
    Test / fork := true
  )
  .dependsOn(core.jvm)

/** Portable semantic view artifacts shared by the Narrative Codex and Narrative Atlas. */
lazy val view = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("view"))
  .settings(moduleSettings("view"))
  .dependsOn(core, proposition, features, acquire, story, document, recall, align)

/** Canonical JSON codecs for all artifacts (circe). */
lazy val codec = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("codec"))
  .settings(moduleSettings("codec"))
  .dependsOn(
    core,
    proposition,
    amrInterop,
    features,
    acquire,
    story,
    recall,
    align,
    interview,
    view
  )
  .dependsOn(laws % "test->compile")
  .settings(
    libraryDependencies ++= Seq(
      "io.circe" %%% "circe-core" % circeV,
      "io.circe" %%% "circe-parser" % circeV
    )
  )

/** Reference fixtures: The War of the Ghosts narrative acceptance fixture, worked recall examples,
  * interview example.
  */
lazy val fixtures = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("fixtures"))
  .settings(moduleSettings("fixtures"))
  .dependsOn(
    core,
    proposition,
    amrInterop,
    features,
    acquire,
    document,
    story,
    recall,
    align,
    interview,
    codec,
    view
  )

/** Published law suites and generators (Discipline). */
lazy val laws = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("laws"))
  .settings(moduleSettings("laws"))
  .dependsOn(core, proposition, amrInterop, features, acquire, story, recall, align, interview)
  .settings(
    libraryDependencies ++= Seq(
      "org.scalameta" %%% "munit" % munitV,
      "org.typelevel" %%% "discipline-munit" % disciplineMunitV,
      "org.typelevel" %%% "cats-laws" % catsV,
      "org.scalacheck" %%% "scalacheck" % scalaCheckV
    )
  )

val allModules = List(
  "core",
  "proposition",
  "amrInterop",
  "features",
  "acquire",
  "story",
  "document",
  "recall",
  "align",
  "interview",
  "embedCore",
  "view",
  "codec",
  "fixtures",
  "laws"
)
val allPlatforms = List("JVM", "JS", "Native")

val jvmOnlyModules = List("providerParser", "embedGrakern", "embedOnnx", "embedBench", "media")

addCommandAlias(
  "compileAll",
  (allModules.flatMap(m => allPlatforms.map(p => s"$m$p/compile")) ++
    jvmOnlyModules.map(m => s"$m/compile")).mkString(";", ";", "")
)
addCommandAlias(
  "testAll",
  (allModules.flatMap(m => allPlatforms.map(p => s"$m$p/test")) ++
    jvmOnlyModules.map(m => s"$m/test")).mkString(";", ";", "")
)
addCommandAlias(
  "testJVM",
  (allModules.map(m => s"${m}JVM/test") ++ jvmOnlyModules.map(m => s"$m/test"))
    .mkString(";", ";", "")
)
addCommandAlias("checkAll", ";scalafmtCheckAll;scalafmtSbtCheck;compileAll;testAll")
