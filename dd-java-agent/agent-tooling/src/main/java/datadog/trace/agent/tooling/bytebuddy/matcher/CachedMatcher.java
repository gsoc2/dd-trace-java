package datadog.trace.agent.tooling.bytebuddy.matcher;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import datadog.trace.agent.tooling.bytebuddy.matcher.jfr.KnownClassesLoaderEvents;
import datadog.trace.agent.tooling.matchercache.MatcherCache;
import datadog.trace.util.AgentTaskScheduler;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import javax.annotation.Nullable;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CachedMatcher<T extends TypeDescription>
    extends ElementMatcher.Junction.ForNonNullValues<T> {
  private static final Logger log = LoggerFactory.getLogger(CachedMatcher.class);

  private static final MatcherCache.EventListener CACHE_EVENT_LISTENER =
      new MatcherCache.EventListener() {
        @Override
        public void cacheMiss(String fqcn) {
          int packageEndsAt = fqcn.lastIndexOf('.');
          String packageName = fqcn.substring(0, Math.max(packageEndsAt, 0));
          KnownClassesLoaderEvents.get().classCachedMatcherEvent(fqcn).finish(false, packageName);
        }
      };

  @Nullable
  public static <T extends TypeDescription> ElementMatcher.Junction<T> create(
      String preBuiltMatcherData) {
    File cacheFile = new File(preBuiltMatcherData);
    MatcherCache matcherCache = null;
    try (InputStream is = Files.newInputStream(cacheFile.toPath())) {
      long startAt = System.nanoTime();
      matcherCache = MatcherCache.deserialize(is, CACHE_EVENT_LISTENER);
      long durationNs = System.nanoTime() - startAt;
      log.info("Loaded pre-built matcher data in ms: {}", durationNs / 1_000_000);
      commitCacheLoadingTimeEvent(durationNs);
    } catch (Throwable e) {
      log.error("Failed to load pre-build ignores matcher data from: " + preBuiltMatcherData, e);
    }
    return matcherCache == null ? null : new CachedMatcher<T>(matcherCache);
  }

  private final MatcherCache matcherCache;

  public CachedMatcher(MatcherCache matcherCache) {
    this.matcherCache = matcherCache;
  }

  @Override
  protected boolean doMatch(T target) {
    String fqcn = target.getActualName();
    return !matcherCache.transform(fqcn);
  }

  private static void commitCacheLoadingTimeEvent(final long durationNs) {
    // delay committing event because JFR could not yet initialized and committing immediately will
    // void it
    AgentTaskScheduler.INSTANCE.schedule(
        new AgentTaskScheduler.Task<Boolean>() {
          @Override
          public void run(Boolean target) {
            KnownClassesLoaderEvents.get().commitEvent(durationNs, -1);
          }
        },
        true,
        1500,
        MILLISECONDS);
  }
}
