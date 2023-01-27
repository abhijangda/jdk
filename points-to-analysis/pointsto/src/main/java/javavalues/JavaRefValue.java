package javavalues;

import javaheap.JavaHeapElem;
import soot.RefLikeType;

public abstract class JavaRefValue extends JavaValue {
  public final JavaHeapElem ref;

  public JavaRefValue(RefLikeType type, JavaHeapElem ref) {
    super(type);
    this.ref = ref;
  }
}
