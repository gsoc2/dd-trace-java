package datadog.smoketest.springboot;

import java.io.IOException;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.impl.client.AutoRetryHttpClient;
import org.apache.http.impl.client.BasicResponseHandler;
import org.apache.http.impl.client.ContentEncodingHttpClient;
import org.apache.http.impl.client.DecompressingHttpClient;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.client.SystemDefaultHttpClient;
import org.apache.http.message.BasicHttpRequest;
import org.apache.http.protocol.BasicHttpContext;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/ssrf")
public class SsrfController {

  @PostMapping("/execute")
  public String apacheSsrf(
      @RequestParam("url") final String url,
      @RequestParam("client") final String client,
      @RequestParam("method") final String method,
      @RequestParam("requestType") final String requestType,
      @RequestParam("scheme") final String scheme) {
    try {
      HttpClient httpClient = getHttpClient(Client.valueOf(client));
      execute(httpClient, url, ExecuteMethod.valueOf(method), requestType, scheme);
    } catch (Exception e) {
    }
    return "OK";
  }

  private void execute(
      final HttpClient client,
      final String url,
      ExecuteMethod executeMethod,
      String requestType,
      String scheme)
      throws IOException {
    HttpUriRequest httpUriRequest = new HttpGet(url);
    boolean isUriRequest = requestType.equals(Request.HttpUriRequest.name());
    HttpHost host =
        new HttpHost(httpUriRequest.getURI().getHost(), httpUriRequest.getURI().getPort(), scheme);
    HttpRequest httpRequest =
        isUriRequest
            ? httpUriRequest
            : new BasicHttpRequest(
                "GET", url.startsWith(scheme) ? url : url.substring(host.toURI().length()));
    switch (executeMethod) {
      case REQUEST:
        client.execute(httpUriRequest);
        break;
      case REQUEST_CONTEXT:
        client.execute(httpUriRequest, new BasicHttpContext());
        break;
      case HOST_REQUEST:
        client.execute(host, httpRequest);
        break;
      case REQUEST_HANDLER:
        client.execute(httpUriRequest, new BasicResponseHandler());
        break;
      case REQUEST_HANDLER_CONTEXT:
        client.execute(httpUriRequest, new BasicResponseHandler(), new BasicHttpContext());
        break;
      case HOST_REQUEST_HANDLER:
        client.execute(host, httpRequest, new BasicResponseHandler());
        break;
      case HOST_REQUEST_HANDLER_CONTEXT:
        client.execute(host, httpRequest, new BasicResponseHandler(), new BasicHttpContext());
        break;
      case HOST_REQUEST_CONTEXT:
        client.execute(host, httpRequest, new BasicHttpContext());
        break;
      default:
        throw new IllegalArgumentException("Unknown execute method: " + executeMethod);
    }
  }

  private HttpClient getHttpClient(final Client client) {
    switch (client) {
      case DefaultHttpClient:
        return new DefaultHttpClient();
      case AutoRetryHttpClient:
        return new AutoRetryHttpClient();
      case ContentEncodingHttpClient:
        return new ContentEncodingHttpClient();
      case DecompressingHttpClient:
        return new DecompressingHttpClient();
      case InternalHttpClient:
        return HttpClientBuilder.create().build();
      case MinimalHttpClient:
        return HttpClients.createMinimal();
      case SystemDefaultHttpClient:
        return new SystemDefaultHttpClient();
      default:
        throw new IllegalArgumentException("Unknown client: " + client);
    }
  }

  public enum Client {
    DefaultHttpClient,
    AutoRetryHttpClient,
    ContentEncodingHttpClient,
    DecompressingHttpClient,
    InternalHttpClient,
    MinimalHttpClient,
    SystemDefaultHttpClient
  }

  public enum Request {
    HttpRequest,
    HttpUriRequest
  }

  private enum ExecuteMethod {
    REQUEST,
    REQUEST_CONTEXT,
    HOST_REQUEST,
    REQUEST_HANDLER,
    REQUEST_HANDLER_CONTEXT,
    HOST_REQUEST_HANDLER,
    HOST_REQUEST_HANDLER_CONTEXT,
    HOST_REQUEST_CONTEXT;
  }
}
