class HasserWithGetter {
  @lombok.Getter @lombok.experimental.Hasser Boolean foo;
  HasserWithGetter() {
    super();
  }
  public @java.lang.SuppressWarnings("all") @javax.annotation.Generated("lombok") Boolean getFoo() {
    return this.foo;
  }
  public @java.lang.SuppressWarnings("all") @javax.annotation.Generated("lombok") boolean hasFoo() {
    return (this.getFoo() != null);
  }
}
