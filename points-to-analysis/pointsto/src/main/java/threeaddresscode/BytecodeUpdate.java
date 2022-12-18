package threeaddresscode;

import java.util.Vector;

public class BytecodeUpdate {
  public final int bci;
  public final int opcode;

  private Vector<Var> inputs_;
  private Vector<Var> outputs_;

  public BytecodeUpdate(int bci, int opcode) {
    this.bci = bci;
    this.opcode = opcode;
    this.inputs_ = new Vector<>();
    this.outputs_ = new Vector<>();
  }

  public void addInput(Var input) {
    inputs_.add(input);
  }

  public void addOutput(Var output) {
    outputs_.add(output);
  }

  //TODO: these should be returned as constant
  Vector<Var> inputs() {return inputs_;}
  Vector<Var> outputs() {return outputs_;}
}
