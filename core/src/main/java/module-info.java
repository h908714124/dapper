module dagger {

  requires transitive java.compiler;
  requires jakarta.inject;

  exports dagger;
  exports dagger.multibindings;
  exports dagger.internal;
  exports dagger.assisted;
}