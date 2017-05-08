// see http://www.scala-sbt.org/1.0/docs/Multi-Project.html


// setting up the common settings
lazy val commonSettings = Seq(
  organization := "Vishnu",
  version := "0.6",
  scalaVersion := "2.11.8",

  // Dependencies
  libraryDependencies ++= Seq (
      "com.typesafe.akka" %% "akka-stream" % "2.4.17",
      "com.typesafe.akka" %% "akka-remote" % "2.4.17",
      "com.typesafe.akka" %% "akka-cluster"% "2.4.17",
      "com.typesafe.akka" %% "akka-cluster-tools" % "2.4.17",
      "com.typesafe.akka" % "akka-slf4j_2.11" % "2.4.17",
      "ch.qos.logback" % "logback-classic" % "1.2.1",
      "com.amazonaws" % "aws-java-sdk"  % "1.11.86",
      "org.fusesource" % "sigar" % "1.6.4",
      "com.github.scopt" %% "scopt" % "3.5.0",
      "org.apache.commons" % "commons-lang3" % "3.1"
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
