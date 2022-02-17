/*
 * Copyright (C) 2018 The Dagger Authors.
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

import static dagger.internal.codegen.xprocessing.XConverters.toXProcessing;
import static java.util.Objects.requireNonNull;

import dagger.Module;
import dagger.internal.codegen.xprocessing.XProcessingEnv;
import dagger.spi.model.BindingKind;
import dagger.spi.model.ComponentPath;
import dagger.spi.model.DaggerElement;
import dagger.spi.model.DaggerTypeElement;
import dagger.spi.model.DependencyRequest;
import dagger.spi.model.Key;
import dagger.spi.model.Scope;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import javax.lang.model.element.Element;

/**
 * An implementation of {@link dagger.spi.model.Binding} that also exposes {@link BindingDeclaration}s
 * associated with the binding.
 */
// TODO(dpb): Consider a supertype of dagger.spi.model.Binding that
// dagger.internal.codegen.binding.Binding
// could also implement.
public final class BindingNode implements dagger.spi.model.Binding {
  private final XProcessingEnv processingEnv;

  public static BindingNode create(
      XProcessingEnv processingEnv,
      ComponentPath component,
      Binding delegate,
      Set<SubcomponentDeclaration> subcomponentDeclarations,
      BindingDeclarationFormatter bindingDeclarationFormatter) {
    return new BindingNode(
        processingEnv,
        component,
        delegate,
        subcomponentDeclarations,
        bindingDeclarationFormatter);
  }

  private final ComponentPath componentPath;
  private final dagger.internal.codegen.binding.Binding delegate;
  private final Set<SubcomponentDeclaration> subcomponentDeclarations;
  private final BindingDeclarationFormatter bindingDeclarationFormatter;

  private BindingNode(
      XProcessingEnv processingEnv,
      ComponentPath componentPath,
      dagger.internal.codegen.binding.Binding delegate,
      Set<SubcomponentDeclaration> subcomponentDeclarations,
      BindingDeclarationFormatter bindingDeclarationFormatter) {
    this.processingEnv = processingEnv;
    this.componentPath = requireNonNull(componentPath);
    this.delegate = requireNonNull(delegate);
    this.subcomponentDeclarations = requireNonNull(subcomponentDeclarations);
    this.bindingDeclarationFormatter = requireNonNull(bindingDeclarationFormatter);
  }

  @Override
  public ComponentPath componentPath() {
    return componentPath;
  }

  public dagger.internal.codegen.binding.Binding delegate() {
    return delegate;
  }

  public Set<SubcomponentDeclaration> subcomponentDeclarations() {
    return subcomponentDeclarations;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    BindingNode that = (BindingNode) o;
    return componentPath.equals(that.componentPath)
        && delegate.equals(that.delegate)
        && subcomponentDeclarations.equals(that.subcomponentDeclarations);
  }

  @Override
  public int hashCode() {
    return Objects.hash(componentPath, delegate, subcomponentDeclarations);
  }

  /**
   * The {@link Element}s (other than the binding's {@link #bindingElement()}) that are associated
   * with the binding.
   *
   * <ul>
   *   <li>{@linkplain Module#subcomponents() module subcomponent} declarations
   * </ul>
   */
  public Set<BindingDeclaration> associatedDeclarations() {
    return new LinkedHashSet<>(subcomponentDeclarations());
  }

  @Override
  public Key key() {
    return delegate().key();
  }

  @Override
  public Set<DependencyRequest> dependencies() {
    return delegate().dependencies();
  }

  @Override
  public Optional<DaggerElement> bindingElement() {
    return delegate().bindingElement().map(DaggerElement::from);
  }

  @Override
  public Optional<DaggerTypeElement> contributingModule() {
    return delegate()
        .contributingModule()
        .map(module -> toXProcessing(module, processingEnv))
        .map(DaggerTypeElement::from);
  }

  @Override
  public boolean requiresModuleInstance() {
    return delegate().requiresModuleInstance();
  }

  @Override
  public Optional<Scope> scope() {
    return delegate().scope();
  }

  @Override
  public BindingKind kind() {
    return delegate().kind();
  }

  @Override
  public String toString() {
    return bindingDeclarationFormatter.format(delegate());
  }
}
