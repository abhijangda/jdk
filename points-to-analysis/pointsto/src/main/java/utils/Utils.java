package utils;

import soot.SootMethod;
import soot.Type;
import soot.Unit;
import soot.Value;
import soot.jimple.ArrayRef;
import soot.jimple.FieldRef;
import soot.jimple.InvokeExpr;
import soot.jimple.StaticFieldRef;
import soot.jimple.internal.JAssignStmt;
import soot.jimple.internal.JNewArrayExpr;
import soot.jimple.internal.JNewExpr;
import soot.jimple.internal.JNewMultiArrayExpr;
import soot.jimple.internal.JStaticInvokeExpr;
import soot.jimple.internal.JThrowStmt;
import soot.shimple.Shimple;
import soot.toolkits.graph.Block;

import java.util.ArrayList;
import java.util.Iterator;

import parsedmethod.ParsedMethodMap;
import parsedmethod.ShimpleMethod;
import parsedmethod.ShimpleMethodList;
import soot.AbstractJasminClass;
import soot.SootClass;

public abstract class Utils {
  public static boolean DEBUG_PRINT = true;

  public static void debugPrintln(String x) {
    if (DEBUG_PRINT) {
      String fileline = getCurrFileAndLine(3);
      System.out.println(fileline + ": " + x);
    }
  }

  public static void debugPrintln(Object x) {
    if (DEBUG_PRINT) {
      String fileline = getCurrFileAndLine(3);
      System.out.println(fileline + ": " + ((x == null) ? "null" : x.toString()));
    }
  }

  public static void debugLog(String fmt, Object... args) {
    if (DEBUG_PRINT) {
      String fileline = getCurrFileAndLine(3);
      System.err.printf(fileline + ": " +fmt, args);
    }
  }

  public static void debugPrintf(String fmt, Object... args) {
    if (DEBUG_PRINT) {
      String fileline = getCurrFileAndLine(3);
      System.err.printf(fileline + ": " +fmt, args);
    }
  }

  public static void shouldNotReachHere() {
    debugAssert(false, "Should not reach here\n");
  }

  public static void debugAssert(boolean b, String fmt, Object... args) {
    if (DEBUG_PRINT) {
      if (!b) {
        System.err.printf(fmt, args);
        throw new AssertionError(b);
      }
    }
  }

  public static String pathToPackage(String path) {
    return path.replace("/", ".");
  }
  
  public static String packageToPath(String path) {
    return path.replace(".", "/");
  }

  public static String methodFullName(SootMethod meth) {
    if (meth == null) return "NULL";
    StringBuilder builder = new StringBuilder();
    builder.append(meth.getDeclaringClass().getName());
    builder.append(".");
    builder.append(meth.getName());
    builder.append("(");
    for (Type t : meth.getParameterTypes()) {
      builder.append(AbstractJasminClass.jasminDescriptorOf(t));
    }
    builder.append(")");
    builder.append(AbstractJasminClass.jasminDescriptorOf(meth.getReturnType()));
    return builder.toString();
  }

  public static boolean methodToCare(ShimpleMethod method) {
    return methodToCare(method.sootMethod);
  }

  public static boolean methodToCare(SootMethod method) {
    return methodToCare(methodFullName(method));
  }

  public static boolean methodToCare(String name) {
    return !name.equals("NULL") && !name.startsWith("java.") && !name.startsWith("jdk.") && 
            !name.startsWith("sun.");
  }

  public static String getCurrFileAndLine(int index) {
    String methodFileAndLine = Thread.currentThread().getStackTrace()[index].toString();
    return "\033[0;31m" + methodFileAndLine.substring(methodFileAndLine.indexOf("(")+1,methodFileAndLine.indexOf(")")) + "\033[0m";
  }

  public static void debugPrintFileAndLine() {
    if (DEBUG_PRINT) {
      System.out.println(getCurrFileAndLine(3));
    }
  }

  public static boolean canStmtUpdateHeap(Unit stmt) {
    if (stmt instanceof JAssignStmt) {
      Value left = ((JAssignStmt)stmt).getLeftOp();
      Value right = ((JAssignStmt)stmt).getRightOp();

      if (right instanceof JNewExpr || 
          right instanceof JNewMultiArrayExpr ||
          right instanceof JNewArrayExpr) {
            return true;
      }
      
      if (left instanceof FieldRef || 
          left instanceof ArrayRef) {
        return true;
      }
    }

    return false;
  }

  public static boolean blockHasThrowStmt(Block block) {
    Iterator<Unit> iter = block.iterator();
    while (iter.hasNext()) {
      Unit stmt = iter.next();
      if (stmt instanceof JThrowStmt) {
        return true;
      }
    }

    return false;
  }

  public static ShimpleMethodList getAllStaticInitializers(JStaticInvokeExpr invokeExpr) {
    return getAllStaticInitializers(invokeExpr.getMethod().getDeclaringClass());
  }

  public static ShimpleMethodList getAllStaticInitializers(StaticFieldRef fieldRef) {
    return getAllStaticInitializers(fieldRef.getFieldRef().declaringClass());
  }

  private static ShimpleMethod getStaticInitializer(SootClass klass) {
    SootMethod clinit = klass.getMethodByNameUnsafe("<clinit>");
    if (clinit != null)
      return ParsedMethodMap.v().getOrParseToShimple(clinit);
    return null;
  }

  public static ShimpleMethodList getAllStaticInitializers(JNewExpr expr) {
    SootClass klass = expr.getBaseType().getSootClass();
    return getAllStaticInitializers(klass);
  }

  public static ShimpleMethodList getAllStaticInitializers(SootClass klass) {
    ShimpleMethodList clinits = new ShimpleMethodList();
    
    while (klass != null && Utils.methodToCare(klass.getName())) {
      ShimpleMethod clinit = Utils.getStaticInitializer(klass);
      if (clinit != null) {
        clinits.add(clinit);
      }
      if (klass.hasSuperclass())
        klass = klass.getSuperclass();
      else
        break;
    }

    return clinits;
  }

  public static ShimpleMethod getMethodForInvokeExpr(InvokeExpr invoke) {
    return ParsedMethodMap.v().getOrParseToShimple(invoke.getMethod());
  }
}
