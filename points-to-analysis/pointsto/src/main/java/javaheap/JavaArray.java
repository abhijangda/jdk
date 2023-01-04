package javaheap;

import soot.Type;
import soot.ArrayType;

public class JavaArray extends JavaHeapElem {
  protected final int length;

  public JavaArray(Type type, int length) {
    super(type);
    this.length = length;
  }

  public int getLength() {
    return length;
  }

  public ArrayType getType() {
    return (ArrayType)type;
  }

  public Type getElemType() {
    return getType().baseType;
  }
}
