package org.elasticsearch.elastiknn

import java.util
import java.util.Collections.singletonMap
import java.util.function.Supplier

import org.elasticsearch.client.Client
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver
import org.elasticsearch.cluster.node.DiscoveryNodes
import org.elasticsearch.cluster.service.ClusterService
import org.elasticsearch.common.io.stream.NamedWriteableRegistry
import org.elasticsearch.common.settings.{ClusterSettings, IndexScopedSettings, Settings, SettingsFilter}
import org.elasticsearch.common.xcontent.NamedXContentRegistry
import org.elasticsearch.elastiknn.mapper.ElastiKnnVectorFieldMapper
import org.elasticsearch.elastiknn.processor.IngestProcessor
import org.elasticsearch.elastiknn.query.{KnnExactQueryBuilder, KnnLshQueryBuilder, KnnQueryBuilder, RadiusQueryBuilder}
import org.elasticsearch.env.{Environment, NodeEnvironment}
import org.elasticsearch.index.mapper.Mapper
import org.elasticsearch.ingest.Processor
import org.elasticsearch.plugins.SearchPlugin.QuerySpec
import org.elasticsearch.plugins._
import org.elasticsearch.rest.{RestController, RestHandler}
import org.elasticsearch.script.ScriptService
import org.elasticsearch.threadpool.ThreadPool
import org.elasticsearch.watcher.ResourceWatcherService

class ElastiKnnPlugin(settings: Settings) extends Plugin with IngestPlugin with SearchPlugin with ActionPlugin with MapperPlugin {

  override def createComponents(client: Client,
                                clusterService: ClusterService,
                                threadPool: ThreadPool,
                                resourceWatcherService: ResourceWatcherService,
                                scriptService: ScriptService,
                                xContentRegistry: NamedXContentRegistry,
                                environment: Environment,
                                nodeEnvironment: NodeEnvironment,
                                namedWriteableRegistry: NamedWriteableRegistry): util.Collection[Object] = {
    util.Collections.emptyList[Object]()
  }

  override def getProcessors(parameters: Processor.Parameters): util.Map[String, Processor.Factory] =
    singletonMap(IngestProcessor.TYPE, new IngestProcessor.Factory)

  override def getQueries: util.List[SearchPlugin.QuerySpec[_]] = util.Arrays.asList(
    new QuerySpec(KnnQueryBuilder.NAME, KnnQueryBuilder.Reader, KnnQueryBuilder.Parser),
    new QuerySpec(KnnExactQueryBuilder.NAME, KnnExactQueryBuilder.Reader, KnnExactQueryBuilder.Parser),
    new QuerySpec(KnnLshQueryBuilder.NAME, KnnLshQueryBuilder.Reader, KnnLshQueryBuilder.Parser),
    new QuerySpec(RadiusQueryBuilder.NAME, RadiusQueryBuilder.Reader, RadiusQueryBuilder.Parser)
  )

  override def getRestHandlers(settings: Settings,
                               restController: RestController,
                               clusterSettings: ClusterSettings,
                               indexScopedSettings: IndexScopedSettings,
                               settingsFilter: SettingsFilter,
                               indexNameExpressionResolver: IndexNameExpressionResolver,
                               nodesInCluster: Supplier[DiscoveryNodes]): util.List[RestHandler] =
    util.Arrays.asList(new rest.SetupRestAction(restController))

  override def getMappers: util.Map[String, Mapper.TypeParser] =
    singletonMap(ElastiKnnVectorFieldMapper.CONTENT_TYPE, new ElastiKnnVectorFieldMapper.TypeParser)

}