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

import static dagger.internal.codegen.base.Util.getOnlyElement;
import static javax.lang.model.type.TypeKind.VOID;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.ParameterSpec;
import dagger.assisted.Assisted;
import dagger.assisted.AssistedFactory;
import dagger.assisted.AssistedInject;
import dagger.internal.codegen.binding.ComponentDescriptor.ComponentMethodDescriptor;
import dagger.internal.codegen.binding.MembersInjectionBinding;
import dagger.internal.codegen.javapoet.Expression;
import javax.lang.model.element.ExecutableElement;

/**
 * A binding expression for members injection component methods. See {@link
 * MembersInjectionMethods}.
 */
final class MembersInjectionBindingExpression extends BindingExpression {
  private final MembersInjectionBinding binding;
  private final MembersInjectionMethods membersInjectionMethods;

  @AssistedInject
  MembersInjectionBindingExpression(
      @Assisted MembersInjectionBinding binding, MembersInjectionMethods membersInjectionMethods) {
    this.binding = binding;
    this.membersInjectionMethods = membersInjectionMethods;
  }

  @Override
  Expression getDependencyExpression(ClassName requestingClass) {
    throw new UnsupportedOperationException(binding.toString());
  }

  // TODO(ronshapiro): This class doesn't need to be a BindingExpression, as
  // getDependencyExpression() should never be called for members injection methods. It's probably
  // better suited as a method on MembersInjectionMethods
  @Override
  protected CodeBlock getComponentMethodImplementation(
      ComponentMethodDescriptor componentMethod, ComponentImplementation component) {
    ExecutableElement methodElement = componentMethod.methodElement();
    ParameterSpec parameter = ParameterSpec.get(getOnlyElement(methodElement.getParameters()));

    if (binding.injectionSites().isEmpty()) {
      return methodElement.getReturnType().getKind().equals(VOID)
          ? CodeBlock.of("")
          : CodeBlock.of("return $N;", parameter);
    } else {
      return methodElement.getReturnType().getKind().equals(VOID)
          ? CodeBlock.of("$L;", membersInjectionInvocation(parameter).codeBlock())
          : CodeBlock.of("return $L;", membersInjectionInvocation(parameter).codeBlock());
    }
  }

  private Expression membersInjectionInvocation(ParameterSpec target) {
    return membersInjectionMethods.getInjectExpression(binding.key(), CodeBlock.of("$N", target));
  }

  @AssistedFactory
  static interface Factory {
    MembersInjectionBindingExpression create(MembersInjectionBinding binding);
  }
}
