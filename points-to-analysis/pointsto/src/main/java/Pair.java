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
}