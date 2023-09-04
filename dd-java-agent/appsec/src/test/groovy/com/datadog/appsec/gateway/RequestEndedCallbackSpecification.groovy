package com.datadog.appsec.gateway

import com.datadog.appsec.AppSecSystem
import com.datadog.appsec.config.TraceSegmentPostProcessor
import com.datadog.appsec.event.EventProducerService
import com.datadog.appsec.event.EventType
import com.datadog.appsec.report.AppSecEventWrapper
import com.datadog.appsec.report.raw.events.AppSecEvent100
import datadog.trace.api.gateway.Flow
import datadog.trace.api.gateway.IGSpanInfo
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.api.internal.TraceSegment
import datadog.trace.api.time.TimeSource
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.test.util.DDSpecification

class RequestEndedCallbackSpecification extends DDSpecification {
  def setup() {
    AppSecSystem.active = true
  }

  def cleanup() {
    AppSecSystem.active = false
  }

  void 'closes context reports attacks and publishes event'() {
    given:
    AppSecEvent100 event = Mock()
    AppSecRequestContext appSecCtx = Mock()
    TraceSegment traceSegment = Mock()
    RequestContext ctx = Mock()
    RateLimiter rateLimiter = Mock()
    IGSpanInfo spanInfo = Mock(AgentSpan)
    EventProducerService eventProducer = Mock()
    TraceSegmentPostProcessor pp = Mock()
    final cb = new RequestEndedCallback(eventProducer, rateLimiter, [pp])

    when:
    def flow = cb.apply(ctx, spanInfo)

    then:
    1 * ctx.getData(RequestContextSlot.APPSEC) >> appSecCtx
    1 * ctx.getTraceSegment() >> traceSegment
    1 * spanInfo.getTags() >> ['http.client_ip': '1.1.1.1']
    1 * appSecCtx.transferCollectedEvents() >> [event]
    1 * appSecCtx.getRequestHeaders() >> ['accept':['header_value']]
    1 * appSecCtx.getResponseHeaders() >> [
      'some-header': ['123'],
      'content-type': ['text/html; charset=UTF-8']
    ]
    1 * appSecCtx.peerAddress >> '2001::1'
    1 * appSecCtx.close()
    1 * traceSegment.setTagTop('manual.keep', true)
    1 * traceSegment.setTagTop("_dd.appsec.enabled", 1)
    1 * traceSegment.setTagTop("_dd.runtime_family", "jvm")
    1 * traceSegment.setTagTop('appsec.event', true)
    1 * traceSegment.setTagTop('actor.ip', '1.1.1.1')
    1 * traceSegment.setDataTop('appsec', new AppSecEventWrapper([event]))
    1 * traceSegment.setTagTop('http.request.headers.accept', 'header_value')
    1 * traceSegment.setTagTop('http.response.headers.content-type', 'text/html; charset=UTF-8')
    1 * traceSegment.setTagTop('network.client.ip', '2001::1')
    1 * eventProducer.publishEvent(appSecCtx, EventType.REQUEST_END)
    1 * pp.processTraceSegment(traceSegment, appSecCtx, [event])
    1 * rateLimiter.isThrottled() >> false
    0 * _
    flow.result == null
    flow.action == Flow.Action.Noop.INSTANCE
  }

  void 'skip on missing AppSec context'() {
    given:
    RequestContext ctx = Mock()
    IGSpanInfo spanInfo = Mock(AgentSpan)
    RateLimiter rateLimiter = Mock()
    EventProducerService eventProducer = Mock()
    TraceSegmentPostProcessor pp = Mock()
    final cb = new RequestEndedCallback(eventProducer, rateLimiter, [pp])

    when:
    def flow = cb.apply(ctx, spanInfo)

    then:
    1 * ctx.getData(RequestContextSlot.APPSEC) >> null
    0 * _
    flow.result == null
    flow.action == Flow.Action.Noop.INSTANCE
  }

  void 'skip on missing TraceSegment'() {
    given:
    RequestContext ctx = Mock()
    AppSecRequestContext appSecCtx = Mock()
    IGSpanInfo spanInfo = Mock(AgentSpan)
    RateLimiter rateLimiter = Mock()
    EventProducerService eventProducer = Mock()
    TraceSegmentPostProcessor pp = Mock()
    final cb = new RequestEndedCallback(eventProducer, rateLimiter, [pp])

    when:
    def flow = cb.apply(ctx, spanInfo)

    then:
    1 * ctx.getData(RequestContextSlot.APPSEC) >> appSecCtx
    1 * ctx.getTraceSegment() >> null
    1 * eventProducer.publishEvent(appSecCtx, EventType.REQUEST_END)
    1 * appSecCtx.close()
    0 * _
    flow.result == null
    flow.action == Flow.Action.Noop.INSTANCE
  }

  void 'event publishing is rate limited'() {
    given:
    AppSecEvent100 event = Mock()
    AppSecRequestContext appSecCtx = Mock()
    TraceSegment traceSegment = Mock()
    RequestContext ctx = Mock()
    RateLimiter rateLimiter = new RateLimiter(10, { -> 0L } as TimeSource, RateLimiter.ThrottledCallback.NOOP)
    IGSpanInfo spanInfo = Mock(AgentSpan)
    EventProducerService eventProducer = Mock()
    TraceSegmentPostProcessor pp = Mock()
    final cb = new RequestEndedCallback(eventProducer, rateLimiter, [pp])

    when:
    11.times { cb.apply(ctx, spanInfo) }

    then:
    11 * ctx.getData(RequestContextSlot.APPSEC) >> appSecCtx
    11 * ctx.getTraceSegment() >> traceSegment
    11 * appSecCtx.transferCollectedEvents() >> [event]
    11 * appSecCtx.close()
    11 * eventProducer.publishEvent(appSecCtx, EventType.REQUEST_END)
    11 * pp.processTraceSegment(traceSegment, appSecCtx, [event])
    10 * appSecCtx.getRequestHeaders() >> null
    10 * appSecCtx.getResponseHeaders() >> null
    10 * appSecCtx.peerAddress >> '2001::1'
    10 * spanInfo.getTags() >> ['http.client_ip': '1.1.1.1']
    10 * traceSegment.setTagTop('manual.keep', true)
    10 * traceSegment.setTagTop("_dd.appsec.enabled", 1)
    10 * traceSegment.setTagTop('appsec.event', true)
    10 * traceSegment.setDataTop('appsec', _)
    _ * traceSegment.setTagTop(_, _)
    0 * _
  }

  void 'actor ip calculated from headers'() {
    given:
    RequestContext ctx = Mock()
    AppSecRequestContext appSecCtx = Mock()
    IGSpanInfo spanInfo = Mock(AgentSpan)
    TraceSegment traceSegment = Mock()
    RateLimiter rateLimiter = Mock()
    EventProducerService eventProducer = Mock()
    TraceSegmentPostProcessor pp = Mock()
    final cb = new RequestEndedCallback(eventProducer, rateLimiter, [pp])

    when:
    cb.apply(ctx, spanInfo)

    then:
    1 * ctx.getData(RequestContextSlot.APPSEC) >> appSecCtx
    1 * ctx.getTraceSegment() >> traceSegment
    1 * appSecCtx.getRequestHeaders() >> [
      'x-real-ip': ['10.0.0.1'],
      forwarded: ['for=127.0.0.1', 'for="[::1]", for=8.8.8.8'],
    ]
    1 * appSecCtx.transferCollectedEvents() >> [Mock(AppSecEvent100)]
    1 * spanInfo.getTags() >> ['http.client_ip':'8.8.8.8']
    1 * traceSegment.setTagTop('actor.ip', '8.8.8.8')
    _ * _
  }
}
