package threeaddresscode;

import java.lang.reflect.Array;
import java.util.ArrayList;

public class BasicBlock {
  public final int number;
  public final int start;
  public final int end;
  private ArrayList<BasicBlock> ins;
  private ArrayList<BasicBlock> outs;

  public BasicBlock(int number, int start, int end) {
    this.number = number;
    this.start = start;
    this.end = end;
    ins = new ArrayList<>();
    outs = new ArrayList<>();
  }

  public int size() {return end - start;}
  public void addIn(BasicBlock in) {ins.add(in);}
  public void addOut(BasicBlock out) {outs.add(out);}
  
  public final ArrayList<BasicBlock> getOuts() {return outs;}

  public final String toString() {
    return String.format("BasicBlock #%d: [%d, %d] size %d\n", number, start, end, size());
  }
}
