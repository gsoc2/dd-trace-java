
package datadog.trace.instrumentation.jdbc;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/** TODO-hide this instrumentation behind a config variable**/
@AutoService(Instrumenter.class)
public class ConnectionHierarchyInstrumentation extends AbstractConnectionInstrumentation
    implements Instrumenter.ForTypeHierarchy {
  public ConnectionHierarchyInstrumentation() {
    super("jdbc", "jdbcMatcher");
  }

  @Override
  public String hierarchyMarkerType() {
    return "java.sql.Connection";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }
}
