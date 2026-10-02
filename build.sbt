ThisBuild / scalaVersion := "3.3.6"
ThisBuild / organization := "io.github.xgao0068-hamster"

lazy val root = (project in file("."))
  .settings(
    name := "abstract-algebra-scala3",
    scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked"),
    libraryDependencies += "org.scalameta" %% "munit" % "1.1.1" % Test
  )
