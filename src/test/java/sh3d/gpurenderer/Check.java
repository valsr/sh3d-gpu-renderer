package sh3d.gpurenderer;

/**
 * Minimal assertions for test classes run through their <code>main</code>.
 */
final class Check {
  private Check() {
  }

  static void equal(Object expected, Object actual, String what) {
    if (expected == null ? actual != null : !expected.equals(actual)) {
      throw new AssertionError(what + ": expected <" + expected + "> but was <" + actual + ">");
    }
  }

  static void isTrue(boolean condition, String what) {
    if (!condition) {
      throw new AssertionError(what);
    }
  }

  interface Failing {
    void run() throws Exception;
  }

  /**
   * Returns the exception of class <code>type</code> thrown by <code>code</code> or fails.
   */
  static <T extends Throwable> T thrown(Class<T> type, Failing code, String what) {
    try {
      code.run();
    } catch (Throwable ex) {
      if (type.isInstance(ex)) {
        return type.cast(ex);
      }
      throw new AssertionError(what + ": expected " + type.getSimpleName() + " but got " + ex, ex);
    }
    throw new AssertionError(what + ": expected " + type.getSimpleName() + " but nothing was thrown");
  }
}
