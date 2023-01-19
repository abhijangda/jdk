package javavalues;

import javaheap.JavaArray;
import javaheap.JavaHeapElem;
import javaheap.JavaObject;
import soot.ArrayType;
import soot.PrimType;
import soot.RefType;
import soot.SootField;
import utils.Utils;

public class JavaObjectRef extends JavaValue {
  public final JavaObject obj;

  public JavaObjectRef(JavaObject obj) {
    super(obj.getType());
    this.obj = obj;
  }
  
  public JavaValue getField(SootField field) {
    if (field.getType() instanceof PrimType) {
      return null;
    }

    JavaHeapElem fieldElem = obj.getField(field.getName());
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
