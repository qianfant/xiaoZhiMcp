package me.xiaozhi.mcp.account;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class LegacyClaim {
    private final JdbcTemplate jdbc;
    public LegacyClaim(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public void claim(Long owner) { jdbc.update("UPDATE external_mcp_connections SET owner_id = ? WHERE owner_id IS NULL", owner); }
}
