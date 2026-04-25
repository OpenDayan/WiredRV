// Import Mill dependencies
import mill._
import mill.scalalib._
import mill.scalalib.publish._
import coursier.maven.MavenRepository

// Import from each repository
import $file.common
import $file.third_party.LaomaSubsystem.`rocket-chip`.dependencies.hardfloat.common
import $file.third_party.LaomaSubsystem.`rocket-chip`.dependencies.cde.common
import $file.third_party.LaomaSubsystem.`rocket-chip`.dependencies.diplomacy.common
import $file.third_party.LaomaSubsystem.`rocket-chip`.common

// Define Scala and Chisel versions
object v {
  val scalaVersions = Map(
    "6.6.0" -> "2.13.15",
    "7.10.0" -> "2.13.18",
  )
  val chiselCrossVersions = Map(
    "6.6.0" -> (ivy"org.chipsalliance::chisel:6.6.0", ivy"org.chipsalliance:::chisel-plugin:6.6.0", ivy"edu.berkeley.cs::chiseltest:6.0.0"),
    "7.10.0" -> (ivy"org.chipsalliance::chisel:7.10.0", ivy"org.chipsalliance:::chisel-plugin:7.10.0", ivy"edu.berkeley.cs::chiseltest:6.0.0"),
  )
  val mainargs = ivy"com.lihaoyi::mainargs:0.5.0"
  val json4sJackson = ivy"org.json4s::json4s-jackson:4.0.7"
  val scalaReflect = ivy"org.scala-lang:scala-reflect:${scalaVersions("7.10.0")}"
  val sourcecode = ivy"com.lihaoyi::sourcecode:0.4.4"
  val scalatest = ivy"org.scalatest::scalatest:3.2.19"
}

val lmssPath = os.pwd / "third_party" / "LaomaSubsystem"
val rocketchipPath = lmssPath / "rocket-chip"

trait CommonModule extends ScalaModule with Cross.Module[String] {
  def chiselVersion = crossValue
  def scalaVersion = v.scalaVersions(chiselVersion)
  def chiselModule = None
  def chiselPluginJar = T(None)
  def chiselIvy = Some(v.chiselCrossVersions(chiselVersion)._1)
  def chiselPluginIvy = Some(v.chiselCrossVersions(chiselVersion)._2)
  override def ivyDeps = T(super.ivyDeps() ++ chiselIvy)
  override def scalacPluginIvyDeps = T(
    super.scalacPluginIvyDeps() ++ chiselPluginIvy.map(Agg(_)).getOrElse(Agg.empty[Dep])
  )
  override def scalacOptions = super.scalacOptions() ++ Seq(
    "-language:reflectiveCalls",
    "-Ymacro-annotations",
    "-deprecation",
    "-feature"
  )
}

trait Macros
  extends millbuild.third_party.LaomaSubsystem.`rocket-chip`.common.MacrosModule
    with CommonModule {
  def scalaReflectIvy = v.scalaReflect
}
object macros extends Cross[Macros](Seq("7.10.0"))

trait Hardfloat
  extends millbuild.third_party.LaomaSubsystem.`rocket-chip`.dependencies.hardfloat.common.HardfloatModule
    with CommonModule {
  override def millSourcePath =
    rocketchipPath / "dependencies" / "hardfloat" / "hardfloat"
}
object hardfloat extends Cross[Hardfloat](Seq("7.10.0"))

trait CDE
  extends millbuild.third_party.LaomaSubsystem.`rocket-chip`.dependencies.cde.common.CDEModule
    with CommonModule {
  override def millSourcePath = rocketchipPath / "dependencies" / "cde" / "cde"
}
object cde extends Cross[CDE](Seq("7.10.0"))

trait Diplomacy
  extends millbuild.third_party.LaomaSubsystem.`rocket-chip`.dependencies.diplomacy.common.DiplomacyModule
    with CommonModule {
  override def millSourcePath =
    rocketchipPath / "dependencies" / "diplomacy" / "diplomacy"

  def cdeModule = cde(chiselVersion)
  def sourcecodeIvy = v.sourcecode
}
object diplomacy extends Cross[Diplomacy](Seq("7.10.0"))

trait RocketChip
  extends millbuild.third_party.LaomaSubsystem.`rocket-chip`.common.RocketChipModule
    with CommonModule {
  override def millSourcePath = rocketchipPath
  override def resources = T.sources {
    millSourcePath / "src" / "main" / "resources"
  }

  def macrosModule = macros(chiselVersion)
  def hardfloatModule = hardfloat(chiselVersion)
  def cdeModule = cde(chiselVersion)
  def diplomacyModule = diplomacy(chiselVersion)
  def mainargsIvy = v.mainargs
  def json4sJacksonIvy = v.json4sJackson
}
object rocketchip extends Cross[RocketChip](Seq("7.10.0"))

trait Xsutils extends CommonModule {
  override def millSourcePath = lmssPath / "xs-utils"
  override def sources = T.sources(
    millSourcePath / "src" / "main" / "scala"
  )
  override def allSourceFiles = T {
    super.allSourceFiles().filterNot { ref =>
      ref.path == millSourcePath / "src" / "main" / "scala" / "xs" / "utils" / "debug" / "HardwareAssertion.scala"
    }
  }

  def cdeModule = cde(chiselVersion)
  def rocketchipModule = rocketchip(chiselVersion)
  override def moduleDeps = super.moduleDeps ++ Seq(rocketchipModule, cdeModule)
  override def ivyDeps = T(super.ivyDeps() ++ Agg(v.sourcecode))
  override def scalacOptions =
    super.scalacOptions() ++ Agg("-Ytasty-reader")

  def debugCompatShim = T {
    val outputFile = T.dest / "xs" / "utils" / "debug" / "HardwareAssertionCompat.scala"
    os.write.over(
      outputFile,
      """package xs.utils.debug
        |
        |import org.chipsalliance.cde.config.Field
        |
        |case class HwaParams(
        |  enable: Boolean = false,
        |  dumpDirectory: String = "",
        |  maxTreeLevel: Int = 0,
        |  maxInfoBits: Int = 0,
        |  triggerDelayCycles: Int = 0
        |)
        |
        |case object HardwareAssertionKey extends Field[HwaParams](HwaParams())
        |""".stripMargin,
      createFolders = true
    )
    PathRef(T.dest)
  }

  override def generatedSources = T {
    super.generatedSources() :+ debugCompatShim()
  }
}
object xsutils extends Cross[Xsutils](Seq("7.10.0"))

trait Lmss extends CommonModule {
  override def millSourcePath = lmssPath
  override def sources = T.sources(
    millSourcePath / "src" / "main" / "scala"
  )

  def hardfloatModule = hardfloat(chiselVersion)
  def xsutilsModule = xsutils(chiselVersion)
  def rocketchipModule = rocketchip(chiselVersion)
  def cdeModule = cde(chiselVersion)
  override def moduleDeps =
    super.moduleDeps ++ Seq(hardfloatModule, xsutilsModule, rocketchipModule, cdeModule)
}
object lmss extends Cross[Lmss](Seq("7.10.0"))

// Define XiangShan Difftest Module
object difftest extends Cross[DiffTest](v.chiselCrossVersions.keys.toSeq)
object wiredrv extends Cross[WiredRV](v.chiselCrossVersions.keys.toSeq)

trait DiffTest extends millbuild.common.DiffTestModule
  with Cross.Module[String] {
  def chiselVersion: String = crossValue
  def scalaVersion = v.scalaVersions(chiselVersion)
  def chiselIvy = Some(v.chiselCrossVersions(chiselVersion)._1)
  def chiselPluginIvy = Some(v.chiselCrossVersions(chiselVersion)._2)

  override def millSourcePath = os.pwd / "difftest"
  }

trait WiredRV
  extends millbuild.common.WiredRVModule
    with Cross.Module[String] {
  def chiselVersion: String = crossValue
  def scalaVersion = v.scalaVersions(chiselVersion)
  def chiselIvy = Some(v.chiselCrossVersions(chiselVersion)._1)
  def chiselPluginIvy = Some(v.chiselCrossVersions(chiselVersion)._2)

  override def millSourcePath = os.pwd

  override def resources = T.sources {
    millSourcePath / "src" / "main" / "resources"
  }

  override def sources = T.sources {
    millSourcePath / "src" / "main" / "scala"
  }

  def difftestModule = difftest(crossValue)
  def lmssModule = None
  def ivyDeps = super.ivyDeps()
  override def moduleDeps = super.moduleDeps ++ lmssModule.toSeq

  override def forkArgs = Seq("-Xmx256G", "-Xss192m")
  override def scalacOptions = super.scalacOptions() ++ Seq(
    "-language:reflectiveCalls",
    "-Ymacro-annotations",
    "-deprecation",
    "-feature",
    "-Xcheckinit"
  )

  override def scalacPluginIvyDeps = super.scalacPluginIvyDeps() ++ Agg(chiselPluginIvy.get)

  // Define tests module
  object tests extends ScalaTests with TestModule.ScalaTest {
    override def forkArgs = Seq("-Xmx256G", "-Xss192m")
    override def ivyDeps = super.ivyDeps() ++ Agg(
      v.chiselCrossVersions(chiselVersion)._2,
      v.chiselCrossVersions(chiselVersion)._3
    )
    override def sources = T.sources {
      WiredRV.this.millSourcePath / "src" / "test" / "scala"
    }
  }
}
