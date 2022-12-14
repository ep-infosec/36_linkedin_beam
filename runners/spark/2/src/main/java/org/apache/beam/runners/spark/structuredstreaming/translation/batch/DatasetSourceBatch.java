/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.runners.spark.structuredstreaming.translation.batch;

import static org.apache.beam.runners.spark.structuredstreaming.Constants.BEAM_SOURCE_OPTION;
import static org.apache.beam.runners.spark.structuredstreaming.Constants.DEFAULT_PARALLELISM;
import static org.apache.beam.runners.spark.structuredstreaming.Constants.PIPELINE_OPTIONS;
import static org.apache.beam.vendor.guava.v26_0_jre.com.google.common.base.Preconditions.checkArgument;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import org.apache.beam.runners.core.construction.SerializablePipelineOptions;
import org.apache.beam.runners.core.serialization.Base64Serializer;
import org.apache.beam.runners.spark.structuredstreaming.translation.helpers.RowHelpers;
import org.apache.beam.runners.spark.structuredstreaming.translation.helpers.SchemaHelpers;
import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.io.BoundedSource;
import org.apache.beam.sdk.io.BoundedSource.BoundedReader;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.util.WindowedValue;
import org.apache.spark.sql.catalyst.InternalRow;
import org.apache.spark.sql.sources.v2.ContinuousReadSupport;
import org.apache.spark.sql.sources.v2.DataSourceOptions;
import org.apache.spark.sql.sources.v2.DataSourceV2;
import org.apache.spark.sql.sources.v2.ReadSupport;
import org.apache.spark.sql.sources.v2.reader.DataSourceReader;
import org.apache.spark.sql.sources.v2.reader.InputPartition;
import org.apache.spark.sql.sources.v2.reader.InputPartitionReader;
import org.apache.spark.sql.types.StructType;

/**
 * This is a spark structured streaming {@link DataSourceV2} implementation that wraps an {@link
 * BoundedSource}.As Continuous streaming is tagged experimental in spark (no aggregation support +
 * no exactly once guaranty), this class does no implement {@link ContinuousReadSupport}.
 */
public class DatasetSourceBatch implements DataSourceV2, ReadSupport {

  @Override
  public DataSourceReader createReader(DataSourceOptions options) {
    return new DatasetReader<>(options);
  }

  /** This class is mapped to Beam {@link BoundedSource}. */
  private static class DatasetReader<T> implements DataSourceReader, Serializable {

    private int numPartitions;
    private BoundedSource<T> source;
    private SerializablePipelineOptions serializablePipelineOptions;

    @SuppressWarnings("unchecked")
    private DatasetReader(DataSourceOptions options) {
      if (!options.get(BEAM_SOURCE_OPTION).isPresent()) {
        throw new RuntimeException("Beam source was not set in DataSource options");
      }
      this.source =
          Base64Serializer.deserializeUnchecked(
              options.get(BEAM_SOURCE_OPTION).get(), BoundedSource.class);

      if (!options.get(DEFAULT_PARALLELISM).isPresent()) {
        throw new RuntimeException("Spark default parallelism was not set in DataSource options");
      }
      this.numPartitions = Integer.parseInt(options.get(DEFAULT_PARALLELISM).get());
      checkArgument(numPartitions > 0, "Number of partitions must be greater than zero.");

      if (!options.get(PIPELINE_OPTIONS).isPresent()) {
        throw new RuntimeException("Beam pipelineOptions were not set in DataSource options");
      }
      this.serializablePipelineOptions =
          new SerializablePipelineOptions(options.get(PIPELINE_OPTIONS).get());
    }

    @Override
    public StructType readSchema() {
      // TODO: find a way to extend schema with a WindowedValue schema
      return SchemaHelpers.binarySchema();
    }

    @Override
    public List<InputPartition<InternalRow>> planInputPartitions() {
      PipelineOptions options = serializablePipelineOptions.get();
      List<InputPartition<InternalRow>> result = new ArrayList<>();
      long desiredSizeBytes;
      try {
        desiredSizeBytes = source.getEstimatedSizeBytes(options) / numPartitions;
        List<? extends BoundedSource<T>> splits = source.split(desiredSizeBytes, options);
        for (BoundedSource<T> split : splits) {
          result.add(
              (InputPartition<InternalRow>)
                  () -> new DatasetPartitionReader<>(split, serializablePipelineOptions));
        }
        return result;

      } catch (Exception e) {
        throw new RuntimeException(
            "Error in splitting BoundedSource " + source.getClass().getCanonicalName(), e);
      }
    }
  }

  /** This class can be mapped to Beam {@link BoundedReader}. */
  private static class DatasetPartitionReader<T> implements InputPartitionReader<InternalRow> {
    private boolean started;
    private boolean closed;

    @SuppressWarnings("unused")
    private final BoundedSource<T> source;

    private BoundedReader<T> reader;
    private final Coder<T> coder;

    DatasetPartitionReader(
        BoundedSource<T> source, SerializablePipelineOptions serializablePipelineOptions) {
      this.started = false;
      this.closed = false;
      this.source = source;
      this.coder = source.getOutputCoder();
      // reader is not serializable so lazy initialize it
      try {
        reader = source.createReader(serializablePipelineOptions.get().as(PipelineOptions.class));
      } catch (IOException e) {
        throw new RuntimeException("Error creating BoundedReader ", e);
      }
    }

    @Override
    public boolean next() throws IOException {
      if (!started) {
        started = true;
        return reader.start();
      } else {
        return !closed && reader.advance();
      }
    }

    @Override
    public InternalRow get() {
      WindowedValue<T> windowedValue =
          WindowedValue.timestampedValueInGlobalWindow(
              reader.getCurrent(), reader.getCurrentTimestamp());
      return RowHelpers.storeWindowedValueInRow(windowedValue, coder);
    }

    @Override
    public void close() throws IOException {
      closed = true;
      reader.close();
    }
  }
}
