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

package dagger.internal.codegen.base;

import static dagger.internal.codegen.javapoet.TypeNames.lazyOf;
import static dagger.internal.codegen.javapoet.TypeNames.providerOf;
import static dagger.internal.codegen.langmodel.DaggerTypes.checkTypePresent;
import static dagger.internal.codegen.langmodel.DaggerTypes.isTypeOf;
import static dagger.internal.codegen.langmodel.DaggerTypes.unwrapType;
import static dagger.internal.codegen.xprocessing.XConverters.toJavac;
import static dagger.spi.model.RequestKind.LAZY;
import static dagger.spi.model.RequestKind.PROVIDER;
import static dagger.spi.model.RequestKind.PROVIDER_OF_LAZY;
import static io.jbock.auto.common.MoreTypes.asDeclared;
import static io.jbock.auto.common.MoreTypes.isType;
import static javax.lang.model.type.TypeKind.DECLARED;

import dagger.internal.codegen.javapoet.TypeNames;
import dagger.internal.codegen.langmodel.DaggerTypes;
import dagger.internal.codegen.xprocessing.XType;
import dagger.spi.model.RequestKind;
import dagger.spi.model.Key;
import io.jbock.javapoet.ClassName;
import io.jbock.javapoet.TypeName;
import java.util.Map;
import javax.lang.model.type.TypeMirror;

/** Utility methods for {@link RequestKind}s. */
public final class RequestKinds {

  /** Returns the type of a request of this kind for a key with a given type. */
  public static TypeMirror requestType(
      RequestKind requestKind, TypeMirror type, DaggerTypes types) {
    switch (requestKind) {
      case INSTANCE:
        return type;

      case PROVIDER_OF_LAZY:
        return types.wrapType(requestType(LAZY, type, types), TypeNames.PROVIDER);

      default:
        return types.wrapType(type, frameworkClass(requestKind));
    }
  }

  /** Returns the type of a request of this kind for a key with a given type. */
  public static TypeName requestTypeName(RequestKind requestKind, TypeName keyType) {
    switch (requestKind) {
      case INSTANCE:
        return keyType;

      case PROVIDER:
        return providerOf(keyType);

      case LAZY:
        return lazyOf(keyType);

      case PROVIDER_OF_LAZY:
        return providerOf(lazyOf(keyType));

      default:
        throw new AssertionError(requestKind);
    }
  }

  private static final Map<RequestKind, ClassName> FRAMEWORK_CLASSES =
      Map.of(
          PROVIDER, TypeNames.PROVIDER,
          LAZY, TypeNames.LAZY);

  /** Returns the {@link RequestKind} that matches the wrapping types (if any) of {@code type}. */
  public static RequestKind getRequestKind(XType type) {
    return getRequestKind(toJavac(type));
  }

  /** Returns the {@link RequestKind} that matches the wrapping types (if any) of {@code type}. */
  public static RequestKind getRequestKind(TypeMirror type) {
    checkTypePresent(type);
    if (!isType(type) // TODO(b/147320669): isType check can be removed once this bug is fixed.
        || !type.getKind().equals(DECLARED)
        || asDeclared(type).getTypeArguments().isEmpty()) {
      // If the type is not a declared type (i.e. class or interface) with type arguments, then we
      // know it can't be a parameterized type of one of the framework classes, so return INSTANCE.
      return RequestKind.INSTANCE;
    }
    for (RequestKind kind : FRAMEWORK_CLASSES.keySet()) {
      if (isTypeOf(frameworkClass(kind), type)) {
        if (kind.equals(PROVIDER) && getRequestKind(unwrapType(type)).equals(LAZY)) {
          return PROVIDER_OF_LAZY;
        }
        return kind;
      }
    }
    return RequestKind.INSTANCE;
  }

  /**
   * Unwraps the framework class(es) of {@code requestKind} from {@code type}. If {@code
   * requestKind} is {@link RequestKind#INSTANCE}, this acts as an identity function.
   *
   * @throws TypeNotPresentException if {@code type} is an {@link javax.lang.model.type.ErrorType},
   *     which may mean that the type will be generated in a later round of processing
   * @throws IllegalArgumentException if {@code type} is not wrapped with {@code requestKind}'s
   *     framework class(es).
   */
  public static XType extractKeyType(XType type) {
    return extractKeyType(getRequestKind(type), type);
  }

  private static XType extractKeyType(RequestKind requestKind, XType type) {
    switch (requestKind) {
      case INSTANCE:
        return type;
      case PROVIDER_OF_LAZY:
        return extractKeyType(LAZY, extractKeyType(PROVIDER, type));
      default:
        return unwrapType(type);
    }
  }

  /**
   * Unwraps the framework class(es) of {@code requestKind} from {@code type}. If {@code
   * requestKind} is {@link RequestKind#INSTANCE}, this acts as an identity function.
   *
   * @throws TypeNotPresentException if {@code type} is an {@link javax.lang.model.type.ErrorType},
   *     which may mean that the type will be generated in a later round of processing
   * @throws IllegalArgumentException if {@code type} is not wrapped with {@code requestKind}'s
   *     framework class(es).
   */
  public static TypeMirror extractKeyType(TypeMirror type) {
    return extractKeyType(getRequestKind(type), type);
  }

  private static TypeMirror extractKeyType(RequestKind requestKind, TypeMirror type) {
    switch (requestKind) {
      case INSTANCE:
        return type;
      case PROVIDER_OF_LAZY:
        return extractKeyType(LAZY, extractKeyType(PROVIDER, type));
      default:
        Preconditions.checkArgument(isType(type));
        return unwrapType(type);
    }
  }

  /**
   * A dagger- or {@code javax.inject}-defined class for {@code requestKind} that that can wrap
   * another type but share the same {@link Key}.
   *
   * <p>For example, {@code Provider<String>} and {@code Lazy<String>} can both be requested if a
   * key exists for {@code String}; they all share the same key.
   *
   * <p>This concept is not well defined and should probably be removed and inlined into the cases
   * that need it. For example, {@link RequestKind#PROVIDER_OF_LAZY} has <em>2</em> wrapping
   * classes.
   */
  public static ClassName frameworkClass(RequestKind requestKind) {
    ClassName result = FRAMEWORK_CLASSES.get(requestKind);
    Preconditions.checkArgument(result != null, "no framework class for %s", requestKind);
    return result;
  }

  private RequestKinds() {
  }
}
