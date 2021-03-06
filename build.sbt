import scala.collection.Seq

ThisBuild / scalaVersion := "2.12.10"

ThisBuild / githubRepository := "quasar-destination-avalanche"

homepage in ThisBuild := Some(url("https://github.com/precog/quasar-destination-avalanche"))

scmInfo in ThisBuild := Some(ScmInfo(
  url("https://github.com/precog/quasar-destination-avalanche"),
  "scm:git@github.com:precog/quasar-destination-avalanche.git"))

val DoobieVersion = "0.8.8"

// Include to also publish a project's tests
lazy val publishTestsSettings = Seq(
  Test / packageBin / publishArtifact := true)

lazy val root = project
  .in(file("."))
  .settings(noPublishSettings)
  .aggregate(core)
  .enablePlugins(AutomateHeaderPlugin)

lazy val core = project
  .in(file("core"))
  .settings(name := "quasar-destination-avalanche")
  .settings(
    performMavenCentralSync := false,
    publishAsOSSProject := true,
    assemblyExcludedJars in assembly := {
      val cp = (fullClasspath in assembly).value

      cp.filter(_.data.getName != "iijdbc.jar") // exclude everything but iijdbc.jar
    },
    quasarPluginName := "avalanche",
    quasarPluginQuasarVersion := managedVersions.value("precog-quasar"),
    quasarPluginDestinationFqcn := Some("quasar.destination.avalanche.AvalancheDestinationModule$"),
    quasarPluginDependencies ++= Seq(
      "org.slf4s" %% "slf4s-api" % "1.7.25",
      "org.tpolecat" %% "doobie-core" % DoobieVersion,
      "org.tpolecat" %% "doobie-hikari" % DoobieVersion,
      "com.precog" %% "async-blobstore-azure" % managedVersions.value("precog-async-blobstore"),
      "com.precog" %% "async-blobstore-core" % managedVersions.value("precog-async-blobstore")),
    excludeDependencies += "org.typelevel" % "scala-library",
    libraryDependencies ++= Seq(
      "org.specs2" %% "specs2-core" % "4.8.3" % Test),
    packageBin in Compile := (assembly in Compile).value)
  .enablePlugins(QuasarPlugin)
