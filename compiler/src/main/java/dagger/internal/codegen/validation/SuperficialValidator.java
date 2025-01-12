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

import static dagger.internal.codegen.xprocessing.XElements.closestEnclosingTypeElement;

import dagger.internal.codegen.base.ClearableCache;
import dagger.internal.codegen.base.DaggerSuperficialValidation;
import dagger.internal.codegen.base.DaggerSuperficialValidation.ValidationException;
import dagger.internal.codegen.xprocessing.XElement;
import dagger.internal.codegen.xprocessing.XTypeElement;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Validates enclosing type elements in a round. */
@Singleton
public final class SuperficialValidator implements ClearableCache {

  private final DaggerSuperficialValidation superficialValidation;
  private final Map<XTypeElement, Optional<ValidationException>> validationExceptions =
      new HashMap<>();

  @Inject
  SuperficialValidator(DaggerSuperficialValidation superficialValidation) {
    this.superficialValidation = superficialValidation;
  }

  public void throwIfNearestEnclosingTypeNotValid(XElement element) {
    Optional<ValidationException> validationException =
        validationExceptions.computeIfAbsent(
            closestEnclosingTypeElement(element),
            this::validationExceptionsUncached);

    if (validationException.isPresent()) {
      throw validationException.get();
    }
  }

  private Optional<ValidationException> validationExceptionsUncached(XTypeElement element) {
    try {
      superficialValidation.validateElement(element);
    } catch (ValidationException validationException) {
      return Optional.of(validationException);
    }
    return Optional.empty();
  }

  @Override
  public void clearCache() {
    validationExceptions.clear();
  }
}
