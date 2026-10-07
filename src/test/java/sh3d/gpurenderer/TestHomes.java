package sh3d.gpurenderer;

import com.eteks.sweethome3d.io.DefaultFurnitureCatalog;
import com.eteks.sweethome3d.model.CatalogLight;
import com.eteks.sweethome3d.model.CatalogPieceOfFurniture;
import com.eteks.sweethome3d.model.FurnitureCategory;
import com.eteks.sweethome3d.model.Home;
import com.eteks.sweethome3d.model.HomeLight;
import com.eteks.sweethome3d.model.HomePieceOfFurniture;
import com.eteks.sweethome3d.model.Room;
import com.eteks.sweethome3d.model.Wall;

/**
 * Homes built in code for tests.
 */
final class TestHomes {
  private TestHomes() {
  }

  /**
   * Returns a lamp of the default catalog, with or without light source materials.
   */
  static HomeLight createLamp(boolean withLightSourceMaterials) {
    for (FurnitureCategory category : new DefaultFurnitureCatalog().getCategories()) {
      for (CatalogPieceOfFurniture piece : category.getFurniture()) {
        if (piece instanceof CatalogLight
            && ((CatalogLight)piece).getLightSources().length > 0
            && (((CatalogLight)piece).getLightSourceMaterialNames().length > 0) == withLightSourceMaterials) {
          return new HomeLight((CatalogLight)piece);
        }
      }
    }
    return null;
  }

  static HomePieceOfFurniture createPiece(String name) {
    for (FurnitureCategory category : new DefaultFurnitureCatalog().getCategories()) {
      for (CatalogPieceOfFurniture piece : category.getFurniture()) {
        if (!(piece instanceof CatalogLight) && !piece.isDoorOrWindow()
            && (name == null || name.equals(piece.getName()))) {
          return new HomePieceOfFurniture(piece);
        }
      }
    }
    return null;
  }

  /**
   * Returns a home with a 5 m x 4 m room closed by a ceiling, a piece of furniture
   * and <code>lamp</code> if it's not <code>null</code>.
   */
  static Home createRoomHome(HomeLight lamp) {
    Home home = new Home();
    float [][] corners = {{0, 0}, {500, 0}, {500, 400}, {0, 400}};
    for (int i = 0; i < corners.length; i++) {
      float [] start = corners [i];
      float [] end = corners [(i + 1) % corners.length];
      home.addWall(new Wall(start [0], start [1], end [0], end [1], 10, home.getWallHeight()));
    }
    Room room = new Room(corners);
    room.setFloorVisible(true);
    room.setCeilingVisible(true);
    home.addRoom(room);
    HomePieceOfFurniture piece = createPiece(null);
    piece.setX(350);
    piece.setY(250);
    home.addPieceOfFurniture(piece);
    if (lamp != null) {
      lamp.setX(200);
      lamp.setY(300);
      lamp.setPower(0.5f);
      home.addPieceOfFurniture(lamp);
    }
    return home;
  }
}
