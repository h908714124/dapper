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

import static dagger.internal.Preconditions.checkNotNull;
import static dagger.internal.codegen.base.Util.asStream;
import static dagger.internal.codegen.extension.DaggerStreams.toImmutableList;
import static dagger.internal.codegen.xprocessing.XConverters.toJavac;
import static java.util.Objects.requireNonNull;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

// TODO(bcorso): Consider moving these methods into XProcessing library.

/** A utility class for {@link XTypeElement} helper methods. */
public final class XTypeElements {
  private enum Visibility {
    PUBLIC,
    PRIVATE,
    OTHER;

    /** Returns the visibility of the given {@link XTypeElement}. */
    private static Visibility of(XTypeElement element) {
      requireNonNull(element);
      if (element.isPrivate()) {
        return Visibility.PRIVATE;
      } else if (element.isPublic()) {
        return Visibility.PUBLIC;
      } else {
        return Visibility.OTHER;
      }
    }
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
  public static List<XMethodElement> getAllUnimplementedMethods(XTypeElement type) {
    return asStream(type.getAllNonPrivateInstanceMethods())
        .filter(XHasModifiers::isAbstract)
        .collect(toImmutableList());
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
  private static Set<Visibility> allVisibilities(XTypeElement element) {
    checkNotNull(element);
    Set<Visibility> visibilities = new LinkedHashSet<>();
    XTypeElement currentElement = element;
    while (currentElement != null) {
      visibilities.add(Visibility.of(currentElement));
      currentElement = currentElement.getEnclosingTypeElement();
    }
    return visibilities;
  }

  private XTypeElements() {
  }
}
