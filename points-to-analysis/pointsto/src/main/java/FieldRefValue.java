import polyglot.ast.Variable;
import soot.SootField;
import soot.SootFieldRef;
import soot.jimple.FieldRef;

public class FieldRefValue extends VariableValue {
  public final VariableValue base;
  public final SootFieldRef field;

  public FieldRefValue(VariableValue base, SootFieldRef field, long value) {
    super(field.type(), value);
    this.base = base;
    this.field = field;
  }

  public FieldRefValue(VariableValue base, SootFieldRef field) {
    this(base, field, VariableValue.UnkownPtr);
  }
}
