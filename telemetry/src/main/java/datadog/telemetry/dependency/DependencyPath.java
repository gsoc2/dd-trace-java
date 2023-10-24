package datadog.telemetry.dependency;

import java.net.URL;

class DependencyPath {
  public final Type type;
  public final String location;
  public final boolean isInJar;

  private DependencyPath(final Type type, final String location, final boolean isInJar) {
    this.type = type;
    this.location = location;
    this.isInJar = isInJar;
  }

  public static DependencyPath forURL(final URL url) {
    final String path = url.getPath();
    if (path.endsWith(".class")) {
      // Ignore individual classes, since we cannot infer a dependency from them.
      return null;
    }
    final Type type = path.endsWith(".jar") ? Type.JAR : Type.OTHER;
    switch (url.getProtocol()) {
      case "file":
        return new DependencyPath(type, path, false);
      case "jar":
        if (!path.startsWith("file:")) {
          // Operate only over local paths.
          return null;
        }
        // Preserve full URL for jar: protocol.
        return new DependencyPath(type, url.toString(), true);
      case "vfs":
        final String physicalPath = JbossVirtualFileHelper.getJbossVfsPath(url);
        return physicalPath != null ? new DependencyPath(type, physicalPath, false) : null;
      default:
        return null;
    }
  }

  enum Type {
    JAR,
    OTHER,
  }
}
