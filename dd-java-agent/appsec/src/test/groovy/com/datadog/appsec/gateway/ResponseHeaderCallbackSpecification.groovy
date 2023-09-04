package com.datadog.appsec.gateway

import com.datadog.appsec.AppSecSystem
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.test.util.DDSpecification


class ResponseHeaderCallbackSpecification extends DDSpecification {
  def setup() {
    AppSecSystem.active = true
  }

  def cleanup() {
    AppSecSystem.active = false
  }

  void 'collect headers'() {
    given:
    RequestContext ctx = Mock()
    AppSecRequestContext appSecCtx = Mock()
    final cb = new ResponseHeaderCallback()
    final name = 'header-name'
    final value = 'header-value'

    when:
    cb.accept(ctx, name, value)

    then:
    1 * ctx.getData(RequestContextSlot.APPSEC) >> appSecCtx
    1 * appSecCtx.addResponseHeader(name, value)
    0 * _
  }

  void 'does nothing if there is no appsec context'() {
    given:
    RequestContext ctx = Mock()
    final cb = new ResponseHeaderCallback()
    final name = 'header-name'
    final value = 'header-value'

    when:
    cb.accept(ctx, name, value)

    then:
    1 * ctx.getData(RequestContextSlot.APPSEC) >> null
    0 * _
  }
}
