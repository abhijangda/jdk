package javatypes;

import org.apache.bcel.classfile.*;
import org.apache.bcel.*;
import org.apache.bcel.generic.*;

/**
 * Denotes reference such as java.lang.String.
 */
public class JavaObjectType extends ReferenceType {

  public static JavaObjectType getInstance(final JavaClass klass) {
      return new JavaObjectType(klass);
  }

  private final JavaClass klass; // Class name of type

  /**
   * Constructs a new instance.
   *
   * @param className fully qualified class name, e.g. java.lang.String
   */
  public JavaObjectType(final JavaClass klass) {
      super(Const.T_REFERENCE, ""); //Ignore the signature
      this.klass = klass;
  }

  /**
   * @return true if both type objects refer to the same class.
   */
  @Override
  public boolean equals(final Object type) {
      return type instanceof JavaObjectType && ((JavaObjectType) type).klass.equals(klass);
  }

  @Override
  public String toString() {
    if (this.klass != null)
        return klass.getClassName();
    return "";
  }
}