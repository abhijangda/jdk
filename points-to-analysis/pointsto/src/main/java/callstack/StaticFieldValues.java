package callstack;
import java.util.*;

import javaheap.JavaHeap;
import javaheap.JavaHeapElem;
import javaheap.JavaNull;
import soot.SootField;
import soot.SootFieldRef;

public class StaticFieldValues {
  private HashMap<SootField, JavaHeapElem> values;
  
  private static StaticFieldValues instance = null;

  private StaticFieldValues() {
    values = new HashMap<>();
  }

  public static StaticFieldValues v() {
    if (instance == null) {
      instance = new StaticFieldValues();
    }

    return instance;
  }

  public JavaHeapElem get(SootFieldRef fieldRef) {
    return get(fieldRef.resolve());
  }

  public JavaHeapElem get(SootField field) {
    JavaHeapElem value = values.get(field);
    if (value == null) {
      return JavaNull.v();
    }
    return value;
  }

  public void set(SootField field, JavaHeapElem obj) {
    values.put(field, obj);
  }

  public void set(SootField field, long ptr) {
    set(field, JavaHeap.v().get(ptr));
  }

  public void set(SootFieldRef fieldRef, long ptr) {
    set(fieldRef.resolve(), JavaHeap.v().get(ptr));
  }
}