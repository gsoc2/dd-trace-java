package foo.bar.smoketest;

import jakarta.servlet.http.Part;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

public class MockPart implements Part {
  private final String name;

  private final Map<String, Collection<String>> headers;

  public MockPart(final String name, final Map<String, Collection<String>> headers) {
    this.name = name;
    this.headers = headers;
  }

  public MockPart(final String name, final String headerName, final String... headerValue) {
    this.name = name;
    this.headers = new HashMap<>();
    this.headers.put(headerName, Arrays.asList(headerValue));
  }

  @Override
  public InputStream getInputStream() throws IOException {
    return null;
  }

  @Override
  public String getContentType() {
    return null;
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public String getSubmittedFileName() {
    return null;
  }

  @Override
  public long getSize() {
    return 0;
  }

  @Override
  public void write(String fileName) throws IOException {}

  @Override
  public void delete() throws IOException {}

  @Override
  public String getHeader(String name) {
    final Collection<String> values = this.headers.get(name);
    return values == null || values.isEmpty() ? null : values.iterator().next();
  }

  @Override
  public Collection<String> getHeaders(String name) {
    return headers.get(name);
  }

  @Override
  public Collection<String> getHeaderNames() {
    return this.headers.keySet();
  }
}
