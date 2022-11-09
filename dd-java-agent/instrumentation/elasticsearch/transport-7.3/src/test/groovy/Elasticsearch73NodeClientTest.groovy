import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.test.checkpoints.CheckpointValidator
import datadog.trace.agent.test.checkpoints.CheckpointValidationMode
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import org.elasticsearch.action.admin.cluster.health.ClusterHealthRequest
import org.elasticsearch.common.io.FileSystemUtils
import org.elasticsearch.common.settings.Settings
import org.elasticsearch.index.IndexNotFoundException
import org.elasticsearch.node.InternalSettingsPreparer
import org.elasticsearch.node.Node
import org.elasticsearch.transport.Netty4Plugin
import spock.lang.Retry
import spock.lang.Shared

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static org.elasticsearch.cluster.ClusterName.CLUSTER_NAME_SETTING

@Retry(count = 3, delay = 1000, mode = Retry.Mode.SETUP_FEATURE_CLEANUP)
class Elasticsearch73NodeClientTest extends AgentTestRunner {
  public static final long TIMEOUT = 10000 // 10 seconds

  @Shared
  Node testNode
  @Shared
  File esWorkingDir
  @Shared
  String clusterName = UUID.randomUUID().toString()

  def client = testNode.client()

  def setupSpec() {

    esWorkingDir = File.createTempDir("test-es-working-dir-", "")
    esWorkingDir.deleteOnExit()
    println "ES work dir: $esWorkingDir"

    def settings = Settings.builder()
      .put("path.home", esWorkingDir.path)
      // Since we use listeners to close spans this should make our span closing deterministic which is good for tests
      .put("thread_pool.listener.size", 1)
      .put(CLUSTER_NAME_SETTING.getKey(), clusterName)
      .put("node.name", "test-node")
      .put("transport.type", "netty4")
      .build()
    testNode = new Node(InternalSettingsPreparer.prepareEnvironment(
      settings, [:], null, null), [Netty4Plugin], false) {}
    testNode.start()
    runUnderTrace("setup") {
      // this may potentially create multiple requests and therefore multiple spans, so we wrap this call
      // into a top level trace to get exactly one trace in the result.
      testNode.client().admin().cluster().prepareHealth().setWaitForYellowStatus().execute().actionGet(TIMEOUT)
    }
    TEST_WRITER.waitForTraces(1)
  }

  def cleanupSpec() {
    testNode?.close()
    if (esWorkingDir != null) {
      FileSystemUtils.deleteSubDirectories(esWorkingDir.toPath())
      esWorkingDir.delete()
    }
  }

  def "test elasticsearch status"() {
    setup:
    def result = client.admin().cluster().health(new ClusterHealthRequest()).get()

    def status = result.status

    expect:
    status.name() == "GREEN"

    assertTraces(1) {
      trace(1) {
        span {
          serviceName "elasticsearch"
          resourceName "ClusterHealthAction"
          operationName "elasticsearch.query"
          spanType DDSpanTypes.ELASTICSEARCH
          topLevel true
          tags {
            "$Tags.COMPONENT" "elasticsearch-java"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" "elasticsearch"
            "elasticsearch.action" "ClusterHealthAction"
            "elasticsearch.request" "ClusterHealthRequest"
            defaultTags()
          }
        }
      }
    }
  }

  def "test elasticsearch error"() {
    setup:
    when:
    client.prepareGet(indexName, indexType, id).get()

    then:
    thrown IndexNotFoundException

    and:
    assertTraces(1) {
      trace(1) {
        span {
          serviceName "elasticsearch"
          resourceName "GetAction"
          operationName "elasticsearch.query"
          spanType DDSpanTypes.ELASTICSEARCH
          errored true
          topLevel true
          tags {
            "$Tags.COMPONENT" "elasticsearch-java"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" "elasticsearch"
            "elasticsearch.action" "GetAction"
            "elasticsearch.request" "GetRequest"
            "elasticsearch.request.indices" indexName
            errorTags IndexNotFoundException, "no such index [invalid-index]"
            defaultTags()
          }
        }
      }
    }

    where:
    indexName = "invalid-index"
    indexType = "test-type"
    id = "1"
  }

  def "test elasticsearch get"() {
    setup:
    CheckpointValidator.excludeValidations_DONOTUSE_I_REPEAT_DO_NOT_USE(
      CheckpointValidationMode.INTERVALS,
      CheckpointValidationMode.THREAD_SEQUENCE)

    assert TEST_WRITER == []
    def indexResult = client.admin().indices().prepareCreate(indexName).get()
    TEST_WRITER.waitForTraces(1)

    expect:
    indexResult.index() == indexName
    TEST_WRITER.size() == 1

    when:
    def emptyResult = client.prepareGet(indexName, indexType, id).get()

    then:
    !emptyResult.isExists()
    emptyResult.id == id
    emptyResult.type == indexType
    emptyResult.index == indexName

    when:
    def createResult = client.prepareIndex(indexName, indexType, id).setSource([:]).get()

    then:
    createResult.id == id
    createResult.type == indexType
    createResult.index == indexName
    createResult.status().status == 201

    when:
    def result = client.prepareGet(indexName, indexType, id).get()

    then:
    result.isExists()
    result.id == id
    result.type == indexType
    result.index == indexName

    and:
    assertTraces(5) {
      trace(1) {
        span {
          serviceName "elasticsearch"
          resourceName "CreateIndexAction"
          operationName "elasticsearch.query"
          spanType DDSpanTypes.ELASTICSEARCH
          topLevel true
          tags {
            "$Tags.COMPONENT" "elasticsearch-java"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" "elasticsearch"
            "elasticsearch.action" "CreateIndexAction"
            "elasticsearch.request" "CreateIndexRequest"
            "elasticsearch.request.indices" indexName
            defaultTags()
          }
        }
      }
      trace(1) {
        span {
          serviceName "elasticsearch"
          resourceName "GetAction"
          operationName "elasticsearch.query"
          spanType DDSpanTypes.ELASTICSEARCH
          topLevel true
          tags {
            "$Tags.COMPONENT" "elasticsearch-java"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" "elasticsearch"
            "elasticsearch.action" "GetAction"
            "elasticsearch.request" "GetRequest"
            "elasticsearch.request.indices" indexName
            "elasticsearch.type" indexType
            "elasticsearch.id" "1"
            "elasticsearch.version"(-1)
            defaultTags()
          }
        }
      }
      trace(1) {
        span {
          serviceName "elasticsearch"
          resourceName "IndexAction"
          operationName "elasticsearch.query"
          spanType DDSpanTypes.ELASTICSEARCH
          topLevel true
          tags {
            "$Tags.COMPONENT" "elasticsearch-java"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" "elasticsearch"
            "elasticsearch.action" "IndexAction"
            "elasticsearch.request" "IndexRequest"
            "elasticsearch.request.indices" indexName
            "elasticsearch.request.write.type" indexType
            "elasticsearch.request.write.version"(-3)
            "elasticsearch.response.status" 201
            "elasticsearch.shard.replication.total" 2
            "elasticsearch.shard.replication.successful" 1
            "elasticsearch.shard.replication.failed" 0
            defaultTags()
          }
        }
      }
      trace(1) {
        span {
          serviceName "elasticsearch"
          resourceName "PutMappingAction"
          operationName "elasticsearch.query"
          spanType DDSpanTypes.ELASTICSEARCH
          topLevel true
          tags {
            "$Tags.COMPONENT" "elasticsearch-java"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" "elasticsearch"
            "elasticsearch.action" "PutMappingAction"
            "elasticsearch.request" "PutMappingRequest"
            defaultTags()
          }
        }
      }
      trace(1) {
        span {
          serviceName "elasticsearch"
          resourceName "GetAction"
          operationName "elasticsearch.query"
          spanType DDSpanTypes.ELASTICSEARCH
          topLevel true
          tags {
            "$Tags.COMPONENT" "elasticsearch-java"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" "elasticsearch"
            "elasticsearch.action" "GetAction"
            "elasticsearch.request" "GetRequest"
            "elasticsearch.request.indices" indexName
            "elasticsearch.type" indexType
            "elasticsearch.id" "1"
            "elasticsearch.version" 1
            defaultTags()
          }
        }
      }
    }

    cleanup:
    client.admin().indices().prepareDelete(indexName).get()

    where:
    indexName = "test-index"
    indexType = "test-type"
    id = "1"
  }
}