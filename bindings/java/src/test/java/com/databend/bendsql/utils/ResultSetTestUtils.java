package com.databend.bendsql.utils;

import org.junit.jupiter.api.Assertions;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

public class ResultSetTestUtils {
    
    /**
     * 验证 ResultSet 的内容是否与预期匹配
     * 
     * @param rs ResultSet 实例
     * @param expectedRows 预期的数据行，每行是一个 Object 数组
     * @throws SQLException 如果访问 ResultSet 出错
     */
    public static void assertResultSet(ResultSet rs, List<Object[]> expectedRows) throws SQLException {
        int rowNum = 0;
        while (rs.next()) {
            Assertions.assertTrue(rowNum < expectedRows.size(), 
                "Got more rows than expected. Expected " + expectedRows.size() + " rows");
            
            Object[] expectedRow = expectedRows.get(rowNum);
            for (int i = 0; i < expectedRow.length; i++) {
                Object expected = expectedRow[i];
                Object actual = rs.getObject(i + 1);
                Assertions.assertEquals(expected, actual, 
                    String.format("Row %d, Column %d mismatch", rowNum + 1, i + 1));
            }
            rowNum++;
        }
        Assertions.assertEquals(expectedRows.size(), rowNum, 
            "Got fewer rows than expected. Expected " + expectedRows.size() + " rows");
    }

    /**
     * 验证 ResultSet 的行数
     * 
     * @param rs ResultSet 实例
     * @param expectedCount 预期的行数
     * @throws SQLException 如果访问 ResultSet 出错
     */
    public static void assertRowCount(ResultSet rs, int expectedCount) throws SQLException {
        int rowCount = 0;
        while (rs.next()) {
            rowCount++;
        }
        Assertions.assertEquals(expectedCount, rowCount, 
            String.format("Expected %d rows but got %d", expectedCount, rowCount));
    }
} 