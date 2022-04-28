/*
 * Copyright (C) 2021 The Dagger Authors.
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

package dagger.internal.codegen.validation;

import static dagger.internal.codegen.extension.DaggerStreams.toImmutableSet;
import static dagger.internal.codegen.xprocessing.XConverters.toJavac;
import static javax.tools.Diagnostic.Kind.ERROR;

import dagger.internal.codegen.collect.ImmutableSet;
import dagger.internal.codegen.collect.Maps;
import dagger.internal.codegen.compileroption.ProcessingOptions;
import dagger.internal.codegen.validation.DiagnosticReporterFactory.DiagnosticReporterImpl;
import dagger.internal.codegen.xprocessing.XFiler;
import dagger.internal.codegen.xprocessing.XProcessingEnv;
import dagger.spi.model.BindingGraph;
import dagger.spi.model.BindingGraphPlugin;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.Set;

/** Initializes {@code BindingGraphPlugin}s. */
public final class ExternalBindingGraphPlugins {
  private final ImmutableSet<BindingGraphPlugin> plugins;
  private final DiagnosticReporterFactory diagnosticReporterFactory;
  private final XFiler filer;
  private final XProcessingEnv processingEnv;
  private final Map<String, String> processingOptions;

  @Inject
  ExternalBindingGraphPlugins(
      ImmutableSet<BindingGraphPlugin> plugins,
      DiagnosticReporterFactory diagnosticReporterFactory,
      XFiler filer,
      XProcessingEnv processingEnv,
      @ProcessingOptions Map<String, String> processingOptions) {
    this.plugins = plugins;
    this.diagnosticReporterFactory = diagnosticReporterFactory;
    this.filer = filer;
    this.processingEnv = processingEnv;
    this.processingOptions = processingOptions;
  }

  /** Returns {@code BindingGraphPlugin#supportedOptions()} from all the plugins. */
  public ImmutableSet<String> allSupportedOptions() {
    return plugins.stream()
        .flatMap(plugin -> plugin.supportedOptions().stream())
        .collect(toImmutableSet());
  }

  /** Initializes the plugins. */
  // TODO(ronshapiro): Should we validate the uniqueness of plugin names?
  public void initializePlugins() {
    plugins.forEach(this::initializePlugin);
  }

  private void initializePlugin(BindingGraphPlugin plugin) {
    plugin.initFiler(toJavac(filer));
    plugin.initTypes(toJavac(processingEnv).getTypeUtils()); // ALLOW_TYPES_ELEMENTS
    plugin.initElements(toJavac(processingEnv).getElementUtils()); // ALLOW_TYPES_ELEMENTS
    Set<String> supportedOptions = plugin.supportedOptions();
    if (!supportedOptions.isEmpty()) {
      plugin.initOptions(Maps.filterKeys(processingOptions, supportedOptions::contains));
    }
  }

  /** Returns {@code false} if any of the plugins reported an error. */
  boolean visit(BindingGraph graph) {
    boolean isClean = true;
    for (BindingGraphPlugin plugin : plugins) {
      DiagnosticReporterImpl reporter =
          diagnosticReporterFactory.reporter(
              graph, plugin.pluginName(), /* reportErrorsAsWarnings= */ false);
      plugin.visitGraph(graph, reporter);
      if (reporter.reportedDiagnosticKinds().contains(ERROR)) {
        isClean = false;
      }
    }
    return isClean;
  }
}
