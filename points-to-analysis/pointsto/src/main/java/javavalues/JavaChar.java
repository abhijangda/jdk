package javavalues;

import soot.CharType;

public class JavaChar extends JavaPrimValue implements JavaPrimOps {
  public final char value;

  public JavaChar(char value) {
    super(CharType.v());
    this.value = value;
  }

  private JavaChar v(int value) {
    return new JavaChar((char)value);
  }

  public JavaPrimValue add(JavaPrimValue o) {
    return v((value + ((JavaChar)o).value));
  }

  public JavaPrimValue minus(JavaPrimValue o) {
    return v((value - ((JavaChar)o).value));
  }

  public JavaPrimValue mul(JavaPrimValue o) {
    return v((value * ((JavaChar)o).value));
  }

  public JavaBool eq(JavaPrimValue o) {
    return JavaValueFactory.v(((JavaChar)o).value == value);
  }

  public JavaBool neq(JavaPrimValue o) {
    return eq(o).not();
  }

  public JavaBool gt(JavaPrimValue o) {
    return JavaValueFactory.v(value > ((JavaChar)o).value);
  }

  public JavaBool lt(JavaPrimValue o) {
    return JavaValueFactory.v(value < ((JavaChar)o).value);
  }

  public JavaBool ge(JavaPrimValue o) {
    return JavaValueFactory.v(value >= ((JavaChar)o).value);
  }

  public JavaBool le(JavaPrimValue o) {
    return JavaValueFactory.v(value <= ((JavaChar)o).value);
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof JavaChar && eq((JavaChar)o).value;
  }

  @Override
  public String toString() {
    return wrapClassName(Character.toString(value));
  }
}
