package sh3d.gpurenderer;

import java.lang.instrument.Instrumentation;

/**
 * Declares {@link BlenderRenderer} to Sweet Home 3D when the jar of this class is run as a Java agent
 * with the option <code>-javaagent:gpu-renderer.jar</code>. This option adds the jar to the class path
 * of the application too, which lets Sweet Home 3D find the renderer without changing its launcher.
 */
public final class Agent {
  private static final String RENDERERS_PROPERTY = "com.eteks.sweethome3d.j3d.rendererClassNames";
  // Names of Sweet Home 3D classes not referenced directly to avoid loading them before the application starts
  private static final String DEFAULT_RENDERERS =
      "com.eteks.sweethome3d.j3d.PhotoRenderer,com.eteks.sweethome3d.j3d.YafarayRenderer";

  private Agent() {
  }

  public static void premain(String args, Instrumentation instrumentation) {
    // Keep the renderers chosen by the user if any
    if (System.getProperty(RENDERERS_PROPERTY) == null) {
      System.setProperty(RENDERERS_PROPERTY, DEFAULT_RENDERERS + ",sh3d.gpurenderer.BlenderRenderer");
    }
  }
}
