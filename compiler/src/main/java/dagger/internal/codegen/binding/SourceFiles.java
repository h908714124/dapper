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

import static dagger.internal.codegen.base.CaseFormat.LOWER_CAMEL;
import static dagger.internal.codegen.base.CaseFormat.UPPER_CAMEL;
import static dagger.internal.codegen.base.Preconditions.checkArgument;
import static dagger.internal.codegen.base.Verify.verify;
import static dagger.internal.codegen.extension.DaggerStreams.toImmutableList;
import static dagger.internal.codegen.javapoet.TypeNames.DOUBLE_CHECK;
import static dagger.internal.codegen.javapoet.TypeNames.PROVIDER_OF_LAZY;
import static dagger.internal.codegen.xprocessing.XConverters.toJavac;
import static dagger.spi.model.BindingKind.ASSISTED_INJECTION;
import static dagger.spi.model.BindingKind.INJECTION;
import static io.jbock.auto.common.MoreElements.asExecutable;
import static io.jbock.auto.common.MoreElements.asType;
import static javax.lang.model.SourceVersion.isName;

import dagger.internal.codegen.base.Joiner;
import dagger.internal.codegen.collect.ImmutableList;
import dagger.internal.codegen.collect.ImmutableMap;
import dagger.internal.codegen.collect.ImmutableSet;
import dagger.internal.codegen.collect.Iterables;
import dagger.internal.codegen.collect.Maps;
import dagger.internal.codegen.xprocessing.XTypeElement;
import dagger.spi.model.DependencyRequest;
import dagger.spi.model.RequestKind;
import io.jbock.auto.common.MoreElements;
import io.jbock.javapoet.ClassName;
import io.jbock.javapoet.CodeBlock;
import io.jbock.javapoet.FieldSpec;
import io.jbock.javapoet.ParameterizedTypeName;
import io.jbock.javapoet.TypeName;
import io.jbock.javapoet.TypeVariableName;
import java.util.List;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.element.VariableElement;

/** Utilities for generating files. */
public class SourceFiles {

  private static final Joiner CLASS_FILE_NAME_JOINER = Joiner.on('_');

  /**
   * Generates names and keys for the factory class fields needed to hold the framework classes for
   * all of the dependencies of {@code binding}. It is responsible for choosing a name that
   *
   * <ul>
   *   <li>represents all of the dependency requests for this key
   *   <li>is <i>probably</i> associated with the type being bound
   *   <li>is unique within the class
   * </ul>
   *
   * @param binding must be an unresolved binding (type parameters must match its type element's)
   */
  public static ImmutableMap<DependencyRequest, FrameworkField>
  generateBindingFieldsForDependencies(Binding binding) {
    checkArgument(!binding.unresolved().isPresent(), "binding must be unresolved: %s", binding);

    FrameworkTypeMapper frameworkTypeMapper =
        FrameworkTypeMapper.forBindingType(binding.bindingType());

    return Maps.toMap(
        binding.dependencies(),
        dependency ->
            FrameworkField.create(
                frameworkTypeMapper.getFrameworkType(dependency.kind()).frameworkClassName(),
                TypeName.get(dependency.key().type().java()),
                DependencyVariableNamer.name(dependency)));
  }

  public static CodeBlock frameworkTypeUsageStatement(
      CodeBlock frameworkTypeMemberSelect, RequestKind dependencyKind) {
    switch (dependencyKind) {
      case LAZY:
        return CodeBlock.of("$T.lazy($L)", DOUBLE_CHECK, frameworkTypeMemberSelect);
      case INSTANCE:
        return CodeBlock.of("$L.get()", frameworkTypeMemberSelect);
      case PROVIDER:
        return frameworkTypeMemberSelect;
      case PROVIDER_OF_LAZY:
        return CodeBlock.of("$T.create($L)", PROVIDER_OF_LAZY, frameworkTypeMemberSelect);
      default: // including PRODUCED
        throw new AssertionError(dependencyKind);
    }
  }

  /**
   * Returns a mapping of {@link DependencyRequest}s to {@link CodeBlock}s that {@linkplain
   * #frameworkTypeUsageStatement(CodeBlock, RequestKind) use them}.
   */
  public static ImmutableMap<DependencyRequest, CodeBlock> frameworkFieldUsages(
      ImmutableSet<DependencyRequest> dependencies,
      ImmutableMap<DependencyRequest, FieldSpec> fields) {
    return Maps.toMap(
        dependencies,
        dep -> frameworkTypeUsageStatement(CodeBlock.of("$N", fields.get(dep)), dep.kind()));
  }

  /** Returns the generated factory or members injector name for a binding. */
  public static ClassName generatedClassNameForBinding(Binding binding) {
    switch (binding.bindingType()) {
      case PROVISION:
        ContributionBinding contribution = (ContributionBinding) binding;
        switch (contribution.kind()) {
          case ASSISTED_INJECTION:
          case INJECTION:
          case PROVISION:
            return elementBasedClassName(
                asExecutable(toJavac(binding.bindingElement().get())), "Factory");

          case ASSISTED_FACTORY:
            return siblingClassName(asType(toJavac(binding.bindingElement().get())), "_Impl");

          default:
            throw new AssertionError();
        }

      case MEMBERS_INJECTION:
        return membersInjectorNameForType(
            ((MembersInjectionBinding) binding).membersInjectedType());
    }
    throw new AssertionError();
  }

  /**
   * Calculates an appropriate {@link ClassName} for a generated class that is based on {@code
   * element}, appending {@code suffix} at the end.
   *
   * <p>This will always return a {@linkplain ClassName#topLevelClassName() top level class name},
   * even if {@code element}'s enclosing class is a nested type.
   */
  public static ClassName elementBasedClassName(ExecutableElement element, String suffix) {
    ClassName enclosingClassName =
        ClassName.get(MoreElements.asType(element.getEnclosingElement()));
    String methodName =
        element.getKind().equals(ElementKind.CONSTRUCTOR)
            ? ""
            : LOWER_CAMEL.to(UPPER_CAMEL, element.getSimpleName().toString());
    return ClassName.get(
        enclosingClassName.packageName(),
        classFileName(enclosingClassName) + "_" + methodName + suffix);
  }

  public static TypeName parameterizedGeneratedTypeNameForBinding(Binding binding) {
    ClassName className = generatedClassNameForBinding(binding);
    ImmutableList<TypeVariableName> typeParameters = bindingTypeElementTypeVariableNames(binding);
    return typeParameters.isEmpty()
        ? className
        : ParameterizedTypeName.get(className, Iterables.toArray(typeParameters, TypeName.class));
  }

  public static ClassName membersInjectorNameForType(XTypeElement typeElement) {
    return membersInjectorNameForType(toJavac(typeElement));
  }

  public static ClassName membersInjectorNameForType(TypeElement typeElement) {
    return siblingClassName(typeElement, "_MembersInjector");
  }

  public static String memberInjectedFieldSignatureForVariable(VariableElement variableElement) {
    return MoreElements.asType(variableElement.getEnclosingElement()).getQualifiedName()
        + "."
        + variableElement.getSimpleName();
  }

  public static String classFileName(ClassName className) {
    return CLASS_FILE_NAME_JOINER.join(className.simpleNames());
  }

  public static ClassName generatedMonitoringModuleName(XTypeElement componentElement) {
    return siblingClassName(toJavac(componentElement), "_MonitoringModule");
  }

  // TODO(ronshapiro): when JavaPoet migration is complete, replace the duplicated code
  // which could use this.
  private static ClassName siblingClassName(TypeElement typeElement, String suffix) {
    ClassName className = ClassName.get(typeElement);
    return className.topLevelClassName().peerClass(classFileName(className) + suffix);
  }

  public static ImmutableList<TypeVariableName> bindingTypeElementTypeVariableNames(
      Binding binding) {
    if (binding instanceof ContributionBinding) {
      ContributionBinding contributionBinding = (ContributionBinding) binding;
      if (!(contributionBinding.kind() == INJECTION
          || contributionBinding.kind() == ASSISTED_INJECTION)
          && !contributionBinding.requiresModuleInstance()) {
        return ImmutableList.of();
      }
    }
    List<? extends TypeParameterElement> typeParameters =
        toJavac(binding.bindingTypeElement().get()).getTypeParameters();
    return typeParameters.stream().map(TypeVariableName::get).collect(toImmutableList());
  }

  /**
   * Returns a name to be used for variables of the given {@linkplain TypeElement type}. Prefer
   * semantically meaningful variable names, but if none can be derived, this will produce something
   * readable.
   */
  // TODO(gak): maybe this should be a function of TypeMirrors instead of Elements?
  public static String simpleVariableName(TypeElement typeElement) {
    return simpleVariableName(ClassName.get(typeElement));
  }

  /**
   * Returns a name to be used for variables of the given {@linkplain ClassName}. Prefer
   * semantically meaningful variable names, but if none can be derived, this will produce something
   * readable.
   */
  public static String simpleVariableName(ClassName className) {
    String candidateName = UPPER_CAMEL.to(LOWER_CAMEL, className.simpleName());
    String variableName = protectAgainstKeywords(candidateName);
    verify(isName(variableName), "'%s' was expected to be a valid variable name", variableName);
    return variableName;
  }

  public static String protectAgainstKeywords(String candidateName) {
    switch (candidateName) {
      case "package":
        return "pkg";
      case "boolean":
        return "b";
      case "double":
        return "d";
      case "byte":
        return "b";
      case "int":
        return "i";
      case "short":
        return "s";
      case "char":
        return "c";
      case "void":
        return "v";
      case "class":
        return "clazz";
      case "float":
        return "f";
      case "long":
        return "l";
      default:
        return SourceVersion.isKeyword(candidateName) ? candidateName + '_' : candidateName;
    }
  }

  private SourceFiles() {
  }
}
