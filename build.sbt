import scala.scalanative.build.{GC, Mode}

ThisBuild / scalaVersion := "3.10.0"
ThisBuild / organization := "br.com.mobilemind"
ThisBuild / version := "0.1.0-SNAPSHOT"

lazy val library = project
  .in(file("library"))
  .enablePlugins(ScalaNativePlugin)
  .settings(
    name := "safezone-library",
    libraryDependencies += "org.scalameta" %%% "munit" % "1.3.4" % Test,
    testFrameworks += new TestFramework("munit.Framework"),
    nativeConfig ~= (_.withGC(GC.immix).withMode(Mode.debug))
  )

lazy val tests = project
  .in(file("tests"))
  .settings(
    name := "safezone-tests",
    publish / skip := true,
    libraryDependencies ++= Seq(
      "org.scala-lang" %% "scala3-compiler" % scalaVersion.value % Test,
      "org.scalameta"  %% "munit"           % "1.3.4"             % Test
    ),
    testFrameworks += new TestFramework("munit.Framework"),
    Test / fork := true,
    // The suite compiles snippets in-process against the Native build of
    // `library`. It's passed as a task input (not via dependsOn) because this
    // is a JVM project and must not put scala-native classes on its own
    // compile classpath.
    Test / javaOptions += {
      val cp = ((library / Compile / dependencyClasspath).value.files ++
        (library / Compile / exportedProducts).value.files)
        .map(_.getAbsolutePath)
        .mkString(java.io.File.pathSeparator)
      s"-Dsafezone.test.classpath=$cp"
    }
  )

lazy val example = project
  .in(file("example"))
  .enablePlugins(ScalaNativePlugin)
  .dependsOn(library)
  .settings(
    name := "safezone-example",
    publish / skip := true,
    nativeConfig ~= (_.withGC(GC.immix).withMode(Mode.debug))
  )

lazy val root = project
  .in(file("."))
  .aggregate(library, tests, example)
  .settings(
    name := "safezone",
    publish / skip := true
  )
