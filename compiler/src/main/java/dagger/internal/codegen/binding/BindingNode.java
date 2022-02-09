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

import static java.util.Objects.requireNonNull;

import dagger.Module;
import dagger.model.BindingKind;
import dagger.model.ComponentPath;
import dagger.model.DependencyRequest;
import dagger.model.Scope;
import dagger.spi.model.DaggerElement;
import dagger.spi.model.DaggerTypeElement;
import dagger.spi.model.Key;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;

/**
 * An implementation of {@link dagger.model.Binding} that also exposes {@link BindingDeclaration}s
 * associated with the binding.
 */
// TODO(dpb): Consider a supertype of dagger.model.Binding that
// dagger.internal.codegen.binding.Binding
// could also implement.
public final class BindingNode implements dagger.model.Binding {
  public static BindingNode create(
      ComponentPath component,
      Binding delegate,
      Set<SubcomponentDeclaration> subcomponentDeclarations,
      BindingDeclarationFormatter bindingDeclarationFormatter) {
    return new BindingNode(
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
      ComponentPath componentPath,
      dagger.internal.codegen.binding.Binding delegate,
      Set<SubcomponentDeclaration> subcomponentDeclarations,
      BindingDeclarationFormatter bindingDeclarationFormatter) {
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
    return delegate().bindingElement().map(DaggerElement::fromJava);
  }

  @Override
  public Optional<DaggerTypeElement> contributingModule() {
    return delegate().contributingModule().map(DaggerTypeElement::fromJava);
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
