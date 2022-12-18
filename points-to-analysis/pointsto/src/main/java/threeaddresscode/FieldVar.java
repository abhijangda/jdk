package threeaddresscode;

import org.apache.bcel.classfile.*;

public class FieldVar extends Var {
  public final String name;

  public FieldVar(JavaClass type, String name) {
    super(type);
    this.name = name;
  }
}
