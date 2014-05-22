import sbt._
import sbt.Keys._

import play.Project._
import sbtbuildinfo.Plugin._
import com.typesafe.sbt.SbtScalariform._

object Membership extends Build {
  val version = "1.0-SNAPSHOT"

  def buildInfoPlugin = buildInfoSettings ++ Seq(
    sourceGenerators in Compile <+= buildInfo,
    buildInfoKeys := Seq[BuildInfoKey](
      name,
      BuildInfoKey.constant("buildNumber", Option(System.getenv("BUILD_NUMBER")) getOrElse "DEV"),
      BuildInfoKey.constant("buildTime", System.currentTimeMillis)
    ),
    buildInfoPackage := "app"
  )

  def coveragePlugin = ScoverageSbtPlugin.instrumentSettings ++ Seq(
    ScoverageSbtPlugin.ScoverageKeys.excludedPackages in ScoverageSbtPlugin.scoverage := "<empty>;Reverse.*;Routes"
  )

  def scalaStylePlugin = {
    lazy val testScalaStyle = taskKey[Unit]("testScalaStyle")

    org.scalastyle.sbt.ScalastylePlugin.Settings ++ Seq(
      org.scalastyle.sbt.PluginKeys.failOnError := true,
      testScalaStyle := {
        org.scalastyle.sbt.PluginKeys.scalastyle.toTask("").value
      },
      (test in Test) <<= (test in Test) dependsOn testScalaStyle
    )
  }

  val webapp = play.Project("webapp", version, path = file("webapp"))
    .settings(organization := "com.gu")
    .settings(scalaVersion := "2.10.4")
    .settings(resolvers += "Guardian Github Releases" at "http://guardian.github.io/maven/repo-releases")
    .settings(libraryDependencies ++= Seq(
      cache,
      "com.github.nscala-time" %% "nscala-time" % "1.0.0",
      "com.gu.identity" %% "identity-cookie" % "3.40",
      "com.gu.identity" %% "identity-model" % "3.40"
    ))
    .settings(parallelExecution in Global := false)
    .settings(buildInfoPlugin: _*)
    .settings(PlayArtifact.magentaPackageName := "app")
    .settings(PlayArtifact.playArtifactDistSettings: _*)
    .settings(coveragePlugin: _*)
    .settings(scalaStylePlugin: _*)
    .settings(scalariformSettings: _*)
}

