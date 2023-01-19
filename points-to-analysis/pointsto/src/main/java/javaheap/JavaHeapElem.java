package javaheap;

import soot.NullType;
import soot.RefLikeType;
import soot.Type;
import utils.Utils;

public class JavaHeapElem extends JavaValue {
  public JavaHeapElem(Type type) {
    super(type);
    Utils.debugAssert(type instanceof RefLikeType || type instanceof NullType, "invalid type " + type.getClass());
  }

  public Type getType() {
    return type;
  }
}
