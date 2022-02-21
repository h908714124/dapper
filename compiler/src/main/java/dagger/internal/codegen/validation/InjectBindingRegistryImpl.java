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

import static dagger.internal.codegen.binding.AssistedInjectionAnnotations.assistedInjectedConstructors;
import static dagger.internal.codegen.binding.InjectionAnnotations.injectedConstructors;
import static dagger.internal.codegen.binding.SourceFiles.generatedClassNameForBinding;
import static dagger.internal.codegen.collect.Keys.isValidImplicitProvisionKey;
import static dagger.internal.codegen.xprocessing.XConverters.toXProcessing;
import static dagger.internal.codegen.xprocessing.XElements.asTypeElement;
import static io.jbock.auto.common.MoreTypes.asTypeElement;
import static java.util.Objects.requireNonNull;
import static javax.lang.model.type.TypeKind.DECLARED;

import dagger.Component;
import dagger.Provides;
import dagger.internal.codegen.base.Preconditions;
import dagger.internal.codegen.base.SourceFileGenerationException;
import dagger.internal.codegen.base.SourceFileGenerator;
import dagger.internal.codegen.base.Util;
import dagger.internal.codegen.binding.Binding;
import dagger.internal.codegen.binding.BindingFactory;
import dagger.internal.codegen.binding.InjectBindingRegistry;
import dagger.internal.codegen.binding.KeyFactory;
import dagger.internal.codegen.binding.ProvisionBinding;
import dagger.internal.codegen.compileroption.CompilerOptions;
import dagger.internal.codegen.langmodel.DaggerElements;
import dagger.internal.codegen.langmodel.DaggerTypes;
import dagger.internal.codegen.xprocessing.XConstructorElement;
import dagger.internal.codegen.xprocessing.XFieldElement;
import dagger.internal.codegen.xprocessing.XMessager;
import dagger.internal.codegen.xprocessing.XMethodElement;
import dagger.internal.codegen.xprocessing.XProcessingEnv;
import dagger.internal.codegen.xprocessing.XType;
import dagger.internal.codegen.xprocessing.XTypeElement;
import dagger.spi.model.Key;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic.Kind;

/**
 * Maintains the collection of provision bindings from {@code Inject} constructors known to the annotation processor.
 * Note that this registry <b>does not</b> handle any explicit bindings (those from {@link Provides}
 * methods, {@link Component} dependencies, etc.).
 */
@Singleton
final class InjectBindingRegistryImpl implements InjectBindingRegistry {
  private final XProcessingEnv processingEnv;
  private final DaggerElements elements;
  private final DaggerTypes types;
  private final XMessager messager;
  private final InjectValidator injectValidator;
  private final InjectValidator injectValidatorWhenGeneratingCode;
  private final KeyFactory keyFactory;
  private final BindingFactory bindingFactory;
  private final CompilerOptions compilerOptions;

  final class BindingsCollection<B extends Binding> {
    private final Class<?> factoryClass;
    private final Map<Key, B> bindingsByKey = new LinkedHashMap<>();
    private final Deque<B> bindingsRequiringGeneration = new ArrayDeque<>();
    private final Set<Key> materializedBindingKeys = new LinkedHashSet<>();

    BindingsCollection(Class<?> factoryClass) {
      this.factoryClass = factoryClass;
    }

    void generateBindings(SourceFileGenerator<B> generator) throws SourceFileGenerationException {
      for (B binding = bindingsRequiringGeneration.poll();
           binding != null;
           binding = bindingsRequiringGeneration.poll()) {
        Preconditions.checkState(binding.unresolved().isEmpty());
        TypeMirror type = binding.key().type().java();
        if (!type.getKind().equals(DECLARED)
            || injectValidatorWhenGeneratingCode
            .validate(toXProcessing(asTypeElement(type), processingEnv))
            .isClean()) {
          generator.generate(binding);
        }
        materializedBindingKeys.add(binding.key());
      }
      // Because Elements instantiated across processing rounds are not guaranteed to be equals() to
      // the logically same element, clear the cache after generating
      bindingsByKey.clear();
    }

    /** Returns a previously cached binding. */
    B getBinding(Key key) {
      return bindingsByKey.get(key);
    }

    /** Caches the binding and generates it if it needs generation. */
    void tryRegisterBinding(B binding, boolean warnIfNotAlreadyGenerated) {
      tryToCacheBinding(binding);

      @SuppressWarnings("unchecked")
      B maybeUnresolved =
          binding.unresolved().isPresent() ? (B) binding.unresolved().get() : binding;
      tryToGenerateBinding(maybeUnresolved, warnIfNotAlreadyGenerated);
    }

    /**
     * Tries to generate a binding, not generating if it already is generated. For resolved
     * bindings, this will try to generate the unresolved version of the binding.
     */
    void tryToGenerateBinding(B binding, boolean warnIfNotAlreadyGenerated) {
      if (shouldGenerateBinding(binding)) {
        bindingsRequiringGeneration.offer(binding);
        if (compilerOptions.warnIfInjectionFactoryNotGeneratedUpstream()
            && warnIfNotAlreadyGenerated) {
          messager.printMessage(
              Kind.NOTE,
              String.format(
                  "Generating a %s for %s. "
                      + "Prefer to run the dagger processor over that class instead.",
                  factoryClass.getSimpleName(),
                  types.erasure(binding.key().type().java()))); // erasure to strip <T> from msgs.
        }
      }
    }

    /** Returns true if the binding needs to be generated. */
    private boolean shouldGenerateBinding(B binding) {
      return binding.unresolved().isEmpty()
          && !materializedBindingKeys.contains(binding.key())
          && !bindingsRequiringGeneration.contains(binding)
          && elements.getTypeElement(generatedClassNameForBinding(binding)) == null;
    }

    /** Caches the binding for future lookups by key. */
    private void tryToCacheBinding(B binding) {
      // We only cache resolved bindings or unresolved bindings w/o type arguments.
      // Unresolved bindings w/ type arguments aren't valid for the object graph.
      if (binding.unresolved().isPresent()
          || binding.bindingTypeElement().get().getType().getTypeArguments().isEmpty()) {
        Key key = binding.key();
        Binding previousValue = bindingsByKey.put(key, binding);
        Preconditions.checkState(previousValue == null || binding.equals(previousValue),
            "couldn't register %s. %s was already registered for %s",
            binding, previousValue, key);
      }
    }

  }

  private final BindingsCollection<ProvisionBinding> provisionBindings =
      new BindingsCollection<>(Provider.class);

  @Inject
  InjectBindingRegistryImpl(
      XProcessingEnv processingEnv,
      DaggerElements elements,
      DaggerTypes types,
      XMessager messager,
      InjectValidator injectValidator,
      KeyFactory keyFactory,
      BindingFactory bindingFactory,
      CompilerOptions compilerOptions) {
    this.processingEnv = processingEnv;
    this.elements = elements;
    this.types = types;
    this.messager = messager;
    this.injectValidator = injectValidator;
    this.injectValidatorWhenGeneratingCode = injectValidator.whenGeneratingCode();
    this.keyFactory = keyFactory;
    this.bindingFactory = bindingFactory;
    this.compilerOptions = compilerOptions;
  }


  // TODO(dpb): make the SourceFileGenerators fields so they don't have to be passed in
  @Override
  public void generateSourcesForRequiredBindings(
      SourceFileGenerator<ProvisionBinding> factoryGenerator)
      throws SourceFileGenerationException {
    provisionBindings.generateBindings(factoryGenerator);
  }

  /**
   * Registers the binding for generation and later lookup. If the binding is resolved, we also
   * attempt to register an unresolved version of it.
   */
  private void registerBinding(ProvisionBinding binding, boolean warnIfNotAlreadyGenerated) {
    provisionBindings.tryRegisterBinding(binding, warnIfNotAlreadyGenerated);
  }

  @Override
  public Optional<ProvisionBinding> tryRegisterInjectConstructor(XConstructorElement constructorElement) {
    return tryRegisterConstructor(constructorElement, Optional.empty(), false);
  }

  private Optional<ProvisionBinding> tryRegisterConstructor(
      XConstructorElement constructorElement,
      Optional<XType> resolvedType,
      boolean warnIfNotAlreadyGenerated) {
    XTypeElement typeElement = constructorElement.getEnclosingElement();

    // Validating here shouldn't have a performance penalty because the validator caches its reports
    ValidationReport report = injectValidator.validate(typeElement);
    report.printMessagesTo(messager);
    if (!report.isClean()) {
      return Optional.empty();
    }

    XType type = typeElement.getType();
    Key key = keyFactory.forInjectConstructorWithResolvedType(type);
    ProvisionBinding cachedBinding = provisionBindings.getBinding(key);
    if (cachedBinding != null) {
      return Optional.of(cachedBinding);
    }

    ProvisionBinding binding = bindingFactory.injectionBinding(constructorElement, resolvedType);
    registerBinding(binding, warnIfNotAlreadyGenerated);
    return Optional.of(binding);
  }

  @Override
  public void tryRegisterInjectField(XFieldElement fieldElement) {
    tryRegisterMembersInjectedType(asTypeElement(fieldElement.getEnclosingElement()));
  }

  @Override
  public void tryRegisterInjectMethod(XMethodElement methodElement) {
    tryRegisterMembersInjectedType(asTypeElement(methodElement.getEnclosingElement()));
  }

  private void tryRegisterMembersInjectedType(XTypeElement typeElement) {
    ValidationReport report =
        injectValidator.validateMembersInjectionType(typeElement);
    report.printMessagesTo(messager);
  }

  @Override
  public Optional<ProvisionBinding> getOrFindProvisionBinding(Key key) {
    requireNonNull(key);
    if (!isValidImplicitProvisionKey(key)) {
      return Optional.empty();
    }
    ProvisionBinding binding = provisionBindings.getBinding(key);
    if (binding != null) {
      return Optional.of(binding);
    }

    // ok, let's see if we can find an @Inject constructor
    XTypeElement element = key.type().xprocessing().getTypeElement();
    Set<XConstructorElement> injectConstructors = new LinkedHashSet<>();
    injectConstructors.addAll(injectedConstructors(element));
    injectConstructors.addAll(assistedInjectedConstructors(element));
    switch (injectConstructors.size()) {
      case 0:
        // No constructor found.
        return Optional.empty();
      case 1:
        return tryRegisterConstructor(
            Util.getOnlyElement(injectConstructors), Optional.of(key.type().xprocessing()), true);
      default:
        throw new IllegalStateException("Found multiple @Inject constructors: "
            + injectConstructors);
    }
  }
}
