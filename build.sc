package build

import mill._
import mill.api.BuildCtx
import mill.scalalib._

val workspaceRoot = BuildCtx.workspaceRoot
val depsRoot = workspaceRoot / "deps"
val laomaSubsystemPath = depsRoot / "LaomaSubsystem"
val laomaAxiPath = laomaSubsystemPath / "src" / "main" / "scala" / "lmss" / "axi"
val laomaXsUtilsPath = laomaSubsystemPath / "xs-utils" / "src" / "main" / "scala" / "xs" / "utils"
val laomaCdePath = laomaSubsystemPath / "rocket-chip" / "dependencies" / "cde" / "cde" / "src"
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

object v {
  val chiselVersion = "7.9.0"
  val scalaVersion = "2.13.18"
  val scalaTestVersion = "3.2.18"
  val chiselIvy = mvn"org.chipsalliance::chisel:$chiselVersion"
  val chiselPluginIvy = mvn"org.chipsalliance:::chisel-plugin:$chiselVersion"
  val scalaTestIvy = mvn"org.scalatest::scalatest:$scalaTestVersion"
  val sourcecodeIvy = mvn"com.lihaoyi::sourcecode:0.4.4"
}

object difftest extends Cross[DiffTest](Seq(v.chiselVersion))
object wiredrv extends Cross[WiredRV](Seq(v.chiselVersion))

trait Chisel7Module extends ScalaModule with Cross.Module[String] {
  require(crossValue == v.chiselVersion, s"only Chisel ${v.chiselVersion} is supported")

  def chiselVersion: String = crossValue
  def scalaVersion = v.scalaVersion
  def chiselIvy = v.chiselIvy
  def chiselPluginIvy = v.chiselPluginIvy
  def millForkArgs = Seq(
    s"-Xmx${sys.env.getOrElse("MILL_FORK_XMX", "256G")}",
    s"-Xss${sys.env.getOrElse("MILL_FORK_XSS", "192m")}"
  )
  override def forkEnv = super.forkEnv() ++ Map("NOOP_HOME" -> os.pwd.toString)

  override def mvnDeps = super.mvnDeps() ++ Seq(v.sourcecodeIvy)

  override def scalacOptions = super.scalacOptions() ++ Seq(
    "-language:reflectiveCalls",
    "-Ymacro-annotations",
    "-deprecation",
    "-feature"
  )
}

trait DiffTest
    extends DiffTestModule
    with Chisel7Module {
  override def moduleDir = difftestPath

  override def sources = Task.Sources {
    moduleDir / "src" / "main" / "scala"
  }
}

trait WiredRV
    extends WiredRVModule
    with Chisel7Module {
  override def moduleDir = workspaceRoot

  override def resources = Task.Sources {
    moduleDir / "src" / "main" / "resources"
  }

  override def sources = Task.Sources(
    moduleDir / "src" / "main" / "scala",
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
    override def mvnDeps = super.mvnDeps() ++ Seq(v.scalaTestIvy)
    override def sources = Task.Sources {
      WiredRV.this.moduleDir / "src" / "test" / "scala"
    }
  }
}
