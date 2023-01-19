package javavalues;

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

  public JavaBool eq(JavaPrimValue o) {
    return JavaValueFactory.v(((JavaByte)o).value == value);
  }

  public JavaBool neq(JavaPrimValue o) {
    return eq(o).not();
  }

  public JavaBool gt(JavaPrimValue o) {
    return JavaValueFactory.v(value > ((JavaByte)o).value);
  }

  public JavaBool lt(JavaPrimValue o) {
    return JavaValueFactory.v(value < ((JavaByte)o).value);
  }

  public JavaBool ge(JavaPrimValue o) {
    return JavaValueFactory.v(value >= ((JavaByte)o).value);
  }

  public JavaBool le(JavaPrimValue o) {
    return JavaValueFactory.v(value <= ((JavaByte)o).value);
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof JavaByte && eq((JavaByte)o).value;
  }

  @Override
  public String toString() {
    return wrapClassName(Byte.toString(value));
  }
}