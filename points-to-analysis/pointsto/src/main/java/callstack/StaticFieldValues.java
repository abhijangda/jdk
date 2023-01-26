package callstack;
import java.util.*;

import javaheap.JavaHeap;
import javaheap.JavaHeapElem;
import javavalues.JavaNull;
import soot.SootField;
import soot.SootFieldRef;

public class StaticFieldValues {
  private HashMap<SootField, JavaHeapElem> values;
  private JavaHeap heap;

  public StaticFieldValues(JavaHeap heap) {
    this.values = new HashMap<>();
    this.heap = heap;
    heap.setStaticFieldValues(this);
  }

  public JavaHeapElem get(SootFieldRef fieldRef) {
    return get(fieldRef.resolve());
  }

  public JavaHeapElem get(SootField field) {
    JavaHeapElem value = values.get(field);
    if (value == null) {
      return null;
    }
    return value;
  }

  public void set(SootField field, JavaHeapElem obj) {
    values.put(field, obj);
  }

  public void set(SootField field, long ptr) {
    set(field, this.heap.get(ptr));
  }

  public void set(SootFieldRef fieldRef, long ptr) {
    set(fieldRef.resolve(), this.heap.get(ptr));
  }

  public StaticFieldValues clone(JavaHeap heap) {
    StaticFieldValues newStatics = new StaticFieldValues(heap);
    for (Map.Entry<SootField, JavaHeapElem> entry : values.entrySet()) {
      newStatics.set(entry.getKey(), heap.get(entry.getValue().getAddress()));
    }

    return newStatics;
  }
}