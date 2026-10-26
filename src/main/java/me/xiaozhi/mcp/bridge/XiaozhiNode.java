package me.xiaozhi.mcp.bridge;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

@Data
@TableName("xiaozhi_nodes")
public class XiaozhiNode {
    @TableId(type = IdType.AUTO) private Long id;
    private Long ownerId;
    private String name;
    private String endpoint;
    @com.fasterxml.jackson.annotation.JsonIgnore private String endpointHash;
    private Boolean enabled;
    private Boolean localTools;
    private String connectionIds;
}
