package datadog.trace.instrumentation.spark;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.spark.scheduler.AccumulableInfo;
import org.apache.spark.sql.execution.SparkPlanInfo;
import org.apache.spark.sql.execution.metric.SQLMetricInfo;
import scala.Tuple2;
import scala.collection.JavaConverters;

public class SparkSQLUtils {

  public static class EnrichedAccumulator {
    private final AccumulableInfo info;
    private final int stageId;

    public EnrichedAccumulator(AccumulableInfo info, int stageId) {
      this.info = info;
      this.stageId = stageId;
    }
  }

  public static class EnrichedSparkPlanInfo {
    private final SparkPlanInfo sparkPlanInfo;
    private final Set<Integer> stageIds = new HashSet<>();
    private final List<EnrichedSparkPlanInfo> children = new ArrayList<>();
    private final List<EnrichedAccumulator> accumulators = new ArrayList<>();

    public EnrichedSparkPlanInfo(
        SparkPlanInfo sparkPlanInfo, HashMap<Long, EnrichedAccumulator> accs) {
      this.sparkPlanInfo = sparkPlanInfo;

      for (SQLMetricInfo metric : JavaConverters.asJavaCollection(sparkPlanInfo.metrics())) {
        EnrichedAccumulator acc = accs.get(metric.accumulatorId());
        if (acc != null) {
          stageIds.add(acc.stageId);
          accumulators.add(acc);
        }
      }

      for (SparkPlanInfo child : JavaConverters.asJavaCollection(sparkPlanInfo.children())) {
        children.add(new EnrichedSparkPlanInfo(child, accs));
      }
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
      generator.writeStringField("node_name", sparkPlanInfo.nodeName());
      generator.writeStringField("simple_string", sparkPlanInfo.simpleString());

      // Writing the list of stage_ids
      if (stageIds.size() > 0) {
        generator.writeFieldName("stage_ids");
        generator.writeStartArray();
        for (int stageId : stageIds) {
          generator.writeNumber(stageId);
        }
        generator.writeEndArray();
      }

      // Writing child nodes
      if (children.size() > 0) {
        generator.writeFieldName("children");
        generator.writeStartArray();
        for (EnrichedSparkPlanInfo child : children) {
          child.toJson(generator);
        }
        generator.writeEndArray();
      }

      // Writing accumulators with their final value
      if (accumulators.size() > 0) {
        generator.writeFieldName("metrics");
        generator.writeStartArray();
        for (EnrichedAccumulator acc : accumulators) {
          if (acc.info.name().isDefined() && acc.info.value().isDefined()) {
            generator.writeStartObject();
            generator.writeStringField("name", acc.info.name().get());
            generator.writeNumberField("value", (Long) acc.info.value().get());
          }
          generator.writeEndObject();
        }
        generator.writeEndArray();
      }

      // Metadata is only present for FileSourceScan nodes
      if (sparkPlanInfo.metadata().size() > 0) {
        generator.writeFieldName("metadata");
        generator.writeStartObject();

        for (Tuple2<String, String> metadata :
            JavaConverters.asJavaCollection(sparkPlanInfo.metadata())) {
          generator.writeStringField(metadata._1, metadata._2);
        }

        generator.writeEndObject();
      }

      generator.writeEndObject();
    }
  }

  public static void addSQLPlanToSpan(
      AgentSpan span,
      SparkPlanInfo sparkPlanInfo,
      HashMap<Long, EnrichedAccumulator> accumulators) {
    EnrichedSparkPlanInfo enriched = new EnrichedSparkPlanInfo(sparkPlanInfo, accumulators);
    String json = enriched.toJson();
    span.setTag("_dd.spark.sql_plan", json);
  }
}
