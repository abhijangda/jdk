import org.apache.bcel.classfile.*;

public class JavaMethod {
  private Method method;
  private JavaClass klass;

  public JavaMethod(Method m, JavaClass k) {
    this.method = m;
    this.klass = k;
  }

  public Method getMethod() {return method;}
  public JavaClass getKlass() {return klass;}

  public String getFullName() {
    return getKlass().getClassName() + "." + getMethod().getName() + getMethod().getSignature();
  }
}
