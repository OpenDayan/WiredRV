import mill._
import mill.scalalib._

import $file.common

val laomaSubsystemPath = os.pwd / "third_party" / "LaomaSubsystem"
val laomaAxiPath = laomaSubsystemPath / "src" / "main" / "scala" / "lmss" / "axi"
val laomaXsUtilsPath = laomaSubsystemPath / "xs-utils" / "src" / "main" / "scala" / "xs" / "utils"
val laomaCdePath = laomaSubsystemPath / "rocket-chip" / "dependencies" / "cde" / "cde" / "src"

object v {
  val chiselVersion = "6.6.0"
  val scalaVersion = "2.13.15"
  val chiseltestVersion = "6.0.0"
  val chiselIvy = ivy"org.chipsalliance::chisel:$chiselVersion"
  val chiselPluginIvy = ivy"org.chipsalliance:::chisel-plugin:$chiselVersion"
  val chiseltestIvy = ivy"edu.berkeley.cs::chiseltest:$chiseltestVersion"
  val sourcecodeIvy = ivy"com.lihaoyi::sourcecode:0.4.4"
}

object difftest extends Cross[DiffTest](Seq(v.chiselVersion))
object wiredrv extends Cross[WiredRV](Seq(v.chiselVersion))

trait Chisel66Module extends ScalaModule with Cross.Module[String] {
  require(crossValue == v.chiselVersion, s"only Chisel ${v.chiselVersion} is supported")

  def chiselVersion: String = crossValue
  def scalaVersion = v.scalaVersion
  def chiselIvy = Some(v.chiselIvy)
  def chiselPluginIvy = Some(v.chiselPluginIvy)
  def millForkArgs = Seq(
    s"-Xmx${sys.env.getOrElse("MILL_FORK_XMX", "256G")}",
    s"-Xss${sys.env.getOrElse("MILL_FORK_XSS", "192m")}"
  )

  override def ivyDeps = super.ivyDeps() ++ Agg(v.sourcecodeIvy)

  override def scalacOptions = super.scalacOptions() ++ Seq(
    "-language:reflectiveCalls",
    "-Ymacro-annotations",
    "-deprecation",
    "-feature"
  )
}

trait DiffTest
    extends millbuild.common.DiffTestModule
    with Chisel66Module {
  override def millSourcePath = os.pwd / "difftest"
}

trait WiredRV
    extends millbuild.common.WiredRVModule
    with Chisel66Module {
  override def millSourcePath = os.pwd

  override def resources = T.sources {
    millSourcePath / "src" / "main" / "resources"
  }

  override def sources = T.sources(
    millSourcePath / "src" / "main" / "scala",
    laomaAxiPath / "package.scala",
    laomaAxiPath / "AxiNarrowToWide.scala",
    laomaXsUtilsPath / "PickOne.scala",
    laomaXsUtilsPath / "queue" / "CircularQueuePtr.scala",
    laomaXsUtilsPath / "queue" / "FastQueue.scala",
    laomaCdePath
  )

  def difftestModule = difftest(chiselVersion)

  override def forkArgs = millForkArgs
  override def scalacOptions = super.scalacOptions() ++ Seq("-Xcheckinit")

  object tests extends ScalaTests with TestModule.ScalaTest {
    override def forkArgs = WiredRV.this.millForkArgs
    override def ivyDeps = super.ivyDeps() ++ Agg(v.chiseltestIvy)
    override def sources = T.sources {
      WiredRV.this.millSourcePath / "src" / "test" / "scala"
    }
  }
}
