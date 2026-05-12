package com.example.demo.service.impl;

import com.example.demo.mapper.PmsProductCategoryMapper;
import com.example.demo.mapper.PmsProductMapper;
import com.example.demo.model.dto.KnowledgeResult;
import com.example.demo.model.entity.PmsProduct;
import com.example.demo.model.entity.PmsProductCategory;
import com.example.demo.model.enums.KnowledgeType;
import com.example.demo.service.KnowledgeBaseService;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
public class KnowledgeBaseServiceImpl implements KnowledgeBaseService {
    @Autowired
    private EmbeddingModel embeddingModel;
    @Autowired
    private PmsProductMapper pmsProductMapper;
    @Autowired
    private PmsProductCategoryMapper categoryMapper;
    //模型一次embedding 的数据
    private static final int BATCH_SIZE=5;
    //召回
    private static final int DEFAULT_TOP_K=5;
    //分数阈值，bge-m3采用的余弦判断，分数低于阈值的认为是噪声
    private static final double MIN_SCORE=0.3;
    private volatile boolean ready = false;
    private final InMemoryEmbeddingStore<TextSegment> embeddingStore = new InMemoryEmbeddingStore<>();

    @PostConstruct
    public void init(){
        log.info("[KnowledgeBase] will build index in background");
        asyncBuild();
    }

    private void asyncBuild() {
        Thread t = new Thread(() -> {
            try {
                // bge-m3 首次加载到 GPU 约需 25 秒，预热触发加载，后续批次 2 秒内完成
                log.info("[KnowledgeBase] warming up embedding model...");
                embeddingModel.embed("warmup").content();
                log.info("[KnowledgeBase] warmup done, starting build");
                buildIndex();
            } catch (Exception e) {
                log.warn("[KnowledgeBase] async build failed: {}", e.getMessage());
            }
        }, "kb-builder");
        t.setDaemon(true);
        t.start();

    }

    @Override
    public void buildIndex() {
        if(ready){
            log.info("[KnowledgeBase] already ready, skip build");
            return;
        }
        //获取所有商品
        List<PmsProduct> products = pmsProductMapper.selectList(null);
        if(products==null||products.isEmpty()){
            log.info("[KnowledgeBase] no products found, skip build");
            return;
        }
        log.info("[KnowledgeBase] build start products={}", products.size());
        long startMs = System.currentTimeMillis();
        ArrayList<TextSegment> segments = new ArrayList<>();
        //文本化+分片
        for (PmsProduct p : products) {
            String text=buildProductText(p);
            TextSegment segment = TextSegment.from(text,
                    Metadata.from("type", "PRODUCT")
                            .put("id", "PRODUCT:" + p.getId())
                            .put("productId", p.getId().toString())
                            .put("productName", p.getName() != null ? p.getName() : "")
                            .put("price", p.getPrice()!=null?p.getPrice().toString():"0")
                            .put("brandName", p.getBrandName() != null ? p.getBrandName() : ""));
                    segments.add(segment);
        }
        segments.add(TextSegment.from(
                "[退货政策] 用户在收到商品后7天内，商品保持完好、不影响二次销售的情况下，可申请无理由退货。" +
                        "退货运费由用户承担，除非商品存在质量问题。退货流程：在订单详情页点击\"申请退货\"→填写退货原因→等待审核→寄回商品→商家确认收货后3个工作日内退款。" +
                "生鲜、定制类商品不支持7天无理由退货。",
                Metadata.from("type", "POLICY")
                        .put("id", "POLICY:return")
                        .put("productId", "")
                        .put("productName", "")
                        .put("price", "0")
                        .put("brandName", "")
        ));

        segments.add(TextSegment.from(
                "[换货政策] 商品存在质量问题时，用户可在收到商品后15天内申请换货。" +
                        "换货流程：提交换货申请→商家审核→寄回问题商品→商家发出新商品。换货运费由商家承担。" +
                        "若同款商品已售罄，可选择换购同等价位其他商品或退款。",
                Metadata.from("type", "POLICY")
                        .put("id", "POLICY:exchange")
                        .put("productId", "").put("productName", "").put("price", "0").put("brandName", "")
        ));

        segments.add(TextSegment.from(
                "[保修政策] 电子产品类商品自购买之日起享受1年免费保修服务。" +
                        "保修范围：非人为因素导致的功能性故障。不保修范围：人为损坏、进水、私自拆修、超出保修期。" +
                        "保修方式：用户联系在线客服→描述故障→寄回维修→维修完成后寄回用户。保修期内维修免费，往返运费由商家承担。",
                Metadata.from("type", "POLICY")
                        .put("id", "POLICY:warranty")
                        .put("productId", "").put("productName", "").put("price", "0").put("brandName", "")
        ));

        segments.add(TextSegment.from(
                "[退款时效] 未发货退款：支付后30分钟内自动退款到原支付账户。已发货退货退款：商家签收退货商品后，1-3个工作日内退款。"
                        +
                        "退款路径：退回原支付账户（微信/支付宝/银行卡）。超过7天未收到退款，联系在线客服查询。",
                Metadata.from("type", "POLICY")
                        .put("id", "POLICY:refund")
                        .put("productId", "").put("productName", "").put("price", "0").put("brandName", "")
        ));

        segments.add(TextSegment.from(
                "[订单状态说明] 0-待付款：订单已创建，等待用户付款，超过30分钟未付款自动取消。" +
                        "1-待发货：已付款，商家备货中，通常24小时内发出。" +
                        "2-已发货：商品已出库，快递运输中，可在订单详情查看物流轨迹。" +
                        "3-已完成：用户已确认收货或发货后15天系统自动确认。" +
                        "4-已关闭：订单取消或退款完成。5-售后中：已发起退货/换货申请，处理中。",
                Metadata.from("type", "ORDER_HELP")
                        .put("id", "ORDER_HELP:order-status")
                        .put("productId", "").put("productName", "").put("price", "0").put("brandName", "")
        ));

        segments.add(TextSegment.from(
                "[发货与物流] 全国包邮（港澳台及偏远地区除外）。发货时间：工作日下单后24小时内发出，节假日顺延。" +
                        "合作快递：顺丰、中通、圆通、韵达。物流查询：在订单详情页可实时查看快递轨迹。如超过48小时未发货，可联系客服催单。",
                Metadata.from("type", "POLICY")
                        .put("id", "POLICY:shipping")
                        .put("productId", "")
                        .put("productName", "")
                        .put("price", "0")
                        .put("brandName", "")
        ));
        List<PmsProductCategory> categories = categoryMapper.selectList(null);
        if (categories != null) {
            for (PmsProductCategory c : categories) {
                String text = "[商品分类] 分类名称：" + c.getName()
                        + "，层级：第" + c.getLevel() + "级"
                        + (c.getProductCount() != null ? "，商品数量：" + c.getProductCount() : "");
                segments.add(TextSegment.from(text,
                        Metadata.from("type", "CATEGORY")
                                .put("id", "CATEGORY:" + c.getId())
                                .put("productId", "").put("productName", "")
                                .put("price", "0").put("brandName", "")
                ));
            }
            log.info("[KnowledgeBase] added {} categories", categories.size());
        }


        //embedding
        for (int i = 0; i < segments.size(); i += BATCH_SIZE) {
            try {
                Thread.sleep(200);  // 200ms 间隔，避免 API 请求过快
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            int end = Math.min(i + BATCH_SIZE, segments.size());
            List<TextSegment> batch = segments.subList(i, end);
            try {
                List<Embedding> embeddings = embeddingModel.embedAll(batch).content();
                embeddingStore.addAll(embeddings, batch);
                log.info("[KnowledgeBase] batch {}/{} done", end, segments.size());
            } catch (Exception e) {
                log.warn("[KnowledgeBase] batch {}/{} failed, skip: {}", end, segments.size(), e.getMessage());
            }
        }
        ready=true;
        long elapsed = System.currentTimeMillis() - startMs;
        log.info("[KnowledgeBase] build done segments={} elapsed={}ms", segments.size(), elapsed);

    }

    private String buildProductText(PmsProduct p) {
        StringBuilder sb = new StringBuilder();
        sb.append("商品名称: ").append(nullToEmpty(p.getName())).append("\n");
        sb.append("品牌: ").append(nullToEmpty(p.getBrandName())).append("\n");
        sb.append("分类: ").append(nullToEmpty(p.getProductCategoryName())).append("\n");
        sb.append("关键词: ").append(nullToEmpty(p.getKeywords())).append("\n");
        sb.append("描述: ").append(nullToEmpty(p.getDescription()));
        return sb.toString();
    }
    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    @Override
    public List<KnowledgeResult> search(String query, int topK) {
        if(!ready){
            return List.of();
        }
        int k=topK>0?topK:DEFAULT_TOP_K;
        Embedding queryEmbedding = embeddingModel.embed(query).content();
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(k)
                .minScore(MIN_SCORE)
                .build();
        List<EmbeddingMatch<TextSegment>> matches = embeddingStore.search(request).matches();
        ArrayList<KnowledgeResult> results = new ArrayList<>();
        for (EmbeddingMatch<TextSegment> match : matches) {
            TextSegment segment = match.embedded();
            Metadata metadata = segment.metadata();
            KnowledgeResult knowledgeResult = new KnowledgeResult(
                    metadata.getString("id"),
                    KnowledgeType.valueOf(metadata.getString("type")),
                    metadata.containsKey("productId")&& !metadata.getString("productId").isEmpty()  ? Long.parseLong(metadata.getString("productId")) : null,
                    metadata.getString("productName"),
                    metadata.containsKey("price")&& !metadata.getString("price").isEmpty() ? new BigDecimal(metadata.getString("price")) : null,
                    metadata.getString("brandName"),
                    segment.text(),
                    match.score()
            );
            results.add(knowledgeResult);
        }
        return results;
    }

    @Override
    public boolean isReady() {
        return ready;
    }

    @Override
    public void rebuild() {
        ready=false;
        asyncBuild();
    }
}
