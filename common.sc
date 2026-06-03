package build

import mill._
import mill.scalalib._

trait HasChisel
  extends ScalaModule {
  def chiselIvy: Option[Dep]
  def chiselPluginIvy: Option[Dep]

  override def mvnDeps = super.mvnDeps() ++ chiselIvy.toSeq
  override def scalacPluginMvnDeps = super.scalacPluginMvnDeps() ++ chiselPluginIvy.toSeq
}

// WiredRV always builds against difftest because the simulation harness exports
// DiffTest interfaces and wraps the top with DifftestModule.top.
trait WiredRVModule extends HasChisel {
  def difftestModule: ScalaModule

  override def moduleDeps = super.moduleDeps ++ Seq(
    difftestModule,
  )
}

trait DiffTestModule extends HasChisel {
}
