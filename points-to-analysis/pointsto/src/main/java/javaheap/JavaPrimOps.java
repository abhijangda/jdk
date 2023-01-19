package javaheap;

public interface JavaPrimOps {
  public JavaPrimValue add(JavaPrimValue o);
  public JavaPrimValue minus(JavaPrimValue o);

  public boolean eq(JavaPrimValue o);
  public boolean neq(JavaPrimValue o);
  public boolean gt(JavaPrimValue o);
  public boolean lt(JavaPrimValue o);
  public boolean ge(JavaPrimValue o);
  public boolean le(JavaPrimValue o);
}