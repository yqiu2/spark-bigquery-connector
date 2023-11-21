/*
 * Copyright 2018 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.cloud.spark.bigquery;

import static com.google.common.base.Optional.fromJavaUtil;

import com.google.cloud.bigquery.Schema;
import com.google.cloud.bigquery.connector.common.BigQueryStorageReadRowsTracer;
import com.google.cloud.bigquery.storage.v1.ReadRowsResponse;
import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import java.io.Serializable;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import org.apache.spark.sql.catalyst.InternalRow;
import org.apache.spark.sql.types.StructType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public interface ReadRowsResponseToInternalRowIteratorConverter {

  static ReadRowsResponseToInternalRowIteratorConverter avro(
      final Schema bqSchema,
      final List<String> columnsInOrder,
      final String rawAvroSchema,
      final Optional<StructType> userProvidedSchema,
      final Optional<BigQueryStorageReadRowsTracer> bigQueryStorageReadRowsTracer,
      final SchemaConvertersConfiguration schemaConvertersConfiguration) {
    return new Avro(
        bqSchema,
        columnsInOrder,
        rawAvroSchema,
        fromJavaUtil(userProvidedSchema),
        fromJavaUtil(bigQueryStorageReadRowsTracer),
        schemaConvertersConfiguration);
  }

  static ReadRowsResponseToInternalRowIteratorConverter arrow(
      final List<String> columnsInOrder,
      final ByteString arrowSchema,
      final Optional<StructType> userProvidedSchema,
      final Optional<BigQueryStorageReadRowsTracer> bigQueryStorageReadRowsTracer) {
    return new Arrow(
        columnsInOrder,
        arrowSchema,
        fromJavaUtil(userProvidedSchema),
        fromJavaUtil(bigQueryStorageReadRowsTracer));
  }

  Iterator<InternalRow> convert(ReadRowsResponse response);

  int getBatchSizeInBytes(ReadRowsResponse response);

  class Avro implements ReadRowsResponseToInternalRowIteratorConverter, Serializable {

    private final com.google.cloud.bigquery.Schema bqSchema;
    private final List<String> columnsInOrder;
    private final String rawAvroSchema;
    private final com.google.common.base.Optional<StructType> userProvidedSchema;
    private final com.google.common.base.Optional<BigQueryStorageReadRowsTracer>
        bigQueryStorageReadRowsTracer;
    private final SchemaConvertersConfiguration schemaConvertersConfiguration;

    public Avro(
        Schema bqSchema,
        List<String> columnsInOrder,
        String rawAvroSchema,
        com.google.common.base.Optional<StructType> userProvidedSchema,
        com.google.common.base.Optional<BigQueryStorageReadRowsTracer>
            bigQueryStorageReadRowsTracer,
        SchemaConvertersConfiguration schemaConvertersConfiguration) {
      this.bqSchema = bqSchema;
      this.columnsInOrder = columnsInOrder;
      this.rawAvroSchema = rawAvroSchema;
      this.userProvidedSchema = userProvidedSchema;
      this.bigQueryStorageReadRowsTracer = bigQueryStorageReadRowsTracer;
      this.schemaConvertersConfiguration = schemaConvertersConfiguration;
    }

    @Override
    public Iterator<InternalRow> convert(ReadRowsResponse response) {
      return new AvroBinaryIterator(
          bqSchema,
          columnsInOrder,
          new org.apache.avro.Schema.Parser().parse(rawAvroSchema),
          response.getAvroRows().getSerializedBinaryRows(),
          userProvidedSchema.toJavaUtil(),
          bigQueryStorageReadRowsTracer.toJavaUtil(),
          schemaConvertersConfiguration);
    }

    @Override
    public int getBatchSizeInBytes(ReadRowsResponse response) {
      return response.getAvroRows().getSerializedBinaryRows().size();
    }
  }

  class Arrow implements ReadRowsResponseToInternalRowIteratorConverter, Serializable {

    private final List<String> columnsInOrder;
    private final ByteString arrowSchema;
    private final com.google.common.base.Optional<StructType> userProvidedSchema;
    private final com.google.common.base.Optional<BigQueryStorageReadRowsTracer>
        bigQueryStorageReadRowsTracer;

    private static final Logger log = LoggerFactory.getLogger(Arrow.class);

    int decompressedArrowRecordBatchSize;
    ByteString decompressedArrowRecordBatch;

    public Arrow(
        List<String> columnsInOrder,
        ByteString arrowSchema,
        com.google.common.base.Optional<StructType> userProvidedSchema,
        com.google.common.base.Optional<BigQueryStorageReadRowsTracer>
            bigQueryStorageReadRowsTracer) {
      this.columnsInOrder = columnsInOrder;
      this.arrowSchema = arrowSchema;
      this.userProvidedSchema = userProvidedSchema;
      this.bigQueryStorageReadRowsTracer = bigQueryStorageReadRowsTracer;
      // TODO(AQIU): get this field that does not yet exist
      // this.decompressed_byte_size = 0;
    }

    @Override
    public Iterator<InternalRow> convert(ReadRowsResponse response) {
      // TODO: AQIU: this is not hit! his is not where we should decompress!
      UnknownFieldSet unknownFieldSet = response.getUnknownFields();
      java.util.Map<Integer, UnknownFieldSet.Field> unknownFieldSetMap = unknownFieldSet.asMap();
      log.info(
          "AQIU: ReadRowsResponseToInternalRowIteratorConverter ReadRowsResponse"
              + " UnknownFieldSet.asMap {}",
          unknownFieldSetMap);
      log.info(
          "AQIU: ReadRowsResponseToInternalRowIteratorConverter serializedRecordBatch",
          response.getArrowRecordBatch().getSerializedRecordBatch());

      /*
      // hack: assume that we are always asking for Snappy compression
      ByteString arrowRecordBatch;
      // TODO(AQIU): the new ReadRowsResponse field does not yet exist
      int statedUncompressedSize = response.getUncompressedByteSize();
      if (statedUncompressedSize > 0) {
        // the result was compressed, we need to decompress it.
        if (decompressedArrowRecordBatchSize > 0) {
          // it has already been decompressed.  use the decompressed result
          arrowRecordBatch = decompressedArrowRecordBatch;
        } else {
          try {
            ByteBuffer arrowRecordBatchBuffer = ByteBuffer.allocate(
                response.getUncompressedBytesSize());
            // https://cloud.google.com/java/docs/reference/protobuf/latest/com.google.protobuf.ByteString#com_google_protobuf_ByteString_asReadOnlyByteBuffer__
            // Use asReadOnlyByteBuffer() because it tries to avoid a copy Byte[].  The result uses the same backing array as the byte string, if possible.
            // ByteBuffer arrowRecordBatchBuffer = ByteBuffer.allocate(Snappy.uncompressedLength(
            //     response.getArrowRecordBatch().getSerializedRecordBatch().asReadOnlyByteBuffer()));
            decompressedArrowRecordBatchSize = Snappy.uncompress(
                response.getArrowRecordBatch().getSerializedRecordBatch().asReadOnlyByteBuffer(),
                arrowRecordBatchBuffer);
            // assert(decompressedArrowRecordBatchSize > 0);
            // assert(decompressedArrowRecordBatch == statedUncompressedSize);
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
          decompressedArrowRecordBatch = ByteString.copyFrom(arrowRecordBatchBuffer);
          arrowRecordBatch = decompressedArrowRecordBatch;
        }
      }


      return new ArrowBinaryIterator(
          columnsInOrder,
          arrowSchema,
          arrowRecordBatch,
          userProvidedSchema.toJavaUtil(),
          bigQueryStorageReadRowsTracer.toJavaUtil());
      */

      return new ArrowBinaryIterator(
          columnsInOrder,
          arrowSchema,
          response.getArrowRecordBatch().getSerializedRecordBatch(),
          userProvidedSchema.toJavaUtil(),
          bigQueryStorageReadRowsTracer.toJavaUtil());
    }

    @Override
    public int getBatchSizeInBytes(ReadRowsResponse response) {
      return response.getArrowRecordBatch().getSerializedRecordBatch().size();
    }
  }
}
