package javaheap;

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

  public boolean eq(JavaPrimValue o) {
    return ((JavaLong)o).value == value;
  }

  public boolean neq(JavaPrimValue o) {
    return !eq(o);
  }

  public boolean gt(JavaPrimValue o) {
    return value > ((JavaLong)o).value;
  }

  public boolean lt(JavaPrimValue o) {
    return value < ((JavaLong)o).value;
  }

  public boolean ge(JavaPrimValue o) {
    return value >= ((JavaLong)o).value;
  }

  public boolean le(JavaPrimValue o) {
    return value <= ((JavaLong)o).value;
  }
}
