package me.xiaozhi.mcp.tool;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class DemoMcpTools {


    private final OpenAiEmbeddingModel openAiEmbeddingModel;
    public final EmbeddingStore<TextSegment> qdEmbeddingStore;
    private final JavaMailSender javaMailSender;
    /**
     * 发送简单文本邮件
     *
     * @ToolParamaram to      邮件接收者邮箱地址
     * @ToolParamaram subject 邮件主题
     * @ToolParamaram content 邮件正文内容
     */
    @Tool(name = "发送邮件",description = "根据用户的提供的信息发送邮件,如果邮件主题默认是玲珑女仆向你问好。")
    public String sendSimpleEmail(@ToolParam(description = "接收人身份")String author,@ToolParam(description = "接收人邮件")String to, @ToolParam(description = "邮件主题")String subject, @ToolParam(description = "邮件内容")String content) {
        try {
//            aiTestService.saveLog(author + "的邮箱号码是:" + to);
            // 创建简单邮件消息对象并设置邮件内容
            SimpleMailMessage message = new SimpleMailMessage();
            message.setTo(to);
            message.setSubject(subject);
            message.setText(content);
//            message.set
            message.setFrom("玲珑女仆 <2597334145@qq.com>");

            // 发送邮件
            javaMailSender.send(message);
            log.info("邮件发送成功");
            return "邮件发送成功";
        }catch (Exception e){
            log.error("邮件发送失败",e);
            return "邮件发送失败";
        }
    }

    @Tool(name = "记录一下",description = "记录主人的感想和分享的一些生活琐事")
    public String saveMessage(@ToolParam(description = "记录内容")String message,@ToolParam(description = "记录内容的标识,方便理解记录的内容是什么")String auth) {
        saveLog(auth+message,0L);
        return "记录成功";
    }

    public void saveLog(String words,Long userId){
        TextSegment segment1 = TextSegment.from(words);
        segment1.metadata().put("author", userId);
        Embedding embedding1 = openAiEmbeddingModel.embed(segment1).content();
        qdEmbeddingStore.add(embedding1, segment1);
    }

    @Tool(name = "获取记录信息",description = "获取之前记录的主人的感想和主人的一些个人信息和人际关系")
    public String getMessage(@ToolParam(description = "记录内容")String message) {
        return getZhuLog(message);
    }

    private String getZhuLog(String message) {
        Embedding queryEmbedding = openAiEmbeddingModel.embed(message).content();
        //创建搜索请求对象
        EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(1) //匹配最相似的一条记录
                //.minScore(0.8)  向量得分最高1 越接近1越相似
                .build();
        //根据搜索请求 searchRequest 在向量存储中进行相似度搜索
        EmbeddingSearchResult<TextSegment> searchResult =
                qdEmbeddingStore.search(searchRequest);
        //searchResult.matches()：获取搜索结果中的匹配项列表。
        //.get(0)：从匹配项列表中获取第一个匹配项
        EmbeddingMatch<TextSegment> embeddingMatch = searchResult.matches().get(0);
        //获取匹配项的相似度得分
        log.info("获取匹配项的相似度得分:{}",embeddingMatch.score()); // 0.8144288515898701
        //返回文本结果
        return embeddingMatch.embedded().text();
    }

    @Tool(name = "获取今天星期几",description = "获取今天星期是星期几")
    public String getWeekday() {
        // 方式2：获取中文星期（完整/缩写）
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("EEEE", Locale.CHINA); // 完整星期：星期一
        String chineseWeekday = LocalDate.now().format(formatter);
        return chineseWeekday;
    }
}
