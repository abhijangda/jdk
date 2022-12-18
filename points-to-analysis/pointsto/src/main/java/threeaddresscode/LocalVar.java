package threeaddresscode;

import org.apache.bcel.classfile.*;
import org.apache.bcel.generic.Type;

public class LocalVar extends Var {
  public final int index;

  public LocalVar(Type type, int index) {
    super(type);
    this.index = index;
  }
}
