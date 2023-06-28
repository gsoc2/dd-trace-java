package datadog.trace.instrumentation.spark;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.Arrays;

class SparkMetricQuantiles {
  private static final int MAX_RETAINED_TASKS = 10000;

  private final long[] metricArray;
  private int currentIndex = 0;

  public SparkMetricQuantiles(int numTasks) {
    int arraySize = Math.max(Math.min(numTasks, MAX_RETAINED_TASKS), 1);
    metricArray = new long[arraySize];
  }

  public void measure(long metricValue) {
    metricArray[currentIndex] = metricValue;
    currentIndex++;

    if (currentIndex >= metricArray.length) {
      currentIndex = 0;
    }
  }

  public void computeQuantiles() {
    Arrays.sort(metricArray);
  }

  public long getMinValue() {
    return metricArray[0];
  }

  public long getMaxValue() {
    return metricArray[metricArray.length - 1];
  }

  public long getValueAtQuantile(double quantile) {
    int index = (int) (metricArray.length * quantile);
    if (index >= metricArray.length) {
      index = metricArray.length - 1;
    }

    return metricArray[index];
  }

  public void setSpanMetrics(AgentSpan span, String name) {
    span.setMetric(name + ".min", getMinValue());
    span.setMetric(name + ".p25", getValueAtQuantile(0.25));
    span.setMetric(name + ".p50", getValueAtQuantile(0.5));
    span.setMetric(name + ".p75", getValueAtQuantile(0.75));
    span.setMetric(name + ".max", getMaxValue());
  }
}
