/*
 * Copyright (C) 2017 The Dagger Authors.
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

import static dagger.internal.codegen.xprocessing.XConverters.toJavac;
import static dagger.internal.codegen.xprocessing.XElements.asConstructor;
import static dagger.internal.codegen.xprocessing.XElements.asTypeElement;

import dagger.internal.codegen.base.Preconditions;
import dagger.internal.codegen.binding.AssistedInjectionAnnotations;
import dagger.internal.codegen.binding.AssistedInjectionAnnotations.AssistedFactoryMetadata;
import dagger.internal.codegen.binding.Binding;
import dagger.internal.codegen.writing.ComponentImplementation.ShardImplementation;
import dagger.internal.codegen.xprocessing.XConstructorElement;
import dagger.internal.codegen.xprocessing.XConstructorType;
import dagger.internal.codegen.xprocessing.XMethodType;
import dagger.internal.codegen.xprocessing.XType;
import dagger.internal.codegen.xprocessing.XTypeElement;
import dagger.internal.codegen.xprocessing.XVariableElement;
import dagger.spi.model.BindingKind;
import io.jbock.javapoet.ParameterSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** Utility class for generating unique assisted parameter names for a component shard. */
final class AssistedInjectionParameters {
  /**
   * Returns the list of assisted factory parameters as {@link ParameterSpec}s.
   *
   * <p>The type of each parameter will be the resolved type given by the binding key, and the name
   * of each parameter will be the name given in the {@link
   * dagger.assisted.AssistedInject}-annotated constructor.
   */
  public static List<ParameterSpec> assistedFactoryParameterSpecs(
      Binding binding,
      ShardImplementation shardImplementation) {
    Preconditions.checkArgument(binding.kind() == BindingKind.ASSISTED_FACTORY);
    XTypeElement factory = asTypeElement(binding.bindingElement().get());
    AssistedFactoryMetadata metadata = AssistedFactoryMetadata.create(factory.getType());
    XMethodType factoryMethodType =
        metadata.factoryMethod().asMemberOf(binding.key().type().xprocessing());
    return assistedParameterSpecs(
        // Use the order of the parameters from the @AssistedFactory method but use the parameter
        // names of the @AssistedInject constructor.
        metadata.assistedFactoryAssistedParameters().stream()
            .map(metadata.assistedInjectAssistedParametersMap()::get)
            .collect(Collectors.toList()),
        factoryMethodType.getParameterTypes(),
        shardImplementation);
  }

  /**
   * Returns the list of assisted parameters as {@link ParameterSpec}s.
   *
   * <p>The type of each parameter will be the resolved type given by the binding key, and the name
   * of each parameter will be the name given in the {@link
   * dagger.assisted.AssistedInject}-annotated constructor.
   */
  public static List<ParameterSpec> assistedParameterSpecs(
      Binding binding, ShardImplementation shardImplementation) {
    Preconditions.checkArgument(binding.kind() == BindingKind.ASSISTED_INJECTION);
    XConstructorElement constructor = asConstructor(binding.bindingElement().get());
    XConstructorType constructorType = constructor.asMemberOf(binding.key().type().xprocessing());
    return assistedParameterSpecs(
        constructor.getParameters(), constructorType.getParameterTypes(), shardImplementation);
  }

  private static List<ParameterSpec> assistedParameterSpecs(
      List<? extends XVariableElement> paramElements,
      List<XType> paramTypes,
      ShardImplementation shardImplementation) {
    List<ParameterSpec> assistedParameterSpecs = new ArrayList<>();
    for (int i = 0; i < paramElements.size(); i++) {
      XVariableElement paramElement = paramElements.get(i);
      XType paramType = paramTypes.get(i);
      if (AssistedInjectionAnnotations.isAssistedParameter(paramElement)) {
        assistedParameterSpecs.add(
            ParameterSpec.builder(
                    paramType.getTypeName(),
                    shardImplementation.getUniqueFieldNameForAssistedParam(toJavac(paramElement)))
                .build());
      }
    }
    return assistedParameterSpecs;
  }

  private AssistedInjectionParameters() {
  }
}