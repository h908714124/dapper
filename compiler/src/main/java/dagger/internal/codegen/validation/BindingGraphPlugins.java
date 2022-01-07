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

package dagger.internal.codegen.validation;

import static dagger.internal.codegen.extension.DaggerStreams.toImmutableSet;

import dagger.internal.codegen.base.Util;
import dagger.internal.codegen.compileroption.ProcessingOptions;
import dagger.internal.codegen.extension.DaggerStreams;
import dagger.internal.codegen.langmodel.DaggerElements;
import dagger.internal.codegen.langmodel.DaggerTypes;
import dagger.spi.BindingGraphPlugin;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.Filer;

/** Initializes {@link BindingGraphPlugin}s. */
public final class BindingGraphPlugins {
  private final Set<BindingGraphPlugin> plugins;
  private final Filer filer;
  private final DaggerTypes types;
  private final DaggerElements elements;
  private final Map<String, String> processingOptions;

  @Inject
  BindingGraphPlugins(
      @Validation Set<BindingGraphPlugin> validationPlugins,
      Set<BindingGraphPlugin> externalPlugins,
      Filer filer,
      DaggerTypes types,
      DaggerElements elements,
      @ProcessingOptions Map<String, String> processingOptions) {
    this.plugins = Util.union(validationPlugins, externalPlugins);
    this.filer = filer;
    this.types = types;
    this.elements = elements;
    this.processingOptions = processingOptions;
  }

  /** Returns {@link BindingGraphPlugin#supportedOptions()} from all the plugins. */
  public Set<String> allSupportedOptions() {
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
    plugin.initFiler(filer);
    plugin.initTypes(types);
    plugin.initElements(elements);
    Set<String> supportedOptions = plugin.supportedOptions();
    if (!supportedOptions.isEmpty()) {
      plugin.initOptions(processingOptions.entrySet().stream()
          .filter(entry -> supportedOptions.contains(entry.getKey()))
          .collect(DaggerStreams.toImmutableMap(Map.Entry::getKey, Map.Entry::getValue)));
    }
  }
}
