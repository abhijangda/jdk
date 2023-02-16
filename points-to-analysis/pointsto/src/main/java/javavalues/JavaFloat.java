package javavalues;

import soot.FloatType;

public class JavaFloat extends JavaPrimValue implements JavaPrimOps {
  public final float value;

  public JavaFloat(float value) {
    super(FloatType.v());
    this.value = value;
  }

  private JavaFloat v(float value) {
    return new JavaFloat(value);
  }

  public JavaPrimValue add(JavaPrimValue o) {
    return v((value + ((JavaFloat)o).value));
  }

  public JavaPrimValue minus(JavaPrimValue o) {
    return v((value - ((JavaFloat)o).value));
  }

  public JavaPrimValue mul(JavaPrimValue o) {
    return v((value * ((JavaFloat)o).value));
  }

  public JavaBool eq(JavaPrimValue o) {
    return JavaValueFactory.v(((JavaFloat)o).value == value);
  }

  public JavaBool neq(JavaPrimValue o) {
    return eq(o).not();
  }

  public JavaBool gt(JavaPrimValue o) {
    return JavaValueFactory.v(value > ((JavaFloat)o).value);
  }

  public JavaBool lt(JavaPrimValue o) {
    return JavaValueFactory.v(value < ((JavaFloat)o).value);
  }

  public JavaBool ge(JavaPrimValue o) {
    return JavaValueFactory.v(value >= ((JavaFloat)o).value);
  }

  public JavaBool le(JavaPrimValue o) {
    return JavaValueFactory.v(value <= ((JavaFloat)o).value);
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof JavaFloat && eq((JavaFloat)o).value;
  }

  @Override
  public String toString() {
    return wrapClassName(Float.toString(value));
  }
}