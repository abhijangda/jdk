package callstack;
import javaheap.JavaHeapElem;
import soot.Type;

public class VariableValue {
  public final Type sootType;
  public final JavaHeapElem ref;

  public VariableValue(Type sootType, JavaHeapElem refValue) {
    this.sootType = sootType;
    this.ref = refValue;
  }

  public VariableValue(Type sootType) {
    this.sootType = sootType;
    this.ref = null;
  }
}