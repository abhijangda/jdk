package utils;

import java.util.*;

public class ArrayListIterator<T> {
  private final ArrayList<T> arrayList;
  private int index;

  public ArrayListIterator(ArrayList<T> arrayList) {
    this.arrayList = arrayList;
    this.index = 0;
  }

  public boolean hasPrevious() {
    return this.index > 0;
  }

  public boolean hasNext() {
    return this.index < arrayList.size() - 1;
  }

  public int nextIndex() {
    return this.index + 1;
  }

  public int previousIndex() {
    return this.index - 1;
  }

  public void movePrevious() {
    this.index--;
  }

  public void moveNext() {
    this.index++;
  }

  public T get() {
    return arrayList.get(this.index);
  }

  public T peekNext() {
    return arrayList.get(this.index + 1);
  }
}
