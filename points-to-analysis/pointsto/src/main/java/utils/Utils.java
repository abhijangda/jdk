package utils;

import soot.SootMethod;
import soot.Type;
import soot.AbstractJasminClass;

public abstract class Utils {
  public static boolean DEBUG_PRINT = true;
  public static void debugPrintln(String x) {
    if (DEBUG_PRINT) {
      System.out.println(x);
    }
  }
  public static void debugLog(String fmt, Object... args) {
    if (DEBUG_PRINT) {
      System.err.printf(fmt, args);
    }
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
    return !name.equals("NULL") && !name.contains("java.") && !name.contains("jdk.") && !name.contains("sun.") && !name.contains("<clinit>");
  }
}
