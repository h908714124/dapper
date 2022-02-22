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

package dagger.internal.codegen.binding;

import static dagger.internal.codegen.binding.SourceFiles.classFileName;
import static dagger.internal.codegen.javapoet.CodeBlocks.makeParametersCodeBlock;
import static dagger.internal.codegen.javapoet.TypeNames.rawTypeName;
import static io.jbock.auto.common.AnnotationMirrors.getAnnotationValuesWithDefaults;
import static io.jbock.auto.common.MoreTypes.asArray;
import static java.util.stream.Collectors.toList;

import dagger.internal.codegen.collect.ImmutableList;
import io.jbock.auto.common.MoreElements;
import io.jbock.auto.common.MoreTypes;
import io.jbock.javapoet.ClassName;
import io.jbock.javapoet.CodeBlock;
import java.util.List;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.SimpleAnnotationValueVisitor8;

/**
 * Returns an expression creating an instance of the visited annotation type. Its parameter must be
 * a class as generated by {@code dagger.internal.codegen.writing.AnnotationCreatorGenerator}.
 *
 * <p>Note that {@link AnnotationValue#toString()} is the source-code representation of the value
 * <em>when used in an annotation</em>, which is not always the same as the representation needed
 * when creating the value in a method body.
 *
 * <p>For example, inside an annotation, a nested array of {@code int}s is simply {@code {1, 2, 3}},
 * but in code it would have to be {@code new int[] {1, 2, 3}}.
 */
public class AnnotationExpression
    extends SimpleAnnotationValueVisitor8<CodeBlock, AnnotationValue> {

  private final AnnotationMirror annotation;
  private final ClassName creatorClass;

  AnnotationExpression(AnnotationMirror annotation) {
    this.annotation = annotation;
    this.creatorClass =
        getAnnotationCreatorClassName(
            MoreTypes.asTypeElement(annotation.getAnnotationType()));
  }

  /**
   * Returns an expression that calls static methods on the annotation's creator class to create an
   * annotation instance equivalent the annotation passed to the constructor.
   */
  CodeBlock getAnnotationInstanceExpression() {
    return getAnnotationInstanceExpression(annotation);
  }

  private CodeBlock getAnnotationInstanceExpression(AnnotationMirror annotation) {
    return CodeBlock.of(
        "$T.$L($L)",
        creatorClass,
        createMethodName(
            MoreElements.asType(annotation.getAnnotationType().asElement())),
        makeParametersCodeBlock(
            getAnnotationValuesWithDefaults(annotation)
                .entrySet()
                .stream()
                .map(entry -> getValueExpression(entry.getKey().getReturnType(), entry.getValue()))
                .collect(toList())));
  }

  /**
   * Returns the name of the generated class that contains the static {@code create} methods for an
   * annotation type.
   */
  public static ClassName getAnnotationCreatorClassName(TypeElement annotationType) {
    ClassName annotationTypeName = ClassName.get(annotationType);
    return annotationTypeName
        .topLevelClassName()
        .peerClass(classFileName(annotationTypeName) + "Creator");
  }

  public static String createMethodName(TypeElement annotationType) {
    return "create" + annotationType.getSimpleName();
  }

  /**
   * Returns an expression that evaluates to a {@code value} of a given type on an {@code
   * annotation}.
   */
  CodeBlock getValueExpression(TypeMirror valueType, AnnotationValue value) {
    CodeBlock codeBlock = visit(value, value);
    return valueType.getKind() == TypeKind.ARRAY
        ? CodeBlock.of("new $T[] $L", rawTypeName(asArray(valueType).getComponentType()), codeBlock)
        : codeBlock;
  }

  @Override
  public CodeBlock visitEnumConstant(VariableElement c, AnnotationValue p) {
    return CodeBlock.of("$T.$L", c.getEnclosingElement(), c.getSimpleName());
  }

  @Override
  public CodeBlock visitAnnotation(AnnotationMirror a, AnnotationValue p) {
    return getAnnotationInstanceExpression(a);
  }

  @Override
  public CodeBlock visitType(TypeMirror t, AnnotationValue p) {
    return CodeBlock.of("$T.class", t);
  }

  @Override
  public CodeBlock visitString(String s, AnnotationValue p) {
    return CodeBlock.of("$S", s);
  }

  @Override
  public CodeBlock visitByte(byte b, AnnotationValue p) {
    return CodeBlock.of("(byte) $L", b);
  }

  @Override
  public CodeBlock visitChar(char c, AnnotationValue p) {
    return CodeBlock.of("$L", p);
  }

  @Override
  public CodeBlock visitDouble(double d, AnnotationValue p) {
    return CodeBlock.of("$LD", d);
  }

  @Override
  public CodeBlock visitFloat(float f, AnnotationValue p) {
    return CodeBlock.of("$LF", f);
  }

  @Override
  public CodeBlock visitLong(long i, AnnotationValue p) {
    return CodeBlock.of("$LL", i);
  }

  @Override
  public CodeBlock visitShort(short s, AnnotationValue p) {
    return CodeBlock.of("(short) $L", s);
  }

  @Override
  protected CodeBlock defaultAction(Object o, AnnotationValue p) {
    return CodeBlock.of("$L", o);
  }

  @Override
  public CodeBlock visitArray(List<? extends AnnotationValue> values, AnnotationValue p) {
    ImmutableList.Builder<CodeBlock> codeBlocks = ImmutableList.builder();
    for (AnnotationValue value : values) {
      codeBlocks.add(this.visit(value, p));
    }
    return CodeBlock.of("{$L}", makeParametersCodeBlock(codeBlocks.build()));
  }
}