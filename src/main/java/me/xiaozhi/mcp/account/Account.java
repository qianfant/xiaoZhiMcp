package me.xiaozhi.mcp.account;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

@Data
@TableName("app_users")
public class Account {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String username;
    @JsonIgnore private String passwordHash;
    private String role;
    private Boolean enabled;
    @JsonIgnore private Integer authVersion;
}
