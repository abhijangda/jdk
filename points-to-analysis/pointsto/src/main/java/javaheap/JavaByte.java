package javaheap;

import soot.ByteType;

public class JavaByte extends JavaPrimValue implements JavaPrimOps {
  public final byte value;

  public JavaByte(byte value) {
    super(ByteType.v());
    this.value = value;
  }

  private JavaByte v(int value) {
    return new JavaByte((byte)value);
  }

  public JavaPrimValue add(JavaPrimValue o) {
    return v((value + ((JavaByte)o).value));
  }

  public JavaPrimValue minus(JavaPrimValue o) {
    return v((value - ((JavaByte)o).value));
  }

  public boolean eq(JavaPrimValue o) {
    return ((JavaByte)o).value == value;
  }

  public boolean neq(JavaPrimValue o) {
    return !eq(o);
  }

  public boolean gt(JavaPrimValue o) {
    return value > ((JavaByte)o).value;
  }

  public boolean lt(JavaPrimValue o) {
    return value < ((JavaByte)o).value;
  }

  public boolean ge(JavaPrimValue o) {
    return value >= ((JavaByte)o).value;
  }

  public boolean le(JavaPrimValue o) {
    return value <= ((JavaByte)o).value;
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof JavaByte && eq((JavaByte)o);
  }

  @Override
  public String toString() {
    return Byte.toString(value);
  }
}