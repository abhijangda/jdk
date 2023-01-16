package javaheap;

import java.util.HashMap;
import soot.RefType;
import soot.SootClass;

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
    boolean hasField = false;
    SootClass klass = getClassType().getSootClass();
    while (klass != null) {
      if (klass.getFieldByNameUnsafe(field) != null) {
        hasField = true;
        break;
      }
      if (klass.hasSuperclass()) {
        klass = klass.getSuperclass();
      } else {
        break;
      }
    }
    utils.Utils.debugAssert(hasField,
                            "field '%s' not present in '%s'", field, getClassType().getClassName());
    return fieldValues.get(field);
  }
}
