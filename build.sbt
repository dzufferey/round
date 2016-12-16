name := "psync"

version := "0.2-SNAPSHOT"

scalaVersion := "2.11.8"

scalacOptions in Compile ++= Seq(
    "-unchecked",
    "-deprecation",
    "-feature",
    "-language:implicitConversions",
    "-Xmax-classfile-name", "110"//,
//    "-Ymacro-debug-lite"
//    "-Xlog-implicits"
//    "-Xlog-implicit-conversions"
)

logBuffered in Test := false

parallelExecution in Test := false

libraryDependencies ++=  Seq(
    "org.scala-lang" % "scala-reflect" % scalaVersion.value,
    "org.scala-lang" % "scala-compiler" % scalaVersion.value,
    "org.scala-lang.modules" %% "scala-xml" % "1.0.6",
    "org.scala-lang.modules" %% "scala-parser-combinators" % "1.0.4",
    "org.scalatest" %% "scalatest" % "3.0.1" % "test",
    "org.scalacheck" %% "scalacheck" % "1.13.4" % "test",
    "org.scala-lang.modules" %% "scala-pickling" % "0.10.1",
    "io.netty" % "netty-all" % "4.1.6.Final",
    "info.hupel" %% "libisabelle" % "0.6.4",
    "info.hupel" %% "libisabelle-setup" % "0.6.4",
    "ch.qos.logback" % "logback-classic" % "1.1.7",
    "io.github.dzufferey" %% "scala-arg" % "0.1-SNAPSHOT",
    "io.github.dzufferey" %% "report" % "0.1-SNAPSHOT",
    "io.github.dzufferey" %% "misc-scala-utils" % "0.1-SNAPSHOT",
    "io.github.dzufferey" %% "scala-smtlib-interface" % "0.1-SNAPSHOT"
)

addCompilerPlugin("org.psywerx.hairyfotr" %% "linter" % "0.1.17")

resolvers += Resolver.sonatypeRepo("snapshots")

resolvers +=  "dzufferey maven repo" at "https://github.com/dzufferey/my_mvn_repo/raw/master/repository"

