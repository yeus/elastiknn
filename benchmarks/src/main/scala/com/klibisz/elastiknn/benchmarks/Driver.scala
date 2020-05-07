package com.klibisz.elastiknn.benchmarks

import java.io.File
import java.util.UUID

import com.klibisz.elastiknn.api._
import com.sksamuel.elastic4s.ElasticDsl._
import zio._
import zio.clock.Clock
import zio.console._
import zio.logging._
import zio.logging.slf4j.Slf4jLogger

import scala.concurrent.duration._

object Driver extends App {

  case class Options(datasetsDirectory: File = new File(s"${System.getProperty("user.home")}/.elastiknn-data"),
                     elasticsearchHost: String = "localhost",
                     elasticsearchPort: Int = 9200,
                     resultsFile: File = new File("/tmp/results.json"),
                     ks: Seq[Int] = Seq(10, 100),
                     holdoutProportion: Double = 0.05)

  private val optionParser = new scopt.OptionParser[Options]("Benchmarks driver") {
    opt[String]('d', "datasetsDirectory").action((s, c) => c.copy(datasetsDirectory = new File(s)))
    opt[String]('h', "elasticsearchHost").action((s, c) => c.copy(elasticsearchHost = s))
    opt[Int]('p', "elasticsearchPort").action((i, c) => c.copy(elasticsearchPort = i))
    opt[String]('o', "resultsFile").action((s, c) => c.copy(resultsFile = new File(s)))
    opt[Double]('h', "holdoutPercentage").action((d, c) => c.copy(holdoutProportion = d))
  }

  private val vectorField: String = "vec"
  private val numCores: Int = java.lang.Runtime.getRuntime.availableProcessors()

  private case class SearchResult(neighborIds: Seq[String], duration: Duration)

  private def recalls(exact: Seq[SearchResult], test: Seq[SearchResult]): Seq[Double] = exact.zip(test).map {
    case (ex, ts) => ex.neighborIds.intersect(ts.neighborIds).length * 1d / ex.neighborIds.length
  }

  private def deleteIndexIfExists(index: String): ZIO[ElastiknnZioClient, Throwable, Unit] =
    for {
      eknnClient <- ZIO.access[ElastiknnZioClient](_.get)
      req = eknnClient.execute(deleteIndex(index))
      _ <- req.map(_ => ()).orElse(ZIO.succeed(()))
    } yield ()

  private def buildIndex(mapping: Mapping, shards: Int, dataset: Dataset, holdoutProportion: Double) = {
    // val theMapping = mapping // ElasticDsl also has a member called mapping.
    val indexName = s"benchmark-${dataset.name}-${UUID.randomUUID.toString}"
    for {
      _ <- deleteIndexIfExists(indexName)
      eknnClient <- ZIO.access[ElastiknnZioClient](_.get)
      _ <- eknnClient.execute(createIndex(indexName).shards(shards).replicas(0))
      _ <- eknnClient.putMapping(indexName, vectorField, mapping)
      datasetClient <- ZIO.access[DatasetClient](_.get)
      stream = datasetClient.stream[Vec](dataset)
      holdout <- stream.grouped(500L).zipWithIndex.foldM(Vector.empty[Vec]) {
        case (acc, (vecs, i)) =>
          val (indexVecs, holdoutVecs) = vecs.partition(_.hashCode.abs % 10 >= 10 * holdoutProportion)
          for {
            (dur, _) <- eknnClient.index(indexName, vectorField, indexVecs).timed
            _ <- log.warn(s"Indexed batch $i containing ${indexVecs.length} vectors in ${dur.toMillis} ms")
          } yield acc ++ holdoutVecs
      }
    } yield (indexName, holdout)
  }

  private def search(index: String, query: NearestNeighborsQuery, holdout: Vector[Vec], k: Int, par: Int = 1) =
    for {
      eknnClient <- ZIO.access[ElastiknnZioClient](_.get)
      _ <- eknnClient.execute(refreshIndex(index))
      requests = holdout.map(query.withVec).zipWithIndex.map {
        case (q, i) =>
          for {
            (dur, res) <- eknnClient.nearestNeighbors(index, q, k).timed
            _ <- log.debug(s"Completed query ${i + 1} of ${holdout.length} in ${dur.toMillis} ms")
          } yield res
      }
      responses <- ZIO.collectAllParN(par)(requests)
    } yield responses.map(r => SearchResult(r.result.hits.hits.map(_.id).toSeq, r.result.took.millis))

  private def run(experiments: Seq[Experiment], ks: Seq[Int], holdoutProportion: Double) =
    ZIO.foreach(experiments) { exp =>
      for {
        (index, holdoutVectors) <- buildIndex(exp.exact.mapping, numCores, exp.dataset, holdoutProportion)
        _ <- ZIO.foreach(ks) { k =>
          for {
            exactResults <- search(index, exp.exact.mkQuery.head(vectorField, holdoutVectors.head, k), holdoutVectors, k, numCores)
            testRuns = for {
              maq <- exp.maqs
              mkQuery <- maq.mkQuery
              emptyQuery = mkQuery(vectorField, Vec.Empty(), k)
              emptyResult = Result(exp.dataset, maq.mapping, emptyQuery, k, Seq.empty, Seq.empty)
            } yield
              for {
                resultClient <- ZIO.access[ResultClient](_.get)
                found <- resultClient.find(emptyResult.dataset, emptyResult.mapping, emptyResult.query, emptyResult.k)
                _ <- if (found.isDefined) ZIO.succeed(())
                else
                  for {
                    (index, _) <- buildIndex(maq.mapping, exp.shards, exp.dataset, holdoutProportion)
                    testResults <- search(index, emptyQuery, holdoutVectors, k)
                    populatedResult: Result = emptyResult.copy(durations = testResults.map(_.duration.toMillis), recalls = recalls(exactResults, testResults))
                    _ <- resultClient.save(populatedResult)
                  } yield ()
              } yield ()
            _ <- ZIO.collectAll(testRuns)
          } yield ()
        }
      } yield ()
    }

  override def run(args: List[String]): ZIO[zio.ZEnv, Nothing, Int] = {
    optionParser.parse(args, Options()) match {
      case Some(opts) =>
        val layer =
          Console.live ++
            Clock.live ++
            Slf4jLogger.make((_, s) => s, Some(getClass.getCanonicalName)) ++
            DatasetClient.local(opts.datasetsDirectory) ++
            ResultClient.local(opts.resultsFile) ++
            ElastiknnZioClient.fromFutureClient(opts.elasticsearchHost, opts.elasticsearchPort, strictFailure = true)
        run(Experiment.defaults.filter(_.dataset == Dataset.AmazonHomePhash), opts.ks, opts.holdoutProportion)
          .provideLayer(layer)
          .mapError(System.err.println)
          .fold(_ => 1, _ => 0)
      case None => sys.exit(1)
    }
  }
}
