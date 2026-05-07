package com.example.demo;

import com.example.demo.model.bo.AdminUserDetails;
import com.example.demo.model.entity.UmsAdmin;
import com.example.demo.service.AgentService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

@SpringBootTest
public class AgentEvaluationTest {

    @Autowired
    private AgentService agentService;

    private List<TestCase> testCases;

    @BeforeEach
    void setUp() throws IOException {
        // 1. 加载测试用例
        ObjectMapper mapper = new ObjectMapper();
        InputStream is = getClass().getClassLoader().getResourceAsStream("eval-test-cases.json");
        testCases = mapper.readValue(is, new TypeReference<List<TestCase>>() {});

        // 2. 手动设置测试用户认证信息（模拟已登录）
        UmsAdmin testAdmin = new UmsAdmin();
        testAdmin.setId(1L);           // 测试用户ID，需与数据库中存在的一致
        testAdmin.setUsername("test");
        AdminUserDetails userDetails = new AdminUserDetails(testAdmin);
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext(); // 清理认证状态
    }

    @Test
    void evaluateAllCases() {
        int total = testCases.size();
        int rulePassed = 0;
        for (TestCase tc : testCases) {
            String reply = agentService.chat(tc.threadId, tc.question);
            boolean pass = tc.mustContain.stream().allMatch(reply::contains)
                    && tc.mustNotContain.stream().noneMatch(reply::contains);
            if (pass) rulePassed++;
            System.out.printf("[%s] %s -> %s%n", pass ? "PASS" : "FAIL", tc.question,
                    reply.substring(0, Math.min(100, reply.length())));
        }
        System.out.printf("通过率: %d/%d = %d%%%n", rulePassed, total, rulePassed * 100 / total);
    }

    // 内部类对应 JSON 结构
    static class TestCase {
        public int id;
        public String threadId;
        public String question;
        public List<String> mustContain;
        public List<String> mustNotContain;
        public String expectedTool;
        public List<String> shouldNotCall;
    }
}
