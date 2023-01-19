package javaheap;

import soot.ArrayType;
import soot.RefType;
import utils.Utils;

public class JavaObjectRef extends JavaValue {
  public final JavaObject obj;

  public JavaObjectRef(JavaObject obj) {
    super(obj.getType());
    this.obj = obj;
  }
  
  public JavaValue getField(String field) {
    JavaHeapElem fieldElem = obj.getField(field);
    if (fieldElem == null) return JavaNull.v();
    if (fieldElem.getType() instanceof RefType) return JavaValueFactory.v((JavaObject)fieldElem);
    if (fieldElem.getType() instanceof ArrayType) return new JavaArrayRef((JavaArray)fieldElem);
    Utils.debugAssert(false, "");
    return null;
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof JavaObjectRef && obj == ((JavaObjectRef)o).obj;
  }

  @Override
  public String toString() {
    return obj.toString();
  }
}
