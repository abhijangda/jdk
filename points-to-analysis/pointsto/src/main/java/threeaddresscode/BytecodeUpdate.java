package threeaddresscode;

import java.util.Vector;

public class BytecodeUpdate {
  public final int bci;
  public final int opcode;

  private Vector<Variable> inputs_;
  private Vector<Variable> outputs_;

  public BytecodeUpdate(int bci, int opcode) {
    this.bci = bci;
    this.opcode = opcode;
    this.inputs_ = new Vector<>();
    this.outputs_ = new Vector<>();
  }

  public void addInput(Variable input) {
    inputs_.add(input);
  }

  public void addOutput(Variable output) {
    outputs_.add(output);
  }

  //TODO: these should be returned as constant
  Vector<Variable> inputs() {return inputs_;}
  Vector<Variable> outputs() {return outputs_;}
}
