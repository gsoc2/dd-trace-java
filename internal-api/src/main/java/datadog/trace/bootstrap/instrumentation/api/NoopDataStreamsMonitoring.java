package datadog.trace.bootstrap.instrumentation.api;

import java.util.LinkedHashMap;

public class NoopDataStreamsMonitoring implements DataStreamsMonitoring {
  @Override
  public void start() {}

  @Override
  public void accept(StatsPoint statsPoint) {}

  @Override
  public PathwayContext newPathwayContext() {
    return AgentTracer.NoopPathwayContext.INSTANCE;
  }

  @Override
  public <C> PathwayContext extractPathwayContext(
      C carrier, AgentPropagation.ContextVisitor<C> getter) {
    return AgentTracer.NoopPathwayContext.INSTANCE;
  }

  @Override
  public void trackBacklog(LinkedHashMap<String, String> sortedTags, long value) {}

  @Override
  public <C> PathwayContext extractBinaryPathwayContext(
      C carrier, AgentPropagation.BinaryContextVisitor<C> getter) {
    return AgentTracer.NoopPathwayContext.INSTANCE;
  }

  @Override
  public void close() {}

  @Override
  public void clear() {}
}
