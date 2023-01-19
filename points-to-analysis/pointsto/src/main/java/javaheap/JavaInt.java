package javaheap;

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

  public boolean eq(JavaPrimValue o) {
    return ((JavaInt)o).value == value;
  }

  public boolean neq(JavaPrimValue o) {
    return !eq(o);
  }

  public boolean gt(JavaPrimValue o) {
    return value > ((JavaInt)o).value;
  }

  public boolean lt(JavaPrimValue o) {
    return value < ((JavaInt)o).value;
  }

  public boolean ge(JavaPrimValue o) {
    return value >= ((JavaInt)o).value;
  }

  public boolean le(JavaPrimValue o) {
    return value <= ((JavaInt)o).value;
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof JavaInt && eq((JavaInt)o);
  }

  @Override
  public String toString() {
    return Integer.toString(value);
  }
}
