package com.azavea.franklin.api

import com.azavea.franklin.crawler.StacImport
import com.azavea.franklin.database.DatabaseConfig
import doobie.hikari.HikariTransactor
import doobie.implicits._
import doobie.util.ExecutionContexts
import org.http4s.implicits._
import org.http4s.server.blaze._
import org.http4s.server.middleware._
import org.http4s.server.{Router, Server => HTTP4sServer}
import tapir.docs.openapi._
import tapir.openapi.circe.yaml._
import tapir.swagger.http4s.SwaggerHttp4s
import cats.effect._
import cats.implicits._
import com.azavea.franklin.endpoints.{
  CollectionEndpoints,
  CollectionItemEndpoints,
  LandingPageEndpoints,
  SearchEndpoints
}
import com.azavea.franklin.services.{
  CollectionItemsService,
  CollectionsService,
  LandingPageService,
  SearchService
}
import com.google.common.util.concurrent.ThreadFactoryBuilder
import com.monovore.decline._
import org.flywaydb.core.Flyway

import scala.concurrent.ExecutionContext

import java.util.concurrent.Executors

object Server extends IOApp {

  val banner =
    """

   $$$$$$$$$
$$$$$$$$$$$$   ________                            __        __  __
$$$$$$$$$$$$  /        |                          /  |      /  |/  |
$$            $$$$$$$$/______   ______   _______  $$ |   __ $$ |$$/  _______
 $$$$$$$$     $$ |__  /      \ /      \ /       \ $$ |  /  |$$ |/  |/       \
$$$$$$$$      $$    |/$$$$$$  |$$$$$$  |$$$$$$$  |$$ |_/$$/ $$ |$$ |$$$$$$$  |
$$$$$$$       $$$$$/ $$ |  $$/ /    $$ |$$ |  $$ |$$   $$<  $$ |$$ |$$ |  $$ |
$             $$ |   $$ |     /$$$$$$$ |$$ |  $$ |$$$$$$  \ $$ |$$ |$$ |  $$ |
$$$$$$        $$ |   $$ |     $$    $$ |$$ |  $$ |$$ | $$  |$$ |$$ |$$ |  $$ |
$$$$$         $$/    $$/       $$$$$$$/ $$/   $$/ $$/   $$/ $$/ $$/ $$/   $$/
$$$$

""".split("\n").toList

  def createServer(
      port: Int,
      dbUser: String,
      dbPass: String,
      dbHost: String,
      dbPort: Int,
      dbName: String
  ): Resource[IO, HTTP4sServer[IO]] =
    for {
      connectionEc  <- ExecutionContexts.fixedThreadPool[IO](2)
      transactionEc <- ExecutionContexts.cachedThreadPool[IO]
      xa <- HikariTransactor.newHikariTransactor(
        "org.postgresql.Driver",
        s"jdbc:postgresql://$dbHost:$dbPort/$dbName",
        dbUser,
        dbPass,
        connectionEc,
        transactionEc
      )
      allEndpoints      = LandingPageEndpoints.endpoints ++ CollectionEndpoints.endpoints ++ CollectionItemEndpoints.endpoints ++ SearchEndpoints.endpoints
      docs              = allEndpoints.toOpenAPI("Franklin", "0.0.1")
      docRoutes         = new SwaggerHttp4s(docs.toYaml, "open-api", "spec.yaml").routes[IO]
      landingPageRoutes = new LandingPageService[IO].routes
      searchRoutes      = new SearchService[IO](xa).routes
      collectionRoutes = new CollectionsService[IO](xa).routes <+> new CollectionItemsService[IO](
        xa
      ).routes
      router = CORS(
        Router(
          "/" -> (landingPageRoutes <+> collectionRoutes <+> searchRoutes <+> docRoutes)
        )
      ).orNotFound
      server <- {
        BlazeServerBuilder[IO]
          .bindHttp(port, "0.0.0.0")
          .withBanner(banner)
          .withHttpApp(router)
          .resource
      }
    } yield {
      server
    }

  case class RunImport(catalogRoot: String)

  val runImportOpts: Opts[RunImport] = Opts.subcommand("import", "Import a STAC catalog") {
    Opts
      .option[String]("catalogRoot", "Root of STAC catalog to import")
      .map(RunImport(_))
  }

  def runImport(stacCatalog: String): fs2.Stream[IO, Unit] = {
    val xa = DatabaseConfig.nonHikariTransactor[IO](DatabaseConfig.jdbcDBName)
    new StacImport(stacCatalog).run().transact(xa)
  }

  case class RunMigrations()

  val runMigrationsOpts: Opts[RunMigrations] =
    Opts.subcommand("migrate", "Runs migrations against database") {
      Opts.unit.map(_ => RunMigrations())
    }

  def runMigrations: IO[ExitCode] = IO {
    Flyway
      .configure()
      .dataSource(
        s"${DatabaseConfig.jdbcUrl}",
        DatabaseConfig.dbUser,
        DatabaseConfig.dbPassword
      )
      .locations("classpath:migrations/")
      .load()
      .migrate()
    ExitCode.Success
  }

  final case class RunServer(
      port: Int,
      dbUser: String,
      dbPass: String,
      dbHost: String,
      dbPort: Int,
      dbName: String
  )

  private val databasePort = Opts
    .option[Int]("db-port", help = "Port to connect to database on")
    .withDefault(5432)

  private val databaseHost = Opts
    .option[Int]("db-host", help = "Database host to connect to")
    .withDefault("database.service.internal")

  private val databaseName = Opts
    .option[String]("db-name", help = "Database name to connect to")
    .withDefault("franklin")

  private val databasePassword = Opts
    .option[String]("db-password", help = "Database password to use")
    .withDefault("franklin")

  private val databaseUser = Opts
    .option[String]("db-user", help = "User to connect with database with")
    .withDefault("franklin")

  private val serverPort = Opts
    .option[Int]("port", help = "Port to start web service on")
    .withDefault(9090)

  val runServerOpts: Opts[RunServer] =
    Opts.subcommand("server", "Runs web service") {
      (serverPort, databaseUser, databasePassword, databaseHost, databasePort, databaseName) mapN (RunServer)
    }

  val applicationCommand: Command[Product] =
    Command("", "Your Friendly Neighborhood OGC API - Features and STAC Web Service") {
      runServerOpts orElse runMigrationsOpts orElse runImportOpts
    }

  override def run(args: List[String]): IO[ExitCode] = {
    applicationCommand.parse(args) map {
      case RunServer(port, dbUser, dbPassword, dbHost, dbPort, dbName) =>
        createServer(port, dbUser, dbPassword, dbHost, dbPort, dbName)
          .use(_ => IO.never)
          .as(ExitCode.Success)
      case RunMigrations() => runMigrations
      case RunImport(catalogRoot) =>
        runImport(catalogRoot).compile.drain map { _ =>
          ExitCode.Success
        }
    } match {
      case Left(e) =>
        IO {
          println(e.toString())
        } map { _ =>
          ExitCode.Error
        }
      case Right(s) => s
    }
  }
}