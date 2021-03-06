// Copyright 2020 Google LLC.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.cloud.healthcare.etl.pipeline;

import static com.google.cloud.healthcare.etl.model.ErrorEntry.ERROR_ENTRY_TAG;

import com.google.cloud.healthcare.etl.model.ErrorEntry;
import com.google.cloud.healthcare.etl.model.mapping.Mappable;
import com.google.cloud.healthcare.etl.provider.mapping.MappingConfigProvider;
import com.google.cloud.healthcare.etl.provider.mapping.MappingConfigProviderFactory;
import com.google.cloud.healthcare.etl.util.library.TransformWrapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.apache.beam.sdk.metrics.Distribution;
import org.apache.beam.sdk.metrics.Metrics;
import org.apache.beam.sdk.options.ValueProvider;
import org.apache.beam.sdk.options.ValueProvider.StaticValueProvider;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.values.TupleTag;
import org.apache.beam.vendor.guava.v26_0_jre.com.google.common.base.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The core function of the mapping pipeline. Input is expected to be a parsed message. At this
 * moment, only higher level language (whistle) is supported.
 */
public class MappingFn<M extends Mappable> extends DoFn<M, String> {
  private static final Logger LOGGER = LoggerFactory.getLogger(MappingFn.class);
  public static final TupleTag<String> MAPPING_TAG = new TupleTag<>("mapping");
  private final Distribution transformMetrics =
      Metrics.distribution(MappingFn.class, "Transform");

  // Ensure the initialization only happens once. Ideally this should be handled by the library.
  private static final AtomicBoolean initializeStarted = new AtomicBoolean();
  private static final AtomicBoolean initializeFinished = new AtomicBoolean();
  private static final CountDownLatch initializeGate = new CountDownLatch(1);

  private final ValueProvider<String> mappingPath;
  private final ValueProvider<String> mappings;

  private TransformWrapper engine;

  // The config parameter should be the string representation of the whole mapping config, including
  // harmonization and libraries.
  private MappingFn(ValueProvider<String> mappingPath, ValueProvider<String> mappings) {
    this.mappingPath = mappingPath;
    this.mappings = mappings;
  }

  public static MappingFn of(ValueProvider<String> mappingPath) {
    return new MappingFn(mappingPath, StaticValueProvider.of(""));
  }

  public static MappingFn of(String mappingPath) {
    return of(StaticValueProvider.of(mappingPath));
  }

  public static MappingFn of(ValueProvider<String> mappingPath, ValueProvider<String> mappings) {
    return new MappingFn(mappingPath, mappings);
  }

  public static MappingFn of(String mappings, String mappingPath) {
    return of(StaticValueProvider.of(mappingPath), StaticValueProvider.of(mappings));
  }

  @Setup
  public void initialize() throws InterruptedException {
    // Make sure the mapping configuration is only initialized once.
    if (initializeStarted.compareAndSet(false, true)) {
      LOGGER.info("Initializing the mapping configurations.");
      engine = TransformWrapper.getInstance();
      try {
        // Mapping configurations are loaded from the `mappingPath` only if `mappings` is absent.
        String mappingsToUse = mappings.get();
        if (Strings.isNullOrEmpty(mappingsToUse)) {
          mappingsToUse = loadMapping(mappingPath.get());
        }
        engine.initializeWhistler(mappingsToUse);
        // This has to be done before opening the gate to avoid race conditions.
        initializeFinished.compareAndSet(false, true);
      } catch (RuntimeException e) {
        LOGGER.error("Unable to initialize mapping configurations.", e);
        // Reset the flag so that initialization can be retried (e.g. users fixed the mapping
        // configurations), by another thread.
        initializeStarted.compareAndSet(true, false);
        throw e; // Fail fast.
      } finally {
        // Open the gate in case other threads are waiting. Successive transformations will fail if
        // initialization failed.
        initializeGate.countDown();
      }
    } else if (!initializeFinished.get()) {
      // Wait for the gate to open if initialization is not done.
      LOGGER.info("Waiting for initialization to finish.");
      initializeGate.await();
    }
  }

  private static String loadMapping(String mappingPath) {
    MappingConfigProvider provider = MappingConfigProviderFactory.createProvider(mappingPath);
    try {
      return new String(provider.getMappingConfig(true /* force */), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new RuntimeException("Unable to load mapping configurations.", e);
    }
  }

  @ProcessElement
  public void process(ProcessContext ctx) {
    M input = ctx.element();
    try {
      ctx.output(runAndReportMetrics(transformMetrics, () -> engine.transform(input.getData())));
    } catch (RuntimeException e) {
      ErrorEntry entry = ErrorEntry.of(e).setStep(getClass().getSimpleName());
      if (input.getId() != null) {
        entry.setSources(Collections.singletonList(input.getId()));
      }
      ctx.output(ERROR_ENTRY_TAG, entry);
    }
  }

  // Runs a lambda and collects the metrics.
  private static <T> T runAndReportMetrics(Distribution metrics, Supplier<T> supplier) {
    Instant start = Instant.now();
    T result = supplier.get();
    metrics.update(Instant.now().toEpochMilli() - start.toEpochMilli());
    return result;
  }
}
