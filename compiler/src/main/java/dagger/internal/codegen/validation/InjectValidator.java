/*
 * Copyright (C) 2014 The Dagger Authors.
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
import static dagger.internal.codegen.binding.AssistedInjectionAnnotations.assistedInjectedConstructors;
import static dagger.internal.codegen.binding.InjectionAnnotations.injectedConstructors;
import static dagger.internal.codegen.collect.Iterables.getOnlyElement;
import static dagger.internal.codegen.xprocessing.XConverters.toJavac;
import static dagger.internal.codegen.xprocessing.XConverters.toXProcessing;
import static dagger.internal.codegen.xprocessing.XElements.getAnyAnnotation;
import static dagger.internal.codegen.xprocessing.XMethodElements.hasTypeParameters;

import dagger.internal.codegen.base.ClearableCache;
import dagger.internal.codegen.binding.InjectionAnnotations;
import dagger.internal.codegen.collect.ImmutableSet;
import dagger.internal.codegen.compileroption.CompilerOptions;
import dagger.internal.codegen.javapoet.TypeNames;
import dagger.internal.codegen.kotlin.KotlinMetadataUtil;
import dagger.internal.codegen.langmodel.Accessibility;
import dagger.internal.codegen.langmodel.DaggerElements;
import dagger.internal.codegen.langmodel.DaggerTypes;
import dagger.internal.codegen.xprocessing.XAnnotation;
import dagger.internal.codegen.xprocessing.XAnnotations;
import dagger.internal.codegen.xprocessing.XConstructorElement;
import dagger.internal.codegen.xprocessing.XElement;
import dagger.internal.codegen.xprocessing.XExecutableParameterElement;
import dagger.internal.codegen.xprocessing.XFieldElement;
import dagger.internal.codegen.xprocessing.XMethodElement;
import dagger.internal.codegen.xprocessing.XProcessingEnv;
import dagger.internal.codegen.xprocessing.XType;
import dagger.internal.codegen.xprocessing.XTypeElement;
import dagger.internal.codegen.xprocessing.XVariableElement;
import dagger.spi.model.Scope;
import io.jbock.javapoet.ClassName;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import javax.tools.Diagnostic;
import javax.tools.Diagnostic.Kind;

/**
 * A {@code ValidationReport validator} for {@code Inject}-annotated elements and the types
 * that contain them.
 */
@Singleton
public final class InjectValidator implements ClearableCache {
  private final XProcessingEnv processingEnv;
  private final DaggerTypes types;
  private final DaggerElements elements;
  private final CompilerOptions compilerOptions;
  private final DependencyRequestValidator dependencyRequestValidator;
  private final Optional<Diagnostic.Kind> privateAndStaticInjectionDiagnosticKind;
  private final InjectionAnnotations injectionAnnotations;
  private final KotlinMetadataUtil metadataUtil;
  private final Map<XTypeElement, ValidationReport> reports = new HashMap<>();

  @Inject
  InjectValidator(
      XProcessingEnv processingEnv,
      DaggerTypes types,
      DaggerElements elements,
      DependencyRequestValidator dependencyRequestValidator,
      CompilerOptions compilerOptions,
      InjectionAnnotations injectionAnnotations,
      KotlinMetadataUtil metadataUtil) {
    this(
        processingEnv,
        types,
        elements,
        compilerOptions,
        dependencyRequestValidator,
        Optional.empty(),
        injectionAnnotations,
        metadataUtil);
  }

  private InjectValidator(
      XProcessingEnv processingEnv,
      DaggerTypes types,
      DaggerElements elements,
      CompilerOptions compilerOptions,
      DependencyRequestValidator dependencyRequestValidator,
      Optional<Kind> privateAndStaticInjectionDiagnosticKind,
      InjectionAnnotations injectionAnnotations,
      KotlinMetadataUtil metadataUtil) {
    this.processingEnv = processingEnv;
    this.types = types;
    this.elements = elements;
    this.compilerOptions = compilerOptions;
    this.dependencyRequestValidator = dependencyRequestValidator;
    this.privateAndStaticInjectionDiagnosticKind = privateAndStaticInjectionDiagnosticKind;
    this.injectionAnnotations = injectionAnnotations;
    this.metadataUtil = metadataUtil;
  }

  @Override
  public void clearCache() {
    reports.clear();
  }

  /**
   * Returns a new validator that performs the same validation as this one, but is strict about
   * rejecting optionally-specified JSR 330 behavior that Dagger doesn't support (unless {@code
   * -Adagger.ignorePrivateAndStaticInjectionForComponent=enabled} was set in the javac options).
   */
  public InjectValidator whenGeneratingCode() {
    return compilerOptions.ignorePrivateAndStaticInjectionForComponent()
        ? this
        : new InjectValidator(
            processingEnv,
            types,
            elements,
            compilerOptions,
            dependencyRequestValidator,
            Optional.of(Diagnostic.Kind.ERROR),
            injectionAnnotations,
            metadataUtil);
  }

  public ValidationReport validate(XTypeElement typeElement) {
    return reentrantComputeIfAbsent(reports, typeElement, this::validateUncached);
  }

  private ValidationReport validateUncached(XTypeElement typeElement) {
    ValidationReport.Builder builder = ValidationReport.about(typeElement);
    builder.addSubreport(validateMembersInjectionType(typeElement));

    ImmutableSet<XConstructorElement> injectConstructors =
        ImmutableSet.<XConstructorElement>builder()
            .addAll(injectedConstructors(typeElement))
            .addAll(assistedInjectedConstructors(typeElement))
            .build();

    switch (injectConstructors.size()) {
      case 0:
        break; // Nothing to validate.
      case 1:
        builder.addSubreport(validateConstructor(getOnlyElement(injectConstructors)));
        break;
      default:
        builder.addError("Types may only contain one injected constructor", typeElement);
    }

    return builder.build();
  }

  private ValidationReport validateConstructor(XConstructorElement constructorElement) {
    DaggerSuperficialValidation.validateElement(constructorElement);
    ValidationReport.Builder builder =
        ValidationReport.about(constructorElement.getEnclosingElement());

    if (constructorElement.hasAnnotation(TypeNames.INJECT)
        && constructorElement.hasAnnotation(TypeNames.ASSISTED_INJECT)) {
      builder.addError("Constructors cannot be annotated with both @Inject and @AssistedInject");
    }

    ClassName injectAnnotation =
        getAnyAnnotation(constructorElement, TypeNames.INJECT, TypeNames.ASSISTED_INJECT)
            .map(XAnnotations::getClassName)
            .get();

    if (constructorElement.isPrivate()) {
      builder.addError(
          "Dagger does not support injection into private constructors", constructorElement);
    }

    for (XAnnotation qualifier : injectionAnnotations.getQualifiers(constructorElement)) {
      builder.addError(
          String.format(
              "@Qualifier annotations are not allowed on @%s constructors",
              injectAnnotation.simpleName()),
          constructorElement,
          qualifier);
    }

    String scopeErrorMsg =
        String.format(
            "@Scope annotations are not allowed on @%s constructors",
            injectAnnotation.simpleName());

    if (injectAnnotation.equals(TypeNames.INJECT)) {
      scopeErrorMsg += "; annotate the class instead";
    }

    for (Scope scope : scopesOf(constructorElement)) {
      builder.addError(
          scopeErrorMsg,
          constructorElement,
          toXProcessing(scope.scopeAnnotation().java(), processingEnv));
    }

    for (XExecutableParameterElement parameter : constructorElement.getParameters()) {
      validateDependencyRequest(builder, parameter);
    }

    if (throwsCheckedExceptions(constructorElement)) {
      builder.addItem(
          String.format(
              "Dagger does not support checked exceptions on @%s constructors",
              injectAnnotation.simpleName()),
          privateMemberDiagnosticKind(),
          constructorElement);
    }

    checkInjectIntoPrivateClass(constructorElement, builder);

    XTypeElement enclosingElement = constructorElement.getEnclosingElement();
    if (enclosingElement.isAbstract()) {
      builder.addError(
          String.format(
              "@%s is nonsense on the constructor of an abstract class",
              injectAnnotation.simpleName()),
          constructorElement);
    }

    if (enclosingElement.isNested() && !enclosingElement.isStatic()) {
      builder.addError(
          String.format(
              "@%s constructors are invalid on inner classes. "
                  + "Did you mean to make the class static?",
              injectAnnotation.simpleName()),
          constructorElement);
    }

    DaggerSuperficialValidation.validateAnnotationsOf(enclosingElement);
    ImmutableSet<Scope> scopes = scopesOf(enclosingElement);
    if (injectAnnotation.equals(TypeNames.ASSISTED_INJECT)) {
      for (Scope scope : scopes) {
        builder.addError(
            "A type with an @AssistedInject-annotated constructor cannot be scoped",
            enclosingElement,
            toXProcessing(scope.scopeAnnotation().java(), processingEnv));
      }
    } else if (scopes.size() > 1) {
      for (Scope scope : scopes) {
        builder.addError(
            "A single binding may not declare more than one @Scope",
            enclosingElement,
            toXProcessing(scope.scopeAnnotation().java(), processingEnv));
      }
    }

    return builder.build();
  }

  private ValidationReport validateField(XFieldElement fieldElement) {
    DaggerSuperficialValidation.validateElement(fieldElement);
    ValidationReport.Builder builder = ValidationReport.about(fieldElement);
    if (fieldElement.isFinal()) {
      builder.addError("@Inject fields may not be final", fieldElement);
    }

    if (fieldElement.isPrivate()) {
      builder.addItem(
          "Dagger does not support injection into private fields",
          privateMemberDiagnosticKind(),
          fieldElement);
    }

    if (fieldElement.isStatic()) {
      builder.addItem(
          "Dagger does not support injection into static fields",
          staticMemberDiagnosticKind(),
          fieldElement);
    }

    validateDependencyRequest(builder, fieldElement);

    return builder.build();
  }

  private ValidationReport validateMethod(XMethodElement methodElement) {
    DaggerSuperficialValidation.validateElement(methodElement);
    ValidationReport.Builder builder = ValidationReport.about(methodElement);
    if (methodElement.isAbstract()) {
      builder.addError("Methods with @Inject may not be abstract", methodElement);
    }

    if (methodElement.isPrivate()) {
      builder.addItem(
          "Dagger does not support injection into private methods",
          privateMemberDiagnosticKind(),
          methodElement);
    }

    if (methodElement.isStatic()) {
      builder.addItem(
          "Dagger does not support injection into static methods",
          staticMemberDiagnosticKind(),
          methodElement);
    }

    if (hasTypeParameters(methodElement)) {
      builder.addError("Methods with @Inject may not declare type parameters", methodElement);
    }

    if (!methodElement.getThrownTypes().isEmpty()) {
      builder.addError("Methods with @Inject may not throw checked exceptions. "
          + "Please wrap your exceptions in a RuntimeException instead.", methodElement);
    }

    for (XExecutableParameterElement parameter : methodElement.getParameters()) {
      validateDependencyRequest(builder, parameter);
    }

    return builder.build();
  }

  private void validateDependencyRequest(
      ValidationReport.Builder builder, XVariableElement parameter) {
    dependencyRequestValidator.validateDependencyRequest(builder, parameter, parameter.getType());
  }

  private ValidationReport validateMembersInjectionType(XTypeElement typeElement) {
    DaggerSuperficialValidation.validateTypeOf(typeElement);
    // TODO(beder): This element might not be currently compiled, so this error message could be
    // left in limbo. Find an appropriate way to display the error message in that case.
    ValidationReport.Builder builder = ValidationReport.about(typeElement);
    boolean hasInjectedMembers = false;
    for (XFieldElement field : typeElement.getDeclaredFields()) {
      if (field.hasAnnotation(TypeNames.INJECT)) {
        hasInjectedMembers = true;
        ValidationReport report = validateField(field);
        if (!report.isClean()) {
          builder.addSubreport(report);
        }
      }
    }
    for (XMethodElement method : typeElement.getDeclaredMethods()) {
      if (method.hasAnnotation(TypeNames.INJECT)) {
        hasInjectedMembers = true;
        ValidationReport report = validateMethod(method);
        if (!report.isClean()) {
          builder.addSubreport(report);
        }
      }
    }

    if (hasInjectedMembers) {
      checkInjectIntoPrivateClass(typeElement, builder);
      checkInjectIntoKotlinObject(typeElement, builder);
    }
    if (typeElement.getSuperType() != null) {
      DaggerSuperficialValidation.validateSuperTypeOf(typeElement);
      ValidationReport report =
          validateMembersInjectionType(typeElement.getSuperType().getTypeElement());
      if (!report.isClean()) {
        builder.addSubreport(report);
      }
    }
    return builder.build();
  }

  /** Returns true if the given method element declares a checked exception. */
  private boolean throwsCheckedExceptions(XConstructorElement constructorElement) {
    XType runtimeException = processingEnv.findType(TypeNames.RUNTIME_EXCEPTION);
    XType error = processingEnv.findType(TypeNames.ERROR);
    DaggerSuperficialValidation.validateThrownTypesOf(constructorElement);
    return !constructorElement.getThrownTypes().stream()
        .allMatch(type -> types.isSubtype(type, runtimeException) || types.isSubtype(type, error));
  }

  private void checkInjectIntoPrivateClass(XElement element, ValidationReport.Builder builder) {
    if (!Accessibility.isElementAccessibleFromOwnPackage(
        DaggerElements.closestEnclosingTypeElement(toJavac(element)))) {
      builder.addItem(
          "Dagger does not support injection into private classes",
          privateMemberDiagnosticKind(),
          element);
    }
  }

  private void checkInjectIntoKotlinObject(XTypeElement element, ValidationReport.Builder builder) {
    if (element.isKotlinObject() || element.isCompanionObject()) {
      builder.addError("Dagger does not support injection into Kotlin objects", element);
    }
  }

  private Diagnostic.Kind privateMemberDiagnosticKind() {
    return privateAndStaticInjectionDiagnosticKind.orElse(
        compilerOptions.privateMemberValidationKind());
  }

  private Diagnostic.Kind staticMemberDiagnosticKind() {
    return privateAndStaticInjectionDiagnosticKind.orElse(
        compilerOptions.staticMemberValidationKind());
  }
}
