/*
 * Copyright 2021 Datafuse Labs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.databend.bendsql;

import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.databend.bendsql.jni_utils.NativeObject;
import com.databend.client.QueryRowField;
import com.databend.client.data.DatabendRawType;


public class NativeRowBatchIterator extends NativeObject implements Iterator<List<List<String>>> {
    private final long connectionHandle;
    private final NativeConnection connection;

    private boolean isFinished;
    private String currentRowBatch;
    private List<QueryRowField> schema;
    ObjectMapper objectMapper = new ObjectMapper();

    static class Field {
        public String name;
        public String type;
    }

    public NativeRowBatchIterator(long nativeHandle, long executorHandle, NativeConnection connection) {
        super(nativeHandle, executorHandle);
        this.connectionHandle = nativeHandle;
        this.connection = connection;
        this.isFinished = false;
        String schemaString = getSchema(nativeHandle);
        if (schemaString!= null) {
            try {
                List<Field> schemaRaw = objectMapper.readValue(schemaString, new TypeReference<List<Field>>() {
                });
                this.schema = schemaRaw.stream()
                        .map(field -> new QueryRowField(field.name, new DatabendRawType(field.type)))
                        .collect(Collectors.toList());
            } catch (JsonProcessingException e) {
                e.printStackTrace();
                throw new RuntimeException("Failed to parse JSON schema: " + e.getMessage(), e);
            }
        } else {
            this.schema = null;
        }
    }

    public List<QueryRowField> getSchema() {
        return schema;
    }

    public boolean hasNext() {
        if (currentRowBatch == null) {
            currentRowBatch = fetchNextRowBatch(nativeHandle, executorHandle);
        }
        if (currentRowBatch == null) {
            isFinished = true;
            return false;
        }
        return true;
    }

    @Override
    public void close() {
        super.close();
        connection.close_result(nativeHandle);
    }

    @Override
    public List<List<String>> next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }
        List<List<String>> rows;
        try {
            rows = objectMapper.readValue(currentRowBatch, new TypeReference<List<List<String>>>() {
            });
        } catch (JsonProcessingException e) {
            e.printStackTrace();
            throw new RuntimeException("Failed to parse JSON data: " + e.getMessage(), e);
        }
        currentRowBatch = null;

        return rows;
    }

    private native String fetchNextRowBatch(long nativeHandle, long executorHandle);

    private native String getSchema(long nativeHandle);

    protected native void disposeInternal(long handle, long executorHandle);
}
