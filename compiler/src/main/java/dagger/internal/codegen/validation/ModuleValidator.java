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

import static dagger.internal.codegen.base.ComponentAnnotation.isComponentAnnotation;
import static dagger.internal.codegen.base.ComponentAnnotation.subcomponentAnnotation;
import static dagger.internal.codegen.base.ModuleAnnotation.isModuleAnnotation;
import static dagger.internal.codegen.base.Util.asStream;
import static dagger.internal.codegen.base.Util.reentrantComputeIfAbsent;
import static dagger.internal.codegen.binding.ComponentCreatorAnnotation.getCreatorAnnotations;
import static dagger.internal.codegen.binding.ConfigurationAnnotations.getSubcomponentCreator;
import static dagger.internal.codegen.collect.Iterables.getOnlyElement;
import static dagger.internal.codegen.extension.DaggerCollectors.toOptional;
import static dagger.internal.codegen.extension.DaggerStreams.toImmutableSet;
import static dagger.internal.codegen.xprocessing.XAnnotations.getClassName;
import static dagger.internal.codegen.xprocessing.XElements.getAnnotatedAnnotations;
import static dagger.internal.codegen.xprocessing.XElements.getSimpleName;
import static dagger.internal.codegen.xprocessing.XElements.hasAnyAnnotation;
import static dagger.internal.codegen.xprocessing.XTypeElements.hasTypeParameters;
import static dagger.internal.codegen.xprocessing.XTypeElements.isEffectivelyPrivate;
import static dagger.internal.codegen.xprocessing.XTypeElements.isEffectivelyPublic;
import static dagger.internal.codegen.xprocessing.XTypes.areEquivalentTypes;
import static dagger.internal.codegen.xprocessing.XTypes.isDeclared;
import static java.util.stream.Collectors.joining;

import dagger.internal.codegen.base.Joiner;
import dagger.internal.codegen.binding.BindingGraphFactory;
import dagger.internal.codegen.binding.ComponentCreatorAnnotation;
import dagger.internal.codegen.binding.ComponentDescriptorFactory;
import dagger.internal.codegen.binding.MethodSignatureFormatter;
import dagger.internal.codegen.binding.ModuleKind;
import dagger.internal.codegen.collect.ImmutableList;
import dagger.internal.codegen.collect.ImmutableListMultimap;
import dagger.internal.codegen.collect.ImmutableSet;
import dagger.internal.codegen.collect.ListMultimap;
import dagger.internal.codegen.collect.MultimapBuilder;
import dagger.internal.codegen.collect.Multimaps;
import dagger.internal.codegen.collect.Sets;
import dagger.internal.codegen.javapoet.TypeNames;
import dagger.internal.codegen.xprocessing.XAnnotation;
import dagger.internal.codegen.xprocessing.XAnnotationValue;
import dagger.internal.codegen.xprocessing.XElements;
import dagger.internal.codegen.xprocessing.XMethodElement;
import dagger.internal.codegen.xprocessing.XProcessingEnv;
import dagger.internal.codegen.xprocessing.XType;
import dagger.internal.codegen.xprocessing.XTypeElement;
import dagger.spi.model.BindingGraph;
import io.jbock.javapoet.ClassName;
import io.jbock.javapoet.TypeName;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A {@code ValidationReport validator} for {@code dagger.Module}s or {@code
 * dagger.producers.ProducerModule}s.
 */
@Singleton
public final class ModuleValidator {
  private static final ImmutableSet<ClassName> SUBCOMPONENT_TYPES =
      ImmutableSet.of(TypeNames.SUBCOMPONENT, TypeNames.PRODUCTION_SUBCOMPONENT);
  private static final ImmutableSet<ClassName> SUBCOMPONENT_CREATOR_TYPES =
      ImmutableSet.of(
          TypeNames.SUBCOMPONENT_BUILDER,
          TypeNames.SUBCOMPONENT_FACTORY,
          TypeNames.PRODUCTION_SUBCOMPONENT_BUILDER,
          TypeNames.PRODUCTION_SUBCOMPONENT_FACTORY);
  private static final Optional<Class<?>> ANDROID_PROCESSOR;
  private static final String CONTRIBUTES_ANDROID_INJECTOR_NAME =
      "dagger.android.ContributesAndroidInjector";
  private static final String ANDROID_PROCESSOR_NAME = "dagger.android.processor.AndroidProcessor";

  static {
    Class<?> clazz;
    try {
      clazz = Class.forName(ANDROID_PROCESSOR_NAME, false, ModuleValidator.class.getClassLoader());
    } catch (ClassNotFoundException ignored) {
      clazz = null;
    }
    ANDROID_PROCESSOR = Optional.ofNullable(clazz);
  }

  private final AnyBindingMethodValidator anyBindingMethodValidator;
  private final MethodSignatureFormatter methodSignatureFormatter;
  private final ComponentDescriptorFactory componentDescriptorFactory;
  private final BindingGraphFactory bindingGraphFactory;
  private final BindingGraphValidator bindingGraphValidator;
  private final Map<XTypeElement, ValidationReport> cache = new HashMap<>();
  private final Set<XTypeElement> knownModules = new HashSet<>();
  private final XProcessingEnv processingEnv;

  @Inject
  ModuleValidator(
      AnyBindingMethodValidator anyBindingMethodValidator,
      MethodSignatureFormatter methodSignatureFormatter,
      ComponentDescriptorFactory componentDescriptorFactory,
      BindingGraphFactory bindingGraphFactory,
      BindingGraphValidator bindingGraphValidator,
      XProcessingEnv processingEnv) {
    this.anyBindingMethodValidator = anyBindingMethodValidator;
    this.methodSignatureFormatter = methodSignatureFormatter;
    this.componentDescriptorFactory = componentDescriptorFactory;
    this.bindingGraphFactory = bindingGraphFactory;
    this.bindingGraphValidator = bindingGraphValidator;
    this.processingEnv = processingEnv;
  }

  /**
   * Adds {@code modules} to the set of module types that will be validated during this compilation
   * step. If a component or module includes a module that is not in this set, that included module
   * is assumed to be valid because it was processed in a previous compilation step. If it were
   * invalid, that previous compilation step would have failed and blocked this one.
   *
   * <p>This logic depends on this method being called before {@code #validate(XTypeElement)
   * validating} any module or {@code #validateReferencedModules(XTypeElement, ModuleKind, Set,
   * DiagnosticReporter.Builder) component}.
   */
  public void addKnownModules(Collection<XTypeElement> modules) {
    knownModules.addAll(modules);
  }

  /** Returns a validation report for a module type. */
  public ValidationReport validate(XTypeElement module) {
    return validate(module, new HashSet<>());
  }

  private ValidationReport validate(XTypeElement module, Set<XTypeElement> visitedModules) {
    if (visitedModules.add(module)) {
      return reentrantComputeIfAbsent(cache, module, m -> validateUncached(module, visitedModules));
    }
    return ValidationReport.about(module).build();
  }

  private ValidationReport validateUncached(XTypeElement module, Set<XTypeElement> visitedModules) {
    ValidationReport.Builder builder = ValidationReport.about(module);
    ModuleKind moduleKind = ModuleKind.forAnnotatedElement(module).get();
    Optional<XType> contributesAndroidInjector =
        Optional.ofNullable(processingEnv.findTypeElement(CONTRIBUTES_ANDROID_INJECTOR_NAME))
            .map(XTypeElement::getType);
    List<XMethodElement> moduleMethods = module.getDeclaredMethods();
    List<XMethodElement> bindingMethods = new ArrayList<>();
    for (XMethodElement moduleMethod : moduleMethods) {
      if (anyBindingMethodValidator.isBindingMethod(moduleMethod)) {
        builder.addSubreport(anyBindingMethodValidator.validate(moduleMethod));
        bindingMethods.add(moduleMethod);
      }

      for (XAnnotation annotation : moduleMethod.getAllAnnotations()) {
        if (!ANDROID_PROCESSOR.isPresent()
            && contributesAndroidInjector.isPresent()
            && areEquivalentTypes(contributesAndroidInjector.get(), annotation.getType())) {
          builder.addSubreport(
              ValidationReport.about(moduleMethod)
                  .addError(
                      String.format(
                          "@%s was used, but %s was not found on the processor path",
                          CONTRIBUTES_ANDROID_INJECTOR_NAME, ANDROID_PROCESSOR_NAME))
                  .build());
          break;
        }
      }
    }

    if (bindingMethods.stream()
        .map(ModuleMethodKind::ofMethod)
        .collect(toImmutableSet())
        .containsAll(
            EnumSet.of(ModuleMethodKind.ABSTRACT_DECLARATION, ModuleMethodKind.INSTANCE_BINDING))) {
      builder.addError(
          String.format(
              "A @%s may not contain both non-static and abstract binding methods",
              moduleKind.annotation().simpleName()));
    }

    validateModuleVisibility(module, moduleKind, builder);

    ImmutableListMultimap<String, XMethodElement> bindingMethodsByName =
        Multimaps.index(bindingMethods, XElements::getSimpleName);

    validateMethodsWithSameName(builder, bindingMethodsByName);
    if (!module.isInterface()) {
      validateBindingMethodOverrides(
          module,
          builder,
          Multimaps.index(moduleMethods, XElements::getSimpleName),
          bindingMethodsByName);
    }
    validateModifiers(module, builder);
    validateReferencedModules(module, moduleKind, visitedModules, builder);
    validateReferencedSubcomponents(module, moduleKind, builder);
    validateNoScopeAnnotationsOnModuleElement(module, moduleKind, builder);
    validateSelfCycles(module, moduleKind, builder);
    module.getEnclosedTypeElements().stream()
        .filter(XTypeElement::isCompanionObject)
        .collect(toOptional())
        .ifPresent(companionModule -> validateCompanionModule(companionModule, builder));

    if (builder.build().isClean()
        && bindingGraphValidator.shouldDoFullBindingGraphValidation(module)) {
      validateModuleBindings(module, builder);
    }

    return builder.build();
  }

  private void validateReferencedSubcomponents(
      XTypeElement subject, ModuleKind moduleKind, ValidationReport.Builder builder) {
    XAnnotation moduleAnnotation = moduleKind.getModuleAnnotation(subject);
    for (XAnnotationValue subcomponentValue :
        moduleAnnotation.getAsAnnotationValueList("subcomponents")) {
      XType type = subcomponentValue.asType();
      if (!isDeclared(type)) {
        builder.addError(
            type + " is not a valid subcomponent type",
            subject,
            moduleAnnotation,
            subcomponentValue);
        continue;
      }

      XTypeElement subcomponentElement = type.getTypeElement();
      if (hasAnyAnnotation(subcomponentElement, SUBCOMPONENT_TYPES)) {
        validateSubcomponentHasBuilder(subject, subcomponentElement, moduleAnnotation, builder);
      } else {
        builder.addError(
            hasAnyAnnotation(subcomponentElement, SUBCOMPONENT_CREATOR_TYPES)
                ? moduleSubcomponentsIncludesCreator(subcomponentElement)
                : moduleSubcomponentsIncludesNonSubcomponent(subcomponentElement),
            subject,
            moduleAnnotation,
            subcomponentValue);
      }
    }
  }

  private static String moduleSubcomponentsIncludesNonSubcomponent(XTypeElement notSubcomponent) {
    return notSubcomponent.getQualifiedName()
        + " is not a @Subcomponent or @ProductionSubcomponent";
  }

  private static String moduleSubcomponentsIncludesCreator(
      XTypeElement moduleSubcomponentsAttribute) {
    XTypeElement subcomponentType = moduleSubcomponentsAttribute.getEnclosingTypeElement();
    ComponentCreatorAnnotation creatorAnnotation =
        getOnlyElement(getCreatorAnnotations(moduleSubcomponentsAttribute));
    return String.format(
        "%s is a @%s.%s. Did you mean to use %s?",
        moduleSubcomponentsAttribute.getQualifiedName(),
        subcomponentAnnotation(subcomponentType).get().simpleName(),
        creatorAnnotation.creatorKind().typeName(),
        subcomponentType.getQualifiedName());
  }

  private static void validateSubcomponentHasBuilder(
      XTypeElement subject,
      XTypeElement subcomponentAttribute,
      XAnnotation moduleAnnotation,
      ValidationReport.Builder builder) {
    if (getSubcomponentCreator(subcomponentAttribute).isPresent()) {
      return;
    }
    builder.addError(
        moduleSubcomponentsDoesntHaveCreator(subcomponentAttribute, moduleAnnotation),
        subject,
        moduleAnnotation);
  }

  private static String moduleSubcomponentsDoesntHaveCreator(
      XTypeElement subcomponent, XAnnotation moduleAnnotation) {
    return String.format(
        "%1$s doesn't have a @%2$s.Builder or @%2$s.Factory, which is required when used with "
            + "@%3$s.subcomponents",
        subcomponent.getQualifiedName(),
        subcomponentAnnotation(subcomponent).get().simpleName(),
        getClassName(moduleAnnotation).simpleName());
  }

  enum ModuleMethodKind {
    ABSTRACT_DECLARATION,
    INSTANCE_BINDING,
    STATIC_BINDING,
    ;

    static ModuleMethodKind ofMethod(XMethodElement moduleMethod) {
      if (moduleMethod.isStatic()) {
        return STATIC_BINDING;
      } else if (moduleMethod.isAbstract()) {
        return ABSTRACT_DECLARATION;
      } else {
        return INSTANCE_BINDING;
      }
    }
  }

  private void validateModifiers(XTypeElement subject, ValidationReport.Builder builder) {
    // This coupled with the check for abstract modules in ComponentValidator guarantees that
    // only modules without type parameters are referenced from @Component(modules={...}).
    if (hasTypeParameters(subject) && !subject.isAbstract()) {
      builder.addError("Modules with type parameters must be abstract", subject);
    }
  }

  private void validateMethodsWithSameName(
      ValidationReport.Builder builder, ListMultimap<String, XMethodElement> bindingMethodsByName) {
    bindingMethodsByName.asMap().values().stream()
        .filter(methods -> methods.size() > 1)
        .flatMap(Collection::stream)
        .forEach(
            duplicateMethod -> {
              builder.addError(
                  "Cannot have more than one binding method with the same name in a single module",
                  duplicateMethod);
            });
  }

  private void validateReferencedModules(
      XTypeElement subject,
      ModuleKind moduleKind,
      Set<XTypeElement> visitedModules,
      ValidationReport.Builder builder) {
    // Validate that all the modules we include are valid for inclusion.
    XAnnotation mirror = moduleKind.getModuleAnnotation(subject);
    builder.addSubreport(
        validateReferencedModules(
            subject, mirror, moduleKind.legalIncludedModuleKinds(), visitedModules));
  }

  /**
   * Validates modules included in a given module or installed in a given component.
   *
   * <p>Checks that the referenced modules are non-generic types annotated with {@code @Module} or
   * {@code @ProducerModule}.
   *
   * <p>If the referenced module is in the {@code #addKnownModules(Collection) known modules
   * set} and has errors, reports an error at that module's inclusion.
   *
   * @param annotatedType the annotated module or component
   * @param annotation the annotation specifying the referenced modules ({@code @Component},
   *     {@code @ProductionComponent}, {@code @Subcomponent}, {@code @ProductionSubcomponent},
   *     {@code @Module}, or {@code @ProducerModule})
   * @param validModuleKinds the module kinds that the annotated type is permitted to include
   */
  ValidationReport validateReferencedModules(
      XTypeElement annotatedType,
      XAnnotation annotation,
      ImmutableSet<ModuleKind> validModuleKinds,
      Set<XTypeElement> visitedModules) {
    ValidationReport.Builder subreport = ValidationReport.about(annotatedType);
    // TODO(bcorso): Consider creating a DiagnosticLocation object to encapsulate the location in a
    // single object to avoid duplication across all reported errors
    for (XAnnotationValue includedModule : getModules(annotation)) {
      XType type = includedModule.asType();
      if (!isDeclared(type)) {
        subreport.addError(
            String.format("%s is not a valid module type.", type),
            annotatedType,
            annotation,
            includedModule);
        continue;
      }

      XTypeElement module = type.getTypeElement();
      if (hasTypeParameters(module)) {
        subreport.addError(
            String.format(
                "%s is listed as a module, but has type parameters", module.getQualifiedName()),
            annotatedType,
            annotation,
            includedModule);
      }

      ImmutableSet<ClassName> validModuleAnnotations =
          validModuleKinds.stream().map(ModuleKind::annotation).collect(toImmutableSet());
      if (!hasAnyAnnotation(module, validModuleAnnotations)) {
        subreport.addError(
            String.format(
                "%s is listed as a module, but is not annotated with %s",
                module.getQualifiedName(),
                (validModuleAnnotations.size() > 1 ? "one of " : "")
                    + validModuleAnnotations.stream()
                        .map(otherClass -> "@" + otherClass.simpleName())
                        .collect(joining(", "))),
            annotatedType,
            annotation,
            includedModule);
      } else if (knownModules.contains(module) && !validate(module, visitedModules).isClean()) {
        subreport.addError(
            String.format("%s has errors", module.getQualifiedName()),
            annotatedType,
            annotation,
            includedModule);
      }
      if (module.isCompanionObject()) {
        subreport.addError(
            String.format(
                "%s is listed as a module, but it is a companion object class. "
                    + "Add @Module to the enclosing class and reference that instead.",
                module.getQualifiedName()),
            annotatedType,
            annotation,
            includedModule);
      }
    }
    return subreport.build();
  }

  private static ImmutableList<XAnnotationValue> getModules(XAnnotation annotation) {
    if (isModuleAnnotation(annotation)) {
      return ImmutableList.copyOf(annotation.getAsAnnotationValueList("includes"));
    }
    if (isComponentAnnotation(annotation)) {
      return ImmutableList.copyOf(annotation.getAsAnnotationValueList("modules"));
    }
    throw new IllegalArgumentException(String.format("unsupported annotation: %s", annotation));
  }

  private void validateBindingMethodOverrides(
      XTypeElement subject,
      ValidationReport.Builder builder,
      ImmutableListMultimap<String, XMethodElement> moduleMethodsByName,
      ImmutableListMultimap<String, XMethodElement> bindingMethodsByName) {
    // For every binding method, confirm it overrides nothing *and* nothing overrides it.
    // Consider the following hierarchy:
    // class Parent {
    //    @Provides Foo a() {}
    //    @Provides Foo b() {}
    //    Foo c() {}
    // }
    // class Child extends Parent {
    //    @Provides Foo a() {}
    //    Foo b() {}
    //    @Provides Foo c() {}
    // }
    // In each of those cases, we want to fail.  "a" is clear, "b" because Child is overriding
    // a binding method in Parent, and "c" because Child is defining a binding method that overrides
    // Parent.
    XTypeElement currentClass = subject;
    XType objectType = processingEnv.findType(TypeName.OBJECT);
    // We keep track of visited methods so we don't spam with multiple failures.
    Set<XMethodElement> visitedMethods = Sets.newHashSet();
    ListMultimap<String, XMethodElement> allMethodsByName =
        MultimapBuilder.hashKeys().arrayListValues().build(moduleMethodsByName);

    while (!currentClass.getSuperType().isSameType(objectType)) {
      currentClass = currentClass.getSuperType().getTypeElement();
      List<XMethodElement> superclassMethods = currentClass.getDeclaredMethods();
      for (XMethodElement superclassMethod : superclassMethods) {
        String name = getSimpleName(superclassMethod);
        // For each method in the superclass, confirm our binding methods don't override it
        for (XMethodElement bindingMethod : bindingMethodsByName.get(name)) {
          if (visitedMethods.add(bindingMethod)
              && bindingMethod.overrides(superclassMethod, subject)) {
            builder.addError(
                String.format(
                    "Binding methods may not override another method. Overrides: %s",
                    methodSignatureFormatter.format(superclassMethod)),
                bindingMethod);
          }
        }
        // For each binding method in superclass, confirm our methods don't override it.
        if (anyBindingMethodValidator.isBindingMethod(superclassMethod)) {
          for (XMethodElement method : allMethodsByName.get(name)) {
            if (visitedMethods.add(method) && method.overrides(superclassMethod, subject)) {
              builder.addError(
                  String.format(
                      "Binding methods may not be overridden in modules. Overrides: %s",
                      methodSignatureFormatter.format(superclassMethod)),
                  method);
            }
          }
        }
        // TODO(b/202521399): Add a test for cases that add to this map.
        allMethodsByName.put(getSimpleName(superclassMethod), superclassMethod);
      }
    }
  }

  private void validateModuleVisibility(
      XTypeElement moduleElement, ModuleKind moduleKind, ValidationReport.Builder reportBuilder) {
    if (moduleElement.isPrivate()) {
      reportBuilder.addError("Modules cannot be private.", moduleElement);
    } else if (isEffectivelyPrivate(moduleElement)) {
      reportBuilder.addError("Modules cannot be enclosed in private types.", moduleElement);
    }
    if (isEffectivelyPublic(moduleElement)) {
      ImmutableSet<XTypeElement> invalidVisibilityIncludes =
          getModuleIncludesWithInvalidVisibility(moduleKind.getModuleAnnotation(moduleElement));
      if (!invalidVisibilityIncludes.isEmpty()) {
        reportBuilder.addError(
            String.format(
                "This module is public, but it includes non-public (or effectively non-public) "
                    + "modules (%s) that have non-static, non-abstract binding methods. Either "
                    + "reduce the visibility of this module, make the included modules "
                    + "public, or make all of the binding methods on the included modules "
                    + "abstract or static.",
                formatListForErrorMessage(invalidVisibilityIncludes.asList())),
            moduleElement);
      }
    }
  }

  private ImmutableSet<XTypeElement> getModuleIncludesWithInvalidVisibility(
      XAnnotation moduleAnnotation) {
    return moduleAnnotation.getAnnotationValue("includes").asTypeList().stream()
        .map(XType::getTypeElement)
        .filter(include -> !isEffectivelyPublic(include))
        .filter(this::requiresModuleInstance)
        .collect(toImmutableSet());
  }

  /**
   * Returns {@code true} if a module instance is needed for any of the binding methods on the given
   * {@code module}. This is the case when the module has any binding methods that are neither
   * {@code abstract} nor {@code static}. Alternatively, if the module is a Kotlin Object then the
   * binding methods are considered {@code static}, requiring no module instance.
   */
  private boolean requiresModuleInstance(XTypeElement module) {
    // Note: We use XTypeElement#getAllMethods() rather than XTypeElement#getDeclaredMethods() here
    // because we need to include binding methods declared in supertypes because unlike most other
    // validations being done in this class, which assume that supertype binding methods will be
    // validated in a separate call to the validator since the supertype itself must be a @Module,
    // we need to look at all the binding methods in the module's type hierarchy here.
    return !(module.isKotlinObject() || module.isCompanionObject())
        && !asStream(module.getAllMethods())
            .filter(anyBindingMethodValidator::isBindingMethod)
            .allMatch(method -> method.isAbstract() || method.isStatic());
  }

  private void validateNoScopeAnnotationsOnModuleElement(
      XTypeElement module, ModuleKind moduleKind, ValidationReport.Builder report) {
    for (XAnnotation scope : getAnnotatedAnnotations(module, TypeNames.SCOPE)) {
      report.addError(
          String.format(
              "@%ss cannot be scoped. Did you mean to scope a method instead?",
              moduleKind.annotation().simpleName()),
          module,
          scope);
    }
  }

  private void validateSelfCycles(
      XTypeElement module, ModuleKind moduleKind, ValidationReport.Builder builder) {
    XAnnotation moduleAnnotation = moduleKind.getModuleAnnotation(module);
    moduleAnnotation.getAsAnnotationValueList("includes").stream()
        .filter(includedModule -> areEquivalentTypes(module.getType(), includedModule.asType()))
        .forEach(
            includedModule ->
                builder.addError(
                    String.format(
                        "@%s cannot include themselves.", moduleKind.annotation().simpleName()),
                    module,
                    moduleAnnotation,
                    includedModule));
  }

  private void validateCompanionModule(
      XTypeElement companionModule, ValidationReport.Builder builder) {
    List<XMethodElement> companionBindingMethods = new ArrayList<>();
    for (XMethodElement companionMethod : companionModule.getDeclaredMethods()) {
      if (anyBindingMethodValidator.isBindingMethod(companionMethod)) {
        builder.addSubreport(anyBindingMethodValidator.validate(companionMethod));
        companionBindingMethods.add(companionMethod);
      }

      // On normal modules only overriding other binding methods is disallowed, but for companion
      // objects we are prohibiting any override. For this can rely on checking the @Override
      // annotation since the Kotlin compiler will always produce them for overriding methods.
      if (companionMethod.hasAnnotation(TypeNames.OVERRIDE)) {
        builder.addError(
            "Binding method in companion object may not override another method.", companionMethod);
      }

      // TODO(danysantiago): Be strict about the usage of @JvmStatic, i.e. tell user to remove it.
    }

    ImmutableListMultimap<String, XMethodElement> bindingMethodsByName =
        Multimaps.index(companionBindingMethods, XElements::getSimpleName);
    validateMethodsWithSameName(builder, bindingMethodsByName);

    // If there are provision methods, then check the visibility. Companion objects are composed by
    // an inner class and a static field, it is not enough to check the visibility on the type
    // element or the field, therefore we check the metadata.
    if (!companionBindingMethods.isEmpty() && companionModule.isPrivate()) {
      builder.addError(
          "A Companion Module with binding methods cannot be private.", companionModule);
    }
  }

  private void validateModuleBindings(XTypeElement module, ValidationReport.Builder report) {
    BindingGraph bindingGraph =
        bindingGraphFactory
            .create(componentDescriptorFactory.moduleComponentDescriptor(module), true)
            .topLevelBindingGraph();
    if (!bindingGraphValidator.isValid(bindingGraph)) {
      // Since the validator uses a DiagnosticReporter to report errors, the ValdiationReport won't
      // have any Items for them. We have to tell the ValidationReport that some errors were
      // reported for the subject.
      report.markDirty();
    }
  }

  private static String formatListForErrorMessage(List<?> things) {
    switch (things.size()) {
      case 0:
        return "";
      case 1:
        return things.get(0).toString();
      default:
        StringBuilder output = new StringBuilder();
        Joiner.on(", ").appendTo(output, things.subList(0, things.size() - 1));
        output.append(" and ").append(things.get(things.size() - 1));
        return output.toString();
    }
  }
}
