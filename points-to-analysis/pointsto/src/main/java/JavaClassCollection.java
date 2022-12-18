import org.apache.bcel.classfile.*;
import org.apache.bcel.*;
import org.apache.bcel.generic.Type;
import org.apache.bcel.util.*;
import java.util.jar.*;

import javax.print.attribute.IntegerSyntax;
import javax.swing.plaf.basic.BasicInternalFrameTitlePane.SystemMenuBar;
import java.nio.file.*;
import java.util.*;
import java.io.*;
import java.util.zip.ZipInputStream;

public class JavaClassCollection extends HashMap<String, JavaClass> {
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
    while ((read = zipIn.read(bytesIn)) != -1) {
        bos.write(bytesIn, 0, read);
    }
    bos.close();
  }

  public static JavaClassCollection createFromJar(String jarFile) {
    JavaClassCollection collection = new JavaClassCollection();
    _createFromJar(jarFile, collection);
    return collection;
  }

  private static void _createFromJar(String jarFile, JavaClassCollection collection) {
    try {
      JarFile jar = new JarFile(jarFile);
      JarInputStream jarIn = new JarInputStream(new FileInputStream (jarFile));
      JarEntry entry = jarIn.getNextJarEntry();
      while (entry != null) {
        if (entry.getName().endsWith(".class")) {
          ClassParser parser = new ClassParser(jarIn, entry.getName());
          JavaClass javaClass = parser.parse();
          collection.put(javaClass.getClassName(), javaClass);
          // System.out.println(javaClass.getClassName());
          // for (Method m : javaClass.getMethods()) {
          //   String methodName = javaClass.getClassName() +
          //                       "." + m.getName() + m.getSignature();
          //   // if (methodName.contains("org.apache.lucene.store.FSDirectory."))
          //   //   System.out.println(methodName);
          //   methodNameMap.put(methodName, m);
          // }
        } else if (entry.getName().endsWith(".jar")) {
          Path entryPath = Paths.get(entry.getName());
          String extractedJarFile = "/tmp/"+entryPath.getFileName();
          extractFile(jarIn, extractedJarFile);
          _createFromJar(extractedJarFile, collection);
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
    return !name.equals("NULL") && !name.contains("<clinit>");
  }

  public static boolean classToCare(String name) {
    return !name.equals("NULL") && !name.contains("<clinit>");
  }

  private void loadJavaLibraryClass(String classStr) {
    Path javaBase = Paths.get("/mnt/homes/aabhinav/jdk/build/linux-x86_64-server-release/jdk/modules/java.base/");
    Path classPath = javaBase.resolve(Utility.packageToPath(classStr) + ".class");
    if (!Files.exists(classPath, LinkOption.NOFOLLOW_LINKS))
      ;//System.out.println(classPath + " do not exists for " + classStr);
    
    ClassParser parser = new ClassParser(classPath.toString());
    try {
      JavaClass javaClass = parser.parse();
      put(javaClass.getClassName(), javaClass);
    } catch (Exception e) {
      // e.printStackTrace();
    }
  }

  public JavaClass getClassForSignature(String sig) {
    boolean isArray = false;
    if (sig.charAt(0) == '[') {
      isArray = true;
      sig = sig.substring(1);
    }

    if (sig.charAt(0) == 'L' && sig.charAt(sig.length() - 1) == ';') {
      return getClassForString(sig.substring(1, sig.length() - 1));
    } else if (sig.equals("B")) {
      return null;
    } else if (sig.equals("Z")) {
      return null;
    } else if (sig.equals("I")) {
      return null;
    }

    System.out.println("Invalid signature " + sig);
    return null;
  }

  public JavaClass getClassForString(String classStr) {
    if ((classStr.contains("java.") || classStr.contains("jdk.") || classStr.contains("sun.")) && !containsKey(classStr))
      loadJavaLibraryClass(classStr);
    if (!classToCare(classStr)) return null;
    return get(classStr);
  }

  public JavaMethod getMethod(String methodStr) {
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

        JavaClass javaclass = getClassForString(classname);
        if (javaclass == null) {
          // System.out.println("null javaclass for " + classname);
          return null;
        }
        for (Method m : javaclass.getMethods()) {
          if (m.getName().equals(methodname) && m.getSignature().equals(signature))
            return new JavaMethod(m, javaclass);
        }
        // System.out.println(classname + methodname + signature);
      }
    }

    System.out.println("Cannot find method " + methodStr);

    return null;
  }
}
