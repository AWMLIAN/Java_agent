package com.example.demo.controller;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/ai")
public class TranslationController {
    @Autowired
    private ChatModel chatModel;
    @PostMapping("/translate")
    public String translate(@RequestBody String text){
        //构建提示词模版（prompt Template）
        String systemPrompt="你是一个专业的翻译助手,将用户输入的中文翻译为英文。";
        //组合消息
        List<ChatMessage> messages = List.of(
                SystemMessage.from(systemPrompt),
                UserMessage.from(text)
        );
        String response = chatModel.chat(messages).aiMessage().text();
        return response.trim();
    }
}
