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

import static dagger.internal.codegen.base.Scopes.scopesOf;
import static dagger.internal.codegen.base.Util.reentrantComputeIfAbsent;
import static dagger.internal.codegen.binding.AssistedInjectionAnnotations.isAssistedFactoryType;
import static dagger.internal.codegen.binding.AssistedInjectionAnnotations.isAssistedInjectionType;
import static dagger.internal.codegen.xprocessing.XTypes.isDeclared;
import static dagger.internal.codegen.xprocessing.XTypes.isPrimitive;
import static dagger.internal.codegen.xprocessing.XTypes.isTypeVariable;
import static java.util.Objects.requireNonNull;

import dagger.internal.codegen.base.FrameworkTypes;
import dagger.internal.codegen.binding.InjectionAnnotations;
import dagger.internal.codegen.xprocessing.XAnnotation;
import dagger.internal.codegen.xprocessing.XElement;
import dagger.internal.codegen.xprocessing.XType;
import dagger.internal.codegen.xprocessing.XTypeElement;
import dagger.model.Scope;
import dagger.spi.model.Key;
import java.util.Formatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** A validator for elements that represent binding declarations. */
public abstract class BindingElementValidator<E extends XElement> {
  private final AllowsScoping allowsScoping;
  private final Map<E, ValidationReport> cache = new HashMap<>();
  private final InjectionAnnotations injectionAnnotations;

  /**
   * Creates a validator object.
   */
  // TODO(bcorso): Consider reworking BindingElementValidator and all subclasses to use composition
  // rather than inheritance. The web of inheritance makes it difficult to track what implementation
  // of a method is actually being used.
  protected BindingElementValidator(
      AllowsScoping allowsScoping,
      InjectionAnnotations injectionAnnotations) {
    this.allowsScoping = allowsScoping;
    this.injectionAnnotations = injectionAnnotations;
  }

  /** Returns a {@link ValidationReport} for {@code element}. */
  final ValidationReport validate(E element) {
    return reentrantComputeIfAbsent(cache, element, this::validateUncached);
  }

  private ValidationReport validateUncached(E element) {
    return elementValidator(element).validate();
  }

  /**
   * Returns an error message of the form "&lt;{@link #bindingElements()}&gt; <i>rule</i>", where
   * <i>rule</i> comes from calling {@link String#format(String, Object...)} on {@code ruleFormat}
   * and the other arguments.
   */
  protected final String bindingElements(String ruleFormat, Object... args) {
    return new Formatter().format("%s ", bindingElements()).format(ruleFormat, args).toString();
  }

  /**
   * The kind of elements that this validator validates. Should be plural. Used for error reporting.
   */
  protected abstract String bindingElements();

  /** The verb describing the {@link ElementValidator#bindingElementType()} in error messages. */
  // TODO(ronshapiro,dpb): improve the name of this method and it's documentation.
  protected abstract String bindingElementTypeVerb();

  /** The error message when a binding element has a bad type. */
  protected String badTypeMessage() {
    return bindingElements(
        "must %s a primitive, an array, a type variable, or a declared type",
        bindingElementTypeVerb());
  }

  /*** Returns an {@link ElementValidator} for validating the given {@code element}. */
  protected abstract ElementValidator elementValidator(E element);

  /** Validator for a single binding element. */
  protected abstract class ElementValidator {
    protected final E element;
    protected final ValidationReport.Builder report;
    private final Set<XAnnotation> qualifiers;

    protected ElementValidator(E element) {
      this.element = element;
      this.report = ValidationReport.about(element);
      qualifiers = injectionAnnotations.getQualifiers(element);
    }

    /** Checks the element for validity. */
    private ValidationReport validate() {
      checkType();
      checkQualifiers();
      checkScopes();
      checkAdditionalProperties();
      return report.build();
    }

    /** Check any additional properties of the element. Does nothing by default. */
    protected void checkAdditionalProperties() {
    }

    /**
     * The type declared by this binding element. This may differ from a binding's {@link
     * Key#type()}, for example in multibindings. An {@link Optional#empty()} return value indicates
     * that the contributed type is ambiguous or missing, i.e. a {@code @BindsInstance} method with
     * zero or many parameters.
     */
    // TODO(dpb): should this be an ImmutableList<XType>, with this class checking the size?
    protected abstract Optional<XType> bindingElementType();

    /**
     * Adds an error if the {@link #bindingElementType() binding element type} is not appropriate.
     *
     * <p>Adds an error if the type is not a primitive, array, declared type, or type variable.
     */
    protected void checkType() {
      // Validate that a unique binding is not attempting to bind a framework type.
      checkFrameworkType();

      // Validate that a unique binding is not attempting to bind an unqualified assisted type.
      checkAssistedType();
      bindingElementType().ifPresent(this::checkKeyType);
    }

    /**
     * Adds an error if {@code keyType} is not a primitive, declared type, array, or type variable.
     */
    protected void checkKeyType(XType keyType) {
      if (keyType.isVoid()) {
        report.addError(bindingElements("must %s a value (not void)", bindingElementTypeVerb()));
      } else if (!(isPrimitive(keyType)
          || isDeclared(keyType)
          || keyType.isArray()
          || isTypeVariable(keyType))) {
        report.addError(badTypeMessage());
      }
    }

    /** Adds errors for unqualified assisted types. */
    private void checkAssistedType() {
      if (qualifiers.isEmpty()
          && bindingElementType().isPresent()
          && isDeclared(bindingElementType().get())) {
        XTypeElement keyElement = bindingElementType().get().getTypeElement();
        if (isAssistedInjectionType(keyElement)) {
          report.addError("Dagger does not support providing @AssistedInject types.", keyElement);
        }
        if (isAssistedFactoryType(keyElement)) {
          report.addError("Dagger does not support providing @AssistedFactory types.", keyElement);
        }
      }
    }

    /**
     * Adds an error if the element has more than one {@linkplain jakarta.inject.Qualifier qualifier} annotation.
     */
    private void checkQualifiers() {
      if (qualifiers.size() > 1) {
        for (XAnnotation qualifier : qualifiers) {
          report.addError(
              bindingElements("may not use more than one @Qualifier"),
              element,
              qualifier);
        }
      }
    }

    /**
     * Adds an error if the element has a scope but doesn't allow scoping, or if it has more than
     * one {@linkplain Scope scope} annotation.
     */
    private void checkScopes() {
      Set<Scope> scopes = scopesOf(element);
      String error = null;
      switch (allowsScoping) {
        case ALLOWS_SCOPING:
          if (scopes.size() <= 1) {
            return;
          }
          error = bindingElements("cannot use more than one @Scope");
          break;
        case NO_SCOPING:
          error = bindingElements("cannot be scoped");
          break;
      }
      requireNonNull(error);
      for (Scope scope : scopes) {
        report.addError(error, element.toJavac(), scope.scopeAnnotation().java());
      }
    }

    /**
     * Adds an error if the {@link #bindingElementType() type} is a {@linkplain FrameworkTypes
     * framework type}.
     */
    private void checkFrameworkType() {
      if (bindingElementType().filter(FrameworkTypes::isFrameworkType).isPresent()) {
        report.addError(bindingElements("must not %s framework types", bindingElementTypeVerb()));
      }
    }
  }

  /** How to check scoping annotations. */
  enum AllowsScoping {
    /** This element disallows scoping, so check that no scope annotations are present. */
    NO_SCOPING,

    /** This element allows scoping, so validate that there's at most one scope annotation. */
    ALLOWS_SCOPING,
  }
}
