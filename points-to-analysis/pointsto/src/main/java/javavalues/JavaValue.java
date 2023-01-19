package javavalues;

import soot.Type;

public abstract class JavaValue {
  protected final Type type;

  protected JavaValue(Type type) {
    this.type = type;
  }

  public Type getType() {
    return type;
  }

  protected String wrapClassName(String s) {
    return this.getClass().getSimpleName() + "(" + s + ")";
  }
  
  @Override
  public abstract boolean equals(Object o);

  @Override
  public abstract String toString();
}
