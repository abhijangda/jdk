package javaheap;

import soot.NullType;
import soot.RefLikeType;
import soot.Type;
import utils.Utils;

public abstract class JavaHeapElem implements Cloneable {
  protected final Type type;
  protected final long address;
  protected int id;
  protected static int numHeapElems = 0;
  public long getAddress() {
    return address;
  }

  public JavaHeapElem(Type type, long address) {
    Utils.debugAssert(type instanceof RefLikeType || type instanceof NullType, "invalid type " + type.getClass());
    this.type = type;
    this.address = address;
    this.id = numHeapElems + 1;
    numHeapElems += 1;
  }

  public int getId() {
    return this.id;
  }
  public Type getType() {
    return type;
  }

  public abstract JavaHeapElem clone();
  public abstract void deepClone(JavaHeap newHeap, JavaHeapElem src);
  public abstract String toString();
}
