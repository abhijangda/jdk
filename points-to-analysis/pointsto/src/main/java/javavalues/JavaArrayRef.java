package javavalues;

import javaheap.JavaArray;
import javaheap.JavaHeapElem;
import javaheap.JavaObject;
import soot.ArrayType;
import soot.RefType;
import utils.Utils;

public class JavaArrayRef extends JavaRefValue {
  public JavaArrayRef(JavaArray array) {
    super(array.getType(), array);
  }

  public JavaArray getArray() {
    return (JavaArray)this.ref;
  }
  public JavaValue getElem(int idx) {
    JavaHeapElem elem = getArray().getElem(idx);
    if (elem == null) return JavaNull.v();
    if (elem.getType() instanceof RefType) return new JavaObjectRef((JavaObject)elem);
    if (elem.getType() instanceof ArrayType) return new JavaArrayRef((JavaArray)elem);
    Utils.debugAssert(false, "");
    return null;
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof JavaArrayRef && getArray() == ((JavaArrayRef)o).getArray();
  }

  @Override
  public String toString() {
    return getArray().toString();
  }
}
