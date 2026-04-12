package me.xiaozhi.mcp;

import me.xiaozhi.mcp.tool.DemoMcpTools;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.set;

@SpringBootTest(properties = {
        "xiaozhi.bridge.enabled=false",
        "server.port=0"
})
class XiaozhiSpringAiMcpApplicationTests {


    @Qualifier("demoToolCallbackProvider")
    @Autowired
    private ToolCallbackProvider toolCallbackProvider;

    @Test
    void contextLoads() {
        assertThat(toolCallbackProvider.getToolCallbacks()).hasSizeGreaterThan(0);
    }

    @Autowired
    private DemoMcpTools demoMcpTools;
    @Test
    void contextLoads1() {
//        demoMcpTools.sendSimpleEmail("测试","2597334145@qq.com","测试邮件","测试邮件内容");

        System.out.println(demoMcpTools.getMessage("我的表弟的邮箱是"));
    }

}
