package threeaddresscode;

import java.lang.constant.Constable;

import org.apache.bcel.classfile.Constant;
import org.apache.bcel.generic.Type;

public class ConstantVal extends Var {
  public final Constant value;

  public ConstantVal(Type type, Constant value) {
    super(type);
    this.value = value;
  }
}