package threeaddresscode;

import java.util.ArrayList;

import org.apache.bcel.generic.Type;

public class LocalVars extends ArrayList<ArrayList<LocalVar>>{
  public LocalVars(int initialCapacity) {
    super(initialCapacity);
    for (int i = 0; i < initialCapacity; i++) {
      add(new ArrayList<LocalVar>());
    }
  }

  public LocalVar findOrAddLocalVar(Type type, int index, int bci) {
    for (LocalVar l : get(index)) {
      if (l.startPc <= bci && bci < l.startPc + l.length) {
        return l;
      }
    }

    LocalVar l = new LocalVar(type, index, bci, 0);
    get(index).add(l);
    return l;
  }
}
