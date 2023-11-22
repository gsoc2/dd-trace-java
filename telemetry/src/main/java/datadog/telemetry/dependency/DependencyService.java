package datadog.telemetry.dependency;

import datadog.trace.util.AgentTaskScheduler;
import java.lang.instrument.Instrumentation;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Service that detects app dependencies from classloading by using a no-op class-file transformer
 */
public class DependencyService implements Runnable {

  private static final Logger log = LoggerFactory.getLogger(DependencyService.class);

  private final DependencyResolverQueue resolverQueue = new DependencyResolverQueue();

  private final BlockingQueue<Dependency> newDependencies = new LinkedBlockingQueue<>();

  private AgentTaskScheduler.Scheduled<Runnable> scheduledTask;

  public void schedulePeriodicResolution() {
    scheduledTask =
        AgentTaskScheduler.INSTANCE.scheduleAtFixedRate(
            AgentTaskScheduler.RunnableTask.INSTANCE, this, 0, 1000L, TimeUnit.MILLISECONDS);
  }

  public void resolveOneDependency() {
    List<Dependency> dependencies = resolverQueue.pollDependency();
    if (!dependencies.isEmpty()) {
      for (Dependency dependency : dependencies) {
        log.debug("Resolved dependency {}", dependency.name);
        newDependencies.add(dependency);
      }
    }
  }

  /**
   * Registers this service as a no-op class file transformer.
   *
   * @param instrumentation instrumentation instance to register on
   */
  public void installOn(Instrumentation instrumentation) {
    instrumentation.addTransformer(new LocationsCollectingTransformer(this));
  }

  public Collection<Dependency> drainDeterminedDependencies() {
    List<Dependency> list = new LinkedList<>();
    int drained = newDependencies.drainTo(list);
    if (drained > 0) {
      return list;
    }
    return Collections.emptyList();
  }

  public void add(final DependencyPath dependencyPath) {
    resolverQueue.add(dependencyPath);
  }

  @Override
  public void run() {
    resolveOneDependency();
  }

  public void stop() {
    if (scheduledTask != null) {
      scheduledTask.cancel();
      scheduledTask = null;
    }
  }
}
