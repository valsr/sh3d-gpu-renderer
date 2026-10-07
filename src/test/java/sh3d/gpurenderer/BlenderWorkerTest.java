package sh3d.gpurenderer;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Tests the worker protocol against shell scripts standing in for Blender.
 */
public class BlenderWorkerTest {
  private static List<String> fake(String script) {
    return Arrays.asList("bash", "-c", script);
  }

  public static void main(String [] args) throws Exception {
    // Replies OK to a first command, ERR to a second one, and mixes in its own chatter
    String talkative = "echo 'Blender 5.2.1'; echo '@@SH3D READY device=HIP';"
        + "read line; echo \"got $line\"; echo '@@SH3D OK';"
        + "read line; echo 'Traceback: boom'; echo '@@SH3D ERR no such file';"
        + "read line";
    BlenderWorker worker = new BlenderWorker(fake(talkative));
    Check.equal("HIP", worker.getDevice(), "device read from READY line");
    worker.send(Map.of("cmd", "load"));
    IOException error = Check.thrown(IOException.class, () -> worker.send(Map.of("cmd", "render")), "ERR reply");
    Check.isTrue(error.getMessage().contains("no such file"), "ERR message reported: " + error.getMessage());
    Check.isTrue(error.getMessage().contains("Traceback: boom"), "Blender output reported: " + error.getMessage());
    Check.isTrue(error.getMessage().contains("got {\"cmd\":\"load\"}"), "command sent as one JSON line: " + error.getMessage());
    worker.quit();
    Check.isTrue(!worker.isAlive(), "process ended by quit");

    error = Check.thrown(IOException.class, () -> new BlenderWorker(fake("echo 'cannot start'; exit 3")), "exit before READY");
    Check.isTrue(error.getMessage().contains("cannot start"), "start failure output reported: " + error.getMessage());

    BlenderWorker dying = new BlenderWorker(fake("echo '@@SH3D READY device=CPU'; read line; echo 'Segfault'"));
    error = Check.thrown(IOException.class, () -> dying.send(Map.of("cmd", "render")), "exit during command");
    Check.isTrue(error.getMessage().contains("Segfault"), "crash output reported: " + error.getMessage());

    // A command blocked in another thread ends when the process is killed
    BlenderWorker hanging = new BlenderWorker(fake("echo '@@SH3D READY device=HIP'; exec sleep 60"));
    Thread killer = new Thread(() -> {
        try {
          Thread.sleep(300);
        } catch (InterruptedException ex) {
        }
        hanging.kill();
      });
    killer.start();
    long start = System.currentTimeMillis();
    Check.thrown(IOException.class, () -> hanging.send(Map.of("cmd", "render")), "killed during command");
    Check.isTrue(System.currentTimeMillis() - start < 5000, "kill unblocks send");
    killer.join();
    Check.isTrue(!hanging.isAlive(), "process ended by kill");
    System.out.println("BlenderWorkerTest OK");
  }
}
