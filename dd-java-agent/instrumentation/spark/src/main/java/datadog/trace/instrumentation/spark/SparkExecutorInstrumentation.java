package datadog.trace.instrumentation.spark;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import org.apache.spark.executor.Executor;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static net.bytebuddy.matcher.ElementMatchers.isDeclaredBy;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

@AutoService(Instrumenter.class)
public class SparkExecutorInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForKnownTypes {

  public SparkExecutorInstrumentation() {
    super("spark", "apache-spark");
  }

  @Override
  public boolean defaultEnabled() {
    return false;
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
        "org.apache.spark.executor.Executor$TaskRunner"
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(named("run"))
            .and(isDeclaredBy(named("org.apache.spark.executor.Executor$TaskRunner"))),
        SparkExecutorInstrumentation.class.getName() + "$RunAdvice");
  }

  public static final class RunAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope enter(@Advice.This Executor.TaskRunner taskRunner) {
      final AgentSpan span = startSpan("spark", "spark.task");

      span.setTag("task_id", taskRunner.taskId());
      span.setTag("spark_thread_name", taskRunner.threadName());

      return activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(@Advice.Enter final AgentScope scope, @Advice.This Executor.TaskRunner taskRunner) {
      if (scope == null) {
        return;
      }

      AgentSpan span = scope.span();
      if (taskRunner.task() != null) {

        span.setTag("stage_id", taskRunner.task().stageId());
        span.setTag("stage_attempt_id", taskRunner.task().stageAttemptId());

        if (taskRunner.task().jobId().isDefined()) {
          span.setTag("job_id", taskRunner.task().jobId().get());
        }
        if (taskRunner.task().appId().isDefined()) {
          span.setTag("app_id", taskRunner.task().appId().get());
        }
      }

      scope.close();
      span.finish();
    }
  }
}
