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

package dagger.internal.codegen.binding;

import static dagger.internal.codegen.base.MoreAnnotationValues.getStringValue;
import static dagger.internal.codegen.base.Preconditions.checkNotNull;
import static dagger.internal.codegen.base.Scopes.scopesOf;
import static dagger.internal.codegen.binding.SourceFiles.factoryNameForElement;
import static dagger.internal.codegen.binding.SourceFiles.memberInjectedFieldSignatureForVariable;
import static dagger.internal.codegen.binding.SourceFiles.membersInjectorNameForType;
import static dagger.internal.codegen.collect.Iterables.getOnlyElement;
import static dagger.internal.codegen.extension.DaggerCollectors.onlyElement;
import static dagger.internal.codegen.extension.DaggerCollectors.toOptional;
import static dagger.internal.codegen.extension.DaggerStreams.toImmutableSet;
import static dagger.internal.codegen.langmodel.DaggerElements.getAnnotationMirror;
import static dagger.internal.codegen.langmodel.DaggerElements.isAnyAnnotationPresent;
import static dagger.internal.codegen.xprocessing.XConverters.toJavac;
import static dagger.internal.codegen.xprocessing.XConverters.toXProcessing;
import static dagger.internal.codegen.xprocessing.XElement.isConstructor;
import static dagger.internal.codegen.xprocessing.XElement.isField;
import static dagger.internal.codegen.xprocessing.XElement.isMethod;
import static dagger.internal.codegen.xprocessing.XElement.isMethodParameter;
import static dagger.internal.codegen.xprocessing.XElement.isTypeElement;
import static dagger.internal.codegen.xprocessing.XElements.asMethod;
import static dagger.internal.codegen.xprocessing.XElements.asMethodParameter;
import static dagger.internal.codegen.xprocessing.XElements.asTypeElement;
import static dagger.internal.codegen.xprocessing.XElements.closestEnclosingTypeElement;
import static io.jbock.auto.common.MoreElements.asType;
import static io.jbock.auto.common.MoreElements.asVariable;
import static javax.lang.model.element.Modifier.STATIC;
import static javax.lang.model.util.ElementFilter.constructorsIn;

import dagger.internal.codegen.collect.FluentIterable;
import dagger.internal.codegen.collect.ImmutableCollection;
import dagger.internal.codegen.collect.ImmutableList;
import dagger.internal.codegen.collect.ImmutableSet;
import dagger.internal.codegen.compileroption.CompilerOptions;
import dagger.internal.codegen.extension.DaggerCollectors;
import dagger.internal.codegen.extension.DaggerStreams;
import dagger.internal.codegen.javapoet.TypeNames;
import dagger.internal.codegen.kotlin.KotlinMetadataUtil;
import dagger.internal.codegen.langmodel.DaggerElements;
import dagger.internal.codegen.xprocessing.XAnnotation;
import dagger.internal.codegen.xprocessing.XConstructorElement;
import dagger.internal.codegen.xprocessing.XElement;
import dagger.internal.codegen.xprocessing.XExecutableElement;
import dagger.internal.codegen.xprocessing.XProcessingEnv;
import dagger.internal.codegen.xprocessing.XTypeElement;
import dagger.spi.model.DaggerAnnotation;
import dagger.spi.model.Scope;
import io.jbock.auto.common.AnnotationMirrors;
import io.jbock.auto.common.Equivalence;
import io.jbock.auto.common.Equivalence.Wrapper;
import io.jbock.javapoet.ClassName;
import jakarta.inject.Inject;
import java.util.Optional;
import java.util.stream.Stream;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.util.ElementFilter;

/** Utilities relating to annotations defined in the {@code javax.inject} package. */
public final class InjectionAnnotations {

  private static final Equivalence<AnnotationMirror> EQUIVALENCE = AnnotationMirrors.equivalence();

  private final XProcessingEnv processingEnv;
  private final DaggerElements elements;
  private final KotlinMetadataUtil kotlinMetadataUtil;
  private final CompilerOptions compilerOptions;

  @Inject
  InjectionAnnotations(
      XProcessingEnv processingEnv,
      DaggerElements elements,
      KotlinMetadataUtil kotlinMetadataUtil,
      CompilerOptions compilerOptions) {
    this.processingEnv = processingEnv;
    this.elements = elements;
    this.kotlinMetadataUtil = kotlinMetadataUtil;
    this.compilerOptions = compilerOptions;
  }

  /**
   * Returns the scope on the given element if it exists.
   *
   * <p>The {@code ScopeMetadata} is used to avoid superficial validation on unnecessary
   * annotations. If the {@code ScopeMetadata} does not exist, then all annotations must be
   * superficially validated before we can determine if they are scopes or not.
   *
   * @throws IllegalArgumentException if the given element has more than one scope.
   */
  public Optional<Scope> getScope(XElement element) {
    return getScopes(element).stream().collect(toOptional());
  }

  /**
   * Returns the scopes on the given element, or an empty set if none exist.
   *
   * <p>The {@code ScopeMetadata} is used to avoid superficial validation on unnecessary
   * annotations. If the {@code ScopeMetadata} does not exist, then all annotations must be
   * superficially validated before we can determine if they are scopes or not.
   */
  public ImmutableSet<Scope> getScopes(XElement element) {
    DaggerSuperficialValidation.validateTypeOf(element);
    return getScopesFromScopeMetadata(element)
        .orElseGet(
            () -> {
              // Validating all annotations if the ScopeMetadata isn't available.
              if (compilerOptions.strictSuperficialValidation()) {
                DaggerSuperficialValidation.strictValidateAnnotationsOf(element);
              } else {
                DaggerSuperficialValidation.validateAnnotationsOf(element);
              }
              return scopesOf(element);
            });
  }

  private Optional<ImmutableSet<Scope>> getScopesFromScopeMetadata(XElement element) {
    Optional<XAnnotation> scopeMetadata = getScopeMetadata(element);
    if (!scopeMetadata.isPresent()) {
      return Optional.empty();
    }
    String scopeName = scopeMetadata.get().getAsString("value");
    if (scopeName.isEmpty()) {
      return Optional.of(ImmutableSet.of());
    }
    XAnnotation scopeAnnotation =
        element.getAllAnnotations().stream()
            .filter(
                annotation ->
                    scopeName.contentEquals(
                        annotation.getType().getTypeElement().getQualifiedName()))
            .collect(onlyElement());
    // Do superficial validation before we convert to a Scope, otherwise the @Scope annotation may
    // appear to be missing from the annotation if it's no longer on the classpath.
    if (compilerOptions.strictSuperficialValidation()) {
      DaggerSuperficialValidation.strictValidateAnnotationOf(element, scopeAnnotation);
      return Optional.of(ImmutableSet.of(Scope.scope(DaggerAnnotation.from(scopeAnnotation))));
    } else {
      // If strictSuperficialValidation is disabled, then we fall back to the old behavior where
      // we may potentially miss a scope rather than report an exception.
      DaggerSuperficialValidation.validateAnnotationOf(element, scopeAnnotation);
      return Scope.isScope(DaggerAnnotation.from(scopeAnnotation))
          ? Optional.of(ImmutableSet.of(Scope.scope(DaggerAnnotation.from(scopeAnnotation))))
          : Optional.empty();
    }
  }

  private Optional<XAnnotation> getScopeMetadata(XElement element) {
    return getGeneratedNameForScopeMetadata(element)
        .flatMap(factoryName -> Optional.ofNullable(processingEnv.findTypeElement(factoryName)))
        .flatMap(factory -> Optional.ofNullable(factory.getAnnotation(TypeNames.SCOPE_METADATA)));
  }

  private Optional<ClassName> getGeneratedNameForScopeMetadata(XElement element) {
    // Currently, we only support ScopeMetadata for inject-constructor types and provides methods.
    if (isTypeElement(element)) {
      return asTypeElement(element).getConstructors().stream()
          .filter(InjectionAnnotations::hasInjectOrAssistedInjectAnnotation)
          .findFirst()
          .map(SourceFiles::factoryNameForElement);
    } else if (isMethod(element) && element.hasAnnotation(TypeNames.PROVIDES)) {
      return Optional.of(factoryNameForElement(asMethod(element)));
    }
    return Optional.empty();
  }

  /*
   * Returns the qualifier on the given element if it exists.
   *
   * <p>The {@code QualifierMetadata} is used to avoid superficial validation on unnecessary
   * annotations. If the {@code QualifierMetadata} does not exist, then all annotations must be
   * superficially validated before we can determine if they are qualifiers or not.
   *
   * @throws IllegalArgumentException if the given element has more than one qualifier.
   */
  public Optional<XAnnotation> getQualifier(XElement element) {
    return getQualifier(toJavac(element)).map(qualifier -> toXProcessing(qualifier, processingEnv));
  }

  /*
   * Returns the qualifier on the given element if it exists.
   *
   * <p>The {@code QualifierMetadata} is used to avoid superficial validation on unnecessary
   * annotations. If the {@code QualifierMetadata} does not exist, then all annotations must be
   * superficially validated before we can determine if they are qualifiers or not.
   *
   * @throws IllegalArgumentException if the given element has more than one qualifier.
   */
  public Optional<AnnotationMirror> getQualifier(Element e) {
    checkNotNull(e);
    ImmutableList<? extends AnnotationMirror> qualifierAnnotations = getQualifiers(e);
    switch (qualifierAnnotations.size()) {
      case 0:
        return Optional.empty();
      case 1:
        return Optional.<AnnotationMirror>of(qualifierAnnotations.iterator().next());
      default:
        throw new IllegalArgumentException(
            e + " was annotated with more than one @Qualifier annotation");
    }
  }

  /*
   * Returns the qualifiers on the given element, or an empty set if none exist.
   *
   * <p>The {@code QualifierMetadata} is used to avoid superficial validation on unnecessary
   * annotations. If the {@code QualifierMetadata} does not exist, then all annotations must be
   * superficially validated before we can determine if they are qualifiers or not.
   */
  public ImmutableSet<XAnnotation> getQualifiers(XElement element) {
    return getQualifiers(toJavac(element)).stream()
        .map(qualifier -> toXProcessing(qualifier, processingEnv))
        .collect(toImmutableSet());
  }

  /*
   * Returns the qualifiers on the given element, or an empty set if none exist.
   *
   * <p>The {@code QualifierMetadata} is used to avoid superficial validation on unnecessary
   * annotations. If the {@code QualifierMetadata} does not exist, then all annotations must be
   * superficially validated before we can determine if they are qualifiers or not.
   */
  public ImmutableList<? extends AnnotationMirror> getQualifiers(Element element) {
    DaggerSuperficialValidation.validateTypeOf(toXProcessing(element, processingEnv));
    ImmutableSet<? extends AnnotationMirror> qualifiers =
        getQualifiersFromQualifierMetadata(element)
            .orElseGet(
                () -> {
                  // Validating all annotations if the QualifierMetadata isn't available.
                  if (compilerOptions.strictSuperficialValidation()) {
                    DaggerSuperficialValidation.strictValidateAnnotationsOf(element);
                  } else {
                    DaggerSuperficialValidation.validateAnnotationsOf(element);
                  }
                  return element.getAnnotationMirrors().stream()
                      .filter(InjectionAnnotations::hasQualifierAnnotation)
                      .collect(toImmutableSet());
                });
    if (element.getKind() == ElementKind.FIELD
        // static injected fields are not supported, no need to get qualifier from kotlin metadata
        && !element.getModifiers().contains(STATIC)
        && hasInjectAnnotation(element)
        && kotlinMetadataUtil.hasMetadata(element)) {
      return Stream.concat(
              qualifiers.stream(), getQualifiersForKotlinProperty(asVariable(element)).stream())
          .map(EQUIVALENCE::wrap) // Wrap in equivalence to deduplicate
          .distinct()
          .map(Wrapper::get)
          .collect(DaggerStreams.toImmutableList());
    } else {
      return ImmutableList.copyOf(qualifiers);
    }
  }

  private Optional<ImmutableSet<? extends AnnotationMirror>> getQualifiersFromQualifierMetadata(
      Element javaElement) {
    XElement element = toXProcessing(javaElement, processingEnv);
    Optional<XAnnotation> qualifierMetadata = getQualifierMetadata(element);
    if (!qualifierMetadata.isPresent()) {
      return Optional.empty();
    }
    ImmutableSet<String> qualifierNames =
        ImmutableSet.copyOf(qualifierMetadata.get().getAsStringList("value"));
    if (qualifierNames.isEmpty()) {
      return Optional.of(ImmutableSet.of());
    }
    ImmutableSet<XAnnotation> qualifierAnnotations =
        element.getAllAnnotations().stream()
            .filter(
                annotation ->
                    qualifierNames.contains(
                        annotation.getType().getTypeElement().getQualifiedName()))
            .collect(toImmutableSet());
    if (qualifierAnnotations.isEmpty()) {
      return Optional.of(ImmutableSet.of());
    }
    // We should be guaranteed that there's exactly one qualifier since the existance of
    // @QualifierMetadata means that this element has already been processed and multiple
    // qualifiers would have been caught already.
    XAnnotation qualifierAnnotation = getOnlyElement(qualifierAnnotations);

    if (compilerOptions.strictSuperficialValidation()) {
      // Ensure the annotation type is superficially valid before we check for @Qualifier, otherwise
      // the @Qualifier marker may appear to be missing from the annotation (b/213880825).
      DaggerSuperficialValidation.strictValidateAnnotationOf(element, qualifierAnnotation);
      return Optional.of(ImmutableSet.of(toJavac(qualifierAnnotation)));
    } else {
      // If strictSuperficialValidation is disabled, then we fall back to the old behavior where
      // we may potentially miss a qualifier rather than report an exception.
      DaggerSuperficialValidation.validateAnnotationOf(element, qualifierAnnotation);
      return hasQualifierAnnotation(toJavac(qualifierAnnotation))
          ? Optional.of(ImmutableSet.of(toJavac(qualifierAnnotation)))
          : Optional.empty();
    }
  }

  /**
   * Returns {@code QualifierMetadata} annotation.
   *
   * <p>Currently, {@code QualifierMetadata} is only associated with inject constructor parameters,
   * inject fields, inject method parameters, provide methods, and provide method parameters.
   */
  private Optional<XAnnotation> getQualifierMetadata(XElement element) {
    return getGeneratedNameForQualifierMetadata(element)
        .flatMap(name -> Optional.ofNullable(processingEnv.findTypeElement(name)))
        .flatMap(type -> Optional.ofNullable(type.getAnnotation(TypeNames.QUALIFIER_METADATA)));
  }

  private Optional<ClassName> getGeneratedNameForQualifierMetadata(XElement element) {
    // Currently we only support @QualifierMetadata for @Inject fields, @Inject method parameters,
    // @Inject constructor parameters, @Provides methods, and @Provides method parameters.
    if (isField(element) && hasInjectAnnotation(element)) {
      return Optional.of(membersInjectorNameForType(closestEnclosingTypeElement(element)));
    } else if (isMethod(element) && element.hasAnnotation(TypeNames.PROVIDES)) {
      return Optional.of(factoryNameForElement(asMethod(element)));
    } else if (isMethodParameter(element)) {
      XExecutableElement executableElement = asMethodParameter(element).getEnclosingMethodElement();
      if (isConstructor(executableElement)
          && hasInjectOrAssistedInjectAnnotation(executableElement)) {
        return Optional.of(factoryNameForElement(executableElement));
      }
      if (isMethod(executableElement) && hasInjectAnnotation(executableElement)) {
        return Optional.of(membersInjectorNameForType(closestEnclosingTypeElement(element)));
      }
      if (isMethod(executableElement) && executableElement.hasAnnotation(TypeNames.PROVIDES)) {
        return Optional.of(factoryNameForElement(executableElement));
      }
    }
    return Optional.empty();
  }

  /** Returns the constructors in {@code type} that are annotated with {@code Inject}. */
  public static ImmutableSet<XConstructorElement> injectedConstructors(XTypeElement type) {
    return type.getConstructors().stream()
        .filter(InjectionAnnotations::hasInjectAnnotation)
        .collect(toImmutableSet());
  }

  /** Returns the constructors in {@code type} that are annotated with {@code Inject}. */
  public static ImmutableSet<ExecutableElement> injectedConstructors(TypeElement type) {
    return FluentIterable.from(constructorsIn(type.getEnclosedElements()))
        .filter(InjectionAnnotations::hasInjectAnnotation)
        .toSet();
  }

  private static boolean hasQualifierAnnotation(AnnotationMirror annotation) {
    return isAnyAnnotationPresent(annotation.getAnnotationType().asElement(), TypeNames.QUALIFIER);
  }

  /** Returns true if the given element is annotated with {@code Inject}. */
  public static boolean hasInjectAnnotation(XElement element) {
    return element.hasAnyAnnotation(TypeNames.INJECT);
  }

  /** Returns true if the given element is annotated with {@code Inject}. */
  public static boolean hasInjectAnnotation(Element element) {
    return isAnyAnnotationPresent(element, TypeNames.INJECT);
  }

  /** Returns true if the given element is annotated with {@code Inject}. */
  public static boolean hasInjectOrAssistedInjectAnnotation(XElement element) {
    return element.hasAnyAnnotation(TypeNames.INJECT, TypeNames.ASSISTED_INJECT);
  }

  /** Returns true if the given element is annotated with {@code Inject}. */
  public static boolean hasInjectOrAssistedInjectAnnotation(Element element) {
    return isAnyAnnotationPresent(element, TypeNames.INJECT, TypeNames.ASSISTED_INJECT);
  }

  /**
   * Gets the qualifiers annotation of a Kotlin Property. Finding these annotations involve finding
   * the synthetic method for annotations as described by the Kotlin metadata or finding the
   * corresponding MembersInjector method for the field, which also contains the qualifier
   * annotation.
   */
  private ImmutableCollection<? extends AnnotationMirror> getQualifiersForKotlinProperty(
      VariableElement fieldElement) {
    // TODO(bcorso): Consider moving this to KotlinMetadataUtil
    if (kotlinMetadataUtil.isMissingSyntheticPropertyForAnnotations(fieldElement)) {
      // If we detect that the synthetic method for annotations is missing, possibly due to the
      // element being from a compiled class, then find the MembersInjector that was generated
      // for the enclosing class and extract the qualifier information from it.
      TypeElement membersInjector =
          elements.getTypeElement(
              membersInjectorNameForType(asType(fieldElement.getEnclosingElement())));
      if (membersInjector != null) {
        String memberInjectedFieldSignature = memberInjectedFieldSignatureForVariable(fieldElement);
        // TODO(danysantiago): We have to iterate over all the injection methods for every qualifier
        //  look up. Making this N^2 when looking through all the injected fields. :(
        return ElementFilter.methodsIn(membersInjector.getEnclosedElements()).stream()
            .filter(
                method ->
                    getAnnotationMirror(method, TypeNames.INJECTED_FIELD_SIGNATURE)
                        .map(annotation -> getStringValue(annotation, "value"))
                        .map(memberInjectedFieldSignature::equals)
                        // If a method is not an @InjectedFieldSignature method then filter it out
                        .orElse(false))
            .collect(DaggerCollectors.toOptional())
            .map(this::getQualifiers)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        String.format(
                            "No matching InjectedFieldSignature for %1$s. This likely means that "
                                + "%1$s was compiled with an older, incompatible version of "
                                + "Dagger. Please update all Dagger dependencies to the same "
                                + "version.",
                            memberInjectedFieldSignature)));
      } else {
        throw new IllegalStateException(
            "No MembersInjector found for " + fieldElement.getEnclosingElement());
      }
    } else {
      return ImmutableSet.<AnnotationMirror>builder()
          .addAll(
              kotlinMetadataUtil.getSyntheticPropertyAnnotations(fieldElement, TypeNames.QUALIFIER))
          .build();
    }
  }
}
