package com.example.demo;

import com.example.demo.mapper.AiConversationMapper;
import com.example.demo.mapper.AiMessageMapper;
import com.example.demo.model.entity.AiConversation;
import com.example.demo.model.entity.AiMessage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
//@Transactional   // 每个测试后自动回滚
public class AiPersistentTest {

    @Autowired
    private AiConversationMapper aiConversationMapper;

    @Autowired
    private AiMessageMapper aiMessageMapper;

    @Test
    void testInsertAndQueryConversation() {
        AiConversation conv = new AiConversation();
        conv.setUserId(1L);                // 自增ID不需要设置
        conv.setTitle("测试会话");
        conv.setThreadId("test-thread-" + System.currentTimeMillis());
        conv.setStatus((byte) 1);
        aiConversationMapper.insert(conv);

        List<AiConversation> list = aiConversationMapper.selectList(null);
        assertThat(list).isNotEmpty();
    }

    @Test
    void testInsertAndQueryMessage() {
        AiConversation conv = new AiConversation();
        conv.setUserId(1L);
        conv.setThreadId("msg-test-" + System.currentTimeMillis());
        conv.setStatus((byte) 1);
        aiConversationMapper.insert(conv);   // 此时 conv.getId() 已自动回填

        AiMessage msg = new AiMessage();
        msg.setConversationId(conv.getId());
        msg.setRole("user");
        msg.setContent("你好");
        msg.setPromptTokens(0);
        msg.setCompletionTokens(0);
        msg.setTotalTokens(0);
        aiMessageMapper.insert(msg);

        AiMessage found = aiMessageMapper.selectById(msg.getId());
        assertThat(found).isNotNull();
        assertThat(found.getContent()).isEqualTo("你好");
    }
}