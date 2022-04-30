package dagger.internal.codegen.base;

import io.jbock.javapoet.TypeSpec;
import java.util.Optional;

public final class TopLevelType {

  private final TypeSpec.Builder typeSpec;
  private final Optional<String> packageName;

  private TopLevelType(TypeSpec.Builder typeSpec, Optional<String> packageName) {
    this.typeSpec = typeSpec;
    this.packageName = packageName;
  }

  public static TopLevelType of(TypeSpec.Builder typeSpec) {
    return new TopLevelType(typeSpec, Optional.empty());
  }

  public static TopLevelType of(TypeSpec.Builder typeSpec, String packageName) {
    return new TopLevelType(typeSpec, Optional.of(packageName));
  }

  public TypeSpec.Builder getTypeSpec() {
    return typeSpec;
  }

  public Optional<String> getPackageName() {
    return packageName;
  }
}
