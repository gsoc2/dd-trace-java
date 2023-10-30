package datadog.trace.instrumentation.spark;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.spark.sql.execution.SparkPlanInfo;
import org.apache.spark.sql.execution.metric.SQLMetricInfo;

public class SparkSQLUtils {
  public static void addSQLPlanToStageSpan(
      AgentSpan span,
      SparkPlanInfo sparkPlanInfo,
      HashMap<Long, Integer> accumulators,
      int stageId) {
    SparkPlanInfoForStage planForStage =
        computeStageInfoForStage(sparkPlanInfo, accumulators, stageId, false);

    if (planForStage != null) {
      String json = planForStage.toJson();
      span.setTag("_dd.spark.sql_plan", json);
    }
  }

  public static SparkPlanInfoForStage computeStageInfoForStage(
      SparkPlanInfo info,
      HashMap<Long, Integer> accumulatorToStage,
      int stageId,
      boolean alreadyStarted) {
    Set<Integer> stageIds = stageIdsForPlan(info, accumulatorToStage);

    boolean hasStageInfo = !stageIds.isEmpty();
    boolean isForStage = stageIds.contains(stageId);

    if (alreadyStarted && hasStageInfo && !isForStage) {
      // Stopping the propagation since this node is for another stage
      return null;
    }

    Collection<SparkPlanInfo> children =
        AbstractDatadogSparkListener.listener.getPlanInfoChildren(info);

    if (alreadyStarted || isForStage) {
      // The expected stage was found, adding its children to the plan
      List<SparkPlanInfoForStage> childrenForStage = new ArrayList<>();
      for (SparkPlanInfo child : children) {
        SparkPlanInfoForStage infoForStage =
            computeStageInfoForStage(child, accumulatorToStage, stageId, true);

        if (infoForStage != null) {
          childrenForStage.add(infoForStage);
        }
      }

      return new SparkPlanInfoForStage(info.nodeName(), info.simpleString(), childrenForStage);
    } else {
      // The expected stage was not found, searching in the children nodes
      for (SparkPlanInfo child : children) {
        SparkPlanInfoForStage infoForStage =
            computeStageInfoForStage(child, accumulatorToStage, stageId, false);

        if (infoForStage != null) {
          return infoForStage;
        }
      }
    }

    return null;
  }

  public static Set<Integer> stageIdsForPlan(
      SparkPlanInfo info, HashMap<Long, Integer> accumulatorToStage) {
    Set<Integer> stageIds = new HashSet<>();

    Collection<SQLMetricInfo> metrics =
        AbstractDatadogSparkListener.listener.getPlanInfoMetrics(info);
    for (SQLMetricInfo metric : metrics) {
      Integer stageId = accumulatorToStage.get(metric.accumulatorId());

      if (stageId != null) {
        stageIds.add(stageId);
      }
    }

    return stageIds;
  }

  public static class SparkPlanInfoForStage {
    private final String nodeName;
    private final String simpleString;
    private final List<SparkPlanInfoForStage> children;

    public SparkPlanInfoForStage(
        String nodeName, String simpleString, List<SparkPlanInfoForStage> children) {
      this.nodeName = nodeName;
      this.simpleString = simpleString;
      this.children = children;
    }

    public String toJson() {
      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      ObjectMapper mapper =
          new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
      try {
        JsonGenerator generator = mapper.getFactory().createGenerator(baos);
        this.toJson(generator);
        generator.close();
        baos.close();
        return new String(baos.toByteArray(), StandardCharsets.UTF_8);
      } catch (IOException e) {
        return null;
      }
    }

    private void toJson(JsonGenerator generator) throws IOException {
      generator.writeStartObject();
      generator.writeStringField("node_name", nodeName);
      generator.writeStringField("simple_string", simpleString);

      // Writing child nodes
      if (children.size() > 0) {
        generator.writeFieldName("children");
        generator.writeStartArray();
        for (SparkPlanInfoForStage child : children) {
          child.toJson(generator);
        }
        generator.writeEndArray();
      }

      generator.writeEndObject();
    }
  }
}
