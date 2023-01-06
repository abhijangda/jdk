package utils;

import soot.SootMethod;
import soot.Type;
import soot.AbstractJasminClass;

public abstract class Utils {
  public static boolean DEBUG_PRINT = true;

  public static void debugPrintln(String x) {
    if (DEBUG_PRINT) {
      String fileline = getCurrFileAndLine(3);
      System.out.println(fileline + ": " + x);
    }
  }

  public static void debugPrintln(Object x) {
    debugPrintln(x.toString());
  }

  public static void debugLog(String fmt, Object... args) {
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

  public static boolean methodToCare(SootMethod method) {
    return methodToCare(methodFullName(method));
  }

  public static boolean methodToCare(String name) {
    return !name.equals("NULL") && !name.startsWith("java.") && !name.startsWith("jdk.") && 
            !name.startsWith("sun.") && !name.contains("<clinit>") && 
            !name.contains("QueryParser.parse");
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
}
