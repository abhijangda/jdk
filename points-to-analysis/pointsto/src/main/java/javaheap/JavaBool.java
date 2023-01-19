package javaheap;

import soot.BooleanType;
import utils.Utils;

public class JavaBool extends JavaPrimValue implements JavaPrimOps {
  public final boolean value;

  public JavaBool(boolean value) {
    super(BooleanType.v());
    this.value = value;
  }

  public JavaPrimValue add(JavaPrimValue o) {
    Utils.shouldNotReachHere();
    return null;
  }

  public JavaPrimValue minus(JavaPrimValue o) {
    Utils.shouldNotReachHere();
    return null;
  }

  public boolean eq(JavaPrimValue o) {
    return value == ((JavaBool)o).value;
  }
  
  public boolean neq(JavaPrimValue o) {
    return !eq(o);
  }

  public boolean gt(JavaPrimValue o) {
    Utils.shouldNotReachHere();
    return false;
  }

  public boolean lt(JavaPrimValue o) {
    Utils.shouldNotReachHere();
    return false;
  }
  public boolean ge(JavaPrimValue o) {
    Utils.shouldNotReachHere();
    return false;
  }

  public boolean le(JavaPrimValue o) {
    Utils.shouldNotReachHere();
    return false;
  }
}
