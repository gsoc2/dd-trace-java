package datadog.trace.instrumentation.java.lang

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.config.TracerConfig
import datadog.trace.api.iast.IastModule
import datadog.trace.api.iast.InstrumentationBridge
import foo.bar.TestStringSuite

import static org.hamcrest.CoreMatchers.sameInstance

class StringCallSiteTest extends AgentTestRunner {

  final iastModule = Mock(IastModule)

  @Override
  protected void configurePreAgent() {
    injectSysConfig(TracerConfig.SCOPE_ITERATION_KEEP_ALIVE, "1") // don't let iteration spans linger
    injectSysConfig("dd.iast.enabled", "true")
  }

  void setup() {
    InstrumentationBridge.registerIastModule(iastModule)
  }

  def 'test string concat call site'() {
    setup:
    final iastModule = Mock(IastModule)
    InstrumentationBridge.registerIastModule(iastModule)

    when:
    final result = TestStringSuite.concat('Hello ', 'World!')

    then:
    result == 'Hello World!'
    1 * iastModule.onStringConcat('Hello ', 'World!', 'Hello World!')
    0 * _
  }

  def 'test String constructor with CharSequence'() {
    setup:
    String result
    String passedResult

    when:
    result = TestStringSuite.stringConstructor(arg)

    then:
    result == 'My String'
    !result.is(arg)
    1 * iastModule.onStringConstructor(sameInstance(arg), _ as String) >> { passedResult = it[1] }
    result.is(passedResult)
    0 * _

    where:
    arg << ['My String', new StringBuilder('My String'), new StringBuffer('My String')]
  }
}