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

package dagger.internal.codegen.validation;

import static dagger.internal.codegen.langmodel.DaggerElements.isAnnotationPresent;
import static dagger.internal.codegen.xprocessing.XConverters.toJavac;

import dagger.internal.codegen.base.ClearableCache;
import dagger.internal.codegen.javapoet.TypeNames;
import dagger.internal.codegen.validation.DaggerSuperficialValidation.ValidationException;
import dagger.internal.codegen.xprocessing.XTypeElement;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.HashMap;
import java.util.Map;
import javax.lang.model.element.TypeElement;


/** Validates inject types in a round. */
@Singleton
public final class SuperficialInjectValidator implements ClearableCache {

  private final Map<XTypeElement, Boolean> validatedTypeElements = new HashMap<>();

  @Inject
  SuperficialInjectValidator() {}

  public void throwIfNotValid(XTypeElement injectTypeElement) {
    if (!validatedTypeElements.computeIfAbsent(injectTypeElement, this::validate)) {
      throw new TypeNotPresentException(injectTypeElement.toString(), null);
    }
  }

  private boolean validate(XTypeElement xInjectTypeElement) {
    // The inject validator inspects:
    //   1. the type itself
    //   2. the type's annotations (needed for scoping)
    //   3. the type's superclass (needed for inherited @Inject members)
    //   4. the direct fields, constructors, and methods annotated with @Inject
    // TODO(bcorso): Call the #validate() methods from XProcessing instead once we have validation
    // for types other than elements, e.g. annotations, annotation values, and types.
    TypeElement injectTypeElement = toJavac(xInjectTypeElement);
    try {
      return DaggerSuperficialValidation.validateType("class type", injectTypeElement.asType())
          && DaggerSuperficialValidation.validateAnnotations(
              injectTypeElement.getAnnotationMirrors())
          && DaggerSuperficialValidation.validateType(
              "superclass type", injectTypeElement.getSuperclass())
          && injectTypeElement.getEnclosedElements().stream()
            .filter(element -> isAnnotationPresent(element, TypeNames.INJECT))
            .allMatch(DaggerSuperficialValidation::validateElement);
    } catch (RuntimeException exception) {
      throw ValidationException.create(
          "SuperficialInjectValidator.validate: " + injectTypeElement, exception);
    }
  }

  @Override
  public void clearCache() {
    validatedTypeElements.clear();
  }
}
