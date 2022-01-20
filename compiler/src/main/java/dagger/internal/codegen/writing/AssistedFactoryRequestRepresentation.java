/*
 * Copyright (C) 2020 The Dagger Authors.
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

package dagger.internal.codegen.writing;

import static com.google.auto.common.MoreElements.asType;
import static com.google.auto.common.MoreTypes.asDeclared;
import static dagger.internal.codegen.base.Util.getOnlyElement;
import static dagger.internal.codegen.binding.AssistedInjectionAnnotations.assistedFactoryMethod;
import static dagger.internal.codegen.binding.AssistedInjectionAnnotations.assistedFactoryParameterSpecs;
import static java.util.Objects.requireNonNull;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import dagger.assisted.Assisted;
import dagger.assisted.AssistedFactory;
import dagger.assisted.AssistedInject;
import dagger.internal.codegen.binding.BindingRequest;
import dagger.internal.codegen.binding.ProvisionBinding;
import dagger.internal.codegen.javapoet.Expression;
import dagger.internal.codegen.langmodel.DaggerElements;
import dagger.internal.codegen.langmodel.DaggerTypes;
import dagger.model.DependencyRequest;
import dagger.model.RequestKind;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;

/**
 * A {@link RequestRepresentation} for {@link
 * dagger.assisted.AssistedFactory} methods.
 */
final class AssistedFactoryRequestRepresentation extends RequestRepresentation {
  private final ProvisionBinding binding;
  private final ComponentRequestRepresentations componentRequestRepresentations;
  private final DaggerElements elements;
  private final DaggerTypes types;

  @AssistedInject
  AssistedFactoryRequestRepresentation(
      @Assisted ProvisionBinding binding,
      ComponentRequestRepresentations componentRequestRepresentations,
      DaggerTypes types,
      DaggerElements elements) {
    this.binding = requireNonNull(binding);
    this.componentRequestRepresentations = componentRequestRepresentations;
    this.elements = elements;
    this.types = types;
  }

  @Override
  Expression getDependencyExpression(ClassName requestingClass) {
    // An assisted factory binding should have a single request for an assisted injection type.
    DependencyRequest assistedInjectionRequest = getOnlyElement(binding.provisionDependencies());
    Expression assistedInjectionExpression =
        ((AssistedPrivateMethodRequestRepresentation)
            componentRequestRepresentations.getRequestRepresentation(
                BindingRequest.bindingRequest(
                    assistedInjectionRequest.key(), RequestKind.INSTANCE)))
            .getAssistedDependencyExpression(requestingClass.peerClass(""));
    return Expression.create(
        assistedInjectionExpression.type(),
        CodeBlock.of("$L", anonymousfactoryImpl(assistedInjectionExpression)));
  }

  private TypeSpec anonymousfactoryImpl(Expression assistedInjectionExpression) {
    TypeElement factory = asType(binding.bindingElement().get());
    DeclaredType factoryType = asDeclared(binding.key().type());
    ExecutableElement factoryMethod = assistedFactoryMethod(factory, elements);

    // We can't use MethodSpec.overriding directly because we need to control the parameter names.
    MethodSpec factoryOverride = MethodSpec.overriding(factoryMethod, factoryType, types).build();
    TypeSpec.Builder builder =
        TypeSpec.anonymousClassBuilder("")
            .addMethod(
                MethodSpec.methodBuilder(factoryMethod.getSimpleName().toString())
                    .addModifiers(factoryOverride.modifiers)
                    .addTypeVariables(factoryOverride.typeVariables)
                    .returns(factoryOverride.returnType)
                    .addAnnotations(factoryOverride.annotations)
                    .addExceptions(factoryOverride.exceptions)
                    .addParameters(assistedFactoryParameterSpecs(binding, elements, types))
                    .addStatement("return $L", assistedInjectionExpression.codeBlock())
                    .build());

    if (factory.getKind() == ElementKind.INTERFACE) {
      builder.addSuperinterface(TypeName.get(factoryType));
    } else {
      builder.superclass(TypeName.get(factoryType));
    }

    return builder.build();
  }

  @AssistedFactory
  static interface Factory {
    AssistedFactoryRequestRepresentation create(ProvisionBinding binding);
  }
}
