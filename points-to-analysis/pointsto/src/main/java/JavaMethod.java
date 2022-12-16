import org.apache.bcel.classfile.*;
import org.apache.bcel.*;

public class JavaMethod {
  private Method method;
  private JavaClass klass;

  public JavaMethod(Method m, JavaClass k) {
    this.method = m;
    this.klass = k;
  }

  Method getMethod() {return method;}
  JavaClass getKlass() {return klass;}
}
