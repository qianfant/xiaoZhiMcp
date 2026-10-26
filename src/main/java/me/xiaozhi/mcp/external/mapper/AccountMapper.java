package me.xiaozhi.mcp.external.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import me.xiaozhi.mcp.account.Account;
import org.apache.ibatis.annotations.Select;

public interface AccountMapper extends BaseMapper<Account> {
    // Serializes initial setup and administrator changes across application instances.
    @Select("SELECT id FROM app_locks WHERE id = 1 FOR UPDATE")
    Long lockAdministration();
}
