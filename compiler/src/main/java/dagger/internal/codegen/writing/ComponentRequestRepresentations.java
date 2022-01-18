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

package dagger.internal.codegen.writing;

import static dagger.internal.codegen.binding.BindingRequest.bindingRequest;
import static dagger.internal.codegen.javapoet.CodeBlocks.makeParametersCodeBlock;
import static dagger.internal.codegen.javapoet.TypeNames.DOUBLE_CHECK;
import static dagger.internal.codegen.javapoet.TypeNames.SINGLE_CHECK;
import static dagger.internal.codegen.langmodel.Accessibility.isRawTypeAccessible;
import static dagger.internal.codegen.langmodel.Accessibility.isTypeAccessibleFrom;
import static dagger.internal.codegen.writing.DelegateRequestRepresentation.isBindsScopeStrongerThanDependencyScope;
import static dagger.internal.codegen.writing.MemberSelect.staticFactoryCreation;
import static dagger.model.BindingKind.DELEGATE;
import static java.util.Objects.requireNonNull;

import com.google.auto.common.MoreTypes;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import dagger.internal.codegen.base.Preconditions;
import dagger.internal.codegen.binding.Binding;
import dagger.internal.codegen.binding.BindingGraph;
import dagger.internal.codegen.binding.BindingRequest;
import dagger.internal.codegen.binding.ComponentDescriptor.ComponentMethodDescriptor;
import dagger.internal.codegen.binding.ComponentRequirement;
import dagger.internal.codegen.binding.ContributionBinding;
import dagger.internal.codegen.binding.FrameworkType;
import dagger.internal.codegen.binding.FrameworkTypeMapper;
import dagger.internal.codegen.compileroption.CompilerOptions;
import dagger.internal.codegen.javapoet.Expression;
import dagger.internal.codegen.langmodel.DaggerTypes;
import dagger.internal.codegen.writing.ComponentImplementation.ShardImplementation;
import dagger.internal.codegen.writing.FrameworkFieldInitializer.FrameworkInstanceCreationExpression;
import dagger.model.BindingKind;
import dagger.model.DependencyRequest;
import dagger.model.RequestKind;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.lang.model.type.TypeMirror;

/** A central repository of code expressions used to access any binding available to a component. */
@PerComponentImplementation
public final class ComponentRequestRepresentations {
  // TODO(dpb,ronshapiro): refactor this and ComponentRequirementExpressions into a
  // HierarchicalComponentMap<K, V>, or perhaps this use a flattened ImmutableMap, built from its
  // parents? If so, maybe make BindingExpression.Factory create it.

  private final Optional<ComponentRequestRepresentations> parent;
  private final BindingGraph graph;
  private final ComponentImplementation componentImplementation;
  private final ComponentRequirementExpressions componentRequirementExpressions;
  private final ComponentMethodRequestRepresentation.Factory componentMethodBindingExpressionFactory;
  private final DelegateRequestRepresentation.Factory delegateBindingExpressionFactory;
  private final DerivedFromFrameworkInstanceRequestRepresentation.Factory
      derivedFromFrameworkInstanceBindingExpressionFactory;
  private final PrivateMethodRequestRepresentation.Factory privateMethodBindingExpressionFactory;
  private final AssistedPrivateMethodRequestRepresentation.Factory
      assistedPrivateMethodBindingExpressionFactory;
  private final ProviderInstanceRequestRepresentation.Factory providerInstanceBindingExpressionFactory;
  private final UnscopedDirectInstanceRequestRepresentationFactory
      unscopedDirectInstanceRequestRepresentationFactory;
  private final UnscopedFrameworkInstanceCreationExpressionFactory
      unscopedFrameworkInstanceCreationExpressionFactory;
  private final DaggerTypes types;
  private final CompilerOptions compilerOptions;
  private final SwitchingProviders switchingProviders;
  private final Map<BindingRequest, RequestRepresentation> expressions = new HashMap<>();

  @Inject
  ComponentRequestRepresentations(
      @ParentComponent Optional<ComponentRequestRepresentations> parent,
      BindingGraph graph,
      ComponentImplementation componentImplementation,
      ComponentRequirementExpressions componentRequirementExpressions,
      ComponentMethodRequestRepresentation.Factory componentMethodBindingExpressionFactory,
      DelegateRequestRepresentation.Factory delegateBindingExpressionFactory,
      DerivedFromFrameworkInstanceRequestRepresentation.Factory
          derivedFromFrameworkInstanceBindingExpressionFactory,
      PrivateMethodRequestRepresentation.Factory privateMethodBindingExpressionFactory,
      AssistedPrivateMethodRequestRepresentation.Factory assistedPrivateMethodBindingExpressionFactory,
      ProviderInstanceRequestRepresentation.Factory providerInstanceBindingExpressionFactory,
      UnscopedDirectInstanceRequestRepresentationFactory unscopedDirectInstanceRequestRepresentationFactory,
      UnscopedFrameworkInstanceCreationExpressionFactory
          unscopedFrameworkInstanceCreationExpressionFactory,
      DaggerTypes types,
      CompilerOptions compilerOptions) {
    this.parent = parent;
    this.graph = graph;
    this.componentImplementation = componentImplementation;
    this.componentRequirementExpressions = requireNonNull(componentRequirementExpressions);
    this.componentMethodBindingExpressionFactory = componentMethodBindingExpressionFactory;
    this.delegateBindingExpressionFactory = delegateBindingExpressionFactory;
    this.derivedFromFrameworkInstanceBindingExpressionFactory =
        derivedFromFrameworkInstanceBindingExpressionFactory;
    this.privateMethodBindingExpressionFactory = privateMethodBindingExpressionFactory;
    this.providerInstanceBindingExpressionFactory = providerInstanceBindingExpressionFactory;
    this.unscopedDirectInstanceRequestRepresentationFactory =
        unscopedDirectInstanceRequestRepresentationFactory;
    this.unscopedFrameworkInstanceCreationExpressionFactory =
        unscopedFrameworkInstanceCreationExpressionFactory;
    this.types = types;
    this.compilerOptions = compilerOptions;
    this.switchingProviders = new SwitchingProviders(componentImplementation, types);
    this.assistedPrivateMethodBindingExpressionFactory =
        assistedPrivateMethodBindingExpressionFactory;
  }

  /**
   * Returns an expression that evaluates to the value of a binding request for a binding owned by
   * this component or an ancestor.
   *
   * @param requestingClass the class that will contain the expression
   * @throws IllegalStateException if there is no binding expression that satisfies the request
   */
  public Expression getDependencyExpression(BindingRequest request, ClassName requestingClass) {
    return getBindingExpression(request).getDependencyExpression(requestingClass);
  }

  /**
   * Equivalent to {@link #getDependencyExpression(BindingRequest, ClassName)} that is used only
   * when the request is for implementation of a component method.
   *
   * @throws IllegalStateException if there is no binding expression that satisfies the request
   */
  Expression getDependencyExpressionForComponentMethod(
      BindingRequest request,
      ComponentMethodDescriptor componentMethod,
      ComponentImplementation componentImplementation) {
    return getBindingExpression(request)
        .getDependencyExpressionForComponentMethod(componentMethod, componentImplementation);
  }

  /**
   * Returns the {@link CodeBlock} for the method arguments used with the factory {@code create()}
   * method for the given {@link ContributionBinding binding}.
   */
  CodeBlock getCreateMethodArgumentsCodeBlock(
      ContributionBinding binding, ClassName requestingClass) {
    return makeParametersCodeBlock(getCreateMethodArgumentsCodeBlocks(binding, requestingClass));
  }

  private List<CodeBlock> getCreateMethodArgumentsCodeBlocks(
      ContributionBinding binding, ClassName requestingClass) {
    List<CodeBlock> arguments = new ArrayList<>();

    if (binding.requiresModuleInstance()) {
      arguments.add(
          componentRequirementExpressions.getExpressionDuringInitialization(
              ComponentRequirement.forModule(binding.contributingModule().orElseThrow().asType()),
              requestingClass));
    }

    binding.dependencies().stream()
        .map(dependency -> frameworkRequest(binding, dependency))
        .map(request -> getDependencyExpression(request, requestingClass))
        .map(Expression::codeBlock)
        .forEach(arguments::add);

    return arguments;
  }

  private static BindingRequest frameworkRequest(
      ContributionBinding binding, DependencyRequest dependency) {
    // TODO(bcorso): See if we can get rid of FrameworkTypeMatcher
    FrameworkType frameworkType =
        FrameworkTypeMapper.forBindingType(binding.bindingType())
            .getFrameworkType(dependency.kind());
    return BindingRequest.bindingRequest(dependency.key(), frameworkType);
  }

  /**
   * Returns an expression that evaluates to the value of a dependency request, for passing to a
   * binding method, an {@code @Inject}-annotated constructor or member, or a proxy for one.
   *
   * <p>If the method is a generated static {@link InjectionMethods injection method}, each
   * parameter will be {@link Object} if the dependency's raw type is inaccessible. If that is the
   * case for this dependency, the returned expression will use a cast to evaluate to the raw type.
   *
   * @param requestingClass the class that will contain the expression
   */
  Expression getDependencyArgumentExpression(
      DependencyRequest dependencyRequest, ClassName requestingClass) {

    TypeMirror dependencyType = dependencyRequest.key().type();
    BindingRequest bindingRequest = bindingRequest(dependencyRequest);
    Expression dependencyExpression = getDependencyExpression(bindingRequest, requestingClass);

    if (dependencyRequest.kind().equals(RequestKind.INSTANCE)
        && !isTypeAccessibleFrom(dependencyType, requestingClass.packageName())
        && isRawTypeAccessible(dependencyType, requestingClass.packageName())) {
      return dependencyExpression.castTo(types.erasure(dependencyType));
    }

    return dependencyExpression;
  }

  /** Returns the implementation of a component method. */
  public MethodSpec getComponentMethod(ComponentMethodDescriptor componentMethod) {
    Preconditions.checkArgument(componentMethod.dependencyRequest().isPresent());
    BindingRequest request = bindingRequest(componentMethod.dependencyRequest().get());
    return MethodSpec.overriding(
            componentMethod.methodElement(),
            MoreTypes.asDeclared(graph.componentTypeElement().asType()),
            types)
        .addCode(
            getBindingExpression(request)
                .getComponentMethodImplementation(componentMethod, componentImplementation))
        .build();
  }

  /** Returns the {@link RequestRepresentation} for the given {@link BindingRequest}. */
  RequestRepresentation getBindingExpression(BindingRequest request) {
    if (expressions.containsKey(request)) {
      return expressions.get(request);
    }

    Optional<Binding> localBinding = graph.localContributionBinding(request.key());

    if (localBinding.isPresent()) {
      RequestRepresentation expression = createBindingExpression(localBinding.get(), request);
      expressions.put(request, expression);
      return expression;
    }

    Preconditions.checkArgument(parent.isPresent(), "no expression found for %s", request);
    return parent.get().getBindingExpression(request);
  }

  /** Creates a binding expression. */
  private RequestRepresentation createBindingExpression(Binding binding, BindingRequest request) {
    switch (binding.bindingType()) {

      case PROVISION:
        return provisionBindingExpression((ContributionBinding) binding, request);

    }
    throw new AssertionError(binding);
  }

  /**
   * Returns a binding expression that uses a {@code Provider} for provision bindings.
   */
  private RequestRepresentation frameworkInstanceBindingExpression(ContributionBinding binding) {
    FrameworkInstanceCreationExpression frameworkInstanceCreationExpression =
        unscopedFrameworkInstanceCreationExpressionFactory.create(binding);

    if (isFastInit()
        // Some creation expressions can opt out of using switching providers.
        && frameworkInstanceCreationExpression.useSwitchingProvider()) {
      // First try to get the instance expression via getBindingExpression(). However, if that
      // expression is a DerivedFromFrameworkInstanceBindingExpression (e.g. fooProvider.get()),
      // then we can't use it to create an instance within the SwitchingProvider since that would
      // cause a cycle. In such cases, we try to use the unscopedDirectInstanceBindingExpression
      // directly, or else fall back to default mode.
      BindingRequest instanceRequest = bindingRequest(binding.key(), RequestKind.INSTANCE);
      RequestRepresentation instanceExpression = getBindingExpression(instanceRequest);
      if (!(instanceExpression instanceof DerivedFromFrameworkInstanceRequestRepresentation)) {
        frameworkInstanceCreationExpression =
            switchingProviders.newFrameworkInstanceCreationExpression(binding, instanceExpression);
      } else {
        Optional<RequestRepresentation> unscopedInstanceExpression =
            unscopedDirectInstanceRequestRepresentationFactory.create(binding);
        if (unscopedInstanceExpression.isPresent()) {
          frameworkInstanceCreationExpression =
              switchingProviders.newFrameworkInstanceCreationExpression(
                  binding,
                  unscopedInstanceExpression.get().requiresMethodEncapsulation()
                      ? privateMethodBindingExpressionFactory.create(
                          instanceRequest, binding, unscopedInstanceExpression.get())
                      : unscopedInstanceExpression.get());
        }
      }
    }

    // TODO(bcorso): Consider merging the static factory creation logic into CreationExpressions?
    Optional<MemberSelect> staticMethod =
        useStaticFactoryCreation() ? staticFactoryCreation(binding) : Optional.empty();
    FrameworkInstanceSupplier frameworkInstanceSupplier =
        staticMethod.isPresent()
            ? staticMethod::get
            : new FrameworkFieldInitializer(
                componentImplementation,
                binding,
                binding.scope().isPresent()
                    ? scope(binding, frameworkInstanceCreationExpression)
                    : frameworkInstanceCreationExpression);

    switch (binding.bindingType()) {
      case PROVISION:
        return providerInstanceBindingExpressionFactory.create(binding, frameworkInstanceSupplier);
      default:
        throw new AssertionError("invalid binding type: " + binding.bindingType());
    }
  }

  private FrameworkInstanceCreationExpression scope(
      ContributionBinding binding, FrameworkInstanceCreationExpression unscoped) {
    return () ->
        CodeBlock.of(
            "$T.provider($L)",
            binding.scope().orElseThrow().isReusable() ? SINGLE_CHECK : DOUBLE_CHECK,
            unscoped.creationExpression());
  }

  /** Returns a binding expression for a provision binding. */
  private RequestRepresentation provisionBindingExpression(
      ContributionBinding binding, BindingRequest request) {
    switch (request.requestKind()) {
      case INSTANCE:
        return instanceBindingExpression(binding);

      case PROVIDER:
        return providerBindingExpression(binding);

      case LAZY:
      case PROVIDER_OF_LAZY:
        return derivedFromFrameworkInstanceBindingExpressionFactory.create(
            request);
    }

    throw new AssertionError();
  }

  /**
   * Returns a binding expression for {@link RequestKind#PROVIDER} requests.
   *
   * <p>{@code @Binds} bindings that don't {@linkplain #needsCaching(ContributionBinding) need to be
   * cached} can use a {@link DelegateRequestRepresentation}.
   *
   * <p>In fastInit mode, use an {@link SwitchingProviders inner switching provider} unless
   * that provider's case statement will simply call {@code get()} on another {@code Provider} (in
   * which case, just use that Provider directly).
   *
   * <p>Otherwise, return a {@link FrameworkInstanceRequestRepresentation}.
   */
  private RequestRepresentation providerBindingExpression(ContributionBinding binding) {
    if (binding.kind().equals(DELEGATE) && !needsCaching(binding)) {
      return delegateBindingExpressionFactory.create(binding, RequestKind.PROVIDER);
    }
    return frameworkInstanceBindingExpression(binding);
  }

  /**
   * Returns a binding expression for {@link RequestKind#INSTANCE} requests.
   */
  private RequestRepresentation instanceBindingExpression(ContributionBinding binding) {
    Optional<RequestRepresentation> maybeDirectInstanceExpression =
        unscopedDirectInstanceRequestRepresentationFactory.create(binding);
    if (maybeDirectInstanceExpression.isPresent()) {
      RequestRepresentation directInstanceExpression = maybeDirectInstanceExpression.orElseThrow();
      if (binding.kind() == BindingKind.ASSISTED_INJECTION) {
        BindingRequest request = bindingRequest(binding.key(), RequestKind.INSTANCE);
        return assistedPrivateMethodBindingExpressionFactory.create(
            request, binding, directInstanceExpression);
      }
      boolean isDefaultModeAssistedFactory =
          binding.kind() == BindingKind.ASSISTED_FACTORY && !isFastInit();

      // If this is the case where we don't need to use Provider#get() because there's no caching
      // and it isn't a default mode assisted factory, we can try to use the direct expression,
      // possibly wrapped in a method if necessary (e.g. if it has dependencies).
      // Note: We choose not to use a direct expression for assisted factories in default mode
      // because they technically act more similar to a Provider than an instance, so we cache them
      // using a field in the component similar to Provider requests. This should also be the case
      // in FastInit, but it hasn't been implemented yet.
      if (!needsCaching(binding) && !isDefaultModeAssistedFactory) {
        return directInstanceExpression.requiresMethodEncapsulation()
            ? wrapInMethod(binding, RequestKind.INSTANCE, directInstanceExpression)
            : directInstanceExpression;
      }
    }
    return derivedFromFrameworkInstanceBindingExpressionFactory.create(
        bindingRequest(binding.key(), RequestKind.INSTANCE));
  }

  /**
   * Returns {@code true} if the binding should use the static factory creation strategy.
   *
   * <p>In default mode, we always use the static factory creation strategy. In fastInit mode, we
   * prefer to use a SwitchingProvider instead of static factories in order to reduce class loading;
   * however, we allow static factories that can reused across multiple bindings, e.g. {@code
   * MapFactory} or {@code SetFactory}.
   */
  private boolean useStaticFactoryCreation() {
    return !isFastInit();
  }

  /**
   * Returns a binding expression that uses a given one as the body of a method that users call. If
   * a component provision method matches it, it will be the method implemented. If it does not
   * match a component provision method and the binding is modifiable, then a new public modifiable
   * binding method will be written. If the binding doesn't match a component method and is not
   * modifiable, then a new private method will be written.
   */
  RequestRepresentation wrapInMethod(
      ContributionBinding binding, RequestKind requestKind, RequestRepresentation requestRepresentation) {
    // If we've already wrapped the expression, then use the delegate.
    if (requestRepresentation instanceof MethodRequestRepresentation) {
      return requestRepresentation;
    }

    BindingRequest request = bindingRequest(binding.key(), requestKind);
    Optional<ComponentMethodDescriptor> matchingComponentMethod =
        graph.componentDescriptor().firstMatchingComponentMethod(request);

    ShardImplementation shardImplementation = componentImplementation.shardImplementation(binding);

    // Consider the case of a request from a component method like:
    //
    //   DaggerMyComponent extends MyComponent {
    //     @Overrides
    //     Foo getFoo() {
    //       <FOO_BINDING_REQUEST>
    //     }
    //   }
    //
    // Normally, in this case we would return a ComponentMethodBindingExpression rather than a
    // PrivateMethodBindingExpression so that #getFoo() can inline the implementation rather than
    // create an unnecessary private method and return that. However, with sharding we don't want to
    // inline the implementation because that would defeat some of the class pool savings if those
    // fields had to communicate across shards. Thus, when a key belongs to a separate shard use a
    // PrivateMethodBindingExpression and put the private method in the shard.
    if (matchingComponentMethod.isPresent() && shardImplementation.isComponentShard()) {
      ComponentMethodDescriptor componentMethod = matchingComponentMethod.get();
      return componentMethodBindingExpressionFactory.create(
          requestRepresentation, componentMethod);
    } else {
      return privateMethodBindingExpressionFactory.create(request, binding, requestRepresentation);
    }
  }

  /**
   * Returns {@code true} if the component needs to make sure the provided value is cached.
   *
   * <p>The component needs to cache the value for scoped bindings except for {@code @Binds}
   * bindings whose scope is no stronger than their delegate's.
   */
  private boolean needsCaching(ContributionBinding binding) {
    if (binding.scope().isEmpty()) {
      return false;
    }
    if (binding.kind().equals(DELEGATE)) {
      return isBindsScopeStrongerThanDependencyScope(binding, graph);
    }
    return true;
  }

  private boolean isFastInit() {
    return compilerOptions.fastInit(
        parent.map(p -> p.graph).orElse(graph).componentDescriptor().typeElement());
  }
}