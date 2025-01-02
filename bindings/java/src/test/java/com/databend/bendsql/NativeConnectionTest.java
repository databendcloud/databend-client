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

import org.junit.jupiter.api.Test;

import com.databend.bendsql.jni_utils.NativeLibrary;

import org.junit.jupiter.api.BeforeEach;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.SQLException;
import java.util.List;

class NativeConnectionTest {
    private NativeConnection connection;
    private static final String TEST_DSN = "databend://root:@localhost:8000/default?sslmode=disable";

    static {
        NativeLibrary.loadLibrary();
    }

    
    @BeforeEach
    void setUp() {
        connection = NativeConnection.of(TEST_DSN);
    }

    @Test
    void testSimpleQuery() {
        String sql = "SELECT 1, 2";
        
        NativeRowBatchIterator result = connection.execute(sql);
        
        assertNotNull(result);
        assertTrue(result.hasNext());
        List<List<String>> batch = result.next();
        assertEquals(1, batch.size());
        assertEquals(2, batch.get(0).size());
        assertEquals("1", batch.get(0).get(0));
        assertEquals("2", batch.get(0).get(1));
        assertFalse(result.hasNext());
    }

    @Test
    void testQueryInvalidQuery() {
        String sql = "INVALID SQL QUERY";
        
        assertThrows(SQLException.class, () -> connection.execute(sql));
    }

    @Test
    void testQueryNullQuery() {
        assertThrows(NullPointerException.class, () -> connection.execute(null));
    }

    @Test
    void testQueryEmptyQuery() {
        String sql = "";
        assertThrows(SQLException.class, () -> connection.execute(sql));
    }

    @Test
    void testMultipleQueriesSequentially() {
        String sql1 = "SELECT 1";
        String sql2 = "SELECT 2";
        
        NativeRowBatchIterator result1 = connection.execute(sql1);
        NativeRowBatchIterator result2 = connection.execute(sql2);
        
        assertNotNull(result1);
        assertNotNull(result2);
    }
} 