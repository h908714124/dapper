/*
 * Copyright (C) 2017 The Dagger Authors.
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

import static dagger.internal.codegen.collect.Iterables.getOnlyElement;
import static dagger.internal.codegen.extension.DaggerCollectors.onlyElement;
import static dagger.internal.codegen.xprocessing.XElements.getSimpleName;
import static dagger.internal.codegen.xprocessing.XProcessingEnvs.getUnboundedWildcardType;
import static dagger.internal.codegen.xprocessing.XTypes.isAssignableTo;

import dagger.internal.codegen.base.ContributionType;
import dagger.internal.codegen.collect.ImmutableList;
import dagger.internal.codegen.javapoet.TypeNames;
import dagger.internal.codegen.xprocessing.XProcessingEnv;
import dagger.internal.codegen.xprocessing.XType;
import dagger.internal.codegen.xprocessing.XTypeElement;
import dagger.internal.codegen.xprocessing.XTypeElements;
import jakarta.inject.Inject;

/**
 * Checks the assignability of one type to another, given a {@code ContributionType} context. This
 * is used by {@code dagger.internal.codegen.validation.BindsMethodValidator} to validate that the
 * right-hand- side of a {@code dagger.Binds} method is valid, as well as in {@code
 * dagger.internal.codegen.writing.DelegateRequestRepresentation} when the right-hand-side in
 * generated code might be an erased type due to accessibility.
 */
public final class BindsTypeChecker {
  private final XProcessingEnv processingEnv;

  @Inject
  BindsTypeChecker(XProcessingEnv processingEnv) {
    this.processingEnv = processingEnv;
  }

  /**
   * Checks the assignability of {@code rightHandSide} to {@code leftHandSide} given a {@code
   * ContributionType} context.
   */
  public boolean isAssignable(
      XType rightHandSide, XType leftHandSide, ContributionType contributionType) {
    return isAssignableTo(rightHandSide, desiredAssignableType(leftHandSide, contributionType));
  }

  private XType desiredAssignableType(XType leftHandSide, ContributionType contributionType) {
    switch (contributionType) {
      case UNIQUE:
        return leftHandSide;
      case SET:
        XType parameterizedSetType = processingEnv.getDeclaredType(setElement(), leftHandSide);
        return methodParameterType(parameterizedSetType, "add");
      case SET_VALUES:
        // TODO(b/211774331): The left hand side type should be limited to Set types.
        return methodParameterType(leftHandSide, "addAll");
      case MAP:
        XType parameterizedMapType =
            processingEnv.getDeclaredType(mapElement(), unboundedWildcard(), leftHandSide);
        return methodParameterTypes(parameterizedMapType, "put").get(1);
    }
    throw new AssertionError("Unknown contribution type: " + contributionType);
  }

  private ImmutableList<XType> methodParameterTypes(XType type, String methodName) {
    return ImmutableList.copyOf(
        XTypeElements.getAllMethods(type.getTypeElement()).stream()
            .filter(method -> methodName.contentEquals(getSimpleName(method)))
            .collect(onlyElement())
            .asMemberOf(type)
            .getParameterTypes());
  }

  private XType methodParameterType(XType type, String methodName) {
    return getOnlyElement(methodParameterTypes(type, methodName));
  }

  private XTypeElement setElement() {
    return processingEnv.requireTypeElement(TypeNames.SET);
  }

  private XTypeElement mapElement() {
    return processingEnv.requireTypeElement(TypeNames.MAP);
  }

  private XType unboundedWildcard() {
    return getUnboundedWildcardType(processingEnv);
  }
}
