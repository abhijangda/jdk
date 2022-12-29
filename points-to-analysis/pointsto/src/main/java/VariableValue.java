import soot.Type;

public class VariableValue {
  public final Type sootType;
  public final long refValue;
  public static final long ThisPtr = 1;
  public static final long UnkownPtr = 1;

  public VariableValue(Type sootType, long refValue) {
    this.sootType = sootType;
    this.refValue = refValue;
  }

  public VariableValue(Type sootType) {
    this.sootType = sootType;
    this.refValue = UnkownPtr;
  }
}