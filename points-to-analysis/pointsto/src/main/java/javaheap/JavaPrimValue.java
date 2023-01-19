package javaheap;

import soot.PrimType;

public abstract class JavaPrimValue extends JavaValue {
  protected JavaPrimValue(PrimType type) {
    super(type);
  }
}
