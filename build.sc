package build

import mill._
import mill.api.{BuildCtx, PathRef}
import mill.scalalib._

val workspaceRoot = BuildCtx.workspaceRoot
val depsRoot = workspaceRoot / "deps"
val axiInfraPath = depsRoot / "AxiInfra"
val axiInfraRocketChipPath = axiInfraPath / "dep" / "rocket-chip"
val axiInfraCdePath = axiInfraPath / "dep" / "cde" / "cde"
val axiInfraDiplomacyPath = axiInfraPath / "dep" / "diplomacy" / "diplomacy"
val axiInfraHardfloatPath = axiInfraPath / "dep" / "hardfloat" / "hardfloat"
val axiInfraXsUtilsPath = axiInfraPath / "dep" / "xs-utils"
val axiInfraChiselPath = axiInfraPath / "dep" / "chisel"
val difftestPath = depsRoot / "difftest"

val defaultVerilogTop = "wiredrv.harness.WiredCoreHarnessTop"

def gen() = Task.Command {
  val top = sys.props.getOrElse("top", defaultVerilogTop)
  val topName = top.split('.').lastOption.getOrElse(top)
  val targetDir = sys.props.getOrElse("target-dir", s"generated/$topName")
  val replSeqMem = sys.props.getOrElse("repl-seq-mem", "false")
  val simUncachedInRam = sys.props.getOrElse("sim-uncached-in-ram", "false")
  val classpath = wiredrv(v.chiselVersion).runClasspath().map(_.path)
  val javaArgs = wiredrv(v.chiselVersion).forkArgs()

  os.proc(
    Seq("java") ++ javaArgs ++ Seq(
      "-cp", classpath.mkString(java.io.File.pathSeparator),
      "wiredrv.Main",
      "--top", top,
      "--target-dir", targetDir,
      "--repl-seq-mem", replSeqMem,
      "--sim-uncached-in-ram", simUncachedInRam
    )
  ).call(workspaceRoot, stdout = os.Inherit, stderr = os.Inherit)
}

object license extends Module {
  private val marker = "SPDX-License-Identifier: MulanPSL-2.0"

  private def scalaFiles =
    os.walk(workspaceRoot / "src")
      .filter(path => os.isFile(path) && path.ext == "scala")

  def create() = Task.Command {
    val header =
      os.read(workspaceRoot / "license-header.txt").stripTrailing() + "\n\n"
    val updated = scalaFiles.filter { path =>
      val content = os.read(path)
      if (content.contains(marker)) {
        false
      } else {
        os.write.over(path, header + content)
        true
      }
    }
    Task.log.info(s"Added license header to ${updated.size} Scala files")
  }

  def check() = Task.Command {
    val missing = scalaFiles.filterNot(path => os.read(path).contains(marker))
    if (missing.nonEmpty) {
      missing.foreach(path => Task.log.error(path.relativeTo(workspaceRoot).toString))
      throw new RuntimeException(
        s"${missing.size} Scala files are missing license headers"
      )
    }
    Task.log.info("All Scala files contain license headers")
  }
}

object v {
  val scalaBinaryVersion = "2.13"
  val chiselVersion = "7.9.0"
  val firtoolVersion = "1.140.0"
  val scalaVersion = "2.13.18"
  val scalaTestVersion = "3.2.18"
  val chiselIvy = mvn"org.chipsalliance::chisel:$chiselVersion"
  val chiselPluginIvy = mvn"org.chipsalliance:::chisel-plugin:$chiselVersion"
  val llvmFirtoolIvy = mvn"org.chipsalliance:llvm-firtool:$firtoolVersion"
  val scalaTestIvy = mvn"org.scalatest::scalatest:$scalaTestVersion"
  val sourcecodeIvy = mvn"com.lihaoyi::sourcecode:0.4.4"
  val json4sJacksonIvy = mvn"org.json4s::json4s-jackson:4.0.7"
  val json4sNativeIvy = mvn"io.github.json4s::json4s-native:4.1.0"
  val mainargsIvy = mvn"com.lihaoyi::mainargs:0.5.0"
  val osLibIvy = mvn"com.lihaoyi::os-lib:0.10.7"
  val upickleIvy = mvn"com.lihaoyi::upickle:3.3.1"
  val firtoolResolverIvy = mvn"org.chipsalliance::firtool-resolver:2.0.1"
  val scoptIvy = mvn"com.github.scopt::scopt:4.1.0"
  val commonTextIvy = mvn"org.apache.commons:commons-text:1.15.0"
  val dataclassIvy = mvn"io.github.alexarchambault::data-class:0.2.7"
  val scalaReflectIvy = mvn"org.scala-lang:scala-reflect:$scalaVersion"
  val unrollPluginIvy = mvn"com.lihaoyi:::unroll-plugin:0.3.0"
  val unrollAnnotationIvy = mvn"com.lihaoyi::unroll-annotation:0.3.0"
}

trait LocalScalaModule extends ScalaModule {
  def scalaVersion = v.scalaVersion

  override def scalacOptions = super.scalacOptions() ++ Seq(
    "-language:reflectiveCalls",
    "-Ymacro-annotations",
    "-deprecation",
    "-feature"
  )
}

trait LocalSbtModule extends SbtModule with LocalScalaModule

trait LocalCrossSbtModule extends CrossSbtModule {
  def scalaVersion = v.scalaVersion

  override def scalacOptions = super.scalacOptions() ++ Seq(
    "-language:reflectiveCalls",
    "-Ymacro-annotations",
    "-deprecation",
    "-feature"
  )
}

trait CommonChiselModule extends HasChisel {
  def scalaVersion = v.scalaVersion
  def chiselIvy = None
  def chiselPluginIvy = Some(v.chiselPluginIvy)
  def millForkArgs = Seq(
    s"-Xmx${sys.env.getOrElse("MILL_FORK_XMX", "256G")}",
    s"-Xss${sys.env.getOrElse("MILL_FORK_XSS", "192m")}"
  )

  override def forkEnv = super.forkEnv() ++ Map("NOOP_HOME" -> workspaceRoot.toString)

  override def moduleDeps = super.moduleDeps ++ Seq(deps.chiselLib(v.scalaBinaryVersion))

  override def mvnDeps = super.mvnDeps() ++ Seq(
    v.sourcecodeIvy,
    v.llvmFirtoolIvy
  )

  override def scalacOptions = super.scalacOptions() ++ Seq(
    "-language:reflectiveCalls",
    "-Ymacro-annotations",
    "-deprecation",
    "-feature"
  )
}

trait CommonChiselSbtModule extends SbtModule with CommonChiselModule

trait Chisel7Module extends CommonChiselSbtModule with Cross.Module[String] {
  require(crossValue == v.chiselVersion, s"only Chisel ${v.chiselVersion} is supported")

  def chiselVersion: String = crossValue
}

object deps extends Module {
  trait DepScalaModule extends CommonChiselModule
  trait DepSbtModule extends CommonChiselSbtModule

  object chiselFirrtl extends Cross[ChiselFirrtl](Seq(v.scalaBinaryVersion))
  trait ChiselFirrtl extends LocalCrossSbtModule {
    override def moduleDir = axiInfraChiselPath / "firrtl"

    override def mvnDeps = super.mvnDeps() ++ Seq(
      v.scoptIvy,
      v.commonTextIvy,
      v.osLibIvy,
      v.json4sNativeIvy,
      v.dataclassIvy
    )

    override def scalacOptions = super.scalacOptions() ++ Seq(
      "-language:existentials",
      "-language:implicitConversions",
      "-Yrangepos",
      "-Xsource-features:infer-override"
    )
  }

  object chiselMacros extends Cross[ChiselMacros](Seq(v.scalaBinaryVersion))
  trait ChiselMacros extends LocalCrossSbtModule {
    override def moduleDir = axiInfraChiselPath / "macros"

    override def mvnDeps = super.mvnDeps() ++ Seq(v.scalaReflectIvy)
  }

  object chiselCore extends Cross[ChiselCore](Seq(v.scalaBinaryVersion))
  trait ChiselCore extends LocalCrossSbtModule {
    override def moduleDir = axiInfraChiselPath / "core"

    override def moduleDeps = super.moduleDeps ++ Seq(
      chiselFirrtl(v.scalaBinaryVersion),
      chiselMacros(v.scalaBinaryVersion)
    )

    override def mvnDeps = super.mvnDeps() ++ Seq(
      v.osLibIvy,
      v.upickleIvy,
      v.firtoolResolverIvy
    )

    def buildInfo = Task {
      val outputFile = Task.dest / "chisel3" / "BuildInfo.scala"
      val firtoolVersionString = "Some(\"" + v.firtoolVersion + "\")"
      val contents =
        s"""
           |package chisel3
           |case object BuildInfo {
           |  val buildInfoPackage: String = "chisel3"
           |  val version: String = "${v.chiselVersion}"
           |  val scalaVersion: String = "${v.scalaVersion}"
           |  val firtoolVersion: scala.Option[String] = $firtoolVersionString
           |  override val toString: String = {
           |    "buildInfoPackage: %s, version: %s, scalaVersion: %s, firtoolVersion %s".format(
           |        buildInfoPackage, version, scalaVersion, firtoolVersion
           |    )
           |  }
           |}
           |""".stripMargin
      os.write(outputFile, contents, createFolders = true)
      PathRef(Task.dest)
    }

    override def generatedSources = Task {
      super.generatedSources() :+ buildInfo()
    }
  }

  object chiselSvsim extends Cross[ChiselSvsim](Seq(v.scalaBinaryVersion))
  trait ChiselSvsim extends LocalCrossSbtModule {
    override def moduleDir = axiInfraChiselPath / "svsim"

    override def compileMvnDeps = super.compileMvnDeps() ++ Seq(v.unrollAnnotationIvy)
    override def scalacPluginMvnDeps = super.scalacPluginMvnDeps() ++ Seq(v.unrollPluginIvy)

    override def scalacOptions = super.scalacOptions() ++ Seq(
      "-Xsource-features:case-apply-copy-access"
    )
  }

  object chiselLib extends Cross[ChiselLib](Seq(v.scalaBinaryVersion))
  trait ChiselLib extends LocalCrossSbtModule {
    override def moduleDir = axiInfraChiselPath

    override def moduleDeps = super.moduleDeps ++ Seq(
      chiselCore(v.scalaBinaryVersion),
      chiselSvsim(v.scalaBinaryVersion)
    )

    override def compileMvnDeps = super.compileMvnDeps() ++ Seq(v.scalaTestIvy)
    override def scalacPluginMvnDeps = super.scalacPluginMvnDeps() ++ Seq(v.chiselPluginIvy)
  }

  object cde extends DepScalaModule {
    override def moduleDir = axiInfraCdePath
  }

  object diplomacy extends DepScalaModule {
    override def moduleDir = axiInfraDiplomacyPath

    override def moduleDeps = super.moduleDeps ++ Seq(cde)
  }

  object hardfloat extends DepSbtModule {
    override def moduleDir = axiInfraHardfloatPath
  }

  object rocketChip extends DepSbtModule {
    override def moduleDir = axiInfraRocketChipPath

    override def mvnDeps = super.mvnDeps() ++ Seq(
      v.json4sJacksonIvy,
      v.mainargsIvy
    )

    override def moduleDeps = super.moduleDeps ++ Seq(
      hardfloat,
      cde,
      diplomacy,
      macros
    )

    object macros extends DepSbtModule {
      override def moduleDir = axiInfraRocketChipPath / "macros"

      override def mvnDeps = super.mvnDeps() ++ Seq(v.scalaReflectIvy)
    }
  }

  object xsUtils extends DepSbtModule {
    override def moduleDir = axiInfraXsUtilsPath

    override def moduleDeps = super.moduleDeps ++ Seq(
      rocketChip,
      cde
    )
  }

  object axiInfra extends DepSbtModule {
    override def moduleDir = axiInfraPath

    override def moduleDeps = super.moduleDeps ++ Seq(
      rocketChip,
      xsUtils
    )
  }

  object difftest extends DepSbtModule with DiffTestModule {
    override def moduleDir = difftestPath
  }
}

object wiredrv extends Cross[WiredRV](Seq(v.chiselVersion))

trait WiredRV
    extends WiredRVModule
    with Chisel7Module {
  override def moduleDir = workspaceRoot

  def difftestModule = deps.difftest

  override def moduleDeps = super.moduleDeps ++ Seq(
    deps.axiInfra,
    deps.xsUtils
  )

  override def forkArgs = millForkArgs
  override def scalacOptions = super.scalacOptions() ++ Seq("-Xcheckinit")

  object tests extends SbtTests with TestModule.ScalaTest {
    override def forkArgs = WiredRV.this.millForkArgs
    override def mvnDeps = super.mvnDeps() ++ Seq(v.scalaTestIvy)
  }
}
