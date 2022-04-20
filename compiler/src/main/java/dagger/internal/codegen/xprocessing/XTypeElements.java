/*
 * Copyright (C) 2021 The Dagger Authors.
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

package dagger.internal.codegen.xprocessing;

import static dagger.internal.codegen.base.Preconditions.checkNotNull;
import static dagger.internal.codegen.base.Util.asStream;
import static dagger.internal.codegen.extension.DaggerStreams.toImmutableList;
import static dagger.internal.codegen.xprocessing.XConverters.toJavac;

import dagger.internal.codegen.collect.ImmutableList;
import dagger.internal.codegen.collect.ImmutableSet;
import io.jbock.javapoet.TypeVariableName;

// TODO(bcorso): Consider moving these methods into XProcessing library.
/** A utility class for {@code XTypeElement} helper methods. */
public final class XTypeElements {
  private enum Visibility {
    PUBLIC,
    PRIVATE,
    OTHER;

    /** Returns the visibility of the given {@code XTypeElement}. */
    private static Visibility of(XTypeElement element) {
      checkNotNull(element);
      if (element.isPrivate()) {
        return Visibility.PRIVATE;
      } else if (element.isPublic()) {
        return Visibility.PUBLIC;
      } else {
        return Visibility.OTHER;
      }
    }
  }

  // TODO(bcorso): Consider XParameterizable interface to handle both methods and types.
  /** Returns the type arguments for the given type as a list of {@code TypeVariableName}. */
  public static ImmutableList<TypeVariableName> typeVariableNames(XTypeElement typeElement) {
    return toJavac(typeElement).getTypeParameters().stream()
        .map(TypeVariableName::get)
        .collect(toImmutableList());
  }

  /** Returns {@code true} if the given element is nested. */
  public static boolean isNested(XTypeElement typeElement) {
    return typeElement.getEnclosingTypeElement() != null;
  }

  /** Returns {@code true} if the given {@code type} has type parameters. */
  public static boolean hasTypeParameters(XTypeElement type) {
    // TODO(bcorso): Add support for XTypeElement#getTypeParameters() or at least
    // XTypeElement#hasTypeParameters() in XProcessing. XTypes#getTypeArguments() isn't quite the
    // same -- it tells you if the declared type has parameters rather than the element itself.
    return !toJavac(type).getTypeParameters().isEmpty();
  }

  /** Returns all non-private, non-static, abstract methods in {@code type}. */
  public static ImmutableList<XMethodElement> getAllUnimplementedMethods(XTypeElement type) {
    return getAllNonPrivateInstanceMethods(type).stream()
        .filter(XHasModifiers::isAbstract)
        .collect(toImmutableList());
  }

  /** Returns all non-private, non-static methods in {@code type}. */
  public static ImmutableList<XMethodElement> getAllNonPrivateInstanceMethods(XTypeElement type) {
    return getAllMethods(type).stream()
        .filter(method -> !method.isPrivate() && !method.isStatic())
        .collect(toImmutableList());
  }

  // TODO(b/229784604): This is needed until the XProcessing getAllMethods fix is upstreamed. Due
  // to the existing bug, XTypeElement#getAllMethods() will currently contain some inaccessible
  // package-private methods from base classes, so we filter them manually here.
  public static ImmutableList<XMethodElement> getAllMethods(XTypeElement type) {
    return asStream(type.getAllMethods())
        .filter(method -> isAccessibleFrom(method, type))
        .collect(toImmutableList());
  }

  private static boolean isAccessibleFrom(XMethodElement method, XTypeElement type) {
    if (method.isPublic() || method.isProtected()) {
      return true;
    }
    if (method.isPrivate()) {
      return false;
    }
    return method
        .getClosestMemberContainer()
        .getClassName()
        .packageName()
        .equals(type.getClassName().packageName());
  }

  public static boolean isEffectivelyPublic(XTypeElement element) {
    return allVisibilities(element).stream()
        .allMatch(visibility -> visibility.equals(Visibility.PUBLIC));
  }

  public static boolean isEffectivelyPrivate(XTypeElement element) {
    return allVisibilities(element).contains(Visibility.PRIVATE);
  }

  /**
   * Returns a list of visibilities containing visibility of the given element and the visibility of
   * its enclosing elements.
   */
  private static ImmutableSet<Visibility> allVisibilities(XTypeElement element) {
    checkNotNull(element);
    ImmutableSet.Builder<Visibility> visibilities = ImmutableSet.builder();
    XTypeElement currentElement = element;
    while (currentElement != null) {
      visibilities.add(Visibility.of(currentElement));
      currentElement = currentElement.getEnclosingTypeElement();
    }
    return visibilities.build();
  }

  private XTypeElements() {}
}
