package javavalues;

import soot.LongType;

public class JavaLong extends JavaPrimValue implements JavaPrimOps {
  public final long value;

  public JavaLong(long value) {
    super(LongType.v());
    this.value = value;
  }

  private JavaLong v(long value) {
    return new JavaLong(value);
  }

  public JavaPrimValue add(JavaPrimValue o) {
    return v((value + ((JavaLong)o).value));
  }

  public JavaPrimValue minus(JavaPrimValue o) {
    return v((value - ((JavaLong)o).value));
  }

  public JavaPrimValue mul(JavaPrimValue o) {
    return v((value * ((JavaLong)o).value));
  }

  public JavaBool eq(JavaPrimValue o) {
    return JavaValueFactory.v(((JavaLong)o).value == value);
  }

  public JavaBool neq(JavaPrimValue o) {
    return eq(o).not();
  }

  public JavaBool gt(JavaPrimValue o) {
    return JavaValueFactory.v(value > ((JavaLong)o).value);
  }

  public JavaBool lt(JavaPrimValue o) {
    return JavaValueFactory.v(value < ((JavaLong)o).value);
  }

  public JavaBool ge(JavaPrimValue o) {
    return JavaValueFactory.v(value >= ((JavaLong)o).value);
  }

  public JavaBool le(JavaPrimValue o) {
    return JavaValueFactory.v(value <= ((JavaLong)o).value);
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof JavaLong && eq((JavaLong)o).value;
  }

  @Override
  public String toString() {
    return wrapClassName(Long.toString(value));
  }
}
