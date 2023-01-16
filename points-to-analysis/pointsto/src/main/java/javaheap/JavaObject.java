package javaheap;

import java.util.HashMap;
import soot.RefType;
import soot.SootClass;
import soot.SootField;

public class JavaObject extends JavaHeapElem {
  protected final HashMap<String, JavaHeapElem> fieldValues;

  public JavaObject(RefType type) {
    super(type);
    this.fieldValues = new HashMap<>();
  }

  public RefType getClassType() {
    return (RefType)type;
  }

  public void addField(String field, JavaHeapElem value) {
    fieldValues.put(field, value);
  }

  public JavaHeapElem getField(String field) {
    SootClass klass = getClassType().getSootClass();
    SootField sootField = null;
    while (klass != null) {
      sootField = klass.getFieldByNameUnsafe(field);
      if (sootField != null) {
        break;
      }
      if (klass.hasSuperclass()) {
        klass = klass.getSuperclass();
      } else {
        break;
      }
    }
    utils.Utils.debugAssert(sootField != null,
                            "field '%s' not present in '%s'", field, getClassType().getClassName());
    JavaHeapElem value = fieldValues.get(field);
    if (value == null) {
      return JavaNull.v();
    }

    return value;
  }
}
