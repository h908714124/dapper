/*
 * Copyright (C) 2018 The Dagger Authors.
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

import static dagger.internal.codegen.langmodel.Accessibility.isElementAccessibleFrom;
import static dagger.internal.codegen.xprocessing.XConverters.toJavac;
import static dagger.internal.codegen.xprocessing.XTypeElements.isNested;
import static io.jbock.javapoet.MethodSpec.constructorBuilder;
import static io.jbock.javapoet.MethodSpec.methodBuilder;
import static io.jbock.javapoet.TypeSpec.classBuilder;
import static javax.lang.model.element.Modifier.FINAL;
import static javax.lang.model.element.Modifier.PRIVATE;
import static javax.lang.model.element.Modifier.PUBLIC;
import static javax.lang.model.element.Modifier.STATIC;
import static javax.lang.model.util.ElementFilter.constructorsIn;

import dagger.internal.codegen.base.ModuleKind;
import dagger.internal.codegen.base.SourceFileGenerator;
import dagger.internal.codegen.binding.SourceFiles;
import dagger.internal.codegen.collect.ImmutableList;
import dagger.internal.codegen.langmodel.Accessibility;
import dagger.internal.codegen.langmodel.DaggerElements;
import dagger.internal.codegen.xprocessing.XElement;
import dagger.internal.codegen.xprocessing.XFiler;
import dagger.internal.codegen.xprocessing.XTypeElement;
import io.jbock.javapoet.ClassName;
import io.jbock.javapoet.CodeBlock;
import io.jbock.javapoet.TypeSpec;
import jakarta.inject.Inject;
import java.util.Optional;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.ExecutableElement;

/** Convenience methods for generating and using module constructor proxy methods. */
public final class ModuleProxies {

  private final DaggerElements elements;
  @Inject
  ModuleProxies(DaggerElements elements) {
    this.elements = elements;
  }

  /** Generates a {@code public static} proxy method for constructing module instances. */
  // TODO(dpb): See if this can become a SourceFileGenerator<ModuleDescriptor> instead. Doing so may
  // cause ModuleProcessingStep to defer elements multiple times.
  public static final class ModuleConstructorProxyGenerator
      extends SourceFileGenerator<XTypeElement> {

    private final ModuleProxies moduleProxies;

    @Inject
    ModuleConstructorProxyGenerator(
        XFiler filer,
        DaggerElements elements,
        SourceVersion sourceVersion,
        ModuleProxies moduleProxies) {
      super(filer, elements, sourceVersion);
      this.moduleProxies = moduleProxies;
    }

    @Override
    public XElement originatingElement(XTypeElement moduleElement) {
      return moduleElement;
    }

    @Override
    public ImmutableList<TypeSpec.Builder> topLevelTypes(XTypeElement moduleElement) {
      ModuleKind.checkIsModule(moduleElement);
      return moduleProxies.nonPublicNullaryConstructor(moduleElement).isPresent()
          ? ImmutableList.of(buildProxy(moduleElement))
          : ImmutableList.of();
    }

    private TypeSpec.Builder buildProxy(XTypeElement moduleElement) {
      return classBuilder(moduleProxies.constructorProxyTypeName(moduleElement))
          .addModifiers(PUBLIC, FINAL)
          .addMethod(constructorBuilder().addModifiers(PRIVATE).build())
          .addMethod(
              methodBuilder("newInstance")
                  .addModifiers(PUBLIC, STATIC)
                  .returns(moduleElement.getClassName())
                  .addStatement("return new $T()", moduleElement.getClassName())
                  .build());
    }
  }

  /** The name of the class that hosts the module constructor proxy method. */
  private ClassName constructorProxyTypeName(XTypeElement moduleElement) {
    ModuleKind.checkIsModule(moduleElement);
    ClassName moduleClassName = moduleElement.getClassName();
    return moduleClassName
        .topLevelClassName()
        .peerClass(SourceFiles.classFileName(moduleClassName) + "_Proxy");
  }

  /**
   * The module constructor being proxied. A proxy is generated if it is not publicly accessible and
   * has no arguments. If an implicit reference to the enclosing class exists, or the module is
   * abstract, no proxy method can be generated.
   */
  private Optional<ExecutableElement> nonPublicNullaryConstructor(XTypeElement moduleElement) {
    ModuleKind.checkIsModule(moduleElement);
    if (moduleElement.isAbstract() || (isNested(moduleElement) && !moduleElement.isStatic())) {
      return Optional.empty();
    }
    return constructorsIn(elements.getAllMembers(toJavac(moduleElement))).stream()
        .filter(constructor -> !Accessibility.isElementPubliclyAccessible(constructor))
        .filter(constructor -> !constructor.getModifiers().contains(PRIVATE))
        .filter(constructor -> constructor.getParameters().isEmpty())
        .findAny();
  }

  /**
   * Returns a code block that creates a new module instance, either by invoking the nullary
   * constructor if it's accessible from {@code requestingClass} or else by invoking the
   * constructor's generated proxy method.
   */
  public CodeBlock newModuleInstance(XTypeElement moduleElement, ClassName requestingClass) {
    ModuleKind.checkIsModule(moduleElement);
    String packageName = requestingClass.packageName();
    return nonPublicNullaryConstructor(moduleElement)
        .filter(constructor -> !isElementAccessibleFrom(constructor, packageName))
        .map(
            constructor ->
                CodeBlock.of("$T.newInstance()", constructorProxyTypeName(moduleElement)))
        .orElse(CodeBlock.of("new $T()", moduleElement.getClassName()));
  }
}
