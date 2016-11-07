import sbt._
import Process._
import Keys._

val kurentoclient = "org.kurento" % "kurento-client" % "6.6.1"
val kurentomodule = "org.kurento.module" % "crowddetector" % "6.6.0"
val kurentojava = "org.kurento" % "kurento-java" % "6.6.1"
val scalauri = "com.netaporter" %% "scala-uri" % "0.4.6"
val akkatest = "com.typesafe.akka" %% "akka-testkit" % "2.3.9"
val async = "org.scala-lang.modules" %% "scala-async" % "0.9.2"
// val scalamockspecs = "org.scalamock" %% "scalamock-specs2-support" % "3.2.1" % "test"
val scalatest = "org.scalatest" %% "scalatest" % "2.2.1" % "test"
// val scalamock =  "org.scalamock" %% "scalamock-scalatest-support" % "3.2.1" % "test"

lazy val commonSettings = Seq(
  version := "0.0.1",
  scalaVersion := "2.11.4"
)

lazy val root = (project in file(".")).
  settings(commonSettings: _*).
  settings(
    name := "kurento-websocket",
    // compileOrder := CompileOrder.JavaThenScala,
    //mainClass in (Compile, run) := Some(""),
    libraryDependencies ++= Seq(
      kurentoclient,
      kurentomodule,
      kurentojava,
      scalauri,
      akkatest,
      scalatest,
      async
      // scalamockspecs,
      // scalamock,
    )  ,scalacOptions ++= Seq("-feature")  // Know features
  ).enablePlugins(PlayScala)

//play.Project.playScalaSettings
