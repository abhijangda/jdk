package javaheap;

import soot.Type;
import soot.ArrayType;

public class JavaArray extends JavaHeapElem {
  protected final int length;
  private final JavaHeapElem array[];

  public JavaArray(Type type, int length) {
    super(type);
    this.length = length;
    this.array = new JavaHeapElem[length];
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

  public void setElem(int idx, JavaHeapElem elem) {
    this.array[idx] = elem;
  }

  public JavaHeapElem getElem(int idx) {
    return this.array[idx];
  }
}
