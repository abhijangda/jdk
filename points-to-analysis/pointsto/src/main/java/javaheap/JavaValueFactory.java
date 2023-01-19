package javaheap;

import utils.Utils;

public abstract class JavaValueFactory {
  public static JavaValue v(JavaHeapElem obj) {
    if (obj instanceof JavaObject)
      return new JavaObjectRef((JavaObject)obj);
    else if (obj instanceof JavaArray) {
      return new JavaArrayRef((JavaArray)obj);
    } else if (obj == null) {
      return nullV();
    }
    Utils.shouldNotReachHere();
    return null;
  }

  public static JavaObjectRef v(JavaObject obj) {
    return new JavaObjectRef(obj);
  }

  public static JavaArrayRef v(JavaArray array) {
    return new JavaArrayRef(array);
  }
  
  public static JavaInt v(int value) {
    return new JavaInt(value);
  }

  public static JavaLong v(long value) {
    return new JavaLong(value);
  }

  public static JavaByte v(byte value) {
    return new JavaByte(value);
  }

  public static JavaChar v(char value) {
    return new JavaChar(value);
  }

  public static JavaBool v(boolean value) {
    return new JavaBool(value);
  }

  public static JavaNull nullV() {
    return JavaNull.v();
  }
}
