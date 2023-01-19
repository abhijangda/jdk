package callstack;
import javaheap.JavaHeapElem;
import javavalues.*;
import soot.Type;

public class VariableValue {
  public final Type sootType;
  public final JavaValue value;

  public VariableValue(Type sootType, JavaValue value) {
    this.sootType = sootType;
    this.value = value;
  }

  public VariableValue(JavaValue value) {
    this.sootType = value.getType();
    this.value = value;
  }
}