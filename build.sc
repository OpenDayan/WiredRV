// Import Mill dependencies
import mill._
import mill.scalalib._
import mill.scalalib.publish._
import coursier.maven.MavenRepository

// Import from each repository
import $file.common

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
}

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
  def ivyDeps = super.ivyDeps()

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
