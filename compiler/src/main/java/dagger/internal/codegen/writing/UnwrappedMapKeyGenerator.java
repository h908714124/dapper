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

package dagger.internal.codegen.writing;

import dagger.internal.codegen.xprocessing.XFiler;
import dagger.internal.codegen.xprocessing.XProcessingEnv;
import dagger.internal.codegen.xprocessing.XTypeElement;
import jakarta.inject.Inject;
import java.util.Set;

/**
 * Generates classes that create annotation instances for an unwrapped {@code MapKey} annotation
 * type whose nested value is an annotation. The generated class will have a private empty
 * constructor and a static method that creates each annotation type that is nested in the top-level
 * annotation type.
 *
 * <p>So for an example {@code MapKey} annotation:
 *
 * <pre>
 *   {@literal @MapKey}(unwrapValue = true)
 *   {@literal @interface} Foo {
 *     Bar bar();
 *   }
 *
 *   {@literal @interface} Bar {
 *     {@literal Class<?> baz();}
 *   }
 * </pre>
 *
 * the generated class will look like:
 *
 * <pre>
 *   public final class FooCreator {
 *     private FooCreator() {}
 *
 *     public static Bar createBar({@literal Class<?> baz}) { … }
 *   }
 * </pre>
 */
public final class UnwrappedMapKeyGenerator extends AnnotationCreatorGenerator {

  @Inject
  UnwrappedMapKeyGenerator(XFiler filer, XProcessingEnv processingEnv) {
    super(filer, processingEnv);
  }

  @Override
  protected Set<XTypeElement> annotationsToCreate(XTypeElement annotationElement) {
    Set<XTypeElement> nestedAnnotationElements = super.annotationsToCreate(annotationElement);
    nestedAnnotationElements.remove(annotationElement);
    return nestedAnnotationElements;
  }
}
