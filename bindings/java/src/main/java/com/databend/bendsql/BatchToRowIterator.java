package com.databend.bendsql;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

import com.databend.client.QueryRowField;
import com.databend.client.data.ColumnTypeHandler;
import com.databend.client.data.ColumnTypeHandlerFactory;
import com.google.common.collect.ImmutableList;
import static java.util.Collections.unmodifiableList;


public class BatchToRowIterator implements Iterator<List<Object>> {
    private final Iterator<List<List<String>>> iterator;
    private final List<QueryRowField> schema;

    private List<List<Object>> buffer;
    private int bufferIndex;

    public BatchToRowIterator(Iterator<List<List<String>>> iterator, List<QueryRowField> schema) {
        this.iterator = iterator;
        this.buffer = null;
        this.bufferIndex = 0;
        this.schema = schema;
    }

    boolean isBufferEmpty() {
        return buffer == null || buffer.isEmpty() || bufferIndex >= buffer.size();
    }

    @Override
    public boolean hasNext() {
        if (isBufferEmpty() && iterator.hasNext()) {
            List<List<String>> stringLists  = iterator.next();
            List<List<Object>> objectLists = stringLists.stream()
                .map(list -> new ArrayList<Object>(list))
                .collect(Collectors.toList());
            buffer = parseRawData(schema, objectLists);
        }
        return !isBufferEmpty();
    }

    @Override
    public List<Object> next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }
        return new ArrayList<>(buffer.get(bufferIndex++));
    }


    public static List<List<Object>> parseRawData(List<QueryRowField> schema, List<List<Object>> data) {
        if (data == null || schema == null) {
            return null;
        }
        int index = 0;
        ColumnTypeHandler[] typeHandlers = new ColumnTypeHandler[schema.size()];
        for (QueryRowField field : schema) {
            typeHandlers[index++] = ColumnTypeHandlerFactory.getTypeHandler(field.getDataType());
        }
        // ensure parsed data is thread safe
        ImmutableList.Builder<List<Object>> rows = ImmutableList.builderWithExpectedSize(data.size());
        for (List<Object> row : data) {
            if (row.size() != typeHandlers.length) {
                throw new IllegalArgumentException("row / column does not match schema");
            }
            ArrayList<Object> newRow = new ArrayList<>(typeHandlers.length);
            int column = 0;
            for (Object value : row) {
                if (value != null) {
                    value = typeHandlers[column].parseValue(value);
                }
                newRow.add(value);
                column++;
            }
            rows.add(unmodifiableList(newRow)); // allow nulls in list
        }
        return rows.build();
    }
}

