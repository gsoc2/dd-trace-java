package datadog.smoketest

import datadog.trace.agent.test.server.http.TestHttpServer
import datadog.trace.agent.test.utils.ThreadUtils
import okhttp3.Request
import spock.lang.AutoCleanup
import spock.lang.Ignore
import spock.lang.Shared

import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicInteger

import static datadog.trace.agent.test.server.http.TestHttpServer.httpServer

@Ignore("Fails sometimes when the TestHttp server returns a 'Not Found' https://github.com/DataDog/dd-trace-java/issues/3868")
class PlayNettySmokeTest extends AbstractServerSmokeTest {

  @Shared
  File playDirectory = new File("${buildDirectory}/stage/main")

  @Shared
  @AutoCleanup
  TestHttpServer clientServer = httpServer {
    handlers {
      prefix("/hello") {
        def parts = request.path.split("/")
        int id = parts.length == 0 ? 0 : Integer.parseInt(parts.last())
        String msg = "Hello ${id}!"
        if (id & 4) {
          Thread.sleep(100)
        }
        response.status(200).send(msg)
      }
    }
  }

  @Override
  ProcessBuilder createProcessBuilder() {
    // If the server is not shut down correctly, this file can be left there and will block
    // the start of a new test
    def runningPid = new File(playDirectory.getPath(), "RUNNING_PID")
    if (runningPid.exists()) {
      runningPid.delete()
    }
    ProcessBuilder processBuilder =
      new ProcessBuilder("${playDirectory}/bin/main")
    processBuilder.directory(playDirectory)
    processBuilder.environment().put("JAVA_OPTS",
      defaultJavaProperties.join(" ")
      + " -Dconfig.file=${playDirectory}/conf/application.conf"
      + " -Dhttp.port=${httpPort}"
      + " -Dhttp.address=127.0.0.1"
      + " -Dplay.server.provider=play.core.server.NettyServerProvider"
      + " -Ddd.writer.type=MultiWriter:TraceStructureWriter:${output.getAbsolutePath()},DDAgentWriter"
      + " -Dclient.request.base=${clientServer.address}/hello/")
    return processBuilder
  }

  @Override
  File createTemporaryFile() {
    return new File("${buildDirectory}/tmp/trace-structure-play-2.4-netty.out")
  }

  @Shared
  int totalInvocations = 100

  @Override
  protected boolean isAcceptable(int processIndex, Map<String, AtomicInteger> traceCounts) {
    int totalTraces = 0
    // Since the filters ([filter2-4]) are executed after each other but potentially on different threads, and the future
    // that is completed is completed before the span is finished, the order of those filters and the request processing
    // is undefined.
    boolean isOk = true
    def allowed = /|\[netty.request\[filter1(\[filter\d])?(\[filter\d])?(\[filter\d])?
                   |\[play.request\[action1\[action2\[do-get\[netty.client.request]]]]]
                   |(\[filter\d])?(\[filter\d])?(\[filter\d])?]]/.stripMargin().replaceAll("[\n\r]", "")
    traceCounts.entrySet().each {
      def matcher = (it.key =~ allowed).findAll()
      assert matcher.size() == 1 : """\
           |Trace ${it.key} does not match allowed pattern:
           |pattern=${allowed}
           |traceCounts=${traceCounts}""".stripMargin()
      def matches = matcher.head().findAll{ it != null }
      isOk &= matches.size() == 4
      isOk &= matches.contains("[filter2]")
      isOk &= matches.contains("[filter3]")
      isOk &= matches.contains("[filter4]")
      assert isOk :  """\
           |Trace ${it.key} does not match allowed pattern:
           |pattern=${allowed}
           |traceCounts=${traceCounts}""".stripMargin()
      totalTraces += it.value.get()
    }
    return totalTraces == totalInvocations && isOk
  }

  void doAndValidateRequest(int id) {
    String requestType = id & 1 ? "j" : "s"
    String responseType = requestType.toUpperCase()
    String url = "http://localhost:$httpPort/welcome$requestType?id=$id"
    def request = new Request.Builder().url(url).get().build()
    def response = client.newCall(request).execute()
    def responseBodyStr = response.body().string()
    assert responseBodyStr == "$responseType Got 'Hello $id!'"
    assert response.code() == 200
  }

  def "get welcome endpoint in parallel with filters and action"() {
    expect:
    // Do one request before to initialize the server
    doAndValidateRequest(1)
    ThreadUtils.runConcurrently(10, totalInvocations - 1, {
      def id = ThreadLocalRandom.current().nextInt(1, 4711)
      doAndValidateRequest(id)
    })
    waitForTraceCount(totalInvocations) == totalInvocations
  }
}