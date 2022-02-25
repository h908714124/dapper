/*
 * Copyright (C) 2016 The Dagger Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dagger.internal.codegen.validation;

import static dagger.internal.codegen.xprocessing.XElements.hasAnyAnnotation;
import static dagger.internal.codegen.xprocessing.XMethodElements.getEnclosingTypeElement;
import static dagger.internal.codegen.xprocessing.XMethodElements.hasTypeParameters;
import static java.util.stream.Collectors.joining;

import dagger.internal.codegen.binding.InjectionAnnotations;
import dagger.internal.codegen.collect.ImmutableSet;
import dagger.internal.codegen.javapoet.TypeNames;
import dagger.internal.codegen.langmodel.DaggerTypes;
import dagger.internal.codegen.xprocessing.XExecutableElement;
import dagger.internal.codegen.xprocessing.XMethodElement;
import dagger.internal.codegen.xprocessing.XProcessingEnv;
import dagger.internal.codegen.xprocessing.XType;
import dagger.internal.codegen.xprocessing.XTypeElement;
import dagger.internal.codegen.xprocessing.XVariableElement;
import io.jbock.javapoet.ClassName;
import java.util.Optional;
import java.util.Set;

/** A validator for methods that represent binding declarations. */
abstract class BindingMethodValidator extends BindingElementValidator<XMethodElement> {

  private final XProcessingEnv processingEnv;
  private final DaggerTypes types;
  private final DependencyRequestValidator dependencyRequestValidator;
  private final ClassName methodAnnotation;
  private final ImmutableSet<ClassName> enclosingElementAnnotations;
  private final Abstractness abstractness;
  private final ExceptionSuperclass exceptionSuperclass;

  /**
   * Creates a validator object.
   *
   * @param methodAnnotation the annotation on a method that identifies it as a binding method
   * @param enclosingElementAnnotations the method must be declared in a class or interface
   *     annotated with one of these annotations
   */
  protected BindingMethodValidator(
      XProcessingEnv processingEnv,
      DaggerTypes types,
      ClassName methodAnnotation,
      Set<ClassName> enclosingElementAnnotations,
      DependencyRequestValidator dependencyRequestValidator,
      Abstractness abstractness,
      ExceptionSuperclass exceptionSuperclass,
      AllowsScoping allowsScoping,
      InjectionAnnotations injectionAnnotations) {
    super(allowsScoping, injectionAnnotations);
    this.processingEnv = processingEnv;
    this.types = types;
    this.methodAnnotation = methodAnnotation;
    this.enclosingElementAnnotations = ImmutableSet.copyOf(enclosingElementAnnotations);
    this.dependencyRequestValidator = dependencyRequestValidator;
    this.abstractness = abstractness;
    this.exceptionSuperclass = exceptionSuperclass;
  }

  /** The annotation that identifies binding methods validated by this object. */
  final ClassName methodAnnotation() {
    return methodAnnotation;
  }

  /**
   * Returns an error message of the form "@<i>annotation</i> methods <i>rule</i>", where
   * <i>rule</i> comes from calling {@code String#format(String, Object...)} on {@code ruleFormat}
   * and the other arguments.
   */
  protected final String bindingMethods(String ruleFormat, Object... args) {
    return bindingElements(ruleFormat, args);
  }

  @Override
  protected final String bindingElements() {
    return String.format("@%s methods", methodAnnotation.simpleName());
  }

  @Override
  protected final String bindingElementTypeVerb() {
    return "return";
  }

  /** Abstract validator for individual binding method elements. */
  protected abstract class MethodValidator extends ElementValidator {
    private final XMethodElement method;

    protected MethodValidator(XMethodElement method) {
      super(method);
      this.method = method;
    }

    @Override
    protected final Optional<XType> bindingElementType() {
      return Optional.of(method.getReturnType());
    }

    @Override
    protected final void checkAdditionalProperties() {
      checkEnclosingElement();
      checkTypeParameters();
      checkNotPrivate();
      checkAbstractness();
      checkThrows();
      checkParameters();
      checkAdditionalMethodProperties();
    }

    /** Checks additional properties of the binding method. */
    protected void checkAdditionalMethodProperties() {}

    /**
     * Adds an error if the method is not declared in a class or interface annotated with one of the
     * {@code #enclosingElementAnnotations}.
     */
    private void checkEnclosingElement() {
      XTypeElement enclosingTypeElement = getEnclosingTypeElement(method);
      if (enclosingTypeElement.isCompanionObject()) {
        // Binding method is in companion object, use companion object's enclosing class instead.
        enclosingTypeElement = enclosingTypeElement.getEnclosingTypeElement();
      }
      if (!hasAnyAnnotation(enclosingTypeElement, enclosingElementAnnotations)) {
        report.addError(
            bindingMethods(
                "can only be present within a @%s",
                enclosingElementAnnotations.stream()
                    .map(ClassName::simpleName)
                    .collect(joining(" or @"))));
      }
    }

    /** Adds an error if the method is generic. */
    private void checkTypeParameters() {
      if (hasTypeParameters(method)) {
        report.addError(bindingMethods("may not have type parameters"));
      }
    }

    /** Adds an error if the method is private. */
    private void checkNotPrivate() {
      if (method.isPrivate()) {
        report.addError(bindingMethods("cannot be private"));
      }
    }

    /** Adds an error if the method is abstract but must not be, or is not and must be. */
    private void checkAbstractness() {
      boolean isAbstract = method.isAbstract();
      switch (abstractness) {
        case MUST_BE_ABSTRACT:
          if (!isAbstract) {
            report.addError(bindingMethods("must be abstract"));
          }
          break;

        case MUST_BE_CONCRETE:
          if (isAbstract) {
            report.addError(bindingMethods("cannot be abstract"));
          }
      }
    }

    /**
     * Adds an error if the method declares throws anything but an {@code Error} or an appropriate
     * subtype of {@code Exception}.
     */
    private void checkThrows() {
      exceptionSuperclass.checkThrows(BindingMethodValidator.this, method, report);
    }

    /** Adds errors for the method parameters. */
    protected void checkParameters() {
      for (XVariableElement parameter : method.getParameters()) {
        checkParameter(parameter);
      }
    }

    /**
     * Adds errors for a method parameter. This implementation reports an error if the parameter has
     * more than one qualifier.
     */
    protected void checkParameter(XVariableElement parameter) {
      dependencyRequestValidator.validateDependencyRequest(report, parameter, parameter.getType());
    }
  }

  /** An abstract/concrete restriction on methods. */
  protected enum Abstractness {
    MUST_BE_ABSTRACT,
    MUST_BE_CONCRETE
  }

  /**
   * The exception class that all {@code throws}-declared throwables must extend, other than {@code
   * Error}.
   */
  protected enum ExceptionSuperclass {
    /** Methods may not declare any throwable types. */
    NO_EXCEPTIONS {
      @Override
      protected String errorMessage(BindingMethodValidator validator) {
        return validator.bindingMethods("may not throw");
      }

      @Override
      protected void checkThrows(
          BindingMethodValidator validator,
          XExecutableElement element,
          ValidationReport.Builder report) {
        if (!element.getThrownTypes().isEmpty()) {
          report.addError(validator.bindingMethods("may not throw"));
          return;
        }
      }
    },

    /** Methods may throw checked or unchecked exceptions or errors. */
    EXCEPTION(TypeNames.EXCEPTION) {
      @Override
      protected String errorMessage(BindingMethodValidator validator) {
        return validator.bindingMethods(
            "may only throw unchecked exceptions or exceptions subclassing Exception");
      }
    },

    /** Methods may throw unchecked exceptions or errors. */
    RUNTIME_EXCEPTION(TypeNames.RUNTIME_EXCEPTION) {
      @Override
      protected String errorMessage(BindingMethodValidator validator) {
        return validator.bindingMethods("may only throw unchecked exceptions");
      }
    },
    ;

    @SuppressWarnings("Immutable")
    private final ClassName superclass;

    ExceptionSuperclass() {
      this(null);
    }

    ExceptionSuperclass(ClassName superclass) {
      this.superclass = superclass;
    }

    /**
     * Adds an error if the method declares throws anything but an {@code Error} or an appropriate
     * subtype of {@code Exception}.
     *
     * <p>This method is overridden in {@code #NO_EXCEPTIONS}.
     */
    protected void checkThrows(
        BindingMethodValidator validator,
        XExecutableElement element,
        ValidationReport.Builder report) {
      XType exceptionSupertype = validator.processingEnv.findType(superclass);
      XType errorType = validator.processingEnv.findType(TypeNames.ERROR);
      for (XType thrownType : element.getThrownTypes()) {
        if (!validator.types.isSubtype(thrownType, exceptionSupertype)
            && !validator.types.isSubtype(thrownType, errorType)) {
          report.addError(errorMessage(validator));
          break;
        }
      }
    }

    protected abstract String errorMessage(BindingMethodValidator validator);
  }
}
