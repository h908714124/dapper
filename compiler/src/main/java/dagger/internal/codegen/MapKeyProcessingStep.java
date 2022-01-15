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

package dagger.internal.codegen;

import com.google.auto.common.MoreElements;
import com.squareup.javapoet.ClassName;
import dagger.MapKey;
import dagger.internal.codegen.javapoet.TypeNames;
import dagger.internal.codegen.validation.MapKeyValidator;
import dagger.internal.codegen.validation.TypeCheckingProcessingStep;
import dagger.internal.codegen.validation.ValidationReport;
import jakarta.inject.Inject;
import java.util.Set;
import javax.annotation.processing.Messager;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;

/**
 * The annotation processor responsible for validating the mapKey annotation and auto-generate
 * implementation of annotations marked with {@link MapKey @MapKey} where necessary.
 */
final class MapKeyProcessingStep extends TypeCheckingProcessingStep<TypeElement> {
  private final Messager messager;
  private final MapKeyValidator mapKeyValidator;

  @Inject
  MapKeyProcessingStep(
      Messager messager,
      MapKeyValidator mapKeyValidator) {
    super(MoreElements::asType);
    this.messager = messager;
    this.mapKeyValidator = mapKeyValidator;
  }

  @Override
  public Set<ClassName> annotationClassNames() {
    return Set.of(TypeNames.MAP_KEY);
  }

  @Override
  protected void process(TypeElement mapKeyAnnotationType, Set<ClassName> annotations) {
    ValidationReport<Element> mapKeyReport = mapKeyValidator.validate(mapKeyAnnotationType);
    mapKeyReport.printMessagesTo(messager);
  }
}
