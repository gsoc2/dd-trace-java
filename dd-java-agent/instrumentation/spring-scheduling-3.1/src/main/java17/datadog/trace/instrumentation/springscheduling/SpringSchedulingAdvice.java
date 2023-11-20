package datadog.trace.instrumentation.springscheduling;

import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import net.bytebuddy.asm.Advice;
import org.springframework.scheduling.TaskScheduler;

public class SpringSchedulingAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static boolean beforeSchedule(
      @Advice.Argument(value = 0, readOnly = false) Runnable runnable) {
    if (CallDepthThreadLocalMap.incrementCallDepth(TaskScheduler.class) > 0) {
      return false;
    }
    runnable = SpringSchedulingRunnableWrapper.wrapIfNeeded(runnable);
    return true;
  }

  @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
  public static void afterSchedule(
      @Advice.Enter boolean reset,
      @Advice.Argument(value = 0, readOnly = false) Runnable runnable) {
    if (reset) {
      CallDepthThreadLocalMap.reset(TaskScheduler.class);
    }
  }
}
