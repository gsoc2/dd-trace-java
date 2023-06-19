package com.datadog.iast.sink

import com.datadog.iast.IastModuleImplTestBase
import com.datadog.iast.IastRequestContext
import com.datadog.iast.model.Vulnerability
import com.datadog.iast.model.VulnerabilityType
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import com.datadog.iast.util.CookieSecurityInfo
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import groovy.transform.CompileDynamic

@CompileDynamic
class NoHttpCookieModuleTest extends IastModuleImplTestBase {

  private List<Object> objectHolder

  private IastRequestContext ctx

  private NoHttpOnlyCookieModuleImpl module

  private AgentSpan span

  def setup() {
    module = registerDependencies(new NoHttpOnlyCookieModuleImpl())
    objectHolder = []
    ctx = new IastRequestContext()
    final reqCtx = Mock(RequestContext) {
      getData(RequestContextSlot.IAST) >> ctx
    }
    span = Mock(AgentSpan) {
      getSpanId() >> 123456
      getRequestContext() >> reqCtx
    }
  }

  void 'report NoHttp cookie with InsecureCookieModule.onCookie'() {
    given:
    Vulnerability savedVul
    final cookie = new CookieSecurityInfo(cookieValue)

    when:
    module.onCookie(cookie)

    then:
    1 * tracer.activeSpan() >> span
    1 * overheadController.consumeQuota(_, _) >> true
    1 * reporter.report(_, _ as Vulnerability) >> { savedVul = it[1] }
    with(savedVul) {
      type == VulnerabilityType.NO_HTTPONLY_COOKIE
      location != null
      with(evidence) {
        value == expected
      }
    }

    where:
    cookieValue | expected
    "user-id=7" | "user-id"
  }

  void 'report insecure cookie with NoHttpOnlyCookieModule.onCookie'() {
    given:
    Vulnerability savedVul
    final cookie = new CookieSecurityInfo(cookieName, true, isHttpOnly, false)

    when:
    module.onCookie(cookie)

    then:
    1 * tracer.activeSpan() >> span
    1 * overheadController.consumeQuota(_, _) >> true
    1 * reporter.report(_, _ as Vulnerability) >> { savedVul = it[1] }
    with(savedVul) {
      type == VulnerabilityType.NO_HTTPONLY_COOKIE
      location != null
      with(evidence) {
        value == expected
      }
    }

    where:
    cookieName | cookieValue | isHttpOnly | expected
    "user-id"  | "7"         | false      | "user-id"
  }

  void 'cases where nothing is reported during NoHttpModuleCookie.onCookie'() {
    given:
    final cookie = new CookieSecurityInfo(cookieValue)

    when:
    module.onCookie(cookie)

    then:
    0 * tracer.activeSpan()
    0 * overheadController._
    0 * reporter._

    where:
    cookieValue           | _
    "user-id=7; HttpOnly=true; Secure" | _
    "user-id=7;HttpOnly=true;Secure"  | _
  }

  void 'insecure no http only is not reported with NoHttpOnlyCookieModule.onCookie'() {
    given:
    final cookie = new CookieSecurityInfo("user-id", true, true, true)

    when:
    module.onCookie(cookie)

    then:
    0 * tracer.activeSpan() >> span
    0 * overheadController.consumeQuota(_, _) >> true
    0 * reporter.report(_, _ as Vulnerability)
  }
}