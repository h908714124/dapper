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

import static dagger.internal.codegen.base.Preconditions.checkNotNull;
import static dagger.internal.codegen.xprocessing.XConverters.toJavac;
import static dagger.internal.codegen.xprocessing.XElements.closestEnclosingTypeElement;
import static dagger.internal.codegen.xprocessing.XElements.getSimpleName;
import static java.util.stream.Collectors.toList;

import dagger.internal.codegen.collect.ImmutableSet;
import dagger.internal.codegen.collect.ImmutableSortedSet;
import dagger.internal.codegen.xprocessing.XElement;
import dagger.internal.codegen.xprocessing.XFieldElement;
import dagger.internal.codegen.xprocessing.XMethodElement;
import dagger.internal.codegen.xprocessing.XTypeElement;
import dagger.spi.model.BindingKind;
import dagger.spi.model.DependencyRequest;
import dagger.spi.model.Key;
import io.jbock.auto.value.AutoValue;
import io.jbock.auto.value.extension.memoized.Memoized;
import java.util.Optional;
import javax.lang.model.element.Modifier;

/** Represents the full members injection of a particular type. */
@AutoValue
public abstract class MembersInjectionBinding extends Binding {
  static MembersInjectionBinding create(
      Key key,
      ImmutableSet<DependencyRequest> dependencies,
      Optional<MembersInjectionBinding> unresolved,
      ImmutableSortedSet<InjectionSite> injectionSites) {
    return new AutoValue_MembersInjectionBinding(key, dependencies, unresolved, injectionSites);
  }

  @Override
  public final Optional<XElement> bindingElement() {
    return Optional.of(membersInjectedType());
  }

  public final XTypeElement membersInjectedType() {
    return key().type().xprocessing().getTypeElement();
  }

  @Override
  public abstract Optional<MembersInjectionBinding> unresolved();

  @Override
  public Optional<XTypeElement> contributingModule() {
    return Optional.empty();
  }

  /** The set of individual sites where {@code Inject} is applied. */
  public abstract ImmutableSortedSet<InjectionSite> injectionSites();

  @Override
  public BindingType bindingType() {
    return BindingType.MEMBERS_INJECTION;
  }

  @Override
  public BindingKind kind() {
    return BindingKind.MEMBERS_INJECTION;
  }

  @Override
  public boolean isNullable() {
    return false;
  }

  /**
   * Returns {@code true} if any of this binding's injection sites are directly on the bound type.
   */
  public boolean hasLocalInjectionSites() {
    return injectionSites().stream()
        .map(InjectionSite::enclosingTypeElement)
        .anyMatch(membersInjectedType()::equals);
  }

  @Override
  public boolean requiresModuleInstance() {
    return false;
  }

  @Memoized
  @Override
  public abstract int hashCode();

  // TODO(ronshapiro,dpb): simplify the equality semantics
  @Override
  public abstract boolean equals(Object obj);

  /** Metadata about a field or method injection site. */
  @AutoValue
  public abstract static class InjectionSite {
    /** The type of injection site. */
    public enum Kind {
      FIELD,
      METHOD,
    }

    public abstract Kind kind();

    public abstract XElement element();

    public abstract XTypeElement enclosingTypeElement();

    public abstract ImmutableSet<DependencyRequest> dependencies();

    /**
     * Returns the index of {@code #element()} in its parents {@code @Inject} members that have the
     * same simple name. This method filters out private elements so that the results will be
     * consistent independent of whether the build system uses header jars or not.
     */
    @Memoized
    public int indexAmongAtInjectMembersWithSameSimpleName() {
      return enclosingTypeElement().getEnclosedElements().stream()
          .filter(InjectionAnnotations::hasInjectAnnotation)
          .filter(element -> !toJavac(element).getModifiers().contains(Modifier.PRIVATE))
          .filter(element -> getSimpleName(element).equals(getSimpleName(this.element())))
          .collect(toList())
          .indexOf(element());
    }

    public static InjectionSite field(XFieldElement field, DependencyRequest dependency) {
      return create(Kind.FIELD, field, ImmutableSet.of(dependency));
    }

    public static InjectionSite method(
        XMethodElement method, Iterable<DependencyRequest> dependencies) {
      return create(Kind.METHOD, method, ImmutableSet.copyOf(dependencies));
    }

    private static InjectionSite create(
        Kind kind, XElement element, ImmutableSet<DependencyRequest> dependencies) {
      XTypeElement enclosingTypeElement = checkNotNull(closestEnclosingTypeElement(element));
      return new AutoValue_MembersInjectionBinding_InjectionSite(
          kind, element, enclosingTypeElement, dependencies);
    }
  }
}
