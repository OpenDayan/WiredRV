package build

import mill._
import mill.scalalib._

trait HasChisel
  extends ScalaModule {
  def chiselIvy: Dep
  def chiselPluginIvy: Dep

  override def mvnDeps = super.mvnDeps() ++ Seq(chiselIvy)
  override def scalacPluginMvnDeps = super.scalacPluginMvnDeps() ++ Seq(chiselPluginIvy)
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
