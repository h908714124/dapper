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

package dagger.internal.codegen;

import static dagger.internal.codegen.CompilerMode.DEFAULT_MODE;
import static dagger.internal.codegen.Compilers.compilerWithOptions;
import static io.jbock.testing.compile.CompilationSubject.assertThat;

import io.jbock.testing.compile.Compilation;
import io.jbock.testing.compile.JavaFileObjects;
import java.util.ArrayList;
import java.util.List;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

class SpaghettiTest {

  private final CompilerMode compilerMode = DEFAULT_MODE;

  @Test
  void resolutionOrder() {
    ArrayList<JavaFileObject> files = new ArrayList<>();
    files.add(binding("A", "B"));
    files.add(binding("B", "C"));
    files.add(binding("C"));
    files.add(binding("X", "C"));

    files.add(
        JavaFileObjects.forSourceLines(
            "test.TestComponent",
            "package test;",
            "",
            "import jakarta.inject.Singleton;",
            "import dagger.Component;",
            "",
            "@Component",
            "@Singleton",
            "interface TestComponent {",
            "  A a();",
            "  C c();",
            "  X x();",
            "}"));

    String[] generatedComponent =
        compilerMode
            .javaFileBuilder("test.DaggerTestComponent")
            .addLines("package test;", "")
            .addLines(GeneratedLines.generatedAnnotations())
            .addLines(
                "final class DaggerTestComponent implements TestComponent {",
                "  private B b() {",
                "    return new B(new C());",
                "  }",
                "",
                "  @Override",
                "  public A a() {",
                "    return new A(b());",
                "  }",
                "",
                "  @Override",
                "  public C c() {",
                "    return new C();",
                "  }",
                "",
                "  @Override",
                "  public X x() {",
                "    return new X(new C());",
                "  }",
                "}")
            .lines();

    Compilation compilation = compilerWithOptions(compilerMode.javacopts()).compile(files);
    assertThat(compilation).succeeded();

    assertThat(compilation)
        .generatedSourceFile("test.DaggerTestComponent")
        .containsLines(List.of(generatedComponent));
  }

  private JavaFileObject binding(String name, String dependency) {
    boolean singleton = name.startsWith("@");
    boolean missing = name.startsWith("0");
    if (singleton) {
      name = name.substring(1);
    }
    if (missing) {
      name = name.substring(1);
    }
    return JavaFileObjects.forSourceLines(
        "test." + name,
        "package test;",
        "",
        "import jakarta.inject.Inject;",
        "import jakarta.inject.Singleton;",
        "",
        String.format("%s final class %s {", singleton ? "@Singleton" : "", name),
        String.format("  %s %s(%s dep) {}", missing ? "" : "@Inject", name, dependency),
        "}");
  }

  private JavaFileObject binding(String name) {
    boolean singleton = name.startsWith("@");
    boolean missing = name.startsWith("0");
    if (singleton) {
      name = name.substring(1);
    }
    if (missing) {
      name = name.substring(1);
    }
    return JavaFileObjects.forSourceLines(
        "test." + name,
        "package test;",
        "",
        "import jakarta.inject.Inject;",
        "import jakarta.inject.Singleton;",
        "",
        String.format("%s final class %s {", singleton ? "@Singleton" : "", name),
        String.format("  %s %s() {}", missing ? "" : "@Inject", name),
        "}");
  }
}
