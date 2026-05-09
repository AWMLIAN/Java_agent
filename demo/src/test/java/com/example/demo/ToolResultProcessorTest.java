package com.example.demo;

import com.example.demo.component.ToolResultProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

public class ToolResultProcessorTest {
    private ToolResultProcessor processor;
    @BeforeEach
    void setup(){
        processor=new ToolResultProcessor();
    }
    //toolName rawResult
    @Test
    void shouldPassThroughShortResult(){
        String shortResult="短结果";
        String processed = processor.process("queryRecentOrders", shortResult);
        assertThat(processed).isEqualTo("短结果");
    }
    @Test
    void shouldSummarizeLongOrderResult(){
        StringBuilder sb=new StringBuilder();
        sb.append("[");
        for (int i = 0; i < 100; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"id\":").append(i)
                    .append(",\"orderSn\":\"SN").append(i)
                    .append("\",\"totalAmount\":").append(i * 100)
                    .append(",\"status\":\"已发货\"")
                    .append(",\"createTime\":\"2025-01-01 12:00:00\"}");
        }
        sb.append("]");
        String longResult = sb.toString();
        String processed = processor.process("queryRecentOrders", longResult);
        assertThat(processed).contains("queryRecentOrders查询结果");
        assertThat(processed).contains("订单总数");
        assertThat(processed.length()).isLessThan(longResult.length());
    }
    @Test
    void shouldSummarizeLongProductResult() {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0; i < 100; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"id\":").append(i)
                    .append(",\"name\":\"商品").append(i)
                    .append("\",\"price\":").append(i * 10)
                    .append(",\"stock\":").append(100 - i)
                    .append("}");
        }
        sb.append("]");
        String longResult = sb.toString();

        String processed = processor.process("searchProducts", longResult);

        assertThat(processed).contains("searchProducts搜索结果");
        assertThat(processed).contains("搜索商品总数");
        assertThat(processed.length()).isLessThan(longResult.length());
    }
    @Test
    void shouldFallbackToTruncateForInvalidJson() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 3000; i++) {
            sb.append("x");
        }
        String invalidJson = sb.toString();

        String processed = processor.process("unknownTool", invalidJson);

        assertThat(processed).contains("结果已截断");
        assertThat(processed.length()).isLessThan(invalidJson.length());
    }
    @Test
    void shouldReturnEmptyMessageForNullResult() {
        String processed = processor.process("anyTool", null);
        assertThat(processed).isEqualTo("查询结果为空");
    }

    @Test
    void shouldReturnEmptyMessageForEmptyResult() {
        String processed = processor.process("anyTool", "");
        assertThat(processed).isEqualTo("查询结果为空");
    }
}
