package javaheap;

import soot.NullType;
import soot.Type;

public class JavaNull extends JavaValue {
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

  @Override
  public boolean equals(Object o) {
    return o instanceof JavaNull;
  }

  @Override
  public String toString() {
    return "null";
  }
}
