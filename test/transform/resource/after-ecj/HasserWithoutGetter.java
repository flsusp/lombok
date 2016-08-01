class HasserWithoutGetter {
  @lombok.experimental.Hasser Boolean foo;
  HasserWithoutGetter() {
    super();
  }
  public @java.lang.SuppressWarnings("all") @javax.annotation.Generated("lombok") boolean hasFoo() {
    return (this.foo != null);
  }
}
