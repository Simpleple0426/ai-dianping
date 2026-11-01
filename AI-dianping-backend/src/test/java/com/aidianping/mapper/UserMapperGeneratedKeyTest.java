package com.aidianping.mapper;

import com.aidianping.entity.User;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UserMapperGeneratedKeyTest {
    @Test
    void insertReturnsDatabaseGeneratedIdToTheSameUserObject() throws Exception {
        // 执行真实 Mapper XML 和 MyBatis 主键回填，仅模拟 JDBC 数据库边界。
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet keys = mock(ResultSet.class);
        ResultSetMetaData metadata = mock(ResultSetMetaData.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.prepareStatement(anyString(), aryEq(new String[]{"id"})))
                .thenReturn(statement);
        when(statement.getUpdateCount()).thenReturn(1);
        when(statement.getGeneratedKeys()).thenReturn(keys);
        when(keys.getMetaData()).thenReturn(metadata);
        when(metadata.getColumnCount()).thenReturn(1);
        when(metadata.getColumnType(1)).thenReturn(Types.BIGINT);
        when(keys.next()).thenReturn(true, false);
        when(keys.getLong(1)).thenReturn(1001L);

        Configuration configuration = new Configuration(new Environment(
                "generated-key-test", new JdbcTransactionFactory(), dataSource));
        try (InputStream xml = Resources.getResourceAsStream("mapper/UserMapper.xml")) {
            new XMLMapperBuilder(xml, configuration, "mapper/UserMapper.xml",
                    configuration.getSqlFragments()).parse();
        }
        try (SqlSession session = new SqlSessionFactoryBuilder().build(configuration).openSession(true)) {
            User user = new User();
            user.setPhone("13800138000");
            user.setNickName("new-user");
            session.getMapper(UserMapper.class).save(user);
            assertEquals(1001L, user.getId());
            verify(statement).execute();
        }
    }
}
