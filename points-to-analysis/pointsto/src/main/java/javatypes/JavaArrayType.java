package javatypes;

import org.apache.bcel.classfile.*;
import org.apache.bcel.*;
import org.apache.bcel.generic.*;

public final class JavaArrayType extends ReferenceType {

  private final int dimensions;
  private final Type basicType;

  public JavaArrayType(final byte type, final int dimensions) {
      this(BasicType.getType(type), dimensions);
  }

  public JavaArrayType(final JavaClass klass, final int dimensions) {
      this(JavaObjectType.getInstance(klass), dimensions);
  }

  public JavaArrayType(final Type type, final int dimensions) {
    super(Const.T_ARRAY, "<dummy>");
    if (dimensions < 1 || dimensions > Const.MAX_BYTE) {
        throw new ClassGenException("Invalid number of dimensions: " + dimensions);
    }
    switch (type.getType()) {
    case Const.T_ARRAY:
        final JavaArrayType array = (JavaArrayType) type;
        this.dimensions = dimensions + array.dimensions;
        basicType = array.basicType;
        break;
    case Const.T_VOID:
        throw new ClassGenException("Invalid type: void[]");
    default: // Basic type or reference
        this.dimensions = dimensions;
        basicType = type;
        break;
    }
    
    // super.setSignature(buf.toString());
  }

  public String getSignatureString() {
    final StringBuilder buf = new StringBuilder();
    for (int i = 0; i < this.dimensions; i++) {
        buf.append('[');
    }
    buf.append(basicType.getSignature());

    return buf.toString();
  }
}