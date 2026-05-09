package com.example.demo;


import com.example.demo.component.ToolExecutor;
import com.example.demo.component.ToolResult;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;


public class ToolExecutorTest {
    private ToolExecutor toolExecutor;
    // 模拟一个有 @Tool 方法的类
    public static class FakeTool {
        public String searchProducts(String arguments) {
            return "[{\"id\":1,\"name\":\"测试商品\"}]";
        }

        public String slowTool(String arguments) throws InterruptedException {
            Thread.sleep(20000);
            return "[]";
        }
    }
    @BeforeEach
    void setup(){
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                3, 3, 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(100),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        toolExecutor=new ToolExecutor(executor);
    }
    //execute requests,toolInstances,toolMethods,traceId
    @Test
    void shouldExecuteSingleToolSuccessfully() throws Exception{
        String traceId="trace-001";
        Method method = FakeTool.class.getMethod("searchProducts", String.class);
        Map<String, Method> toolMethods = Map.of("searchProducts", method);
        Object toolInstance = new FakeTool();
        Map<String, Object> toolInstances = Map.of("searchProducts", toolInstance);
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("call_1")
                .arguments("{\"keyword\":\"测试\"}")
                .name("searchProducts")
                .build();
        List<ToolResult> results = toolExecutor.execute(List.of(request), toolInstances, toolMethods, traceId);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).isSuccess()).isTrue();
        assertThat(results.get(0).getMessage().text()).contains("测试商品");
    }
    @Test
    void shouldDedupSameToolAndArgs() throws Exception {
        Object toolInstance = new FakeTool();
        Map<String, Object> toolInstances = Map.of("searchProducts", toolInstance);
        Map<String, Method> toolMethods = Map.of(
                "searchProducts", FakeTool.class.getMethod("searchProducts", String.class)
        );

        ToolExecutionRequest req1 = ToolExecutionRequest.builder()
                .id("call-1").name("searchProducts").arguments("{\"keyword\":\"测试\"}").build();
        ToolExecutionRequest req2 = ToolExecutionRequest.builder()
                .id("call-2").name("searchProducts").arguments("{\"keyword\":\"测试\"}").build();

        List<ToolResult> results = toolExecutor.execute(
                List.of(req1, req2), toolInstances, toolMethods, "trace-002"
        );

        // 两个结果，但实际只执行一次（去重基于 toolName + 标准化参数）
        assertThat(results).hasSize(2);
    }
    @Test
    void shouldHandleUnknownTool() {
        Map<String, Object> toolInstances = Collections.emptyMap();
        Map<String, Method> toolMethods = Collections.emptyMap();

        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("call-1")
                .name("unknownTool")
                .arguments("{}")
                .build();

        List<ToolResult> results = toolExecutor.execute(
                List.of(request), toolInstances, toolMethods, "trace-003"
        );

        assertThat(results).hasSize(1);
        assertThat(results.get(0).isSuccess()).isFalse();
        assertThat(results.get(0).getMessage().text()).contains("工具不存在");
    }
    @Test
    void shouldHandleTimeout() throws Exception {
        Object toolInstance = new FakeTool();
        Map<String, Object> toolInstances = Map.of("slowTool", toolInstance);
        Map<String, Method> toolMethods = Map.of(
                "slowTool", FakeTool.class.getMethod("slowTool", String.class)
        );

        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("call-1")
                .name("slowTool")
                .arguments("{}")
                .build();

        List<ToolResult> results = toolExecutor.execute(
                List.of(request), toolInstances, toolMethods, "trace-004"
        );

        assertThat(results).hasSize(1);
        assertThat(results.get(0).isSuccess()).isFalse();
    }
}
