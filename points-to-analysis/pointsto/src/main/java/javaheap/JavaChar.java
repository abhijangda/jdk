package javaheap;

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

  public boolean eq(JavaPrimValue o) {
    return ((JavaChar)o).value == value;
  }

  public boolean neq(JavaPrimValue o) {
    return !eq(o);
  }

  public boolean gt(JavaPrimValue o) {
    return value > ((JavaChar)o).value;
  }

  public boolean lt(JavaPrimValue o) {
    return value < ((JavaChar)o).value;
  }

  public boolean ge(JavaPrimValue o) {
    return value >= ((JavaChar)o).value;
  }

  public boolean le(JavaPrimValue o) {
    return value <= ((JavaChar)o).value;
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof JavaChar && eq((JavaChar)o);
  }

  @Override
  public String toString() {
    return Character.toString(value);
  }
}
