package stryker4s.report

import grizzled.slf4j.Logging
import stryker4s.config._
import stryker4s.files.DiskFileIO
import stryker4s.model.{Mutant, MutantRunResult}
import stryker4s.report.dashboard.DashboardConfigProvider
import cats.effect.{Concurrent, ContextShift, IO}
import fs2.Stream
import sttp.client.asynchttpclient.cats.AsyncHttpClientCatsBackend
import sttp.client.okhttp.OkHttpFutureBackend
import scala.util.{Failure, Success, Try}
import scala.concurrent.{ExecutionContext, Future}

class Reporter(implicit config: Config, ec: ExecutionContext)
    extends FinishedRunReporter
    with ProgressReporter
    with Logging {

  lazy val reporters: Iterable[MutationRunReporter] = config.reporters map {
    case Console => new ConsoleReporter()
    case Html    => new HtmlReporter(new DiskFileIO())
    case Json    => new JsonReporter(new DiskFileIO())
    case Dashboard =>
      implicit val contextShift = IO.contextShift(ExecutionContext.global)
      implicit val concurrent = IO.ioConcurrentEffect
      AsyncHttpClientCatsBackend().flatMap { _ => new DashboardReporter(new DashboardConfigProvider(sys.env)) }
      ???
  }

  private[this] lazy val progressReporters = reporters collect { case r: ProgressReporter => r }
  private[this] lazy val finishedRunReporters = reporters collect { case r: FinishedRunReporter => r }

  override def reportMutationStart(mutant: Mutant): Future[Unit] =
    reportAll[ProgressReporter](
      progressReporters,
      _.reportMutationStart(mutant)
    )

  override def reportMutationComplete(result: MutantRunResult, totalMutants: Int): Future[Unit] =
    reportAll[ProgressReporter](
      progressReporters,
      _.reportMutationComplete(result, totalMutants)
    )

  override def reportRunFinished(runReport: FinishedRunReport): Future[Unit] = {
    reportAll[FinishedRunReporter](
      finishedRunReporters,
      reporter => reporter.reportRunFinished(runReport)
    )
  }
  override def reportRunFinishedF[F[_]: Concurrent: ContextShift](runReport: FinishedRunReport): F[Unit] = {
    val reporterFs = finishedRunReporters
      .map(_.reportRunFinishedF(runReport))
      .map(fs2.Stream.eval(_))
      .toSeq
    Stream
      .emits(reporterFs)
      .handleErrorWith(e => { s"Reporter failed: ${e.getMessage}"; Stream.empty })
      .parJoinUnbounded
      .compile
      .drain
  }

  /** Calls all @param reporters with the given @param reportF function, logging any that failed
    *
    * @param reporters
    * @param reportF
    */
  private def reportAll[T](reporters: Iterable[T], reportF: T => Future[Unit]): Future[Unit] =
    Future.traverse(reporters)(reporter =>
      reportF(reporter)
        .map(Success(_))
        .recover({ case e: Throwable => Failure(e) })
    ) map { reported =>
      val failed = reported.collect({ case f: Failure[Unit] => f })
      if (failed.nonEmpty) {
        warn(s"${failed.size} reporter(s) failed to report:")
        failed.map(_.exception).foreach(warn(_))
      }
    }
}
