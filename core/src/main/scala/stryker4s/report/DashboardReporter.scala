package stryker4s.report

import cats.data.Validated.{Invalid, Valid}
import cats.effect.{IO, Resource}
import cats.syntax.foldable._
import io.circe.Error
import mutationtesting.{MetricsResult, MutationTestResult}
import stryker4s.config.{Config, Full, MutationScoreOnly}
import stryker4s.log.Logger
import stryker4s.report.dashboard.DashboardConfigProvider
import stryker4s.report.model._
import sttp.client3._
import sttp.client3.circe.{asJson, circeBodySerializer}
import sttp.model.{MediaType, StatusCode}

class DashboardReporter(dashboardConfigProvider: DashboardConfigProvider)(implicit
    log: Logger,
    httpBackend: Resource[IO, SttpBackend[IO, Any]]
) extends Reporter {

  override def onRunFinished(runReport: FinishedRunEvent): IO[Unit] =
    dashboardConfigProvider.resolveConfig() match {
      case Invalid(configKeys) =>
        val configKeysString = configKeys.map(c => s"'$c'").mkString_(", ")
        IO(log.warn(s"Could not resolve dashboard configuration key(s) $configKeysString. Not sending report"))
      case Valid(dashboardConfig) =>
        val request = buildRequest(dashboardConfig, runReport.report, runReport.metrics)
        httpBackend
          .use(request.send(_))
          .map(logResponse(_))
    }

  def buildRequest(dashConfig: DashboardConfig, report: MutationTestResult[Config], metrics: MetricsResult) = {
    import io.circe.{Decoder, Encoder}
    implicit val decoder: Decoder[DashboardPutResult] = Decoder.forProduct1("href")(DashboardPutResult.apply)
    // Separate so any slashes won't be escaped in project or version
    val baseUrl = s"${dashConfig.baseUrl}/api/reports/${dashConfig.project}/${dashConfig.version}"
    val uri = uri"$baseUrl?module=${dashConfig.module}"
    val request = basicRequest
      .header("X-Api-Key", dashConfig.apiKey)
      .contentType(MediaType.ApplicationJson)
      .response(asJson[DashboardPutResult])
      .put(uri)
    dashConfig.reportType match {
      case Full =>
        import mutationtesting.circe._
        request
          .body(report)
      case MutationScoreOnly =>
        implicit val encoder: Encoder[ScoreOnlyReport] = Encoder.forProduct1("mutationScore")(r => r.mutationScore)
        request
          .body(ScoreOnlyReport(metrics.mutationScore))
    }
  }

  private def logResponse(response: Response[Either[ResponseException[String, Error], DashboardPutResult]]): Unit =
    response.body match {
      case Left(HttpError(errorBody, StatusCode.Unauthorized)) =>
        log.error(
          s"Error HTTP PUT '$errorBody'. Status code 401 Unauthorized. Did you provide the correct api key in the 'STRYKER_DASHBOARD_API_KEY' environment variable?"
        )
      case Left(HttpError(errorBody, statusCode)) =>
        log.error(
          s"Failed to PUT report to dashboard. Response status code: ${statusCode.code}. Response body: '$errorBody'"
        )
      case Left(DeserializationException(original, error)) =>
        log.warn(
          s"Dashboard report was sent successfully, but could not decode the response: '$original'. Error:",
          error
        )
      case Right(DashboardPutResult(href)) =>
        log.info(s"Sent report to dashboard. Available at $href")
    }
}
