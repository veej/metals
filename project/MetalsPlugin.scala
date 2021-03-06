import sbt._
import sbt.Keys._
import java.io._
import sbt.Def

object MetalsPlugin extends AutoPlugin {
  override def trigger = allRequirements
  override def requires = sbt.plugins.JvmPlugin
  object autoImport {

    val metalsCompilerConfig =
      taskKey[String](
        "String containing build metadata in properties file format."
      )
    val metalsWriteCompilerConfig =
      taskKey[Unit](
        "Generate build metadata for completions and indexing dependency sources"
      )

    lazy val semanticdbSettings = List(
      addCompilerPlugin(
        "org.scalameta" % "semanticdb-scalac" % SemanticdbEnable.semanticdbVersion cross CrossVersion.full
      ),
      scalacOptions += "-Yrangepos"
    )

    def metalsConfig(c: Configuration) = Seq(
      metalsCompilerConfig := {
        val props = new java.util.Properties()
        props.setProperty(
          "sources",
          sources.value.distinct.mkString(File.pathSeparator)
        )
        props.setProperty(
          "unmanagedSourceDirectories",
          unmanagedSourceDirectories.value.distinct
            .mkString(File.pathSeparator)
        )
        props.setProperty(
          "managedSourceDirectories",
          managedSourceDirectories.value.distinct
            .mkString(File.pathSeparator)
        )
        props.setProperty(
          "scalacOptions",
          scalacOptions.value.mkString(" ")
        )
        props.setProperty(
          "classDirectory",
          classDirectory.value.getAbsolutePath
        )
        props.setProperty(
          "dependencyClasspath",
          dependencyClasspath.value
            .map(_.data.toString)
            .mkString(File.pathSeparator)
        )
        props.setProperty(
          "scalaVersion",
          scalaVersion.value
        )
        val sourceJars = for {
          configurationReport <- updateClassifiers.value.configurations
          moduleReport <- configurationReport.modules
          (artifact, file) <- moduleReport.artifacts
          if artifact.classifier.exists(_ == "sources")
        } yield file
        props.setProperty(
          "sourceJars",
          sourceJars.mkString(File.pathSeparator)
        )
        val out = new ByteArrayOutputStream()
        props.store(out, null)
        out.toString()
      },
      metalsWriteCompilerConfig := {
        val filename = s"${c.name}.properties"
        val basedir = baseDirectory.in(ThisBuild).value /
          ".metals" / "buildinfo" / thisProject.value.id
        basedir.mkdirs()
        val outFile = basedir / filename
        IO.write(outFile, metalsCompilerConfig.value)
        streams.value.log.info("Created: " + outFile.getAbsolutePath)
      }
    )
  }
  import autoImport._
  override lazy val globalSettings = List(
    commands += SemanticdbEnable.command,
    commands += Command.command(
      "metalsSetup",
      briefHelp =
        "Generates .metals/buildinfo/**.properties files containing build metadata " +
          "such as classpath and source directories.",
      detail = ""
    ) { s =>
      val configDir = s.baseDir / ".metals" / "buildinfo"
      IO.delete(configDir)
      configDir.mkdirs()
      "semanticdbEnable" ::
        "*:metalsWriteCompilerConfig" ::
        s
    },
    metalsWriteCompilerConfig := Def.taskDyn {
      val filter = ScopeFilter(inAnyProject, inConfigurations(Compile, Test))
      metalsWriteCompilerConfig.all(filter)
    }.value
  )
  override lazy val projectSettings: List[Def.Setting[_]] =
    List(Compile, Test).flatMap(c => inConfig(c)(metalsConfig(c)))
}

/** Command to automatically enable semanticdb-scalac for shell session */
object SemanticdbEnable {

  /** sbt 1.0 and 0.13 compatible implementation of partialVersion */
  private def partialVersion(version: String): Option[(Long, Long)] =
    CrossVersion.partialVersion(version).map {
      case (a, b) => (a.toLong, b.toLong)
    }

  val scala211 = "2.11.12"
  val scala212 = "2.12.4"
  val supportedScalaVersions = List(scala212, scala211)
  val semanticdbVersion = "2.1.7"

  lazy val partialToFullScalaVersion: Map[(Long, Long), String] = (for {
    v <- supportedScalaVersions
    p <- partialVersion(v).toList
  } yield p -> v).toMap

  def projectsWithMatchingScalaVersion(
      state: State
  ): Seq[(ProjectRef, String)] = {
    val extracted = Project.extract(state)
    for {
      p <- extracted.structure.allProjectRefs
      version <- scalaVersion.in(p).get(extracted.structure.data).toList
      partialVersion <- partialVersion(version).toList
      fullVersion <- partialToFullScalaVersion.get(partialVersion).toList
    } yield p -> fullVersion
  }

  lazy val command = Command.command(
    "semanticdbEnable",
    briefHelp =
      "Configure libraryDependencies, scalaVersion and scalacOptions for scalafix.",
    detail = """1. enables the semanticdb-scalac compiler plugin
               |2. sets scalaVersion to latest Scala version supported by scalafix
               |3. add -Yrangepos to scalacOptions""".stripMargin
  ) { s =>
    val extracted = Project.extract(s)
    val settings: Seq[Setting[_]] = for {
      (p, fullVersion) <- projectsWithMatchingScalaVersion(s)
      isEnabled = libraryDependencies
        .in(p)
        .get(extracted.structure.data)
        .exists(_.exists(_.name == "semanticdb-scalac"))
      if !isEnabled
      setting <- List(
        scalaVersion.in(p) := fullVersion,
        scalacOptions.in(p) ++= List(
          "-Yrangepos",
          s"-Xplugin-require:semanticdb"
        ),
        libraryDependencies.in(p) += compilerPlugin(
          "org.scalameta" % "semanticdb-scalac" %
            semanticdbVersion cross CrossVersion.full
        )
      )
    } yield setting
    val semanticdbInstalled = extracted.append(settings, s)
    s.log.info("semanticdb-scalac installed 👌")
    semanticdbInstalled
  }
}
