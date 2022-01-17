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

import static com.google.auto.common.MoreElements.asType;
import static com.google.auto.common.MoreTypes.asDeclared;
import static com.google.auto.common.MoreTypes.asExecutable;
import static com.google.auto.common.MoreTypes.asTypeElement;
import static dagger.internal.codegen.base.ComponentAnnotation.anyComponentAnnotation;
import static dagger.internal.codegen.base.ModuleAnnotation.moduleAnnotation;
import static dagger.internal.codegen.base.Util.reentrantComputeIfAbsent;
import static dagger.internal.codegen.binding.ComponentCreatorAnnotation.creatorAnnotationsFor;
import static dagger.internal.codegen.binding.ComponentCreatorAnnotation.subcomponentCreatorAnnotations;
import static dagger.internal.codegen.binding.ComponentKind.annotationsFor;
import static dagger.internal.codegen.binding.ConfigurationAnnotations.enclosedAnnotatedTypes;
import static dagger.internal.codegen.binding.ConfigurationAnnotations.getTransitiveModules;
import static dagger.internal.codegen.binding.ErrorMessages.ComponentCreatorMessages.builderMethodRequiresNoArgs;
import static dagger.internal.codegen.binding.ErrorMessages.ComponentCreatorMessages.moreThanOneRefToSubcomponent;
import static dagger.internal.codegen.extension.DaggerStreams.toImmutableSet;
import static dagger.internal.codegen.langmodel.DaggerElements.getAnnotationMirror;
import static dagger.internal.codegen.langmodel.DaggerElements.isAnnotationPresent;
import static java.util.Comparator.comparing;
import static javax.lang.model.element.ElementKind.CLASS;
import static javax.lang.model.element.ElementKind.INTERFACE;
import static javax.lang.model.element.Modifier.ABSTRACT;
import static javax.lang.model.type.TypeKind.VOID;
import static javax.lang.model.util.ElementFilter.methodsIn;

import com.google.auto.common.MoreTypes;
import com.squareup.javapoet.ClassName;
import dagger.Component;
import dagger.internal.codegen.base.ClearableCache;
import dagger.internal.codegen.base.ComponentAnnotation;
import dagger.internal.codegen.base.Preconditions;
import dagger.internal.codegen.base.Util;
import dagger.internal.codegen.binding.ComponentKind;
import dagger.internal.codegen.binding.DependencyRequestFactory;
import dagger.internal.codegen.binding.ErrorMessages;
import dagger.internal.codegen.binding.MethodSignatureFormatter;
import dagger.internal.codegen.binding.ModuleKind;
import dagger.internal.codegen.javapoet.TypeNames;
import dagger.internal.codegen.langmodel.DaggerElements;
import dagger.internal.codegen.langmodel.DaggerTypes;
import dagger.model.DependencyRequest;
import dagger.model.Key;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVisitor;
import javax.lang.model.util.SimpleTypeVisitor8;

/**
 * Performs superficial validation of the contract of the {@link Component} annotation.
 */
@Singleton
public final class ComponentValidator implements ClearableCache {
  private final DaggerElements elements;
  private final DaggerTypes types;
  private final ModuleValidator moduleValidator;
  private final ComponentCreatorValidator creatorValidator;
  private final DependencyRequestValidator dependencyRequestValidator;
  private final MethodSignatureFormatter methodSignatureFormatter;
  private final DependencyRequestFactory dependencyRequestFactory;
  private final Map<TypeElement, ValidationReport<TypeElement>> reports = new HashMap<>();

  @Inject
  ComponentValidator(
      DaggerElements elements,
      DaggerTypes types,
      ModuleValidator moduleValidator,
      ComponentCreatorValidator creatorValidator,
      DependencyRequestValidator dependencyRequestValidator,
      MethodSignatureFormatter methodSignatureFormatter,
      DependencyRequestFactory dependencyRequestFactory) {
    this.elements = elements;
    this.types = types;
    this.moduleValidator = moduleValidator;
    this.creatorValidator = creatorValidator;
    this.dependencyRequestValidator = dependencyRequestValidator;
    this.methodSignatureFormatter = methodSignatureFormatter;
    this.dependencyRequestFactory = dependencyRequestFactory;
  }

  @Override
  public void clearCache() {
    reports.clear();
  }

  /** Validates the given component. */
  public ValidationReport<TypeElement> validate(TypeElement component) {
    return reentrantComputeIfAbsent(reports, component, this::validateUncached);
  }

  private ValidationReport<TypeElement> validateUncached(TypeElement component) {
    return new ElementValidator(component).validateElement();
  }

  private class ElementValidator {
    private final TypeElement component;
    private final ValidationReport.Builder<TypeElement> report;
    private final Set<ComponentKind> componentKinds;

    // Populated by ComponentMethodValidators
    private final Map<Element, Set<ExecutableElement>> referencedSubcomponents =
        new LinkedHashMap<>();

    ElementValidator(TypeElement component) {
      this.component = component;
      this.report = ValidationReport.about(component);
      this.componentKinds = ComponentKind.getComponentKinds(component);
    }

    private ComponentKind componentKind() {
      return Util.getOnlyElement(componentKinds);
    }

    private ComponentAnnotation componentAnnotation() {
      return anyComponentAnnotation(component).orElseThrow();
    }

    private DeclaredType componentType() {
      return asDeclared(component.asType());
    }

    ValidationReport<TypeElement> validateElement() {
      if (componentKinds.size() > 1) {
        return moreThanOneComponentAnnotation();
      }

      validateIsAbstractType();
      validateCreators();
      validateNoReusableAnnotation();
      validateComponentMethods();
      validateNoConflictingEntryPoints();
      validateSubcomponentReferences();
      validateComponentDependencies();
      validateReferencedModules();
      validateSubcomponents();

      return report.build();
    }

    private ValidationReport<TypeElement> moreThanOneComponentAnnotation() {
      String error =
          "Components may not be annotated with more than one component annotation: found "
              + annotationsFor(componentKinds);
      report.addError(error, component);
      return report.build();
    }

    private void validateIsAbstractType() {
      if (!component.getKind().equals(INTERFACE)
          && !(component.getKind().equals(CLASS) && component.getModifiers().contains(ABSTRACT))) {
        report.addError(
            String.format(
                "@%s may only be applied to an interface or abstract class",
                componentKind().annotation().simpleName()),
            component);
      }
    }

    private void validateCreators() {
      List<DeclaredType> creators =
          creatorAnnotationsFor(componentAnnotation()).stream()
              .flatMap(annotation -> enclosedAnnotatedTypes(component, annotation).stream())
              .collect(Collectors.toList());
      creators.forEach(
          creator -> report.addSubreport(creatorValidator.validate(asTypeElement(creator))));
      if (creators.size() > 1) {
        report.addError(
            String.format(
                ErrorMessages.componentMessagesFor(componentKind()).moreThanOne(), creators),
            component);
      }
    }

    private void validateNoReusableAnnotation() {
      Optional<AnnotationMirror> reusableAnnotation =
          getAnnotationMirror(component, TypeNames.REUSABLE);
      reusableAnnotation.ifPresent(annotationMirror -> report.addError(
          "@Reusable cannot be applied to components or subcomponents",
          component,
          annotationMirror));
    }

    private void validateComponentMethods() {
      elements.getUnimplementedMethods(component).stream()
          .map(ComponentMethodValidator::new)
          .forEachOrdered(ComponentMethodValidator::validateMethod);
    }

    private class ComponentMethodValidator {
      private final ExecutableElement method;
      private final ExecutableType resolvedMethod;
      private final List<? extends TypeMirror> parameterTypes;
      private final List<? extends VariableElement> parameters;
      private final TypeMirror returnType;

      ComponentMethodValidator(ExecutableElement method) {
        this.method = method;
        this.resolvedMethod = asExecutable(types.asMemberOf(componentType(), method));
        this.parameterTypes = resolvedMethod.getParameterTypes();
        this.parameters = method.getParameters();
        this.returnType = resolvedMethod.getReturnType();
      }

      void validateMethod() {
        validateNoTypeVariables();

        // abstract methods are ones we have to implement, so they each need to be validated
        // first, check the return type. if it's a subcomponent, validate that method as
        // such.
        Optional<AnnotationMirror> subcomponentAnnotation = subcomponentAnnotation();
        if (subcomponentAnnotation.isPresent()) {
          validateSubcomponentFactoryMethod(subcomponentAnnotation.get());
        } else if (subcomponentCreatorAnnotation().isPresent()) {
          validateSubcomponentCreatorMethod();
        } else {
          // if it's not a subcomponent...
          switch (parameters.size()) {
            case 0:
              validateProvisionMethod();
              break;
            case 1:
              report.addError(
                  "This method isn't a valid provision method or "
                      + "subcomponent factory method. Members injection has been disabled",
                  method);
              break;
            default:
              reportInvalidMethod();
              break;
          }
        }
      }

      private void validateNoTypeVariables() {
        if (!resolvedMethod.getTypeVariables().isEmpty()) {
          report.addError("Component methods cannot have type variables", method);
        }
      }

      private Optional<AnnotationMirror> subcomponentAnnotation() {
        return checkForAnnotations(
            returnType,
            componentKind().legalSubcomponentKinds().stream()
                .map(ComponentKind::annotation)
                .collect(Collectors.toSet()));
      }

      private Optional<AnnotationMirror> subcomponentCreatorAnnotation() {
        return checkForAnnotations(
            returnType,
            subcomponentCreatorAnnotations());
      }

      private void validateSubcomponentFactoryMethod(AnnotationMirror subcomponentAnnotation) {
        referencedSubcomponents.merge(MoreTypes.asElement(returnType), Set.of(method), Util::mutableUnion);

        ComponentKind subcomponentKind =
            ComponentKind.forAnnotatedElement(MoreTypes.asTypeElement(returnType)).orElseThrow();
        Set<TypeElement> moduleTypes =
            ComponentAnnotation.componentAnnotation(subcomponentAnnotation).modules();

        // TODO(gak): This logic maybe/probably shouldn't live here as it requires us to traverse
        // subcomponents and their modules separately from how it is done in ComponentDescriptor and
        // ModuleDescriptor
        @SuppressWarnings("deprecation")
        Set<TypeElement> transitiveModules =
            getTransitiveModules(types, elements, moduleTypes);

        Set<TypeElement> variableTypes = new HashSet<>();

        for (int i = 0; i < parameterTypes.size(); i++) {
          VariableElement parameter = parameters.get(i);
          TypeMirror parameterType = parameterTypes.get(i);
          Optional<TypeElement> moduleType =
              parameterType.accept(
                  new SimpleTypeVisitor8<Optional<TypeElement>, Void>() {
                    @Override
                    protected Optional<TypeElement> defaultAction(TypeMirror e, Void p) {
                      return Optional.empty();
                    }

                    @Override
                    public Optional<TypeElement> visitDeclared(DeclaredType t, Void p) {
                      for (ModuleKind moduleKind : subcomponentKind.legalModuleKinds()) {
                        if (isAnnotationPresent(t.asElement(), moduleKind.annotation())) {
                          return Optional.of(MoreTypes.asTypeElement(t));
                        }
                      }
                      return Optional.empty();
                    }
                  },
                  null);
          if (moduleType.isPresent()) {
            if (variableTypes.contains(moduleType.get())) {
              report.addError(
                  String.format(
                      "A module may only occur once an an argument in a Subcomponent factory "
                          + "method, but %s was already passed.",
                      moduleType.get().getQualifiedName()),
                  parameter);
            }
            if (!transitiveModules.contains(moduleType.get())) {
              report.addError(
                  String.format(
                      "%s is present as an argument to the %s factory method, but is not one of the"
                          + " modules used to implement the subcomponent.",
                      moduleType.get().getQualifiedName(),
                      MoreTypes.asTypeElement(returnType).getQualifiedName()),
                  method);
            }
            variableTypes.add(moduleType.get());
          } else {
            report.addError(
                String.format(
                    "Subcomponent factory methods may only accept modules, but %s is not.",
                    parameterType),
                parameter);
          }
        }
      }

      private void validateSubcomponentCreatorMethod() {
        referencedSubcomponents.merge(MoreTypes.asElement(returnType).getEnclosingElement(), Set.of(method), Util::mutableUnion);

        if (!parameters.isEmpty()) {
          report.addError(builderMethodRequiresNoArgs(), method);
        }

        TypeElement creatorElement = MoreTypes.asTypeElement(returnType);
        // TODO(sameb): The creator validator right now assumes the element is being compiled
        // in this pass, which isn't true here.  We should change error messages to spit out
        // this method as the subject and add the original subject to the message output.
        report.addSubreport(creatorValidator.validate(creatorElement));
      }

      private void validateProvisionMethod() {
        dependencyRequestValidator.validateDependencyRequest(report, method, returnType);
      }

      private void reportInvalidMethod() {
        report.addError(
            "This method isn't a valid provision method or "
                + "subcomponent factory method. Dagger cannot implement this method",
            method);
      }
    }

    private void validateNoConflictingEntryPoints() {
      // Collect entry point methods that are not overridden by others. If the "same" method is
      // inherited from more than one supertype, each will be in the multimap.
      Map<String, Set<ExecutableElement>> entryPointMethods = new LinkedHashMap<>();

      methodsIn(elements.getAllMembers(component)).stream()
          .filter(
              method ->
                  isEntryPoint(method, asExecutable(types.asMemberOf(componentType(), method))))
          .forEach(
              method -> {
                String key = method.getSimpleName().toString();
                Set<ExecutableElement> methods = entryPointMethods.getOrDefault(key, Set.of());
                if (methods.stream().noneMatch(existingMethod -> overridesAsDeclared(existingMethod, method))) {
                  methods.forEach(existingMethod -> {
                    if (overridesAsDeclared(method, existingMethod)) {
                      entryPointMethods.merge(key, Set.of(method), Util::difference);
                    }
                  });
                  entryPointMethods.merge(key, Set.of(method), Util::mutableUnion);
                }
              });

      for (Set<ExecutableElement> methods : entryPointMethods.values()) {
        if (distinctKeys(methods).size() > 1) {
          reportConflictingEntryPoints(methods);
        }
      }
    }

    private void reportConflictingEntryPoints(Collection<ExecutableElement> methods) {
      Preconditions.checkState(
          methods.stream().map(ExecutableElement::getEnclosingElement).distinct().count()
              == methods.size(),
          "expected each method to be declared on a different type: %s",
          methods);
      StringBuilder message = new StringBuilder("conflicting entry point declarations:");
      methodSignatureFormatter
          .typedFormatter(componentType())
          .formatIndentedList(
              message,
              methods.stream()
                  .sorted(comparing(method -> asType(method.getEnclosingElement()).getQualifiedName().toString()))
                  .collect(Collectors.toList()),
              1);
      report.addError(message.toString());
    }

    private void validateSubcomponentReferences() {
      referencedSubcomponents.entrySet().stream()
          .filter(e -> e.getValue().size() > 1)
          .forEach(e -> {
            Element subcomponent = e.getKey();
            Collection<ExecutableElement> methods = e.getValue();
            report.addError(
                String.format(moreThanOneRefToSubcomponent(), subcomponent, methods),
                component);
          });
    }

    private void validateComponentDependencies() {
      for (TypeMirror type : componentAnnotation().dependencyTypes()) {
        type.accept(CHECK_DEPENDENCY_TYPES, report);
      }
    }

    private void validateReferencedModules() {
      report.addSubreport(
          moduleValidator.validateReferencedModules(
              component,
              componentAnnotation().annotation(),
              componentKind().legalModuleKinds(),
              new HashSet<>()));
    }

    private void validateSubcomponents() {
      // Make sure we validate any subcomponents we're referencing.
      for (Element subcomponent : referencedSubcomponents.keySet()) {
        ValidationReport<TypeElement> subreport = validate(asType(subcomponent));
        report.addSubreport(subreport);
      }
    }

    private Set<Key> distinctKeys(Set<ExecutableElement> methods) {
      return methods.stream()
          .map(this::dependencyRequest)
          .map(DependencyRequest::key)
          .collect(toImmutableSet());
    }

    private DependencyRequest dependencyRequest(ExecutableElement method) {
      ExecutableType methodType = asExecutable(types.asMemberOf(componentType(), method));
      return dependencyRequestFactory.forComponentProvisionMethod(method, methodType);
    }
  }

  private static boolean isEntryPoint(ExecutableElement method, ExecutableType methodType) {
    return method.getModifiers().contains(ABSTRACT)
        && method.getParameters().isEmpty()
        && !methodType.getReturnType().getKind().equals(VOID)
        && methodType.getTypeVariables().isEmpty();
  }

  /**
   * Returns {@code true} if {@code overrider} overrides {@code overridden} considered from within
   * the type that declares {@code overrider}.
   */
  // TODO(dpb): Does this break for ECJ?
  private boolean overridesAsDeclared(ExecutableElement overrider, ExecutableElement overridden) {
    return elements.overrides(overrider, overridden, asType(overrider.getEnclosingElement()));
  }

  private static final TypeVisitor<Void, ValidationReport.Builder<?>> CHECK_DEPENDENCY_TYPES =
      new SimpleTypeVisitor8<>() {
        @Override
        protected Void defaultAction(TypeMirror type, ValidationReport.Builder<?> report) {
          report.addError(type + " is not a valid component dependency type");
          return null;
        }

        @Override
        public Void visitDeclared(DeclaredType type, ValidationReport.Builder<?> report) {
          if (moduleAnnotation(MoreTypes.asTypeElement(type)).isPresent()) {
            report.addError(type + " is a module, which cannot be a component dependency");
          }
          return null;
        }
      };

  private static Optional<AnnotationMirror> checkForAnnotations(
      TypeMirror type, final Set<ClassName> annotations) {
    return type.accept(
        new SimpleTypeVisitor8<Optional<AnnotationMirror>, Void>(Optional.empty()) {
          @Override
          public Optional<AnnotationMirror> visitDeclared(DeclaredType t, Void p) {
            return DaggerElements.getAnyAnnotation(t.asElement(), annotations);
          }
        },
        null);
  }
}
