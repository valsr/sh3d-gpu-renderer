package sh3d.gpurenderer;

import com.eteks.sweethome3d.j3d.AbstractPhotoRenderer;

/**
 * Prints the renderers Sweet Home 3D would list in its dialogs.
 */
public class AgentProbe {
  public static void main(String [] args) {
    System.out.println("RENDERERS " + AbstractPhotoRenderer.getAvailableRenderers());
    System.exit(0);
  }
}
