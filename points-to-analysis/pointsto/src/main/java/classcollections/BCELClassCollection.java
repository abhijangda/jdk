package classcollections;

import org.apache.bcel.classfile.*;
import java.util.jar.*;

import java.nio.file.*;
import java.util.*;
import java.io.*;

public class BCELClassCollection extends HashMap<String, JavaClass> {
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

  private static BCELClassCollection collection = null;

  public static BCELClassCollection v() {
    if (collection == null) {
      collection = new BCELClassCollection();
    }
    return collection;
  }
  public static BCELClassCollection createFromJar(String jarFile) {
    if (collection == null) {
      collection = new BCELClassCollection();
    }
    _createFromJar(jarFile, collection);
    return collection;
  }

  private static void _createFromJar(String jarFile, BCELClassCollection collection) {
    try {
      JarInputStream jarIn = new JarInputStream(new FileInputStream (jarFile));
      JarEntry entry = jarIn.getNextJarEntry();
      while (entry != null) {
        if (entry.getName().endsWith(".class")) {
          ClassParser parser = new ClassParser(jarIn, entry.getName());
          JavaClass javaClass = parser.parse();
          collection.put(javaClass.getClassName(), javaClass);
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

  private void loadJavaLibraryClass(String classStr) {
    Path javaBase = Paths.get("/mnt/homes/aabhinav/jdk/build/linux-x86_64-server-release/jdk/modules/java.base/");
    Path classPath = javaBase.resolve(classStr.replace(".", "/") + ".class");
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

  public JavaClass getClassForString(String classStr) {
    if ((classStr.contains("java.") || classStr.contains("jdk.") || classStr.contains("sun.")) && !containsKey(classStr))
      loadJavaLibraryClass(classStr);
    if (!JavaClassCollection.classToCare(classStr)) return null;
    return get(classStr);
  }

  public Method getMethod(String methodStr) {
    if (!JavaClassCollection.methodToCare(methodStr)) {      
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
            return m;
        }
        // System.out.println(classname + methodname + signature);
      }
    }

    System.out.println("Cannot find method " + methodStr);

    return null;
  }
}

