package utils;
public class Pair<T, U> {
  public final T first;
  public final U second;

  public Pair(T first, U second) {
    this.first = first;
    this.second = second;
  }

  public static <T, U> Pair<T, U> v(T first, U second) {
    return new Pair<T, U>(first, second);
  }

  @Override
  public int hashCode() {
      int hash = 7;
      hash = 31 * hash + (null == first ? 0 : first.hashCode());
      hash = 31 * hash + (null == second ? 0 : second.hashCode());
      return hash;
  }

  @Override
    public boolean equals(Object obj) {
      if (obj == null)
          return false;
      if (obj == this)
          return true;
      if (obj.getClass() != getClass())
          return false;

      Pair<?, ?> p = (Pair<?, ?>)obj;

      return first.equals(p.first) && second.equals(p.second);
    }
}