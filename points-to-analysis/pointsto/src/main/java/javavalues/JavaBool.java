package javavalues;

import soot.BooleanType;
import utils.Utils;

public class JavaBool extends JavaPrimValue implements JavaPrimOps {
  public final boolean value;

  public JavaBool(boolean value) {
    super(BooleanType.v());
    this.value = value;
  }

  public JavaPrimValue add(JavaPrimValue o) {
    Utils.shouldNotReachHere();
    return null;
  }

  public JavaPrimValue minus(JavaPrimValue o) {
    Utils.shouldNotReachHere();
    return null;
  }

  public JavaBool not() {
    return JavaValueFactory.v(!value);
  }

  public JavaBool eq(JavaPrimValue o) {
    return JavaValueFactory.v(value == ((JavaBool)o).value);
  }
  
  public JavaBool neq(JavaPrimValue o) {
    return eq(o).not();
  }

  public JavaBool gt(JavaPrimValue o) {
    Utils.shouldNotReachHere();
    return null;
  }

  public JavaBool lt(JavaPrimValue o) {
    Utils.shouldNotReachHere();
    return null;
  }
  public JavaBool ge(JavaPrimValue o) {
    Utils.shouldNotReachHere();
    return null;
  }

  public JavaBool le(JavaPrimValue o) {
    Utils.shouldNotReachHere();
    return null;
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof JavaBool && eq((JavaBool)o).value;
  }

  @Override
  public String toString() {
    return wrapClassName(Boolean.toString(value));
  }
}
