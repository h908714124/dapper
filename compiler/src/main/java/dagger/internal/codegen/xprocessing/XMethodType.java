package dagger.internal.codegen.xprocessing;

import io.jbock.javapoet.TypeVariableName;
import java.util.List;
import java.util.stream.Collectors;
import javax.lang.model.type.ExecutableType;

/**
 * Represents a type information for a method.
 *
 * It is not an XType as it does not represent a class or primitive.
 */
public class XMethodType {

  private final XProcessingEnv env;
  private final XMethodElement element;
  private final ExecutableType executableType;

  XMethodType(
      XProcessingEnv env,
      XMethodElement element,
      ExecutableType executableType) {
    this.env = env;
    this.element = element;
    this.executableType = executableType;
  }

  /**
   * The return type of the method
   */
  XType getReturnType() {
    return env.wrap(executableType.getReturnType());
  }

  /**
   * Returns the names of [TypeVariableName]s for this executable.
   */
  List<TypeVariableName> getTypeVariableNames() {
    return executableType.getTypeVariables().stream()
        .map(TypeVariableName::get)
        .collect(Collectors.toList());
  }
}
