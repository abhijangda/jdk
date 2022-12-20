package threeaddresscode;

import org.apache.bcel.classfile.*;
import org.apache.bcel.generic.Type;

public class LocalVar extends Var {
  public final int index;
  public final int startPc;
  public final int length;

  public LocalVar(Type type, int index, int startPc, int length) {
    super(type);
    this.index = index;
    this.startPc = startPc;
    this.length = length;
  }
}
