/*
 * Copyright (C) 2016 The Dagger Authors.
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

import static dagger.internal.codegen.langmodel.DaggerElements.getAnnotationMirror;
import static javax.tools.Diagnostic.Kind.ERROR;

import com.google.auto.common.MoreElements;
import com.google.common.collect.ImmutableSet;
import com.squareup.javapoet.ClassName;
import dagger.internal.codegen.javapoet.TypeNames;
import javax.annotation.processing.Messager;
import javax.lang.model.element.ExecutableElement;

/**
 * Processing step that verifies that {@link dagger.multibindings.IntoSet}, {@link
 * dagger.multibindings.ElementsIntoSet} and {@link dagger.multibindings.IntoMap} are not present on
 * non-binding methods.
 */
public final class MultibindingAnnotationsProcessingStep
    extends TypeCheckingProcessingStep<ExecutableElement> {
  private final AnyBindingMethodValidator anyBindingMethodValidator;
  private final Messager messager;

  @jakarta.inject.Inject
  MultibindingAnnotationsProcessingStep(
      AnyBindingMethodValidator anyBindingMethodValidator, Messager messager) {
    super(MoreElements::asExecutable);
    this.anyBindingMethodValidator = anyBindingMethodValidator;
    this.messager = messager;
  }

  @Override
  public ImmutableSet<ClassName> annotationClassNames() {
    return ImmutableSet.of(TypeNames.INTO_SET, TypeNames.ELEMENTS_INTO_SET, TypeNames.INTO_MAP);
  }

  @Override
  protected void process(ExecutableElement method, ImmutableSet<ClassName> annotations) {
    if (!anyBindingMethodValidator.isBindingMethod(method)) {
      annotations.forEach(
          annotation ->
              messager.printMessage(
                  ERROR,
                  "Multibinding annotations may only be on @Provides, @Produces, or @Binds methods",
                  method,
                  getAnnotationMirror(method, annotation).get()));
    }
  }
}
