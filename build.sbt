val scala3Version = "3.3.6"

val catsEffectVersion = "3.6.3"
val fs2Version = "3.12.0"
val munitCatsEffectVersion = "2.1.0"
val munitScalaCheckVersion = "1.1.0"

lazy val root = project
  .in(file("."))
  .settings(
    name := "noisy-neighbor",
    version := "0.1.0-SNAPSHOT",
    scalaVersion := scala3Version,
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % catsEffectVersion,
      "co.fs2" %% "fs2-core" % fs2Version,
      "org.typelevel" %% "cats-effect-testkit" % catsEffectVersion % Test,
      "org.typelevel" %% "munit-cats-effect" % munitCatsEffectVersion % Test,
      "org.scalameta" %% "munit-scalacheck" % munitScalaCheckVersion % Test
    )
  )
