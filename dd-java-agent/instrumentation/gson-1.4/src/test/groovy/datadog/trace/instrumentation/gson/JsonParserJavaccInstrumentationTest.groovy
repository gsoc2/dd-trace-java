package datadog.trace.instrumentation.gson

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.JsonParserJavacc
import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.propagation.PropagationModule

class JsonParserJavaccInstrumentationTest extends AgentTestRunner {

  @Override
  protected void configurePreAgent() {
    injectSysConfig("dd.iast.enabled", "true")
  }

  void 'test'() {
    given:
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    new JsonParser().parse(new StringReader(json))

    then:
    1 * module.taintIfInputIsTainted(_ as JsonParserJavacc, _ as StringReader)
    callsAfterParse * module.taintIfInputIsTainted(_ as String, _ as JsonParserJavacc)
    0 * _

    where:
    json | callsAfterParse
    '"Test"' | 1
    '1' | 0
    '{"name": "nameTest", "value" : "valueTest"}' | 4
    '[{"name": "nameTest", "value" : "valueTest"}]' | 4
    '[{"name": "nameTest", "value" : "valueTest"}, {"name": "nameTest2", "value" : "valueTest2"}]' | 8
  }


  static final class TestBean {

    private String name

    private String value
  }
}