package com.example.demo.service;

import com.example.demo.model.dto.KnowledgeResult;

import java.util.List;

public interface KnowledgeBaseService {
    //构建知识库索引，每次启动自动调用
    void buildIndex();
    //语义检索，返回匹配的知识条目列表
    List<KnowledgeResult> search(String query,int topk);
    //索引是否已就绪
    boolean isReady();
    void rebuild();
}
