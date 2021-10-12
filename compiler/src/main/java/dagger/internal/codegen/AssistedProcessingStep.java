/*
 * Copyright (C) 2020 The Dagger Authors.
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

import static com.google.auto.common.MoreElements.isAnnotationPresent;
import static dagger.internal.codegen.langmodel.DaggerElements.closestEnclosingTypeElement;

import com.google.auto.common.MoreElements;
import com.google.common.collect.ImmutableSet;
import com.squareup.javapoet.ClassName;
import dagger.assisted.AssistedInject;
import dagger.internal.codegen.binding.AssistedInjectionAnnotations;
import dagger.internal.codegen.binding.InjectionAnnotations;
import dagger.internal.codegen.javapoet.TypeNames;
import dagger.internal.codegen.langmodel.DaggerElements;
import dagger.internal.codegen.validation.TypeCheckingProcessingStep;
import dagger.internal.codegen.validation.ValidationReport;
import jakarta.inject.Inject;
import javax.annotation.processing.Messager;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;

/**
 * An annotation processor for {@link dagger.assisted.Assisted}-annotated types.
 *
 * <p>This processing step should run after {@link AssistedFactoryProcessingStep}.
 */
final class AssistedProcessingStep extends TypeCheckingProcessingStep<VariableElement> {
  private final InjectionAnnotations injectionAnnotations;
  private final DaggerElements elements;
  private final Messager messager;

  @Inject
  AssistedProcessingStep(
      InjectionAnnotations injectionAnnotations,
      DaggerElements elements,
      Messager messager) {
    super(MoreElements::asVariable);
    this.injectionAnnotations = injectionAnnotations;
    this.elements = elements;
    this.messager = messager;
  }

  @Override
  public ImmutableSet<ClassName> annotationClassNames() {
    return ImmutableSet.of(TypeNames.ASSISTED);
  }

  @Override
  protected void process(VariableElement assisted, ImmutableSet<ClassName> annotations) {
    new AssistedValidator().validate(assisted).printMessagesTo(messager);
  }

  private final class AssistedValidator {
    ValidationReport<VariableElement> validate(VariableElement assisted) {
      ValidationReport.Builder<VariableElement> report = ValidationReport.about(assisted);

      Element enclosingElement = assisted.getEnclosingElement();
      if (!isAssistedInjectConstructor(enclosingElement)
          && !isAssistedFactoryCreateMethod(enclosingElement)) {
        report.addError(
            "@Assisted parameters can only be used within an @AssistedInject-annotated "
                + "constructor.",
            assisted);
      }

      injectionAnnotations
          .getQualifiers(assisted)
          .forEach(
              qualifier ->
                  report.addError(
                      "Qualifiers cannot be used with @Assisted parameters.", assisted, qualifier));

      return report.build();
    }
  }

  private boolean isAssistedInjectConstructor(Element element) {
    return element.getKind() == ElementKind.CONSTRUCTOR
        && isAnnotationPresent(element, AssistedInject.class);
  }

  private boolean isAssistedFactoryCreateMethod(Element element) {
    if (element.getKind() == ElementKind.METHOD) {
      TypeElement enclosingElement = closestEnclosingTypeElement(element);
      return AssistedInjectionAnnotations.isAssistedFactoryType(enclosingElement)
          // This assumes we've already validated AssistedFactory and that a valid method exists.
          && AssistedInjectionAnnotations.assistedFactoryMethod(enclosingElement, elements)
              .equals(element);
    }
    return false;
  }
}
