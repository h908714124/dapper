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
import static dagger.internal.codegen.CompilerMode.DEFAULT_MODE;
import static dagger.internal.codegen.CompilerMode.FAST_INIT_MODE;
import static dagger.internal.codegen.Compilers.compilerWithOptions;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import javax.tools.JavaFileObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class MapBindingExpressionWithGuavaTest {
  @Parameters(name = "{0}")
  public static Collection<Object[]> parameters() {
    return CompilerMode.TEST_PARAMETERS;
  }

  private final CompilerMode compilerMode;

  public MapBindingExpressionWithGuavaTest(CompilerMode compilerMode) {
    this.compilerMode = compilerMode;
  }

  @Test
  public void mapBindings() {
    JavaFileObject mapModuleFile =
        JavaFileObjects.forSourceLines(
            "test.MapModule",
            "package test;",
            "",
            "import dagger.Module;",
            "import dagger.Provides;",
            "import dagger.multibindings.IntKey;",
            "import dagger.multibindings.IntoMap;",
            "import dagger.multibindings.LongKey;",
            "import dagger.multibindings.Multibinds;",
            "import java.util.Map;",
            "",
            "@Module",
            "interface MapModule {",
            "  @Multibinds Map<String, String> stringMap();",
            "  @Provides @IntoMap @IntKey(0) static int provideInt() { return 0; }",
            "  @Provides @IntoMap @LongKey(0) static long provideLong0() { return 0; }",
            "  @Provides @IntoMap @LongKey(1) static long provideLong1() { return 1; }",
            "  @Provides @IntoMap @LongKey(2) static long provideLong2() { return 2; }",
            "}");
    JavaFileObject subcomponentModuleFile =
        JavaFileObjects.forSourceLines(
            "test.SubcomponentMapModule",
            "package test;",
            "",
            "import dagger.Module;",
            "import dagger.Provides;",
            "import dagger.multibindings.IntKey;",
            "import dagger.multibindings.IntoMap;",
            "import dagger.multibindings.LongKey;",
            "import dagger.multibindings.Multibinds;",
            "import java.util.Map;",
            "",
            "@Module",
            "interface SubcomponentMapModule {",
            "  @Provides @IntoMap @LongKey(3) static long provideLong3() { return 3; }",
            "  @Provides @IntoMap @LongKey(4) static long provideLong4() { return 4; }",
            "  @Provides @IntoMap @LongKey(5) static long provideLong5() { return 5; }",
            "}");
    JavaFileObject componentFile =
        JavaFileObjects.forSourceLines(
            "test.TestComponent",
            "package test;",
            "",
            "import dagger.Component;",
            "import java.util.Map;",
            "import jakarta.inject.Provider;",
            "",
            "@Component(modules = MapModule.class)",
            "interface TestComponent {",
            "  Map<String, String> strings();",
            "  Map<String, Provider<String>> providerStrings();",
            "",
            "  Map<Integer, Integer> ints();",
            "  Map<Integer, Provider<Integer>> providerInts();",
            "  Map<Long, Long> longs();",
            "  Map<Long, Provider<Long>> providerLongs();",
            "",
            "  Sub sub();",
            "}");
    JavaFileObject subcomponent =
        JavaFileObjects.forSourceLines(
            "test.Sub",
            "package test;",
            "",
            "import dagger.Subcomponent;",
            "import java.util.Map;",
            "import jakarta.inject.Provider;",
            "",
            "@Subcomponent(modules = SubcomponentMapModule.class)",
            "interface Sub {",
            "  Map<Long, Long> longs();",
            "  Map<Long, Provider<Long>> providerLongs();",
            "}");
    String[] generatedComponent =
        compilerMode
            .javaFileBuilder("test.DaggerTestComponent")
            .addLines(
                "package test;",
                "")
            .addLines(GeneratedLines.generatedAnnotationsIndividual())
            .addLines(
                "final class DaggerTestComponent implements TestComponent {")
            .addLinesIn(
                FAST_INIT_MODE,
                "  private volatile Provider<Integer> provideIntProvider;",
                "  private volatile Provider<Long> provideLong0Provider;",
                "  private volatile Provider<Long> provideLong1Provider;",
                "  private volatile Provider<Long> provideLong2Provider;",
                "",
                "  private Provider<Integer> provideIntProvider() {",
                "    Object local = provideIntProvider;",
                "    if (local == null) {",
                "      local = new SwitchingProvider<>(testComponent, 0);",
                "      provideIntProvider = (Provider<Integer>) local;",
                "    }",
                "    return (Provider<Integer>) local;",
                "  }",
                "",
                "  private Provider<Long> provideLong0Provider() {",
                "    Object local = provideLong0Provider;",
                "    if (local == null) {",
                "      local = new SwitchingProvider<>(testComponent, 1);",
                "      provideLong0Provider = (Provider<Long>) local;",
                "    }",
                "    return (Provider<Long>) local;",
                "  }",
                "",
                "  private Provider<Long> provideLong1Provider() {",
                "    Object local = provideLong1Provider;",
                "    if (local == null) {",
                "      local = new SwitchingProvider<>(testComponent, 2);",
                "      provideLong1Provider = (Provider<Long>) local;",
                "    }",
                "    return (Provider<Long>) local;",
                "  }",
                "",
                "  private Provider<Long> provideLong2Provider() {",
                "    Object local = provideLong2Provider;",
                "    if (local == null) {",
                "      local = new SwitchingProvider<>(testComponent, 3);",
                "      provideLong2Provider = (Provider<Long>) local;",
                "    }",
                "    return (Provider<Long>) local;",
                "  }")
            .addLines(
                "  @Override",
                "  public Map<String, String> strings() {",
                "    return ImmutableMap.<String, String>of();",
                "  }",
                "",
                "  @Override",
                "  public Map<String, Provider<String>> providerStrings() {",
                "    return ImmutableMap.<String, Provider<String>>of();",
                "  }",
                "",
                "  @Override",
                "  public Map<Integer, Integer> ints() {",
                "    return ImmutableMap.<Integer, Integer>of(0, MapModule.provideInt());",
                "  }",
                "",
                "  @Override",
                "  public Map<Integer, Provider<Integer>> providerInts() {")
            .addLinesIn(
                DEFAULT_MODE,
                "    return ImmutableMap.<Integer, Provider<Integer>>of(0, MapModule_ProvideIntFactory.create());")
            .addLinesIn(
                FAST_INIT_MODE,
                "    return ImmutableMap.<Integer, Provider<Integer>>of(0, provideIntProvider());")
            .addLines(
                "  }",
                "",
                "  @Override",
                "  public Map<Long, Long> longs() {",
                "    return ImmutableMap.<Long, Long>of(0L, MapModule.provideLong0(), 1L, MapModule.provideLong1(), 2L, MapModule.provideLong2());",
                "  }",
                "",
                "  @Override",
                "  public Map<Long, Provider<Long>> providerLongs() {")
            .addLinesIn(
                DEFAULT_MODE,
                "    return ImmutableMap.<Long, Provider<Long>>of(0L, MapModule_ProvideLong0Factory.create(), 1L, MapModule_ProvideLong1Factory.create(), 2L, MapModule_ProvideLong2Factory.create());")
            .addLinesIn(
                FAST_INIT_MODE,
                "    return ImmutableMap.<Long, Provider<Long>>of(0L, provideLong0Provider(), 1L, provideLong1Provider(), 2L, provideLong2Provider());")
            .addLines(
                "  }",
                "",
                "  @Override",
                "  public Sub sub() {",
                "    return new SubImpl(testComponent);",
                "  }",
                "",
                "  private static final class SubImpl implements Sub {")
            .addLinesIn(
                FAST_INIT_MODE,
                "    private volatile Provider<Long> provideLong3Provider;",
                "    private volatile Provider<Long> provideLong4Provider;",
                "    private volatile Provider<Long> provideLong5Provider;",
                "",
                "    private Provider<Long> provideLong3Provider() {",
                "      Object local = provideLong3Provider;",
                "      if (local == null) {",
                "        local = new SwitchingProvider<>(testComponent, subImpl, 0);",
                "        provideLong3Provider = (Provider<Long>) local;",
                "      }",
                "      return (Provider<Long>) local;",
                "    }",
                "",
                "    private Provider<Long> provideLong4Provider() {",
                "      Object local = provideLong4Provider;",
                "      if (local == null) {",
                "        local = new SwitchingProvider<>(testComponent, subImpl, 1);",
                "        provideLong4Provider = (Provider<Long>) local;",
                "      }",
                "      return (Provider<Long>) local;",
                "    }",
                "",
                "    private Provider<Long> provideLong5Provider() {",
                "      Object local = provideLong5Provider;",
                "      if (local == null) {",
                "        local = new SwitchingProvider<>(testComponent, subImpl, 2);",
                "        provideLong5Provider = (Provider<Long>) local;",
                "      }",
                "      return (Provider<Long>) local;",
                "    }")
            .addLines(
                "    @Override",
                "    public Map<Long, Long> longs() {",
                "      return ImmutableMap.<Long, Long>builderWithExpectedSize(6).put(0L, MapModule.provideLong0()).put(1L, MapModule.provideLong1()).put(2L, MapModule.provideLong2()).put(3L, SubcomponentMapModule.provideLong3()).put(4L, SubcomponentMapModule.provideLong4()).put(5L, SubcomponentMapModule.provideLong5()).build();",
                "    }",
                "",
                "    @Override",
                "    public Map<Long, Provider<Long>> providerLongs() {")
            .addLinesIn(
                DEFAULT_MODE,
                "      return ImmutableMap.<Long, Provider<Long>>builderWithExpectedSize(6).put(0L, MapModule_ProvideLong0Factory.create()).put(1L, MapModule_ProvideLong1Factory.create()).put(2L, MapModule_ProvideLong2Factory.create()).put(3L, SubcomponentMapModule_ProvideLong3Factory.create()).put(4L, SubcomponentMapModule_ProvideLong4Factory.create()).put(5L, SubcomponentMapModule_ProvideLong5Factory.create()).build();")
            .addLinesIn(
                FAST_INIT_MODE,
                "      return ImmutableMap.<Long, Provider<Long>>builderWithExpectedSize(6).put(0L, testComponent.provideLong0Provider()).put(1L, testComponent.provideLong1Provider()).put(2L, testComponent.provideLong2Provider()).put(3L, provideLong3Provider()).put(4L, provideLong4Provider()).put(5L, provideLong5Provider()).build();")
            .addLines(
                "    }")
            .addLinesIn(
                FAST_INIT_MODE,
                "    private static final class SwitchingProvider<T> implements Provider<T> {",
                "      @SuppressWarnings(\"unchecked\")",
                "      @Override",
                "      public T get() {",
                "        switch (id) {",
                "          case 0: // java.util.Map<java.lang.Long,jakarta.inject.Provider<java.lang.Long>> test.SubcomponentMapModule#provideLong3 ",
                "          return (T) (Long) SubcomponentMapModule.provideLong3();",
                "          case 1: // java.util.Map<java.lang.Long,jakarta.inject.Provider<java.lang.Long>> test.SubcomponentMapModule#provideLong4 ",
                "          return (T) (Long) SubcomponentMapModule.provideLong4();",
                "          case 2: // java.util.Map<java.lang.Long,jakarta.inject.Provider<java.lang.Long>> test.SubcomponentMapModule#provideLong5 ",
                "          return (T) (Long) SubcomponentMapModule.provideLong5();",
                "          default: throw new AssertionError(id);",
                "        }",
                "      }",
                "    }",
                "  }",
                "",
                "  private static final class SwitchingProvider<T> implements Provider<T> {",
                "    @SuppressWarnings(\"unchecked\")",
                "    @Override",
                "    public T get() {",
                "      switch (id) {",
                "        case 0: // java.util.Map<java.lang.Integer,jakarta.inject.Provider<java.lang.Integer>> test.MapModule#provideInt ",
                "        return (T) (Integer) MapModule.provideInt();",
                "        case 1: // java.util.Map<java.lang.Long,jakarta.inject.Provider<java.lang.Long>> test.MapModule#provideLong0 ",
                "        return (T) (Long) MapModule.provideLong0();",
                "        case 2: // java.util.Map<java.lang.Long,jakarta.inject.Provider<java.lang.Long>> test.MapModule#provideLong1 ",
                "        return (T) (Long) MapModule.provideLong1();",
                "        case 3: // java.util.Map<java.lang.Long,jakarta.inject.Provider<java.lang.Long>> test.MapModule#provideLong2 ",
                "        return (T) (Long) MapModule.provideLong2();",
                "        default: throw new AssertionError(id);",
                "      }",
                "    }",
                "  }",
                "}")
            .lines();
    Compilation compilation =
        compilerWithOptions(compilerMode.javacopts())
            .compile(mapModuleFile, componentFile, subcomponentModuleFile, subcomponent);
    assertThat(compilation).succeeded();
    assertThat(compilation)
        .generatedSourceFile("test.DaggerTestComponent")
        .containsLines(generatedComponent);
  }

  @Test
  public void inaccessible() {
    JavaFileObject inaccessible =
        JavaFileObjects.forSourceLines(
            "other.Inaccessible", "package other;", "", "class Inaccessible {}");
    JavaFileObject usesInaccessible =
        JavaFileObjects.forSourceLines(
            "other.UsesInaccessible",
            "package other;",
            "",
            "import java.util.Map;",
            "import jakarta.inject.Inject;",
            "",
            "public class UsesInaccessible {",
            "  @Inject UsesInaccessible(Map<Integer, Inaccessible> map) {}",
            "}");

    JavaFileObject module =
        JavaFileObjects.forSourceLines(
            "other.TestModule",
            "package other;",
            "",
            "import dagger.Module;",
            "import dagger.multibindings.Multibinds;",
            "import java.util.Map;",
            "",
            "@Module",
            "public abstract class TestModule {",
            "  @Multibinds abstract Map<Integer, Inaccessible> ints();",
            "}");
    JavaFileObject componentFile =
        JavaFileObjects.forSourceLines(
            "test.TestComponent",
            "package test;",
            "",
            "import dagger.Component;",
            "import java.util.Map;",
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
        "package test;",
        "",
        "import other.UsesInaccessible;",
        "import other.UsesInaccessible_Factory;",
        "");
    Collections.addAll(generatedComponent,
        GeneratedLines.generatedAnnotationsIndividual());
    Collections.addAll(generatedComponent,
        "final class DaggerTestComponent implements TestComponent {",
        "  @Override",
        "  public UsesInaccessible usesInaccessible() {",
        "    return UsesInaccessible_Factory.newInstance((Map) ImmutableMap.of());",
        "  }",
        "}");
    Compilation compilation =
        compilerWithOptions(compilerMode.javacopts())
            .compile(module, inaccessible, usesInaccessible, componentFile);
    assertThat(compilation).succeeded();
    assertThat(compilation)
        .generatedSourceFile("test.DaggerTestComponent")
        .containsLines(generatedComponent);
  }

  @Test
  public void subcomponentOmitsInheritedBindings() {
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
            "import dagger.multibindings.IntoMap;",
            "import dagger.multibindings.StringKey;",
            "",
            "@Module",
            "class ParentModule {",
            "  @Provides @IntoMap @StringKey(\"parent key\") Object parentKeyObject() {",
            "    return \"parent value\";",
            "  }",
            "}");
    JavaFileObject child =
        JavaFileObjects.forSourceLines(
            "test.Child",
            "package test;",
            "",
            "import dagger.Subcomponent;",
            "import java.util.Map;",
            "",
            "@Subcomponent",
            "interface Child {",
            "  Map<String, Object> objectMap();",
            "}");
    List<String> generatedComponent = new ArrayList<>();
    Collections.addAll(generatedComponent,
        "package test;");
    Collections.addAll(generatedComponent,
        GeneratedLines.generatedAnnotationsIndividual());
    Collections.addAll(generatedComponent,
        "final class DaggerParent implements Parent {",
        "  private final ParentModule parentModule;",
        "",
        "  private static final class ChildImpl implements Child {",
        "    @Override",
        "    public Map<String, Object> objectMap() {",
        "      return ImmutableMap.<String, Object>of(\"parent key\", ParentModule_ParentKeyObjectFactory.parentKeyObject(parent.parentModule));",
        "    }",
        "  }",
        "}");

    Compilation compilation =
        compilerWithOptions(compilerMode.javacopts()).compile(parent, parentModule, child);
    assertThat(compilation).succeeded();
    assertThat(compilation)
        .generatedSourceFile("test.DaggerParent")
        .containsLines(generatedComponent);
  }

  @Test
  public void productionComponents() {
    JavaFileObject mapModuleFile =
        JavaFileObjects.forSourceLines(
            "test.MapModule",
            "package test;",
            "",
            "import dagger.Module;",
            "import dagger.multibindings.Multibinds;",
            "import java.util.Map;",
            "",
            "@Module",
            "interface MapModule {",
            "  @Multibinds Map<String, String> stringMap();",
            "}");
    JavaFileObject componentFile = JavaFileObjects.forSourceLines("test.TestComponent",
        "package test;",
        "",
        "import com.google.common.util.concurrent.ListenableFuture;",
        "import dagger.producers.ProductionComponent;",
        "import java.util.Map;",
        "",
        "@ProductionComponent(modules = MapModule.class)",
        "interface TestComponent {",
        "  ListenableFuture<Map<String, String>> stringMap();",
        "}");
    List<String> generatedComponent = new ArrayList<>();
    Collections.addAll(generatedComponent,
        "package test;",
        "",
        "import dagger.producers.internal.CancellationListener;");
    Collections.addAll(generatedComponent, GeneratedLines.generatedAnnotationsIndividual());
    Collections.addAll(generatedComponent,
        "final class DaggerTestComponent implements TestComponent, CancellationListener {",
        "  @Override",
        "  public ListenableFuture<Map<String, String>> stringMap() {",
        "    return Futures.immediateFuture(ImmutableMap.<String, String>of());",
        "  }",
        "",
        "  @Override",
        "  public void onProducerFutureCancelled(boolean mayInterruptIfRunning) {",
        "  }",
        "}");
    Compilation compilation =
        compilerWithOptions(
            compilerMode)
            .compile(mapModuleFile, componentFile);
    assertThat(compilation).succeeded();
    assertThat(compilation)
        .generatedSourceFile("test.DaggerTestComponent")
        .containsLines(generatedComponent);
  }
}
