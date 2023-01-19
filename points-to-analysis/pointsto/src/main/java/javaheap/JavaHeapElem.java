package javaheap;

import soot.NullType;
import soot.RefLikeType;
import soot.Type;
import utils.Utils;

public class JavaHeapElem {
  protected final Type type;
  public JavaHeapElem(Type type) {
    Utils.debugAssert(type instanceof RefLikeType || type instanceof NullType, "invalid type " + type.getClass());
    this.type = type;
  }

  public Type getType() {
    return type;
  }
}
