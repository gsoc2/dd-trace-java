package datadog.trace.instrumentation.springscheduling;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameStartsWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public final class SpringSchedulingInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForTypeHierarchy {

  public SpringSchedulingInstrumentation() {
    super("spring-scheduling");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".SpringSchedulingDecorator", packageName + ".SpringSchedulingRunnableWrapper",
    };
  }

  @Override
  public String hierarchyMarkerType() {
    return "org.springframework.scheduling.TaskScheduler";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod().and(nameStartsWith("schedule")).and(takesArgument(0, Runnable.class)),
        packageName + ".SpringSchedulingAdvice");
  }
}
