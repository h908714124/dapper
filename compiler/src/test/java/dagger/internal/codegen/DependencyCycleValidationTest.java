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

package dagger.internal.codegen;

import static dagger.internal.codegen.Compilers.compilerWithOptions;
import static dagger.internal.codegen.Compilers.daggerCompiler;
import static dagger.internal.codegen.TestUtils.endsWithMessage;
import static dagger.internal.codegen.TestUtils.message;
import static io.jbock.testing.compile.CompilationSubject.assertThat;

import io.jbock.testing.compile.Compilation;
import io.jbock.testing.compile.JavaFileObjects;
import java.util.regex.Pattern;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

public class DependencyCycleValidationTest {
  private static final JavaFileObject SIMPLE_CYCLIC_DEPENDENCY =
      JavaFileObjects.forSourceLines(
          "test.Outer",
          "package test;",
          "",
          "import dagger.Binds;",
          "import dagger.Component;",
          "import dagger.Module;",
          "import dagger.Provides;",
          "import jakarta.inject.Inject;",
          "",
          "final class Outer {",
          "  static class A {",
          "    @Inject A(C cParam) {}",
          "  }",
          "",
          "  static class B {",
          "    @Inject B(A aParam) {}",
          "  }",
          "",
          "  static class C {",
          "    @Inject C(B bParam) {}",
          "  }",
          "",
          "  @Module",
          "  interface MModule {",
          "    @Binds Object object(C c);",
          "  }",
          "",
          "  @Component",
          "  interface CComponent {",
          "    C getC();",
          "  }",
          "}");

  @Test
  void cyclicDependency() {
    Compilation compilation = daggerCompiler().compile(SIMPLE_CYCLIC_DEPENDENCY);
    assertThat(compilation).failed();

    assertThat(compilation)
        .hadErrorContaining(
            message(
                "Found a dependency cycle:",
                "    Outer.C is injected at",
                "        Outer.A(cParam)",
                "    Outer.A is injected at",
                "        Outer.B(aParam)",
                "    Outer.B is injected at",
                "        Outer.C(bParam)",
                "    Outer.C is requested at",
                "        Outer.CComponent.getC()"))
        .inFile(SIMPLE_CYCLIC_DEPENDENCY)
        .onLineContaining("interface CComponent");

    assertThat(compilation).hadErrorCount(1);
  }

  @Test
  void cyclicDependencyWithModuleBindingValidation() {
    // Cycle errors should not show a dependency trace to an entry point when doing full binding
    // graph validation. So ensure that the message doesn't end with "test.Outer.C is requested at
    // test.Outer.CComponent.getC()", as the previous test's message does.
    Pattern moduleBindingValidationError =
        endsWithMessage(
            "Found a dependency cycle:",
            "    Outer.C is injected at",
            "        Outer.A(cParam)",
            "    Outer.A is injected at",
            "        Outer.B(aParam)",
            "    Outer.B is injected at",
            "        Outer.C(bParam)",
            "",
            "======================",
            "Full classname legend:",
            "======================",
            "Outer: test.Outer",
            "========================",
            "End of classname legend:",
            "========================");

    Compilation compilation =
        compilerWithOptions("-Adagger.fullBindingGraphValidation=ERROR")
            .compile(SIMPLE_CYCLIC_DEPENDENCY);
    assertThat(compilation).failed();

    assertThat(compilation)
        .hadErrorContainingMatch(moduleBindingValidationError)
        .inFile(SIMPLE_CYCLIC_DEPENDENCY)
        .onLineContaining("interface MModule");

    assertThat(compilation)
        .hadErrorContainingMatch(moduleBindingValidationError)
        .inFile(SIMPLE_CYCLIC_DEPENDENCY)
        .onLineContaining("interface CComponent");

    assertThat(compilation).hadErrorCount(2);
  }

  @Test
  void cyclicDependencyNotIncludingEntryPoint() {
    JavaFileObject component =
        JavaFileObjects.forSourceLines(
            "test.Outer",
            "package test;",
            "",
            "import dagger.Component;",
            "import dagger.Module;",
            "import dagger.Provides;",
            "import jakarta.inject.Inject;",
            "",
            "final class Outer {",
            "  static class A {",
            "    @Inject A(C cParam) {}",
            "  }",
            "",
            "  static class B {",
            "    @Inject B(A aParam) {}",
            "  }",
            "",
            "  static class C {",
            "    @Inject C(B bParam) {}",
            "  }",
            "",
            "  static class D {",
            "    @Inject D(C cParam) {}",
            "  }",
            "",
            "  @Component",
            "  interface DComponent {",
            "    D getD();",
            "  }",
            "}");

    Compilation compilation = daggerCompiler().compile(component);
    assertThat(compilation).failed();
    assertThat(compilation)
        .hadErrorContaining(
            message(
                "Found a dependency cycle:",
                "    Outer.C is injected at",
                "        Outer.A(cParam)",
                "    Outer.A is injected at",
                "        Outer.B(aParam)",
                "    Outer.B is injected at",
                "        Outer.C(bParam)",
                "    Outer.C is injected at",
                "        Outer.D(cParam)",
                "    Outer.D is requested at",
                "        Outer.DComponent.getD()"))
        .inFile(component)
        .onLineContaining("interface DComponent");
  }

  @Test
  void falsePositiveCyclicDependencyIndirectionDetected() {
    JavaFileObject component =
        JavaFileObjects.forSourceLines(
            "test.Outer",
            "package test;",
            "",
            "import dagger.Component;",
            "import dagger.Module;",
            "import dagger.Provides;",
            "import jakarta.inject.Inject;",
            "import jakarta.inject.Provider;",
            "",
            "final class Outer {",
            "  static class A {",
            "    @Inject A(C cParam) {}",
            "  }",
            "",
            "  static class B {",
            "    @Inject B(A aParam) {}",
            "  }",
            "",
            "  static class C {",
            "    @Inject C(B bParam) {}",
            "  }",
            "",
            "  static class D {",
            "    @Inject D(Provider<C> cParam) {}",
            "  }",
            "",
            "  @Component",
            "  interface DComponent {",
            "    D getD();",
            "  }",
            "}");

    Compilation compilation = daggerCompiler().compile(component);
    assertThat(compilation).failed();
    assertThat(compilation)
        .hadErrorContaining(
            message(
                "Found a dependency cycle:",
                "    Outer.C is injected at",
                "        Outer.A(cParam)",
                "    Outer.A is injected at",
                "        Outer.B(aParam)",
                "    Outer.B is injected at",
                "        Outer.C(bParam)",
                "    Provider<Outer.C> is injected at",
                "        Outer.D(cParam)",
                "    Outer.D is requested at",
                "        Outer.DComponent.getD()"))
        .inFile(component)
        .onLineContaining("interface DComponent");
  }

  @Test
  void cyclicDependencyInSubcomponents() {
    JavaFileObject parent =
        JavaFileObjects.forSourceLines(
            "test.Parent",
            "package test;",
            "",
            "import dagger.Component;",
            "",
            "@Component",
            "interface Parent {",
            "  Child.Builder child();",
            "}");
    JavaFileObject child =
        JavaFileObjects.forSourceLines(
            "test.Child",
            "package test;",
            "",
            "import dagger.Subcomponent;",
            "",
            "@Subcomponent(modules = CycleModule.class)",
            "interface Child {",
            "  Grandchild.Builder grandchild();",
            "",
            "  @Subcomponent.Builder",
            "  interface Builder {",
            "    Child build();",
            "  }",
            "}");
    JavaFileObject grandchild =
        JavaFileObjects.forSourceLines(
            "test.Grandchild",
            "package test;",
            "",
            "import dagger.Subcomponent;",
            "",
            "@Subcomponent",
            "interface Grandchild {",
            "  String entry();",
            "",
            "  @Subcomponent.Builder",
            "  interface Builder {",
            "    Grandchild build();",
            "  }",
            "}");
    JavaFileObject cycleModule =
        JavaFileObjects.forSourceLines(
            "test.CycleModule",
            "package test;",
            "",
            "import dagger.Module;",
            "import dagger.Provides;",
            "",
            "@Module",
            "abstract class CycleModule {",
            "  @Provides static Object object(String string) {",
            "    return string;",
            "  }",
            "",
            "  @Provides static String string(Object object) {",
            "    return object.toString();",
            "  }",
            "}");

    Compilation compilation = daggerCompiler().compile(parent, child, grandchild, cycleModule);
    assertThat(compilation).failed();
    assertThat(compilation)
        .hadErrorContaining(
            message(
                "Found a dependency cycle:",
                "    String is injected at",
                "        CycleModule.object(string)",
                "    Object is injected at",
                "        CycleModule.string(object)",
                "    String is requested at",
                "        Grandchild.entry()"))
        .inFile(parent)
        .onLineContaining("interface Parent");
  }

  @Test
  void cyclicDependencyInSubcomponentsWithChildren() {
    JavaFileObject parent =
        JavaFileObjects.forSourceLines(
            "test.Parent",
            "package test;",
            "",
            "import dagger.Component;",
            "",
            "@Component",
            "interface Parent {",
            "  Child.Builder child();",
            "}");
    JavaFileObject child =
        JavaFileObjects.forSourceLines(
            "test.Child",
            "package test;",
            "",
            "import dagger.Subcomponent;",
            "",
            "@Subcomponent(modules = CycleModule.class)",
            "interface Child {",
            "  String entry();",
            "",
            "  Grandchild.Builder grandchild();",
            "",
            "  @Subcomponent.Builder",
            "  interface Builder {",
            "    Child build();",
            "  }",
            "}");
    // Grandchild has no entry point that depends on the cycle. http://b/111317986
    JavaFileObject grandchild =
        JavaFileObjects.forSourceLines(
            "test.Grandchild",
            "package test;",
            "",
            "import dagger.Subcomponent;",
            "",
            "@Subcomponent",
            "interface Grandchild {",
            "",
            "  @Subcomponent.Builder",
            "  interface Builder {",
            "    Grandchild build();",
            "  }",
            "}");
    JavaFileObject cycleModule =
        JavaFileObjects.forSourceLines(
            "test.CycleModule",
            "package test;",
            "",
            "import dagger.Module;",
            "import dagger.Provides;",
            "",
            "@Module",
            "abstract class CycleModule {",
            "  @Provides static Object object(String string) {",
            "    return string;",
            "  }",
            "",
            "  @Provides static String string(Object object) {",
            "    return object.toString();",
            "  }",
            "}");

    Compilation compilation = daggerCompiler().compile(parent, child, grandchild, cycleModule);
    assertThat(compilation).failed();
    assertThat(compilation)
        .hadErrorContaining(
            message(
                "Found a dependency cycle:",
                "    String is injected at",
                "        CycleModule.object(string)",
                "    Object is injected at",
                "        CycleModule.string(object)",
                "    String is requested at",
                "        Child.entry() [Parent \u2192 Child]"))
        .inFile(parent)
        .onLineContaining("interface Parent");
  }

  @Test
  void circularBindsMethods() {
    JavaFileObject qualifier =
        JavaFileObjects.forSourceLines(
            "test.SomeQualifier",
            "package test;",
            "",
            "import jakarta.inject.Qualifier;",
            "",
            "@Qualifier @interface SomeQualifier {}");
    JavaFileObject module =
        JavaFileObjects.forSourceLines(
            "test.TestModule",
            "package test;",
            "",
            "import dagger.Binds;",
            "import dagger.Module;",
            "",
            "@Module",
            "abstract class TestModule {",
            "  @Binds abstract Object bindUnqualified(@SomeQualifier Object qualified);",
            "  @Binds @SomeQualifier abstract Object bindQualified(Object unqualified);",
            "}");
    JavaFileObject component =
        JavaFileObjects.forSourceLines(
            "test.TestComponent",
            "package test;",
            "",
            "import dagger.Component;",
            "",
            "@Component(modules = TestModule.class)",
            "interface TestComponent {",
            "  Object unqualified();",
            "}");

    Compilation compilation = daggerCompiler().compile(qualifier, module, component);
    assertThat(compilation).failed();
    assertThat(compilation)
        .hadErrorContaining(
            message(
                "Found a dependency cycle:",
                "    Object is injected at",
                "        TestModule.bindQualified(unqualified)",
                "    @SomeQualifier Object is injected at",
                "        TestModule.bindUnqualified(qualified)",
                "    Object is requested at",
                "        TestComponent.unqualified()"))
        .inFile(component)
        .onLineContaining("interface TestComponent");
  }

  @Test
  void selfReferentialBinds() {
    JavaFileObject module =
        JavaFileObjects.forSourceLines(
            "test.TestModule",
            "package test;",
            "",
            "import dagger.Binds;",
            "import dagger.Module;",
            "",
            "@Module",
            "abstract class TestModule {",
            "  @Binds abstract Object bindToSelf(Object sameKey);",
            "}");
    JavaFileObject component =
        JavaFileObjects.forSourceLines(
            "test.TestComponent",
            "package test;",
            "",
            "import dagger.Component;",
            "",
            "@Component(modules = TestModule.class)",
            "interface TestComponent {",
            "  Object selfReferential();",
            "}");

    Compilation compilation = daggerCompiler().compile(module, component);
    assertThat(compilation).failed();
    assertThat(compilation)
        .hadErrorContaining(
            message(
                "Found a dependency cycle:",
                "    Object is injected at",
                "        TestModule.bindToSelf(sameKey)",
                "    Object is requested at",
                "        TestComponent.selfReferential()"))
        .inFile(component)
        .onLineContaining("interface TestComponent");
  }

  @Test
  void longCycleMaskedByShortBrokenCycles() {
    JavaFileObject cycles =
        JavaFileObjects.forSourceLines(
            "test.Cycles",
            "package test;",
            "",
            "import jakarta.inject.Inject;",
            "import jakarta.inject.Provider;",
            "import dagger.Component;",
            "",
            "final class Cycles {",
            "  static class A {",
            "    @Inject A(Provider<A> aProvider, B b) {}",
            "  }",
            "",
            "  static class B {",
            "    @Inject B(Provider<B> bProvider, A a) {}",
            "  }",
            "",
            "  @Component",
            "  interface C {",
            "    A a();",
            "  }",
            "}");
    Compilation compilation = daggerCompiler().compile(cycles);
    assertThat(compilation).failed();
    assertThat(compilation)
        .hadErrorContaining("Found a dependency cycle:")
        .inFile(cycles)
        .onLineContaining("interface C");
  }
}
