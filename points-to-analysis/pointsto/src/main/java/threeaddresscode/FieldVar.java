package threeaddresscode;

import org.apache.bcel.classfile.*;
import org.apache.bcel.generic.Type;

public class FieldVar extends Var {
  public final String name;

  public FieldVar(Type type, String name) {
    super(type);
    this.name = name;
  }
}
