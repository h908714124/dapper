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

package dagger.internal.codegen;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static dagger.internal.codegen.Compilers.CLASS_PATH_WITHOUT_GUAVA_OPTION;
import static dagger.internal.codegen.Compilers.compilerWithOptions;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.tools.JavaFileObject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class SetBindingRequestFulfillmentTest {

  @EnumSource(CompilerMode.class)
  @ParameterizedTest
  void setBindings(CompilerMode compilerMode) {
    JavaFileObject emptySetModuleFile = JavaFileObjects.forSourceLines("test.EmptySetModule",
        "package test;",
        "",
        "import dagger.Module;",
        "import dagger.Provides;",
        "import dagger.multibindings.ElementsIntoSet;",
        "import dagger.multibindings.Multibinds;",
        "import java.util.Collections;",
        "import java.util.Set;",
        "",
        "@Module",
        "abstract class EmptySetModule {",
        "  @Multibinds abstract Set<Object> objects();",
        "",
        "  @Provides @ElementsIntoSet",
        "  static Set<String> emptySet() { ",
        "    return Collections.emptySet();",
        "  }",
        "}");
    JavaFileObject setModuleFile = JavaFileObjects.forSourceLines("test.SetModule",
        "package test;",
        "",
        "import dagger.Module;",
        "import dagger.Provides;",
        "import dagger.multibindings.IntoSet;",
        "",
        "@Module",
        "final class SetModule {",
        "  @Provides @IntoSet static String string() { return \"\"; }",
        "}");
    JavaFileObject componentFile = JavaFileObjects.forSourceLines("test.TestComponent",
        "package test;",
        "",
        "import dagger.Component;",
        "import java.util.Set;",
        "import jakarta.inject.Provider;",
        "",
        "@Component(modules = {EmptySetModule.class, SetModule.class})",
        "interface TestComponent {",
        "  Set<String> strings();",
        "  Set<Object> objects();",
        "}");
    List<String> generatedComponent = new ArrayList<>();
    Collections.addAll(generatedComponent,
        "package test;");
    Collections.addAll(generatedComponent,
        "import dagger.internal.SetBuilder;");
    Collections.addAll(generatedComponent,
        GeneratedLines.generatedAnnotationsIndividual());
    Collections.addAll(generatedComponent,
        "final class DaggerTestComponent implements TestComponent {",
        "  @Override",
        "  public Set<String> strings() {",
        "    return SetBuilder.<String>newSetBuilder(2).addAll(EmptySetModule_EmptySetFactory.emptySet()).add(SetModule_StringFactory.string()).build();",
        "  }",
        "",
        "  @Override",
        "  public Set<Object> objects() {",
        "    return Set.<Object>of();",
        "  }",
        "}");
    Compilation compilation =
        daggerCompilerWithoutGuava(compilerMode).compile(emptySetModuleFile, setModuleFile, componentFile);
    assertThat(compilation).succeeded();
    assertThat(compilation)
        .generatedSourceFile("test.DaggerTestComponent")
        .containsLines(generatedComponent);
  }

  @EnumSource(CompilerMode.class)
  @ParameterizedTest
  void inaccessible(CompilerMode compilerMode) {
    JavaFileObject inaccessible =
        JavaFileObjects.forSourceLines(
            "other.Inaccessible",
            "package other;",
            "",
            "class Inaccessible {}");
    JavaFileObject inaccessible2 =
        JavaFileObjects.forSourceLines(
            "other.Inaccessible2",
            "package other;",
            "",
            "class Inaccessible2 {}");
    JavaFileObject usesInaccessible =
        JavaFileObjects.forSourceLines(
            "other.UsesInaccessible",
            "package other;",
            "",
            "import java.util.Set;",
            "import jakarta.inject.Inject;",
            "",
            "public class UsesInaccessible {",
            "  @Inject UsesInaccessible(Set<Inaccessible> set1, Set<Inaccessible2> set2) {}",
            "}");

    JavaFileObject module =
        JavaFileObjects.forSourceLines(
            "other.TestModule",
            "package other;",
            "",
            "import dagger.Module;",
            "import dagger.Provides;",
            "import dagger.multibindings.ElementsIntoSet;",
            "import dagger.multibindings.Multibinds;",
            "import java.util.Collections;",
            "import java.util.Set;",
            "",
            "@Module",
            "public abstract class TestModule {",
            "  @Multibinds abstract Set<Inaccessible> objects();",
            "",
            "  @Provides @ElementsIntoSet",
            "  static Set<Inaccessible2> emptySet() { ",
            "    return Set.of();",
            "  }",
            "}");
    JavaFileObject componentFile =
        JavaFileObjects.forSourceLines(
            "test.TestComponent",
            "package test;",
            "",
            "import dagger.Component;",
            "import java.util.Set;",
            "import jakarta.inject.Provider;",
            "import other.TestModule;",
            "import other.UsesInaccessible;",
            "",
            "@Component(modules = TestModule.class)",
            "interface TestComponent {",
            "  UsesInaccessible usesInaccessible();",
            "}");
    List<String> generatedComponent = new ArrayList<>();
    Collections.addAll(generatedComponent,
        "package test;");
    Collections.addAll(generatedComponent,
        "import dagger.internal.SetBuilder;",
        "import other.TestModule_EmptySetFactory;",
        "import other.UsesInaccessible;",
        "import other.UsesInaccessible_Factory;");
    Collections.addAll(generatedComponent,
        GeneratedLines.generatedAnnotationsIndividual());
    Collections.addAll(generatedComponent,
        "final class DaggerTestComponent implements TestComponent {",
        "  private Set setOfInaccessible2() {",
        "    return SetBuilder.newSetBuilder(1).addAll(TestModule_EmptySetFactory.emptySet()).build();",
        "  }",
        "",
        "  @Override",
        "  public UsesInaccessible usesInaccessible() {",
        "    return UsesInaccessible_Factory.newInstance((Set) Set.of(), (Set) setOfInaccessible2());",
        "  }",
        "}");
    Compilation compilation =
        daggerCompilerWithoutGuava(compilerMode)
            .compile(module, inaccessible, inaccessible2, usesInaccessible, componentFile);
    assertThat(compilation).succeeded();
    assertThat(compilation)
        .generatedSourceFile("test.DaggerTestComponent")
        .containsLines(generatedComponent);
  }

  @EnumSource(CompilerMode.class)
  @ParameterizedTest
  void subcomponentOmitsInheritedBindings(CompilerMode compilerMode) {
    JavaFileObject parent =
        JavaFileObjects.forSourceLines(
            "test.Parent",
            "package test;",
            "",
            "import dagger.Component;",
            "",
            "@Component(modules = ParentModule.class)",
            "interface Parent {",
            "  Child child();",
            "}");
    JavaFileObject parentModule =
        JavaFileObjects.forSourceLines(
            "test.ParentModule",
            "package test;",
            "",
            "import dagger.Module;",
            "import dagger.Provides;",
            "import dagger.multibindings.IntoSet;",
            "import dagger.multibindings.StringKey;",
            "",
            "@Module",
            "class ParentModule {",
            "  @Provides @IntoSet static Object parentObject() {",
            "    return \"parent object\";",
            "  }",
            "}");
    JavaFileObject child =
        JavaFileObjects.forSourceLines(
            "test.Child",
            "package test;",
            "",
            "import dagger.Subcomponent;",
            "import java.util.Set;",
            "",
            "@Subcomponent",
            "interface Child {",
            "  Set<Object> objectSet();",
            "}");
    List<String> generatedComponent = new ArrayList<>();
    Collections.addAll(generatedComponent,
        "package test;");
    Collections.addAll(generatedComponent,
        GeneratedLines.generatedImportsIndividual(
            "import dagger.internal.Preconditions;",
            "import java.util.Set;"));
    Collections.addAll(generatedComponent,
        GeneratedLines.generatedAnnotationsIndividual());
    Collections.addAll(generatedComponent,
        "final class DaggerParent implements Parent {",
        "  private final DaggerParent parent = this;",
        "",
        "  private DaggerParent() {",
        "  }",
        "",
        "  public static Builder builder() {",
        "    return new Builder();",
        "  }",
        "",
        "  public static Parent create() {",
        "    return new Builder().build();",
        "  }",
        "",
        "  @Override",
        "  public Child child() {",
        "    return new ChildImpl(parent);",
        "  }",
        "",
        "  static final class Builder {",
        "    private Builder() {",
        "    }",
        "",
        "    @Deprecated",
        "    public Builder parentModule(ParentModule parentModule) {",
        "      Preconditions.checkNotNull(parentModule);",
        "      return this;",
        "    }",
        "",
        "    public Parent build() {",
        "      return new DaggerParent();",
        "    }",
        "  }",
        "",
        "  private static final class ChildImpl implements Child {",
        "    private final DaggerParent parent;",
        "",
        "    private final ChildImpl childImpl = this;",
        "",
        "    private ChildImpl(DaggerParent parent) {",
        "      this.parent = parent;",
        "    }",
        "",
        "    @Override",
        "    public Set<Object> objectSet() {",
        "      return Set.<Object>of(ParentModule_ParentObjectFactory.parentObject());",
        "    }",
        "  }",
        "}");

    Compilation compilation = daggerCompilerWithoutGuava(compilerMode).compile(parent, parentModule, child);
    assertThat(compilation).succeeded();
    assertThat(compilation)
        .generatedSourceFile("test.DaggerParent")
        .containsLines(generatedComponent);
  }

  private Compiler daggerCompilerWithoutGuava(CompilerMode compilerMode) {
    return compilerWithOptions(compilerMode.javacopts())
        .withClasspath(CLASS_PATH_WITHOUT_GUAVA_OPTION);
  }
}
