package threeaddresscode;

import org.apache.bcel.classfile.*;
import org.apache.bcel.*;
import org.apache.bcel.generic.Type;
import org.apache.bcel.util.*;

public class Variable {
  public final JavaClass type;
  public final int startBci;

  public Variable(JavaClass type, int startBci) {
    this.type = type;
    this.startBci = startBci;
  }
}
