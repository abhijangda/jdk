package javaheap;

import soot.NullType;
import soot.Type;

public class JavaNull extends JavaHeapElem {
  private JavaNull() {
    super(NullType.v());
  }

  private static JavaNull instance = null;

  public static JavaNull v() {
    if (instance == null) {
      instance = new JavaNull();
    }

    return instance;
  }
}
