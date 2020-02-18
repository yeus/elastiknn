package com.klibisz.elastiknn.benchmarks

import java.io.{File, FileReader}
import java.net.URL
import java.util.UUID

import com.klibisz.elastiknn.ProcessorOptions.ModelOptions
import com.klibisz.elastiknn.client.ElastiKnnClient
import com.klibisz.elastiknn.utils.ElastiKnnVectorUtils
import com.klibisz.elastiknn.{JaccardLshOptions, ProcessorOptions, TestData}
import com.sksamuel.elastic4s.ElasticDsl._
import com.sksamuel.elastic4s.requests.common.RefreshPolicy
import com.typesafe.scalalogging.LazyLogging
import io.circe.parser.decode
import io.circe.yaml

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

object Runner extends LazyLogging with ElastiKnnVectorUtils {

  final case class CLIArgs(host: String = "localhost",
                           port: Int = 9200,
                           benchmarkFile: URL = getClass.getResource("benchmarks.yaml"),
                           datasets: Seq[String] = Seq("kosarak"),
                           duration: Duration = Duration("8 hours"))

  private val cliParser = new scopt.OptionParser[CLIArgs]("benchmark runner") {
    opt[String]('h', "host").action((s, a) => a.copy(host = s))
    opt[Int]('p', "port").action((i, a) => a.copy(port = i))
    opt[String]('f', "file").action((s, a) => a.copy(benchmarkFile = new URL(s)))
    opt[Seq[String]]('d', "datasets").action((ss, a) => a.copy(datasets = ss))
  }

  final case class BenchmarkResult()

  private def paramsToModelOpts(space: ParameterSpace): Seq[ModelOptions] = space match {
    case ParameterSpace.JaccardLSH(tables, bands, rows) =>
      for {
        t <- tables
        b <- bands
        r <- rows
      } yield ModelOptions.Jaccard(JaccardLshOptions(0, fieldProcessed = "vec_proc", t, b, r))
  }

  private def apply(testData: TestData, shards: Double, queries: Double, modelOptions: ModelOptions)(
      implicit ec: ExecutionContext,
      client: ElastiKnnClient): Future[Unit] = {
    val index = s"benchmark-${UUID.randomUUID}"
    val pipeline = s"process-$index"
    val rawField = "vec_raw"
    val numProcs = Runtime.getRuntime.availableProcessors
    val numShards = shards match {
      case d if d < 0                 => numProcs + d.toInt
      case m if m.toInt.toDouble != m => (numProcs * m).floor.toInt
      case _                          => shards.toInt
    }
    val ids = testData.corpus.indices.map(_.toString)
    for {
      dim <- Future.fromTry(testData.corpus.head.dimensions)
      _ <- client.execute(createIndex(index).shards(numShards))
      _ <- client.createPipeline(pipeline, ProcessorOptions(rawField, dim, modelOptions))
      _ <- Future.traverse(testData.corpus.zip(ids).grouped(10)) { pairs =>
        val (vecs, ids) = (pairs.map(_._1), pairs.map(_._2))
        client.indexVectors(index, pipeline, rawField, vecs, Some(ids), RefreshPolicy.IMMEDIATE)
      }
    } yield Future.successful(())
  }

  private def apply(bdef: BenchmarkDefinition)(implicit ec: ExecutionContext, client: ElastiKnnClient): Future[Seq[Unit]] =
    parseTestData(bdef.dataset) match {
      case Success(testData) =>
        // This hacky setup makes sure they run one at a time.
        val runs: Seq[() => Future[Unit]] = for {
          shards <- bdef.shards
          queries <- bdef.queryParallelism
          modelOpts <- paramsToModelOpts(bdef.space)
        } yield () => apply(testData, shards, queries, modelOpts)
        Future.traverse(runs)(run => run())
      case Failure(t) => Future.failed(t)
    }

  private val annBenchmarksRoot: File = new File(s"${System.getProperty("user.home")}/.ann-benchmarks")

  private def parseTestData(dataset: String): Try[TestData] = dataset match {
    case "kosarak" =>
      val src = scala.io.Source.fromFile(s"$annBenchmarksRoot/kosarak-jaccard.json")
      val raw = try src.mkString
      finally src.close()
      io.circe.parser.decode[TestData](raw).toTry
    case other => Failure(new IllegalArgumentException(s"Unknown dataset: $other"))
  }

  private def parseDefinitions(cliArgs: CLIArgs): Try[Seq[BenchmarkDefinition]] =
    (for {
      parsed <- yaml.parser.parse(new FileReader(cliArgs.benchmarkFile.getFile))
      decoded <- decode[Seq[BenchmarkDefinition]](parsed.noSpaces)
      matching = decoded.filter(d => cliArgs.datasets.contains(d.dataset))
    } yield matching).toTry

  def main(args: Array[String]): Unit = cliParser.parse(args, CLIArgs()) match {
    case Some(cliArgs) =>
      implicit val ec: ExecutionContext = ExecutionContext.global
      implicit val client: ElastiKnnClient = ElastiKnnClient()
      lazy val pipeline = for {
        bdefs <- Future.fromTry(parseDefinitions(cliArgs))
        _ = logger.info(s"Parsed ${bdefs.length} benchmark definitions:\n ${bdefs.mkString("\n")}")
        _ <- Future.traverse(bdefs)(apply)
      } yield ()
      try Await.result(pipeline, Duration.Inf)
      finally client.close()
    case None => System.exit(1)
  }

}