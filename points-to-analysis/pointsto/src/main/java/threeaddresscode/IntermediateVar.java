package threeaddresscode;

import org.apache.bcel.classfile.*;
import org.apache.bcel.*;
import org.apache.bcel.generic.Type;
import org.apache.bcel.util.*;

public class IntermediateVar extends Var {
  public final int startBci;

  public IntermediateVar(Type type, int startBci) {
    super(type);
    this.startBci = startBci;
  }
}
