/*
 * Copyright 2014–2019 SlamData Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package quasar.destination.avalanche

import scala.Predef._
import scala._
import scala.concurrent.ExecutionContext
import scala.util.{Either, Random}

import java.util.concurrent.Executors

import quasar.api.destination.DestinationError.InitializationError
import quasar.api.destination.{Destination, DestinationError, DestinationType}
import quasar.blobstore.azure.{
  Azure,
  AzureCredentials,
  ClientId,
  ClientSecret,
  TenantId
}
import quasar.connector.{DestinationModule, MonadResourceErr}
import quasar.concurrent.NamedDaemonThreadFactory

import argonaut._, Argonaut._

import cats._
import cats.data._
import cats.effect._
import cats.implicits._

import eu.timepit.refined.auto._

import doobie._
import doobie.implicits._
import doobie.hikari.HikariTransactor

object AvalancheDestinationModule extends DestinationModule {
  val IngresDriverFqcn = "com.ingres.jdbc.IngresDriver"
  val Redacted = "<REDACTED>"
  val PoolSize: Int = 10

  def destinationType: DestinationType =
    DestinationType("avalanche", 1L)

  def sanitizeDestinationConfig(config: Json): Json =
    config.as[AvalancheConfig].result.fold(_ => Json.jEmptyObject, cfg =>
      cfg.copy(
        azureCredentials =
          AzureCredentials.ActiveDirectory(
            ClientId(Redacted),
            TenantId(Redacted),
            ClientSecret(Redacted)),
        password =
          ClusterPassword(Redacted)).asJson)

  def destination[F[_]: ConcurrentEffect: ContextShift: MonadResourceErr: Timer](
    config: Json): Resource[F, Either[InitializationError[Json], Destination[F]]] =
    (for {
      cfg <- EitherT.fromEither[Resource[F, ?]](config.as[AvalancheConfig].result) leftMap {
        case (err, _) => DestinationError.malformedConfiguration((destinationType, config, err))
      }
      poolSuffix <- EitherT.right(Resource.liftF(Sync[F].delay(Random.alphanumeric.take(5).mkString)))
      connectPool <- EitherT.right(boundedPool[F](s"avalanche-dest-connect-$poolSuffix", PoolSize))
      transactPool <- EitherT.right(unboundedPool[F](s"avalanche-dest-transact-$poolSuffix"))

      jdbcUri = cfg.connectionUri.toString

      transactor <- EitherT.right[InitializationError[Json]](
        HikariTransactor.newHikariTransactor[F](
          IngresDriverFqcn,
          jdbcUri,
          "dbuser", // username is always dbuser
          cfg.password.value,
          connectPool,
          transactPool))

      (refContainerURL, refresh) <- EitherT.right[InitializationError[Json]](
        Resource.liftF(Azure.refContainerUrl(AvalancheConfig.toConfig(cfg))))

      dest: Destination[F] =
        new AvalancheDestination[F](transactor, refContainerURL, refresh, cfg)

    } yield dest).value

  private def boundedPool[F[_]: Sync](name: String, threadCount: Int): Resource[F, ExecutionContext] =
    Resource.make(
      Sync[F].delay(
        Executors.newFixedThreadPool(
          threadCount,
          NamedDaemonThreadFactory(name))))(es => Sync[F].delay(es.shutdown()))
      .map(ExecutionContext.fromExecutor(_))

  private def unboundedPool[F[_]: Sync](name: String): Resource[F, ExecutionContext] =
    Resource.make(
      Sync[F].delay(
        Executors.newCachedThreadPool(
          NamedDaemonThreadFactory(name))))(es => Sync[F].delay(es.shutdown()))
      .map(ExecutionContext.fromExecutor(_))
}
