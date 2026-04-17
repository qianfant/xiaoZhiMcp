package me.xiaozhi.mcp.external.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import me.xiaozhi.mcp.external.entity.ExternalMcpConnection;
import me.xiaozhi.mcp.external.mapper.ExternalMcpConnectionMapper;
import me.xiaozhi.mcp.external.service.ExternalMcpConnectionService;
import org.springframework.stereotype.Service;

@Service
public class ExternalMcpConnectionServiceImpl
        extends ServiceImpl<ExternalMcpConnectionMapper, ExternalMcpConnection>
        implements ExternalMcpConnectionService {
}
