package javaheap;

import java.util.HashMap;
import soot.RefType;

public class JavaObject extends JavaHeapElem {
  protected final HashMap<String, JavaHeapElem> fieldValues;

  public JavaObject(RefType type) {
    super(type);
    this.fieldValues = new HashMap<>();
  }

  public RefType getType() {
    return (RefType)type;
  }

  public void addField(String field, JavaHeapElem value) {
    fieldValues.put(field, value);
  }

  public JavaHeapElem getField(String field) {
    return fieldValues.get(field);
  }
}
