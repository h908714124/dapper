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

import dagger.internal.codegen.javapoet.TypeNames;
import dagger.internal.codegen.langmodel.DaggerElements;
import dagger.internal.codegen.xprocessing.XAnnotation;
import dagger.internal.codegen.xprocessing.XMethodElement;
import dagger.internal.codegen.xprocessing.XType;
import dagger.internal.codegen.xprocessing.XTypeElement;
import jakarta.inject.Inject;
import java.util.List;

/** A validator for {@code MapKey} annotations. */
// TODO(dpb,gak): Should unwrapped MapKeys be required to have their single member be named "value"?
public final class MapKeyValidator {
  private final DaggerElements elements;

  @Inject
  MapKeyValidator(DaggerElements elements) {
    this.elements = elements;
  }

  public ValidationReport validate(XTypeElement element) {
    ValidationReport.Builder builder = ValidationReport.about(element);
    List<XMethodElement> members = element.getDeclaredMethods();
    if (members.isEmpty()) {
      builder.addError("Map key annotations must have members", element);
    } else if (XAnnotation.get(
        element.getAnnotation(TypeNames.MAP_KEY), "unwrapValue", Boolean.class)) {
      if (members.size() > 1) {
        builder.addError(
            "Map key annotations with unwrapped values must have exactly one member", element);
      } else if (XType.isArray(members.get(0).getReturnType())) {
        builder.addError("Map key annotations with unwrapped values cannot use arrays", element);
      }
    } else if (autoAnnotationIsMissing()) {
      builder.addError(
          "@AutoAnnotation is a necessary dependency if @MapKey(unwrapValue = false). Add a "
              + "dependency for the annotation, "
              + "\"com.google.auto.value:auto-value-annotations:<current version>\", "
              + "and the annotation processor, "
              + "\"com.google.auto.value:auto-value:<current version>\"");
    }
    return builder.build();
  }

  private boolean autoAnnotationIsMissing() {
    return elements.getTypeElement("com.google.auto.value.AutoAnnotation") == null;
  }
}