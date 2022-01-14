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

package dagger.internal.codegen.writing;

import static com.squareup.javapoet.MethodSpec.constructorBuilder;
import static com.squareup.javapoet.MethodSpec.methodBuilder;
import static com.squareup.javapoet.TypeSpec.classBuilder;
import static dagger.internal.codegen.binding.AnnotationExpression.createMethodName;
import static dagger.internal.codegen.binding.AnnotationExpression.getAnnotationCreatorClassName;
import static dagger.internal.codegen.javapoet.CodeBlocks.makeParametersCodeBlock;
import static javax.lang.model.element.Modifier.FINAL;
import static javax.lang.model.element.Modifier.PRIVATE;
import static javax.lang.model.element.Modifier.PUBLIC;
import static javax.lang.model.element.Modifier.STATIC;
import static javax.lang.model.util.ElementFilter.methodsIn;

import com.google.auto.common.MoreTypes;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import dagger.internal.codegen.base.SourceFileGenerator;
import dagger.internal.codegen.javapoet.TypeNames;
import dagger.internal.codegen.langmodel.DaggerElements;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.processing.Filer;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.util.SimpleTypeVisitor8;

/**
 * Generates classes that create annotation instances for an annotation type. The generated class
 * will have a private empty constructor, a static method that creates the annotation type itself,
 * and a static method that creates each annotation type that is nested in the top-level annotation
 * type.
 *
 * <p>So for an example annotation:
 *
 * <pre>
 *   {@literal @interface} Foo {
 *     String s();
 *     int i();
 *     Bar bar(); // an annotation defined elsewhere
 *   }
 * </pre>
 *
 * the generated class will look like:
 *
 * <pre>
 *   public final class FooCreator {
 *     private FooCreator() {}
 *
 *     public static Foo createFoo(String s, int i, Bar bar) { … }
 *     public static Bar createBar(…) { … }
 *   }
 * </pre>
 */
public class AnnotationCreatorGenerator extends SourceFileGenerator<TypeElement> {

  @Inject
  AnnotationCreatorGenerator(Filer filer, DaggerElements elements) {
    super(filer, elements);
  }

  @Override
  public Element originatingElement(TypeElement annotationType) {
    return annotationType;
  }

  @Override
  public List<TypeSpec.Builder> topLevelTypes(TypeElement annotationType) {
    ClassName generatedTypeName = getAnnotationCreatorClassName(annotationType);
    TypeSpec.Builder annotationCreatorBuilder =
        classBuilder(generatedTypeName)
            .addModifiers(PUBLIC, FINAL)
            .addMethod(constructorBuilder().addModifiers(PRIVATE).build());

    for (TypeElement annotationElement : annotationsToCreate(annotationType)) {
      annotationCreatorBuilder.addMethod(buildCreateMethod(generatedTypeName, annotationElement));
    }

    return List.of(annotationCreatorBuilder);
  }

  private MethodSpec buildCreateMethod(ClassName generatedTypeName, TypeElement annotationElement) {
    String createMethodName = createMethodName(annotationElement);
    MethodSpec.Builder createMethod =
        methodBuilder(createMethodName)
            .addAnnotation(TypeNames.AUTO_ANNOTATION)
            .addModifiers(PUBLIC, STATIC)
            .returns(TypeName.get(annotationElement.asType()));

    List<CodeBlock> parameters = new ArrayList<>();
    for (ExecutableElement annotationMember : methodsIn(annotationElement.getEnclosedElements())) {
      String parameterName = annotationMember.getSimpleName().toString();
      TypeName parameterType = TypeName.get(annotationMember.getReturnType());
      createMethod.addParameter(parameterType, parameterName);
      parameters.add(CodeBlock.of("$L", parameterName));
    }

    ClassName autoAnnotationClass =
        generatedTypeName.peerClass(
            "AutoAnnotation_" + generatedTypeName.simpleName() + "_" + createMethodName);
    createMethod.addStatement(
        "return new $T($L)", autoAnnotationClass, makeParametersCodeBlock(parameters));
    return createMethod.build();
  }

  /**
   * Returns the annotation types for which {@code @AutoAnnotation static Foo createFoo(…)} methods
   * should be written.
   */
  protected Set<TypeElement> annotationsToCreate(TypeElement annotationElement) {
    return nestedAnnotationElements(annotationElement, new LinkedHashSet<>());
  }

  private static Set<TypeElement> nestedAnnotationElements(
      TypeElement annotationElement, Set<TypeElement> annotationElements) {
    if (annotationElements.add(annotationElement)) {
      for (ExecutableElement method : methodsIn(annotationElement.getEnclosedElements())) {
        TRAVERSE_NESTED_ANNOTATIONS.visit(method.getReturnType(), annotationElements);
      }
    }
    return annotationElements;
  }

  private static final SimpleTypeVisitor8<Void, Set<TypeElement>> TRAVERSE_NESTED_ANNOTATIONS =
      new SimpleTypeVisitor8<>() {
        @Override
        public Void visitDeclared(DeclaredType t, Set<TypeElement> p) {
          TypeElement typeElement = MoreTypes.asTypeElement(t);
          if (typeElement.getKind() == ElementKind.ANNOTATION_TYPE) {
            nestedAnnotationElements(typeElement, p);
          }
          return null;
        }
      };
}
