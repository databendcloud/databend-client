package com.databend.bendsql;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import static com.databend.bendsql.utils.ResultSetTestUtils.assertResultSet;

public class DatabendDriverTest {
    private static final String TEST_DSN = "jdbc:databend://root:@localhost:8000/default?sslmode=disable";

    @Test
    public void testSimpleSelect() throws Exception {
        try (Connection conn = DriverManager.getConnection(TEST_DSN, null, null);
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT 1, 'hello'")) {

            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
            assertEquals("hello", rs.getString(2));

            assertFalse(rs.next());
        }
    }

    @Test
    public void testBatchInsert() throws Exception {
        try (Connection conn = DriverManager.getConnection(TEST_DSN, null, null);) {
            try(Statement stmt = conn.createStatement();) {
                stmt.execute("create or replace table test_prepare_statement (a int, b string)");
            }

            try(PreparedStatement ps = conn.prepareStatement("insert into test_prepare_statement values");) {
                ps.setInt(1, 1);
                ps.setString(2, "a");
                ps.addBatch();
                ps.setInt(1, 2);
                ps.setString(2, "b");
                ps.addBatch(); 
                int[] ans = ps.executeBatch();
                assertEquals(ans.length, 2);
                //assertEquals(ans[0], 1);
                //assertEquals(ans[1], 1);
                Statement statement = conn.createStatement();

                boolean hasResultSet = statement.execute("SELECT * from test_prepare_statement");
                assertTrue(hasResultSet);
                try(ResultSet rs = statement.getResultSet();) {
                    List<Object[]> expected = Arrays.asList(
                        new Object[]{1, "a"},
                        new Object[]{2, "b"}
                    );
                    assertResultSet(rs, expected);
                }
            }
        }
    }
}
