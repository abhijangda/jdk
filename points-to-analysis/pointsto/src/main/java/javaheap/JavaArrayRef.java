package javaheap;

import soot.ArrayType;
import soot.RefType;
import utils.Utils;

public class JavaArrayRef extends JavaValue {
  private JavaArray array;

  public JavaArrayRef(JavaArray array) {
    super(array.type);
    this.array = array;
  }

  public JavaValue getElem(int idx) {
    JavaHeapElem elem = array.getElem(idx);
    if (elem == null) return JavaNull.v();
    if (elem.getType() instanceof RefType) return new JavaObjectRef((JavaObject)elem);
    if (elem.getType() instanceof ArrayType) return new JavaArrayRef((JavaArray)elem);
    Utils.debugAssert(false, "");
    return null;
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof JavaArrayRef && array == ((JavaArrayRef)o).array;
  }

  @Override
  public String toString() {
    return array.toString();
  }
}
