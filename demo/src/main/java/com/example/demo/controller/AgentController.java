package com.example.demo.controller;

import com.example.demo.model.dto.ApiResponse;
import com.example.demo.model.dto.ChatRequest;
import com.example.demo.service.AgentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.ExecutorService;

@RestController
@RequestMapping("/api/agent")
public class AgentController {
    @Autowired
    private AgentService agentService;
    @Autowired
    @Qualifier("toolExecutorThreadPool")
    private ExecutorService executor;

    @PostMapping("/chat")
    public ApiResponse<String> chat(@RequestBody ChatRequest request) {
        return ApiResponse.ok(agentService.chat(request.getThreadId(), request.getMessage()));
    }
//    @PostMapping("/chat/stream")
//    public SseEmitter chatStream(@RequestBody ChatRequest request){
//        SseEmitter emitter = new SseEmitter(120_000L);
//        executor.execute(() -> {
//            agentService.chatStream(request.getThreadId(), request.getMessage(), emitter);
//        });
//        return emitter;
//    }
    @PostMapping("/chat/stream")
    public SseEmitter chatStream(@RequestBody ChatRequest request){
        SseEmitter emitter = new SseEmitter(120_000L);
        SecurityContext context = SecurityContextHolder.getContext();  // 在 Tomcat 线程捕获
        executor.execute(() -> {
            SecurityContextHolder.setContext(context);                  // 传递到工作线程
            try {
                agentService.chatStream(request.getThreadId(), request.getMessage(), emitter);
            } finally {
                SecurityContextHolder.clearContext();                   // 清理
            }
        });
        return emitter;
    }
}