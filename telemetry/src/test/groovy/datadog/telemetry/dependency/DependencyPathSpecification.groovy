package datadog.telemetry.dependency

import datadog.trace.test.util.DDSpecification

class DependencyPathSpecification extends DDSpecification {

  void 'forURL returns null for unsupported cases'() {
    when:
    def dep = DependencyPath.forURL(new URL(url))

    then:
    dep == null

    where:
    url                          | _
    'https://example.com/my.jar' | _
    'file:///foo.class'          | _
  }

  void 'forURL works with spaces'() {
    given:
    def url = new URL("file:/C:/Program Files/IBM/WebSphere/AppServer_1/plugins/com.ibm.cds_2.1.0.jar")

    when:
    def path = DependencyPath.forURL(url)

    then:
    !path.isInJar
    path.type == DependencyPath.Type.JAR
    path.location == "/C:/Program Files/IBM/WebSphere/AppServer_1/plugins/com.ibm.cds_2.1.0.jar"
  }

  void 'forURL works with directory nested in jar'() {
    given:
    def url = new URL("jar:file:/Users/test/spring-petclinic.jar!/BOOT-INF/classes!/")

    when:
    def path = DependencyPath.forURL(url)

    then:
    path.isInJar
    path.type == DependencyPath.Type.OTHER
    path.location == "jar:file:/Users/test/spring-petclinic.jar!/BOOT-INF/classes!/"
  }
}
