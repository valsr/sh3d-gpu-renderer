package sh3d.gpurenderer;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Tests that Sweet Home 3D lists the renderer when its jar is only given as a Java agent.
 */
public class AgentTest {
  private static String run(String... options) throws Exception {
    // Run a JVM with the class path of this test without the renderer jar
    List<String> classPath = new ArrayList<String>();
    String jar = null;
    for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
      if (entry.endsWith("gpu-renderer.jar")) {
        jar = new File(entry).getAbsolutePath();
      } else {
        classPath.add(entry);
      }
    }
    Check.isTrue(jar != null, "renderer jar in class path");
    List<String> command = new ArrayList<String>(Arrays.asList(
        System.getProperty("java.home") + "/bin/java", "-javaagent:" + jar,
        "-Djava.library.path=" + System.getProperty("java.library.path"),
        "--add-opens=java.desktop/sun.awt=ALL-UNNAMED",
        "-Dsh3d.gpurenderer.blender=" + System.getProperty("sh3d.gpurenderer.blender", "blender"),
        "-cp", String.join(File.pathSeparator, classPath)));
    command.addAll(Arrays.asList(options));
    command.add(AgentProbe.class.getName());
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    process.waitFor();
    for (String line : output.split("\n")) {
      if (line.startsWith("RENDERERS ")) {
        return line.substring("RENDERERS ".length());
      }
    }
    throw new AssertionError("No renderers listed:\n" + output);
  }

  public static void main(String [] args) throws Exception {
    String renderers = run();
    Check.isTrue(renderers.startsWith("[com.eteks.sweethome3d.j3d.PhotoRenderer"), "default renderer still first: " + renderers);
    Check.isTrue(renderers.endsWith("sh3d.gpurenderer.BlenderRenderer]"), "renderer added by agent: " + renderers);

    renderers = run("-Dcom.eteks.sweethome3d.j3d.rendererClassNames=com.eteks.sweethome3d.j3d.PhotoRenderer");
    Check.equal("[com.eteks.sweethome3d.j3d.PhotoRenderer]", renderers, "renderers chosen by the user kept");
    System.out.println("AgentTest OK");
  }
}
