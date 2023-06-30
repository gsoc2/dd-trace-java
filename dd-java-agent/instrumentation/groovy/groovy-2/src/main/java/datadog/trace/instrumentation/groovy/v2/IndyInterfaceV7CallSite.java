package datadog.trace.instrumentation.groovy.v2;

import static datadog.trace.instrumentation.groovy.helper.InvocationHelper.INVOKE_TYPE;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastCallSites;
import datadog.trace.api.iast.Propagation;
import datadog.trace.api.iast.csi.SkipDynamicHelpers;
import datadog.trace.instrumentation.groovy.helper.CallSiteInvoker;
import datadog.trace.instrumentation.groovy.helper.InvocationHelper;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import org.codehaus.groovy.vmplugin.v7.IndyInterface;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Propagation
@CallSite(
    spi = IastCallSites.class,
    helpers = {InvocationHelper.class, CallSiteInvoker.class},
    enabled = {"datadog.trace.api.iast.IastEnabledChecks", "isGroovyIndyEnabled"})
@SkipDynamicHelpers
public class IndyInterfaceV7CallSite {

  private static final Logger LOG = LoggerFactory.getLogger(IndyInterfaceV7CallSite.class);

  @CallSite.Around(
      value =
          "java.lang.invoke.CallSite org.codehaus.groovy.vmplugin.v7.IndyInterface.bootstrap(java.lang.invoke.MethodHandles$Lookup, java.lang.String, java.lang.invoke.MethodType)",
      invokeDynamic = true)
  public static java.lang.invoke.CallSite aroundBootstrap(
      @CallSite.Argument(0) final MethodHandles.Lookup caller,
      @CallSite.Argument(1) final String name,
      @CallSite.Argument(2) final MethodType type) {
    final java.lang.invoke.CallSite result = IndyInterface.bootstrap(caller, name, type);
    if (InvocationHelper.supports(name)) {
      try {
        return InvocationHelper.adaptCallSite(INVOKE_TYPE, name, type, result);
      } catch (final Throwable e) {
        LOG.error("Error handling v7.IndyInterface", e);
      }
    }
    return result;
  }
}