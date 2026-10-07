package sh3d.gpurenderer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * A running Blender process answering commands sent as JSON lines.
 * Lines of its output starting with {@link #PROTOCOL_PREFIX} are replies,
 * the other ones are kept to explain failures.
 */
class BlenderWorker {
  static final String PROTOCOL_PREFIX = "@@SH3D ";

  private static final int LOG_TAIL_SIZE = 25;

  private final Process        process;
  private final BufferedReader output;
  private final OutputStream   input;
  private final Deque<String>  logTail = new ArrayDeque<String>();
  private final String         device;

  /**
   * Starts <code>command</code> and waits until it's ready to receive commands.
   */
  BlenderWorker(List<String> command) throws IOException {
    this.process = new ProcessBuilder(command).redirectErrorStream(true).start();
    this.output = new BufferedReader(new InputStreamReader(this.process.getInputStream(), StandardCharsets.UTF_8));
    this.input = this.process.getOutputStream();
    try {
      String ready = readReply();
      if (!ready.startsWith("READY device=")) {
        throw new IOException("Unexpected reply from Blender: " + ready);
      }
      this.device = ready.substring("READY device=".length()).trim();
    } catch (IOException ex) {
      kill();
      throw ex;
    }
  }

  /**
   * Returns the device Cycles renders with, <code>CPU</code> if no GPU is usable.
   */
  String getDevice() {
    return this.device;
  }

  /**
   * Sends <code>command</code> and waits until Blender has executed it.
   * @throws IOException if Blender reported an error or exited
   */
  void send(Map<String, ?> command) throws IOException {
    try {
      this.input.write((Json.write(command) + "\n").getBytes(StandardCharsets.UTF_8));
      this.input.flush();
    } catch (IOException ex) {
      throw new IOException("Blender exited" + getLog(), ex);
    }
    String reply = readReply();
    if (reply.startsWith("ERR")) {
      throw new IOException("Blender error: " + reply.substring(3).trim() + getLog());
    } else if (!reply.equals("OK")) {
      throw new IOException("Unexpected reply from Blender: " + reply);
    }
  }

  private String readReply() throws IOException {
    String line;
    while ((line = this.output.readLine()) != null) {
      if (line.startsWith(PROTOCOL_PREFIX)) {
        return line.substring(PROTOCOL_PREFIX.length());
      }
      if (this.logTail.size() == LOG_TAIL_SIZE) {
        this.logTail.removeFirst();
      }
      this.logTail.addLast(line);
    }
    throw new IOException("Blender exited" + getLog());
  }

  private String getLog() {
    return this.logTail.isEmpty()
        ? ""
        : "\n" + String.join("\n", this.logTail);
  }

  boolean isAlive() {
    return this.process.isAlive();
  }

  /**
   * Asks Blender to quit and makes sure its process ended.
   */
  void quit() {
    try {
      this.input.write((Json.write(Map.of("cmd", "quit")) + "\n").getBytes(StandardCharsets.UTF_8));
      this.input.close();
      this.process.waitFor(5, TimeUnit.SECONDS);
    } catch (IOException ex) {
      // Already gone
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
    }
    kill();
  }

  /**
   * Ends Blender process at once. May be called from any thread.
   */
  void kill() {
    this.process.destroyForcibly();
    try {
      this.process.waitFor(5, TimeUnit.SECONDS);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
    }
  }
}
