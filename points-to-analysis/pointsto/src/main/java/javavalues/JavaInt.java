package javavalues;

import soot.IntType;

public class JavaInt extends JavaPrimValue implements JavaPrimOps {
  public final int value;

  public JavaInt(int value) {
    super(IntType.v());
    this.value = value;
  }

  private JavaInt v(int value) {
    return new JavaInt(value);
  }

  public JavaPrimValue add(JavaPrimValue o) {
    return v((value + ((JavaInt)o).value));
  }

  public JavaPrimValue minus(JavaPrimValue o) {
    return v((value - ((JavaInt)o).value));
  }

  public JavaBool eq(JavaPrimValue o) {
    return JavaValueFactory.v(((JavaInt)o).value == value);
  }

  public JavaBool neq(JavaPrimValue o) {
    return eq(o).not();
  }

  public JavaBool gt(JavaPrimValue o) {
    return JavaValueFactory.v(value > ((JavaInt)o).value);
  }

  public JavaBool lt(JavaPrimValue o) {
    return JavaValueFactory.v(value < ((JavaInt)o).value);
  }

  public JavaBool ge(JavaPrimValue o) {
    return JavaValueFactory.v(value >= ((JavaInt)o).value);
  }

  public JavaBool le(JavaPrimValue o) {
    return JavaValueFactory.v(value <= ((JavaInt)o).value);
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof JavaInt && eq((JavaInt)o).value;
  }

  @Override
  public String toString() {
    return wrapClassName(Integer.toString(value));
  }
}
