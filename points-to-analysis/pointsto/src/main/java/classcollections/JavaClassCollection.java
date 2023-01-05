package classcollections;

import javatypes.JavaArrayType;
import javatypes.JavaObjectType;
import soot.AbstractJasminClass;
import soot.ArrayType;
import soot.BooleanType;
import soot.ByteType;
import soot.CharType;
import soot.DoubleType;
import soot.FloatType;
import soot.IntType;
import soot.LongType;
import soot.Scene;
import soot.ShortType;
import soot.SootClass;
import soot.SootMethod;
import soot.Type;
import soot.options.Options;
import utils.Utils;

import java.util.jar.*;
import java.nio.file.*;
import java.util.*;
import java.io.*;

public class JavaClassCollection extends HashMap<String, SootClass> {
  private Scene scene;
  
  public static JavaClassCollection loadFromJar(String jarFile) {
    JavaClassCollection collection = new JavaClassCollection();
    ArrayList<String> jars = new ArrayList<>();
    String extractPath = "/mnt/homes/aabhinav/jdk/points-to-analysis/pointsto/";
    jarFile = extractPath + "dacapo-9.12-MR1-bach.jar";
    getJars(jarFile, extractPath, jars);
    
    System.out.println(jars);
    Options.v().set_allow_phantom_refs(true);
    Options.v().set_whole_program(true);
    Options.v().set_process_dir(jars);
    // Options.v().set_soot_classpath(extractPath + "dacapo-9.12-MR1-bach.jar");
    // Options.v().set_prepend_classpath(true);

    collection.scene = Scene.v();
    collection.scene.loadNecessaryClasses();

    for (SootClass c : collection.scene.getClasses()) {
      collection.put(c.getName(), c);
      // System.out.println(c.getName());
    }
    return collection;
  }

  /**
   * Extracts a zip entry (file entry)
   * @param zipIn
   * @param filePath
   * @throws IOException
   */
  private static void extractFile(JarInputStream zipIn, String filePath) throws IOException {
    BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(filePath));
    byte[] bytesIn = new byte[1024*1024];
    int read = 0;
    try {
      while ((read = zipIn.read(bytesIn)) != -1) {
        bos.write(bytesIn, 0, read);
      }
    } catch (Exception e) {
    }
    bos.close();
    System.out.println("writing to file " + filePath + " " + Files.size(Paths.get(filePath)));
  }

  private static void getJars(String jarFile, String extractPath, ArrayList<String> jars) {
    jars.add(jarFile);
    try {
      JarInputStream jarIn = new JarInputStream(new FileInputStream (jarFile));
      JarEntry entry = jarIn.getNextJarEntry();
      while (entry != null) {
        if (entry.getName().endsWith(".jar") && !entry.getName().contains("antlr-3.1.3.jar")) {
          Path entryPath = Paths.get(entry.getName());
          String extractedJarFile = extractPath+entryPath.getFileName();
          extractFile(jarIn, extractedJarFile);
          getJars(extractedJarFile, extractPath, jars);
        }
        jarIn.closeEntry();
        entry = jarIn.getNextJarEntry();
      }
      jarIn.close();
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  public static boolean methodToCare(String name) {
    return !name.equals("NULL") && !name.contains("<clinit>") && !name.contains("0x") && !name.contains("_LL") &&
      !name.contains("jdk.internal.util.Preconditions$4") && !name.contains("sun.launcher.LauncherHelper") && !name.contains("sun.security.provider");
  }

  public static boolean classToCare(String name) {
    return !name.equals("NULL") && !name.contains("<clinit>") && !name.contains("0x") && !name.contains("_LL")
    && !name.contains("sun.launcher.LauncherHelper") && !name.contains("sun.security.provider");
  }

  public Type javaTypeForSignature(String sig) {
    if (!classToCare(sig)) return null;
    int arraydims = 0;
    String origSig = sig;
    for (; arraydims < sig.length() && sig.charAt(arraydims) == '['; arraydims++);
    sig = sig.substring(arraydims);
    
    Type basicType = null;
    if (sig.charAt(0) == 'L' && sig.charAt(sig.length() - 1) == ';') {
      SootClass cl = getClassForString(sig.substring(1, sig.length() - 1));
      basicType = cl.getType();
    } else if (sig.equals("Z") || sig.equals("boolean")) {
      basicType = BooleanType.v();
    } else if (sig.equals("B") || sig.equals("byte")) {
      basicType = ByteType.v();
    } else if (sig.equals("I") || sig.equals("int")) {
      basicType = IntType.v();
    } else if (sig.equals("C") || sig.equals("char")) {
      basicType = CharType.v();
    } else if (sig.equals("S") || sig.equals("short")) {
      basicType = ShortType.v();
    } else if (sig.equals("J") || sig.equals("long")) {
      basicType = LongType.v();
    } else if (sig.equals("F") || sig.equals("float")) {
      basicType = FloatType.v();
    } else if (sig.equals("D") || sig.equals("double")) {
      basicType = DoubleType.v();
    } else {
      SootClass cl = getClassForString(sig);
      if (cl == null) System.out.println(sig);
      basicType = cl.getType();
    }
    
    if (basicType == null)
      System.out.println("128: Invalid signature " + origSig);
    
    if (arraydims > 0) 
      return ArrayType.v(basicType, arraydims);
    else
      return basicType;
  }

  public SootClass getObjectClass() {
    return getClassForString("java.lang.Object");
  }
  public SootClass getClassForString(String classStr) {
    if (!classToCare(classStr)) return null;
    //Due to Soot's weird class loading, it loads classes from Jar as harness.org.dacapo.harness instead of
    //org.dacapo.harness
    if ((classStr.startsWith("org.dacapo") || classStr.startsWith("org.apache.commons")) && !classStr.startsWith("org.dacapo.lusearch")) {
      classStr = "harness." + classStr;
    }
    return get(classStr); //scene.loadClassAndSupport(classStr);
  }

  public SootMethod getMethod(String methodStr) {
    if (!methodToCare(methodStr)) {      
      return null;
    }
    if (methodStr == "NULL") {return null;}
    int bracket = methodStr.indexOf("(");
    if (bracket != -1) {
      int dotBeforeMethod = methodStr.lastIndexOf(".", bracket);
      if (dotBeforeMethod != -1) {
        String classname = methodStr.substring(0, dotBeforeMethod);
        String methodname = methodStr.substring(dotBeforeMethod + 1, bracket);
        String signature = methodStr.substring(bracket);
        int returnStrIdx = signature.indexOf(")") + 1;
        String returnStr = signature.substring(returnStrIdx);
        //params between "(" and ")"
        String paramsStr = signature.substring(1, returnStrIdx - 1);
        // System.out.println("\n158: " + classname + " " + methodname + " " + signature);
        SootClass javaclass = getClassForString(classname);
        if (javaclass == null) {
          System.out.println("null javaclass for " + classname);
          return null;
        }

        // for (SootMethod m : javaclass.getMethods()) {
        //   Utils.debugPrintln(m.toString());
        // }

        while (javaclass != null) {
          for (SootMethod m : javaclass.getMethods()) {
            if (m.getName().equals(methodname)) {
              if (AbstractJasminClass.jasminDescriptorOf(m.getReturnType()).equals(returnStr)) {
                List<Type> paramTypes = m.getParameterTypes();
                String _paramsStr = paramsStr;

                if (_paramsStr.isEmpty() && paramTypes.size() == 0)
                  return m;

                if (!_paramsStr.isEmpty() && paramTypes.size() > 0) {
                  boolean paramTypesSame = true;
                  int p;
                  for (p = 0; p < paramTypes.size(); p++) {
                    String jasminTypeStr = AbstractJasminClass.jasminDescriptorOf(paramTypes.get(p));
                    // if (m.getDeclaringClass().getName().contains("Set")) {
                    //   Utils.debugPrintln(m.toString() + " " + _paramsStr + " " + jasminTypeStr);
                    // }
                    if (!_paramsStr.isEmpty() && _paramsStr.startsWith(jasminTypeStr)) {
                      _paramsStr = _paramsStr.substring(jasminTypeStr.length());
                    } else {
                      paramTypesSame = false;
                      break;
                    }
                  }

                  paramTypesSame = paramTypesSame && _paramsStr.isEmpty() && p == paramTypes.size();
                  if (paramTypesSame)
                    return m;
                }
              }
            }
          }
          // System.out.println("173: " + javaclass.getName() + " " + javaclass.getMethodCount());
          // if (!javaclass.getName().equals("java.lang.Object")) {
          if (javaclass.hasSuperclass())
            javaclass = javaclass.getSuperclass();
          else
            javaclass = null;
          // } else {
          //   break;
          // }
        }
        // System.out.println(classname + " " + methodname + " " + signature);
      }
    }

    System.out.println("Cannot find method " + methodStr);

    return null;
  }
}
