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

package dagger.internal.codegen;

import static dagger.internal.codegen.langmodel.DaggerElements.closestEnclosingTypeElement;
import static io.jbock.auto.common.MoreTypes.asDeclared;

import dagger.internal.codegen.base.Preconditions;
import dagger.internal.codegen.binding.AssistedInjectionAnnotations;
import dagger.internal.codegen.binding.AssistedInjectionAnnotations.AssistedParameter;
import dagger.internal.codegen.javapoet.TypeNames;
import dagger.internal.codegen.langmodel.DaggerTypes;
import dagger.internal.codegen.validation.ValidationReport;
import dagger.internal.codegen.validation.XTypeCheckingProcessingStep;
import dagger.internal.codegen.xprocessing.XExecutableElement;
import io.jbock.javapoet.ClassName;
import jakarta.inject.Inject;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.processing.Messager;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.type.DeclaredType;

/** An annotation processor for {@link dagger.assisted.AssistedInject}-annotated elements. */
final class AssistedInjectProcessingStep extends XTypeCheckingProcessingStep<XExecutableElement> {
  private final DaggerTypes types;
  private final Messager messager;

  @Inject
  AssistedInjectProcessingStep(DaggerTypes types, Messager messager) {
    this.types = types;
    this.messager = messager;
  }

  @Override
  public Set<ClassName> annotationClassNames() {
    return Set.of(TypeNames.ASSISTED_INJECT);
  }

  @Override
  protected void process(
      XExecutableElement xElement, Set<ClassName> annotations) {
    // TODO(bcorso): Remove conversion to javac type and use XProcessing throughout.
    ExecutableElement assistedInjectElement = xElement.toJavac();
    new AssistedInjectValidator().validate(assistedInjectElement).printMessagesTo(messager);
  }

  private final class AssistedInjectValidator {
    ValidationReport<ExecutableElement> validate(ExecutableElement constructor) {
      Preconditions.checkState(constructor.getKind() == ElementKind.CONSTRUCTOR);
      ValidationReport.Builder<ExecutableElement> report = ValidationReport.about(constructor);

      DeclaredType assistedInjectType =
          asDeclared(closestEnclosingTypeElement(constructor).asType());
      List<AssistedParameter> assistedParameters =
          AssistedInjectionAnnotations.assistedInjectAssistedParameters(assistedInjectType, types);

      Set<AssistedParameter> uniqueAssistedParameters = new HashSet<>();
      for (AssistedParameter assistedParameter : assistedParameters) {
        if (!uniqueAssistedParameters.add(assistedParameter)) {
          report.addError(
              String.format("@AssistedInject constructor has duplicate @Assisted type: %s. "
                      + "Consider setting an identifier on the parameter by using "
                      + "@Assisted(\"identifier\") in both the factory and @AssistedInject constructor",
                  assistedParameter),
              assistedParameter.variableElement());
        }
      }

      return report.build();
    }
  }
}
