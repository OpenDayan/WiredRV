import mill._
import mill.scalalib._

trait HasChisel
  extends ScalaModule {
  def chiselIvy: Option[Dep]
  def chiselPluginIvy: Option[Dep]

  override def ivyDeps = super.ivyDeps() ++ Agg(chiselIvy.get) ++ Agg(chiselPluginIvy.get)
  override def scalacPluginIvyDeps = super.scalacPluginIvyDeps() ++ Agg(chiselPluginIvy.get)
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
