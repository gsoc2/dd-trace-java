package datadog.trace.instrumentation.sslsocket;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;
import static net.bytebuddy.matcher.ElementMatchers.not;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers;
import datadog.trace.bootstrap.instrumentation.usm.UsmConnection;
import datadog.trace.bootstrap.instrumentation.usm.UsmExtractor;
import datadog.trace.bootstrap.instrumentation.usm.UsmMessage;
import datadog.trace.bootstrap.instrumentation.usm.UsmMessageFactory;
import java.net.Inet6Address;
import javax.net.ssl.SSLSocket;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public final class SslSocketInstrumentation extends Instrumenter.Usm
    implements Instrumenter.ForBootstrap, Instrumenter.ForTypeHierarchy {

  public SslSocketInstrumentation() {
    super("sslsocket");
  }

  @Override
  public String hierarchyMarkerType() {
    return null;
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return HierarchyMatchers.extendsClass(named("javax.net.ssl.SSLSocket"))
        .and(not(HierarchyMatchers.abstractClass()));
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod().and(named("close").and(takesArguments(0))),
        SslSocketInstrumentation.class.getName() + "$CloseAdvice");
  }

  public static final class CloseAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void close(@Advice.This final SSLSocket socket) {
      boolean isIPv6 = socket.getLocalAddress() instanceof Inet6Address;
      UsmConnection connection =
          new UsmConnection(
              socket.getLocalAddress(),
              socket.getLocalPort(),
              socket.getInetAddress(),
              socket.getPort(),
              isIPv6);
      UsmMessage message = UsmMessageFactory.Supplier.getCloseMessage(connection);
      UsmExtractor.Supplier.send(message);
    }
  }
}
