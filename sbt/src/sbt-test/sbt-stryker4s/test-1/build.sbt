scalaVersion := "2.12.15"

libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.9" % Test // scala-steward:off

// Reproduce https://github.com/stryker-mutator/stryker4s/issues/726
(Compile / scalaSource) := baseDirectory.value / "src" / "main" / "scala"
(Compile / javaSource) := baseDirectory.value / "src" / "main" / "scala"
