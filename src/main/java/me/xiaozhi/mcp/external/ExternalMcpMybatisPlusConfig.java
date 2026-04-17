package me.xiaozhi.mcp.external;

import javax.sql.DataSource;

import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration
@MapperScan(
        basePackages = "me.xiaozhi.mcp.external.mapper",
        sqlSessionFactoryRef = "externalMcpSqlSessionFactory")
@ConditionalOnProperty(prefix = "xiaozhi.external-mcp.database", name = "enabled", havingValue = "true")
public class ExternalMcpMybatisPlusConfig {

    @Bean("externalMcpDataSource")
    public DataSource externalMcpDataSource(ExternalMcpProperties properties) {
        ExternalMcpProperties.DatabaseProperties database = properties.getDatabase();
        if (!StringUtils.hasText(database.getUrl())
                || !StringUtils.hasText(database.getUsername())
                || !StringUtils.hasText(database.getPassword())) {
            throw new IllegalStateException("外部 MCP 数据库已启用，但 url/username/password 配置不完整");
        }

        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(database.getUrl());
        dataSource.setUsername(database.getUsername());
        dataSource.setPassword(database.getPassword());
        return dataSource;
    }

    @Bean("externalMcpSqlSessionFactory")
    public SqlSessionFactory externalMcpSqlSessionFactory(
            @Qualifier("externalMcpDataSource") DataSource dataSource) throws Exception {
        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        return factoryBean.getObject();
    }
}
