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

import static dagger.internal.codegen.base.Preconditions.checkState;
import static dagger.internal.codegen.base.Util.reentrantComputeIfAbsent;
import static dagger.internal.codegen.binding.BindingRequest.bindingRequest;
import static dagger.internal.codegen.langmodel.Accessibility.isTypeAccessibleFrom;
import static dagger.internal.codegen.writing.ComponentImplementation.MethodSpecKind.MEMBERS_INJECTION_METHOD;
import static dagger.internal.codegen.xprocessing.XElements.getSimpleName;
import static io.jbock.javapoet.MethodSpec.methodBuilder;
import static javax.lang.model.element.Modifier.PRIVATE;
import static javax.lang.model.element.Modifier.STATIC;

import dagger.internal.codegen.binding.Binding;
import dagger.internal.codegen.binding.BindingGraph;
import dagger.internal.codegen.binding.MembersInjectionBinding;
import dagger.internal.codegen.binding.MembersInjectionBinding.InjectionSite;
import dagger.internal.codegen.binding.ProvisionBinding;
import dagger.internal.codegen.collect.ImmutableSet;
import dagger.internal.codegen.javapoet.Expression;
import dagger.internal.codegen.writing.ComponentImplementation.ShardImplementation;
import dagger.internal.codegen.writing.InjectionMethods.InjectionSiteMethod;
import dagger.internal.codegen.xprocessing.XProcessingEnv;
import dagger.internal.codegen.xprocessing.XType;
import dagger.internal.codegen.xprocessing.XTypeElement;
import dagger.spi.model.Key;
import io.jbock.javapoet.ClassName;
import io.jbock.javapoet.CodeBlock;
import io.jbock.javapoet.MethodSpec;
import io.jbock.javapoet.ParameterSpec;
import io.jbock.javapoet.TypeName;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.Map;

/** Manages the member injection methods for a component. */
@PerComponentImplementation
final class MembersInjectionMethods {
  private final Map<Key, Expression> injectMethodExpressions = new LinkedHashMap<>();
  private final Map<Key, Expression> experimentalInjectMethodExpressions = new LinkedHashMap<>();
  private final ComponentImplementation componentImplementation;
  private final ComponentRequestRepresentations bindingExpressions;
  private final BindingGraph graph;
  private final XProcessingEnv processingEnv;

  @Inject
  MembersInjectionMethods(
      ComponentImplementation componentImplementation,
      ComponentRequestRepresentations bindingExpressions,
      BindingGraph graph,
      XProcessingEnv processingEnv) {
    this.componentImplementation = componentImplementation;
    this.bindingExpressions = bindingExpressions;
    this.graph = graph;
    this.processingEnv = processingEnv;
  }

  /**
   * Returns the members injection {@code Expression} for the given {@code Key}, creating it if
   * necessary.
   */
  Expression getInjectExpression(Key key, CodeBlock instance, ClassName requestingClass) {
    Binding binding =
        graph.localMembersInjectionBinding(key).isPresent()
            ? graph.localMembersInjectionBinding(key).get()
            : graph.localContributionBinding(key).get();
    Expression expression =
        reentrantComputeIfAbsent(
            injectMethodExpressions, key, k -> injectMethodExpression(binding, false));
    ShardImplementation shardImplementation = componentImplementation.shardImplementation(binding);
    return Expression.create(
        expression.type(),
        shardImplementation.name().equals(requestingClass)
            ? CodeBlock.of("$L($L)", expression.codeBlock(), instance)
            : CodeBlock.of(
                "$L.$L($L)",
                shardImplementation.shardFieldReference(),
                expression.codeBlock(),
                instance));
  }

  /**
   * Returns the members injection {@code Expression} for the given {@code Key}, creating it if
   * necessary.
   */
  Expression getInjectExpressionExperimental(
      ProvisionBinding provisionBinding, CodeBlock instance, ClassName requestingClass) {
    checkState(
        componentImplementation.compilerMode().isExperimentalMergedMode(),
        "Compiler mode should be experimentalMergedMode!");
    Expression expression =
        reentrantComputeIfAbsent(
            experimentalInjectMethodExpressions,
            provisionBinding.key(),
            k -> injectMethodExpression(provisionBinding, true));
    return Expression.create(
        expression.type(), CodeBlock.of("$L($L, dependencies)", expression.codeBlock(), instance));
  }

  private Expression injectMethodExpression(Binding binding, boolean useStaticInjectionMethod) {
    // TODO(wanyingd): move Switching Providers and injection methods to Shard classes to avoid
    // exceeding component class constant pool limit.
    // Add to Component Shard so that is can be accessible from Switching Providers.
    ShardImplementation shardImplementation =
        useStaticInjectionMethod
            ? componentImplementation.getComponentShard()
            : componentImplementation.shardImplementation(binding);
    XType keyType = binding.key().type().xprocessing();
    XType membersInjectedType =
        isTypeAccessibleFrom(keyType, shardImplementation.name().packageName())
            ? keyType
            : processingEnv.requireType(TypeName.OBJECT);
    String bindingTypeName = getSimpleName(binding.bindingTypeElement().get());
    // TODO(ronshapiro): include type parameters in this name e.g. injectFooOfT, and outer class
    // simple names Foo.Builder -> injectFooBuilder
    String methodName = shardImplementation.getUniqueMethodName("inject" + bindingTypeName);
    ParameterSpec parameter =
        ParameterSpec.builder(membersInjectedType.getTypeName(), "instance").build();
    MethodSpec.Builder methodBuilder =
        useStaticInjectionMethod
            ? methodBuilder(methodName)
                .addModifiers(PRIVATE, STATIC)
                .returns(membersInjectedType.getTypeName())
                .addParameter(parameter)
                .addParameter(Object[].class, "dependencies")
            : methodBuilder(methodName)
                .addModifiers(PRIVATE)
                .returns(membersInjectedType.getTypeName())
                .addParameter(parameter);
    XTypeElement canIgnoreReturnValue =
        processingEnv.findTypeElement("com.google.errorprone.annotations.CanIgnoreReturnValue");
    if (canIgnoreReturnValue != null) {
      methodBuilder.addAnnotation(canIgnoreReturnValue.getClassName());
    }
    CodeBlock instance = CodeBlock.of("$N", parameter);
    methodBuilder.addCode(
        InjectionSiteMethod.invokeAll(
            injectionSites(binding),
            shardImplementation.name(),
            instance,
            membersInjectedType,
            request ->
                (useStaticInjectionMethod
                        ? bindingExpressions
                            .getExperimentalSwitchingProviderDependencyRepresentation(
                                bindingRequest(request))
                            .getDependencyExpression(request.kind(), (ProvisionBinding) binding)
                        : bindingExpressions.getDependencyArgumentExpression(
                            request, shardImplementation.name()))
                    .codeBlock(),
            processingEnv));
    methodBuilder.addStatement("return $L", instance);

    MethodSpec method = methodBuilder.build();
    shardImplementation.addMethod(MEMBERS_INJECTION_METHOD, method);
    return Expression.create(membersInjectedType, CodeBlock.of("$N", method));
  }

  private static ImmutableSet<InjectionSite> injectionSites(Binding binding) {
    if (binding instanceof ProvisionBinding) {
      return ((ProvisionBinding) binding).injectionSites();
    } else if (binding instanceof MembersInjectionBinding) {
      return ((MembersInjectionBinding) binding).injectionSites();
    }
    throw new IllegalArgumentException(binding.key().toString());
  }
}
