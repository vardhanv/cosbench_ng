// see http://www.scala-sbt.org/1.0/docs/Multi-Project.html


// setting up the common settings
lazy val commonSettings = Seq(
  organization := "Vishnu",
  version := "0.6",
  scalaVersion := "2.11.8",

  // Dependencies
  libraryDependencies ++= Seq (
      "com.typesafe.akka" %% "akka-stream" % "2.4.16",
      "com.typesafe.akka" %% "akka-remote" % "2.4.16",
      "com.amazonaws" % "aws-java-sdk"  % "1.11.86"
      ),

  // Scala compiler options
  scalacOptions ++= Seq(
      "-deprecation",
      "-unchecked",
      "-feature",
      "-optimise",
      "-explaintypes",
      "-encoding", "UTF-8",
      "-Xlint"
      )
  )


assemblyMergeStrategy in assembly := {
  case "application.conf"  => MergeStrategy.discard
  case "common.conf"       => MergeStrategy.discard
}

// the two sub-projects within this project

lazy val agent = project.in(file("agent")).
   settings(commonSettings: _*).dependsOn("server")
lazy val server = project.in(file("server")).
   settings(commonSettings: _*)
