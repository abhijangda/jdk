package javaheap;

import java.util.HashMap;
import java.util.Map;
import soot.RefType;
import soot.SootClass;
import soot.SootField;
import soot.sootify.ValueTemplatePrinter;
import utils.Utils;

public class JavaObject extends JavaHeapElem {
  public final HashMap<String, JavaHeapElem> fieldValues;

  public JavaObject(RefType type, long address) {
    super(type, address);
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
    
    return value;
  }

  public JavaHeapElem clone() {
    JavaObject newObj = new JavaObject(getClassType(), address);
    for (Map.Entry<String, JavaHeapElem> entry : fieldValues.entrySet()) {
      newObj.addField(entry.getKey(), entry.getValue());
    }

    return newObj;
  }

  public void deepClone(JavaHeap newHeap, JavaHeapElem src) {
    this.fieldValues.clear();
    for (Map.Entry<String, JavaHeapElem> entry : ((JavaObject)src).fieldValues.entrySet()) {
      if (entry.getValue() == null) {
        this.fieldValues.put(entry.getKey(), null);
      } else {
        JavaHeapElem newHeapElem = newHeap.get(entry.getValue().getAddress());
        this.fieldValues.put(entry.getKey(), newHeapElem);
      }
    }
  }

  public String toString() {
    StringBuilder builder = new StringBuilder();
    builder.append("JavaObject: " + getType().toString() + ": " + getAddress());
    return builder.toString();
  }
}