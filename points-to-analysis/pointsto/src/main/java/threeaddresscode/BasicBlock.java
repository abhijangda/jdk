package threeaddresscode;

import java.lang.reflect.Array;
import java.util.ArrayList;

public class BasicBlock {
  public final int number;
  public final int start;
  private int end;
  private ArrayList<BasicBlock> ins;
  private ArrayList<BasicBlock> outs;

  public BasicBlock(int number, int start) {
    this.number = number;
    this.start = start;
    this.end = 0;
    ins = new ArrayList<>();
    outs = new ArrayList<>();
  }

  public void setEnd(int e) {end = e;}
  public int getEnd() {return end;}
  public int size() {return end - start;}
  public void addIn(BasicBlock in) {ins.add(in);}
  public void addOut(BasicBlock out) {outs.add(out);}

  public final ArrayList<BasicBlock> getOuts() {return outs;}
}
