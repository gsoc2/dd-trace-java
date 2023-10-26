package datadog.remoteconfig.state;

import datadog.trace.api.Config;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ExtraServicesProvider {

  private static final Logger log = LoggerFactory.getLogger(ExtraServicesProvider.class);

  private static final int MAX_EXTRA_SERVICE = 64;

  private static final ConcurrentHashMap<String, String> extraServices = new ConcurrentHashMap<>();

  public static void maybeAddExtraService(final String serviceName) {
    if (serviceName == null) {
      return;
    }
    if (extraServices.size() >= MAX_EXTRA_SERVICE) {
      log.debug(
          "extra service limit({}) reached: service {} can't be added",
          MAX_EXTRA_SERVICE,
          serviceName);
      return;
    }
    if (!Config.get().getServiceName().equalsIgnoreCase(serviceName)) {
      extraServices.put(serviceName.toLowerCase(), serviceName);
    }
  }

  @Nullable
  public static String[] getExtraServices() {
    return extraServices.isEmpty() ? null : extraServices.values().toArray(new String[0]);
  }

  public static void clear() {
    extraServices.clear();
  }
}
