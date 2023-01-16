package javaheap;

import soot.Type;

public class JavaNull extends JavaHeapElem {
  private JavaNull() {
    super(null);
  }

  private static JavaNull instance = null;

  public static JavaNull v() {
    if (instance == null) {
      instance = new JavaNull();
    }

    return instance;
  }
}
