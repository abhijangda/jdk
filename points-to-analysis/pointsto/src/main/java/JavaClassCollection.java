import org.apache.bcel.classfile.*;
import org.apache.bcel.generic.Type;

import javatypes.JavaArrayType;
import javatypes.JavaObjectType;
import soot.Scene;
import soot.SootClass;
import soot.options.Options;

import java.util.jar.*;
import java.nio.file.*;
import java.util.*;
import java.io.*;

public class JavaClassCollection extends HashMap<String, JavaClass> {
  private Scene scene;
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

  public static JavaClassCollection createFromJar(String jarFile) {
    JavaClassCollection collection = new JavaClassCollection();
    ArrayList<String> jars = new ArrayList<>();
    String extractPath = "/mnt/homes/aabhinav/jdk/points-to-analysis/pointsto/";
    jarFile = extractPath + "dacapo-9.12-MR1-bach.jar";
    getJars(jarFile, extractPath, jars);
    
    System.out.println(jars);
    Options.v().set_allow_phantom_refs(true);
    Options.v().set_whole_program(true);

    Options.v().set_process_dir(jars);
    Options.v().set_soot_classpath(extractPath);
    Options.v().set_prepend_classpath(true);

    collection.scene = Scene.v();
    collection.scene.loadNecessaryClasses();
    collection.scene.loadBasicClasses();
    for (SootClass clazz : Scene.v().getClasses()) {
        if (!clazz.getName().equals("java.lang.Object")) {
          SootClass s = clazz.getSuperclass();
          System.out.println("Class name: " + clazz.getName() + " " + clazz.getMethods().size() + " " + ((s == null) ? "" : s.getName())); 
        }
      }

    return collection;
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
    return !name.equals("NULL") && !name.contains("<clinit>") && !name.contains("0x") && !name.contains("_LL");
  }

  public static boolean classToCare(String name) {
    return !name.equals("NULL") && !name.contains("<clinit>") && !name.contains("0x") && !name.contains("_LL");
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

  public Type javaTypeForSignature(String sig) {
    int arraydims = 0;
    String origSig = sig;
    for (; arraydims < sig.length() && sig.charAt(arraydims) == '['; arraydims++);
    sig = sig.substring(arraydims);
    
    Type basicType = null;
    if (sig.charAt(0) == 'L' && sig.charAt(sig.length() - 1) == ';') {
      JavaClass cl = getClassForString(sig.substring(1, sig.length() - 1));
      basicType = JavaObjectType.getInstance(cl);
    } else if (sig.equals("Z")) {
      basicType = Type.BOOLEAN;
    } else if (sig.equals("B")) {
      basicType = Type.BYTE;
    } else if (sig.equals("I")) {
      basicType = Type.INT;
    } else if (sig.equals("C")) {
      basicType = Type.CHAR;
    } else if (sig.equals("S")) {
      basicType = Type.SHORT;
    } else if (sig.equals("J")) {
      basicType = Type.LONG;
    } else if (sig.equals("F")) {
      basicType = Type.FLOAT;
    } else if (sig.equals("D")) {
      basicType = Type.DOUBLE;
    } else {
      JavaClass cl = getClassForString(sig);
      basicType = JavaObjectType.getInstance(cl);
    }
    
    if (basicType == null)
      System.out.println("128: Invalid signature " + origSig);
    
    if (arraydims > 0) 
      return new JavaArrayType(basicType, arraydims);
    else
      return basicType;
  }

  public JavaClass getObjectClass() {
    return getClassForString("java.lang.Object");
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
          System.out.println("null javaclass for " + classname);
          return null;
        }
        while (true) {
          for (Method m : javaclass.getMethods()) {
            // if (javaclass.getClassName().contains("store.Directory") || 
            //     javaclass.getClassName().contains("store.FSDirectory")) {
            //   System.out.println(m.isPublic() + " " + m.isProtected() + " " + javaclass.getClassName() + "." + m.getName() + m.getSignature());
            // }
            if (m.getName().equals(methodname) && m.getSignature().equals(signature))
              return new JavaMethod(m, javaclass);
          }
          
          if (!javaclass.getClassName().equals("java.lang.Object")) {
            String supername = javaclass.getSuperclassName();
            javaclass = getClassForString(supername);
          } else {
            break;
          }
        }
        System.out.println(classname + " " + methodname + " " + signature);
      }
    }

    System.out.println("Cannot find method " + methodStr);

    return null;
  }
}
