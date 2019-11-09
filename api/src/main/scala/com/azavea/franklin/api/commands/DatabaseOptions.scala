package com.azavea.franklin.api.commands

import cats.implicits._
import com.monovore.decline.Opts
import cats.effect._
import doobie.util.transactor.Transactor
import doobie.implicits._

import scala.util.Try

trait DatabaseOptions {

  private val databasePort = Opts
    .option[Int]("db-port", help = "Port to connect to database on")
    .withDefault(5432)

  private val databaseHost = Opts
    .option[String]("db-host", help = "Database host to connect to")
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

  def databaseConfig(implicit contextShift: ContextShift[IO]): Opts[DatabaseConfig] =
    ((
      databaseUser,
      databasePassword,
      databaseHost,
      databasePort,
      databaseName
    ) mapN DatabaseConfig).validate(
      "Unable to connect to database - please ensure database is configured and listening at entered port"
    ) { config =>
      val xa =
        Transactor
          .fromDriverManager[IO](config.driver, config.jdbcUrl, config.dbUser, config.dbPass)
      val select = Try {
        fr"SELECT 1".query[Int].unique.transact(xa).unsafeRunSync()
      }
      select.toEither match {
        case Right(_) => true
        case Left(_)  => false
      }
    }
}
