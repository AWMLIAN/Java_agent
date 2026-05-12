package com.example.demo;

import com.example.demo.mapper.PmsProductCategoryMapper;
import com.example.demo.mapper.PmsProductMapper;
import com.example.demo.model.dto.KnowledgeResult;
import com.example.demo.model.enums.KnowledgeType;
import com.example.demo.service.impl.KnowledgeBaseServiceImpl;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.List;

public class KnowledgeBaseServiceImplTest {
    @Mock
    private EmbeddingModel embeddingModel;
    @Mock
    private PmsProductMapper pmsProductMapper;
    @Mock
    private PmsProductCategoryMapper categoryMapper;
    //注入模拟依赖
    @InjectMocks
    private KnowledgeBaseServiceImpl knowledgeBaseService;
    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void shouldReturnEmptyWhenNotReady(){
        List<KnowledgeResult> results = knowledgeBaseService.search("手机", 5);
        assertThat(results).isEmpty();
    }
    @Test
    void shouldReturnResultsWhenReady() throws Exception{
        Field readyFiled = KnowledgeBaseServiceImpl.class.getDeclaredField("ready");
        //强制开启
        readyFiled.setAccessible(true);
        readyFiled.set(knowledgeBaseService,true);
        // 2. 往 embeddingStore 里塞一条数据
        var storeField = KnowledgeBaseServiceImpl.class.getDeclaredField("embeddingStore");
        storeField.setAccessible(true);
        @SuppressWarnings("unchecked")
        var store = (InMemoryEmbeddingStore<TextSegment>) storeField.get(knowledgeBaseService);

        var segment = TextSegment.from("商品名称: iPhone 15",
                dev.langchain4j.data.document.Metadata
                        .from("type", "PRODUCT")
                        .put("id", "PRODUCT:1")
                        .put("productId", "1")
                        .put("productName", "iPhone 15")
                        .put("price", "6999")
                        .put("brandName", "苹果"));
        var embedding = Embedding.from(new float[1024]);
        store.add(embedding, segment);
        // 3. mock embedModel 返回一个假 embedding
        when(embeddingModel.embed(any(String.class)))
                .thenReturn(Response.from(Embedding.from(new float[1024])));
        // 4. 检索
        List<KnowledgeResult> results = knowledgeBaseService.search("手机", 5);

        // 5. 断言
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getProductName()).isEqualTo("iPhone 15");
        assertThat(results.get(0).getType()).isEqualTo(KnowledgeType.PRODUCT);
        assertThat(results.get(0).getId()).isEqualTo("PRODUCT:1");
    }

    @Test
    void shouldSkipBuildWhenAlreadyReady() throws Exception {
        // 设 ready=true
        var readyField = KnowledgeBaseServiceImpl.class.getDeclaredField("ready");
        readyField.setAccessible(true);
        readyField.set(knowledgeBaseService, true);

        // 调 buildIndex，应该直接返回（日志含 "already ready, skip build"）
        knowledgeBaseService.buildIndex();

        // pmsProductMapper 未被调用
        // 如果走到了 selectList(null)，Mockito 默认返回 null，后面会 NPE——没 NPE 说明幂等生效
    }
}
