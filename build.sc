import mill._
import mill.scalalib._

import $file.common

object v {
  val chiselVersion = "6.6.0"
  val scalaVersion = "2.13.15"
  val chiselIvy = ivy"org.chipsalliance::chisel:6.6.0"
  val chiselPluginIvy = ivy"org.chipsalliance:::chisel-plugin:6.6.0"
  val chiseltestIvy = ivy"edu.berkeley.cs::chiseltest:6.0.0"
}

object difftest extends Cross[DiffTest](Seq(v.chiselVersion))
object wiredrv extends Cross[WiredRV](Seq(v.chiselVersion))

trait Chisel66Module extends ScalaModule with Cross.Module[String] {
  require(crossValue == v.chiselVersion, s"only Chisel ${v.chiselVersion} is supported")

  def chiselVersion: String = crossValue
  def scalaVersion = v.scalaVersion
  def chiselIvy = Some(v.chiselIvy)
  def chiselPluginIvy = Some(v.chiselPluginIvy)

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

  override def sources = T.sources {
    millSourcePath / "src" / "main" / "scala"
  }

  def difftestModule = difftest(chiselVersion)

  override def forkArgs = Seq("-Xmx256G", "-Xss192m")
  override def scalacOptions = super.scalacOptions() ++ Seq("-Xcheckinit")

  object tests extends ScalaTests with TestModule.ScalaTest {
    override def forkArgs = Seq("-Xmx256G", "-Xss192m")
    override def ivyDeps = super.ivyDeps() ++ Agg(v.chiseltestIvy)
    override def sources = T.sources {
      WiredRV.this.millSourcePath / "src" / "test" / "scala"
    }
  }
}
