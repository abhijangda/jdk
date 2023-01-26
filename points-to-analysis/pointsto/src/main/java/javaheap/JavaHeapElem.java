package javaheap;

import soot.NullType;
import soot.RefLikeType;
import soot.Type;
import utils.Utils;

public abstract class JavaHeapElem implements Cloneable {
  protected final Type type;
  protected final long address;
  public long getAddress() {
    return address;
  }

  public JavaHeapElem(Type type, long address) {
    Utils.debugAssert(type instanceof RefLikeType || type instanceof NullType, "invalid type " + type.getClass());
    this.type = type;
    this.address = address;
  }

  public Type getType() {
    return type;
  }

  public abstract JavaHeapElem clone();
}
