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

package dagger.internal.codegen.writing;

import static dagger.internal.codegen.base.Preconditions.checkNotNull;
import static dagger.internal.codegen.langmodel.Accessibility.isTypeAccessibleFrom;
import static dagger.internal.codegen.xprocessing.XProcessingEnvs.wrapType;

import dagger.internal.codegen.binding.ContributionBinding;
import dagger.internal.codegen.binding.FrameworkType;
import dagger.internal.codegen.javapoet.Expression;
import dagger.internal.codegen.xprocessing.XProcessingEnv;
import dagger.internal.codegen.xprocessing.XType;
import io.jbock.javapoet.ClassName;

/** A binding expression that uses a {@code FrameworkType} field. */
abstract class FrameworkInstanceRequestRepresentation extends RequestRepresentation {
  private final ContributionBinding binding;
  private final FrameworkInstanceSupplier frameworkInstanceSupplier;
  private final XProcessingEnv processingEnv;

  FrameworkInstanceRequestRepresentation(
      ContributionBinding binding,
      FrameworkInstanceSupplier frameworkInstanceSupplier,
      XProcessingEnv processingEnv) {
    this.binding = checkNotNull(binding);
    this.frameworkInstanceSupplier = checkNotNull(frameworkInstanceSupplier);
    this.processingEnv = checkNotNull(processingEnv);
  }

  /**
   * The expression for the framework instance for this binding. The field will be initialized and
   * added to the component the first time this method is invoked.
   */
  @Override
  Expression getDependencyExpression(ClassName requestingClass) {
    MemberSelect memberSelect = frameworkInstanceSupplier.memberSelect();
    XType expressionType =
        isTypeAccessibleFrom(binding.contributedType(), requestingClass.packageName())
                || isInlinedFactoryCreation(memberSelect)
            ? wrapType(
                frameworkType().frameworkClassName(), binding.contributedType(), processingEnv)
            : rawFrameworkType();
    return Expression.create(expressionType, memberSelect.getExpressionFor(requestingClass));
  }

  /** Returns the framework type for the binding. */
  protected abstract FrameworkType frameworkType();

  /**
   * Returns {@code true} if a factory is created inline each time it is requested. For example, in
   * the initialization {@code this.fooProvider = Foo_Factory.create(Bar_Factory.create());}, {@code
   * Bar_Factory} is considered to be inline.
   *
   * <p>This is used in {@code #getDependencyExpression(ClassName)} when determining the type of a
   * factory. Normally if the {@code ContributionBinding#contributedType()} is not accessible from
   * the component, the type of the expression will be a raw {@code jakarta.inject.Provider}. However,
   * if the factory is created inline, even if contributed type is not accessible, javac will still
   * be able to determine the type that is returned from the {@code Foo_Factory.create()} method.
   */
  private static boolean isInlinedFactoryCreation(MemberSelect memberSelect) {
    return memberSelect.staticMember();
  }

  private XType rawFrameworkType() {
    return processingEnv.getDeclaredType(
        processingEnv.requireTypeElement(frameworkType().frameworkClassName()));
  }
}
