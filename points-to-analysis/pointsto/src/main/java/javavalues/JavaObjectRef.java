package javavalues;

import javaheap.JavaArray;
import javaheap.JavaHeapElem;
import javaheap.JavaObject;
import soot.ArrayType;
import soot.PrimType;
import soot.RefType;
import soot.SootField;
import utils.Utils;

public class JavaObjectRef extends JavaRefValue {
  public JavaObjectRef(JavaObject obj) {
    super((RefType)obj.getType(), obj);
  }
  
  public JavaObject getObject() {
    return (JavaObject)this.ref;
  }

  public JavaValue getField(SootField field) {
    if (field.getType() instanceof PrimType) {
      return null;
    }

    JavaHeapElem fieldElem = getObject().getField(field.getName());
    if (fieldElem == null) return JavaNull.v();
    if (fieldElem.getType() instanceof RefType) return JavaValueFactory.v((JavaObject)fieldElem);
    if (fieldElem.getType() instanceof ArrayType) return new JavaArrayRef((JavaArray)fieldElem);
    Utils.debugAssert(false, "");
    return null;
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof JavaObjectRef && getObject() == ((JavaObjectRef)o).getObject();
  }

  @Override
  public String toString() {
    return getObject().toString();
  }
}
