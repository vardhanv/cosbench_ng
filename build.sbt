// see http://www.scala-sbt.org/1.0/docs/Multi-Project.html


// setting up the common settings
lazy val commonSettings = Seq(
  organization := "Vishnu",
  version := "0.9",
  scalaVersion := "2.12.4",


  // Dependencies
  libraryDependencies ++= Seq (
      "com.typesafe.akka" %% "akka-stream" % "2.5.8",
      "com.typesafe.akka" %% "akka-remote" % "2.5.8",
      "com.typesafe.akka" %% "akka-cluster"% "2.5.8",
      "com.typesafe.akka" %% "akka-cluster-tools" % "2.5.8",
      "com.typesafe.akka" %% "akka-slf4j" % "2.5.8",
      "ch.qos.logback"    % "logback-classic" % "1.2.3",
      "com.amazonaws"     % "aws-java-sdk-s3"  % "1.11.255",
      "org.fusesource"    % "sigar" % "1.6.4",
      "com.github.scopt"  %% "scopt" % "3.5.0",
      "org.apache.commons" % "commons-lang3" % "3.1"
      ),

  // Scala compiler options
  scalacOptions ++= Seq(
      "-deprecation",
      "-unchecked",
      "-feature",
      "-explaintypes",
      "-encoding", "UTF-8",
      "-Xlint",
      "-target:jvm-1.8",
      "-Ywarn-dead-code",
      "-Ywarn-numeric-widen",
      "-Ywarn-value-discard",
      "-Ywarn-unused"
      ),

   PB.targets in Compile := Seq(
      scalapb.gen() -> (sourceManaged in Compile).value
      )
 )



// the three sub-projects within this project

lazy val common = project.in(file("common"))
                         .settings(commonSettings: _*)

lazy val agent = project.in(file("agent"))
                        .settings(commonSettings: _*)
                        .dependsOn(common)

lazy val server = project.in(file("server"))
                         .settings(commonSettings: _*)
                         .dependsOn(common)

