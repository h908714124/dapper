/*
 * Copyright (C) 2015 The Dagger Authors.
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

package dagger.internal.codegen.base;

import static io.jbock.auto.common.MoreTypes.isType;
import static dagger.internal.codegen.base.Preconditions.checkArgument;
import static dagger.internal.codegen.langmodel.DaggerTypes.isTypeOf;
import static dagger.internal.codegen.langmodel.DaggerTypes.unwrapType;
import static dagger.internal.codegen.xprocessing.XTypes.isTypeOf;

import dagger.internal.codegen.xprocessing.XType;
import io.jbock.auto.value.AutoValue;
import io.jbock.javapoet.ClassName;
import io.jbock.javapoet.TypeName;
import dagger.internal.codegen.javapoet.TypeNames;
import dagger.spi.model.Key;
import javax.lang.model.type.TypeMirror;

/** Information about a {@code java.util.Set} {@code TypeMirror}. */
@AutoValue
public abstract class SetType {
  private XType type;

  /** The set type itself. */
  abstract TypeName typeName();

  /** The set type itself. */
  private XType type() {
    return type;
  }

  /** {@code true} if the set type is the raw {@code java.util.Set} type. */
  public boolean isRawType() {
    return type().getTypeArguments().isEmpty();
  }

  /** Returns the element type. */
  public XType elementType() {
    return unwrapType(type());
  }

  /** Returns {@code true} if {@code #elementType()} is of type {@code className}. */
  public boolean elementsAreTypeOf(ClassName className) {
    return !isRawType() && isTypeOf(elementType(), className);
  }

  /**
   * {@code T} if {@code #elementType()} is a {@code WrappingClass<T>}.
   *
   * @throws IllegalStateException if {@code #elementType()} is not a {@code WrappingClass<T>}
   */
  // TODO(b/202033221): Consider using stricter input type, e.g. FrameworkType.
  public XType unwrappedElementType(ClassName wrappingClass) {
    checkArgument(
        elementsAreTypeOf(wrappingClass),
        "expected elements to be %s, but this type is %s",
        wrappingClass,
        type());
    return unwrapType(elementType());
  }

  /** {@code true} if {@code type} is a {@code java.util.Set} type. */
  public static boolean isSet(XType type) {
    return isTypeOf(type, TypeNames.SET);
  }

  /** {@code true} if {@code type} is a {@code java.util.Set} type. */
  public static boolean isSet(TypeMirror type) {
    return isType(type) && isTypeOf(TypeNames.SET, type);
  }

  /** {@code true} if {@code key.type()} is a {@code java.util.Set} type. */
  public static boolean isSet(Key key) {
    return isSet(key.type().xprocessing());
  }

  /**
   * Returns a {@code SetType} for {@code type}.
   *
   * @throws IllegalArgumentException if {@code type} is not a {@code java.util.Set} type
   */
  public static SetType from(XType type) {
    checkArgument(isSet(type), "%s must be a Set", type);
    SetType setType = new AutoValue_SetType(type.getTypeName());
    setType.type = type;
    return setType;
  }

  /**
   * Returns a {@code SetType} for {@code key}'s {@code Key#type() type}.
   *
   * @throws IllegalArgumentException if {@code key.type()} is not a {@code java.util.Set} type
   */
  public static SetType from(Key key) {
    return from(key.type().xprocessing());
  }
}