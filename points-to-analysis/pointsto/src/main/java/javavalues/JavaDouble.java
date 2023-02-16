package javavalues;

import soot.DoubleType;

public class JavaDouble extends JavaPrimValue implements JavaPrimOps {
  public final double value;

  public JavaDouble(double value) {
    super(DoubleType.v());
    this.value = value;
  }

  private JavaDouble v(double value) {
    return new JavaDouble(value);
  }

  public JavaPrimValue add(JavaPrimValue o) {
    return v((value + ((JavaDouble)o).value));
  }

  public JavaPrimValue minus(JavaPrimValue o) {
    return v((value - ((JavaDouble)o).value));
  }

  public JavaPrimValue mul(JavaPrimValue o) {
    return v((value * ((JavaDouble)o).value));
  }

  public JavaBool eq(JavaPrimValue o) {
    return JavaValueFactory.v(((JavaDouble)o).value == value);
  }

  public JavaBool neq(JavaPrimValue o) {
    return eq(o).not();
  }

  public JavaBool gt(JavaPrimValue o) {
    return JavaValueFactory.v(value > ((JavaDouble)o).value);
  }

  public JavaBool lt(JavaPrimValue o) {
    return JavaValueFactory.v(value < ((JavaDouble)o).value);
  }

  public JavaBool ge(JavaPrimValue o) {
    return JavaValueFactory.v(value >= ((JavaDouble)o).value);
  }

  public JavaBool le(JavaPrimValue o) {
    return JavaValueFactory.v(value <= ((JavaDouble)o).value);
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof JavaDouble && eq((JavaDouble)o).value;
  }

  @Override
  public String toString() {
    return wrapClassName(Double.toString(value));
  }
}
