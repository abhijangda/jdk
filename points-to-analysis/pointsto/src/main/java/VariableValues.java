import java.util.HashSet;

import soot.Unit;
import soot.Value;

public class VariableValues extends HashSet<VariableValue> {
  public final Value variable;
  public final Unit stmt;

  public VariableValues(Value variable, Unit stmt) {
    super();
    this.variable = variable;
    this.stmt = stmt;
  }
}