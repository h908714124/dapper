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


import static dagger.internal.codegen.javapoet.AnnotationSpecs.Suppression.RAWTYPES;
import static dagger.internal.codegen.javapoet.AnnotationSpecs.Suppression.UNCHECKED;
import static java.util.Objects.requireNonNull;

import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.TypeSpec;
import dagger.internal.codegen.extension.DaggerStreams;
import dagger.internal.codegen.javapoet.AnnotationSpecs;
import dagger.internal.codegen.javapoet.AnnotationSpecs.Suppression;
import dagger.internal.codegen.javapoet.TypeNames;
import dagger.internal.codegen.langmodel.DaggerElements;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.lang.model.element.Element;

/**
 * A template class that provides a framework for properly handling IO while generating source files
 * from an annotation processor. Particularly, it makes a best effort to ensure that files that fail
 * to write successfully are deleted.
 *
 * @param <T> The input type from which source is to be generated.
 */
public abstract class SourceFileGenerator<T> {
  private static final String GENERATED_COMMENTS = "https://github.com/jbock-java/dapper";

  private final Filer filer;
  private final DaggerElements elements;

  public SourceFileGenerator(Filer filer, DaggerElements elements) {
    this.filer = requireNonNull(filer);
    this.elements = requireNonNull(elements);
  }

  public SourceFileGenerator(SourceFileGenerator<T> delegate) {
    this(delegate.filer, delegate.elements);
  }

  /**
   * Generates a source file to be compiled for {@code T}. Writes any generation exception to {@code
   * messager} and does not throw.
   */
  public void generate(T input, Messager messager) {
    try {
      generate(input);
    } catch (SourceFileGenerationException e) {
      e.printMessageTo(messager);
    }
  }

  /** Generates a source file to be compiled for {@code T}. */
  public void generate(T input) throws SourceFileGenerationException {
    for (TypeSpec.Builder type : topLevelTypes(input)) {
      try {
        buildJavaFile(input, type).writeTo(filer);
      } catch (Exception e) {
        throw new SourceFileGenerationException(Optional.empty(), e, originatingElement(input));
      }
    }
  }

  private JavaFile buildJavaFile(T input, TypeSpec.Builder typeSpecBuilder) {
    typeSpecBuilder.addOriginatingElement(originatingElement(input));
    AnnotationSpec generatedAnnotation =
        AnnotationSpec.builder(TypeNames.GENERATED)
            .addMember("value", "$S", "dagger.internal.codegen.ComponentProcessor")
            .addMember("comments", "$S", GENERATED_COMMENTS)
            .build();
    typeSpecBuilder.addAnnotation(generatedAnnotation);

    // TODO(b/134590785): remove this and only suppress annotations locally, if necessary
    typeSpecBuilder.addAnnotation(
        AnnotationSpecs.suppressWarnings(
            Stream.of(warningSuppressions(), Set.of(UNCHECKED), Set.of(RAWTYPES))
                .flatMap(Set::stream)
                .collect(DaggerStreams.toImmutableSet())));

    JavaFile.Builder javaFileBuilder =
        JavaFile.builder(
                elements.getPackageOf(originatingElement(input)).getQualifiedName().toString(),
                typeSpecBuilder.build())
            .skipJavaLangImports(true);
    return javaFileBuilder.build();
  }

  /** Returns the originating element of the generating type. */
  public abstract Element originatingElement(T input);

  /**
   * Returns {@link TypeSpec.Builder types} be generated for {@code T}, or an empty list if no types
   * should be generated.
   *
   * <p>Every type will be generated in its own file.
   */
  public abstract List<TypeSpec.Builder> topLevelTypes(T input);

  /** Returns {@link Suppression}s that are applied to files generated by this generator. */
  // TODO(b/134590785): When suppressions are removed locally, remove this and inline the usages
  protected Set<Suppression> warningSuppressions() {
    return Set.of();
  }
}
