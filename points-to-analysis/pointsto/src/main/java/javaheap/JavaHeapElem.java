package javaheap;

import soot.Type;

public class JavaHeapElem {
  protected final Type type;

  public JavaHeapElem(Type type) {
    this.type = type;
  }

  public Type getType() {
    return type;
  }
}
