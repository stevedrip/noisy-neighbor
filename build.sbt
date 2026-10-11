val scala3Version = "3.3.6"

val algebraVersion = "2.13.0"
val catsVersion = "2.13.0"
val disciplineMunitVersion = "2.0.0"
val scodecBitsVersion = "1.1.38"
val catsEffectVersion = "3.6.3"
val fs2Version = "3.12.0"
val munitCatsEffectVersion = "2.1.0"
val munitScalaCheckVersion = "1.1.0"

ThisBuild / scalaVersion := scala3Version

lazy val root: Project = project
  .in(file("."))
  .aggregate(LocalProject("example"))
  .settings(
    name := "noisy-neighbor",
    version := "0.1.0-SNAPSHOT",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "algebra" % algebraVersion,
      "org.typelevel" %% "cats-effect" % catsEffectVersion,
      "co.fs2" %% "fs2-core" % fs2Version,
      "org.scodec" %% "scodec-bits" % scodecBitsVersion,
      "org.typelevel" %% "algebra-laws" % algebraVersion % Test,
      "org.typelevel" %% "cats-laws" % catsVersion % Test,
      "org.typelevel" %% "cats-effect-testkit" % catsEffectVersion % Test,
      "org.typelevel" %% "discipline-munit" % disciplineMunitVersion % Test,
      "org.typelevel" %% "munit-cats-effect" % munitCatsEffectVersion % Test,
      "org.scalameta" %% "munit-scalacheck" % munitScalaCheckVersion % Test
    )
  )

/** Runnable examples, in example/ with their own sources and tests. They use
  * the library, and it does not see them: `sbt example/run`. They run in their
  * own JVM with stdin connected, so the interactive ones can read it. They are
  * not published.
  */
lazy val example: Project = project
  .in(file("example"))
  .dependsOn(root)
  .settings(
    name := "noisy-neighbor-example",
    publish / skip := true,
    run / fork := true,
    run / baseDirectory := (ThisBuild / baseDirectory).value,
    run / connectInput := true,
    run / outputStrategy := Some(StdoutOutput),
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect-testkit" % catsEffectVersion % Test,
      "org.typelevel" %% "munit-cats-effect" % munitCatsEffectVersion % Test
    )
  )
