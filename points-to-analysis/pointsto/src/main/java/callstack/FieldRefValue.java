package callstack;
import javaheap.JavaHeapElem;
import polyglot.ast.Variable;
import soot.SootField;
import soot.SootFieldRef;
import soot.jimple.FieldRef;
import utils.Utils;

public class FieldRefValue extends VariableValue {
  public final VariableValue base;
  public final SootFieldRef field;

  public FieldRefValue(VariableValue base, SootFieldRef field, JavaHeapElem value) {
    super(null, null);
    Utils.shouldNotReachHere();
    this.base = base;
    this.field = field;
  }
}
