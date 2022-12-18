package threeaddresscode;

import org.apache.bcel.classfile.*;

public class LocalVar extends Var {
  public final int index;

  public LocalVar(JavaClass type, int index) {
    super(type);
    this.index = index;
  }
}
