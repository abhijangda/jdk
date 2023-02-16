package javavalues;

import soot.PrimType;
import soot.jimple.BinopExpr;
import soot.jimple.internal.*;
import utils.Utils;

public abstract class JavaPrimValue extends JavaValue {
  protected JavaPrimValue(PrimType type) {
    super(type);
  }

  public abstract JavaPrimValue add(JavaPrimValue o);
  public abstract JavaPrimValue minus(JavaPrimValue o);
  public abstract JavaPrimValue mul(JavaPrimValue o);

  public abstract JavaBool eq(JavaPrimValue o);
  public abstract JavaBool neq(JavaPrimValue o);
  public abstract JavaBool gt(JavaPrimValue o);
  public abstract JavaBool lt(JavaPrimValue o);
  public abstract JavaBool ge(JavaPrimValue o);
  public abstract JavaBool le(JavaPrimValue o);

  public static JavaPrimValue processJBinOp(AbstractBinopExpr binop, 
                                            JavaPrimValue primVal1, JavaPrimValue primVal2) {
    if (binop instanceof JAddExpr) {
      return primVal1.add(primVal2);
    } else if (binop instanceof JSubExpr) {
      return primVal1.minus(primVal2);
    } else if (binop instanceof JMulExpr) {
      return primVal1.mul(primVal2);
    } else if (binop instanceof JDivExpr) {
    } else if (binop instanceof JMulExpr) {
    } else if (binop instanceof JRemExpr) {
    } else if (binop instanceof JCmpExpr) {
    } else if (binop instanceof JCmpgExpr) {
    } else if (binop instanceof JCmplExpr) {
    } else if (binop instanceof JEqExpr) {
      return primVal1.eq(primVal2);
    } else if (binop instanceof JGeExpr) {
      return primVal1.ge(primVal2);
    } else if (binop instanceof JGtExpr) {
      return primVal1.gt(primVal2);
    } else if (binop instanceof JLeExpr) {
      return primVal1.le(primVal2);
    } else if (binop instanceof JLtExpr) {
      return primVal1.lt(primVal2);
    } else if (binop instanceof JNeExpr) {
      return primVal1.neq(primVal2);
    } else if (binop instanceof JAndExpr) {
    } else if (binop instanceof JOrExpr) {
    } else if (binop instanceof JShlExpr) {
    } else if (binop instanceof JShrExpr) {
    } else if (binop instanceof JUshrExpr) {
    } else if (binop instanceof JXorExpr) {
    }

    Utils.shouldNotReachHere(binop.getClass().toString());

    return null;
  }
}
