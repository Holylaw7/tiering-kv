package io.tieringkv.webconsole.config;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.Configuration;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证多副本冷启动 Flyway MySQL 咨询锁串行化与 H2 透传。 */
class FlywayConfigTest {

    @Test
    void acquiresAndReleasesMysqlAdvisoryLockAroundMigrate() throws Exception {
        Flyway flyway = mock(Flyway.class);
        Configuration cfg = mock(Configuration.class);
        DataSource ds = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        DatabaseMetaData meta = mock(DatabaseMetaData.class);
        PreparedStatement lockPs = mock(PreparedStatement.class);
        PreparedStatement unlockPs = mock(PreparedStatement.class);
        ResultSet lockRs = mock(ResultSet.class);
        ResultSet unlockRs = mock(ResultSet.class);

        when(flyway.getConfiguration()).thenReturn(cfg);
        when(cfg.getDataSource()).thenReturn(ds);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.getMetaData()).thenReturn(meta);
        when(meta.getDatabaseProductName()).thenReturn("MySQL");
        when(conn.prepareStatement("SELECT GET_LOCK(?, ?)")).thenReturn(lockPs);
        when(lockPs.executeQuery()).thenReturn(lockRs);
        when(lockRs.next()).thenReturn(true);
        when(lockRs.getInt(1)).thenReturn(1);
        when(conn.prepareStatement("SELECT RELEASE_LOCK(?)")).thenReturn(unlockPs);
        when(unlockPs.executeQuery()).thenReturn(unlockRs);

        FlywayConfig.migrateWithAdvisoryLock(flyway);

        InOrder order = inOrder(lockPs, flyway, unlockPs);
        order.verify(lockPs).executeQuery();
        order.verify(flyway).migrate();
        order.verify(unlockPs).executeQuery();
    }

    @Test
    void failsFastWhenMysqlAdvisoryLockTimesOut() throws Exception {
        Flyway flyway = mock(Flyway.class);
        Configuration cfg = mock(Configuration.class);
        DataSource ds = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        DatabaseMetaData meta = mock(DatabaseMetaData.class);
        PreparedStatement lockPs = mock(PreparedStatement.class);
        ResultSet lockRs = mock(ResultSet.class);

        when(flyway.getConfiguration()).thenReturn(cfg);
        when(cfg.getDataSource()).thenReturn(ds);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.getMetaData()).thenReturn(meta);
        when(meta.getDatabaseProductName()).thenReturn("MySQL");
        when(conn.prepareStatement("SELECT GET_LOCK(?, ?)")).thenReturn(lockPs);
        when(lockPs.executeQuery()).thenReturn(lockRs);
        when(lockRs.next()).thenReturn(true);
        when(lockRs.getInt(1)).thenReturn(0);

        assertThatThrownBy(() -> FlywayConfig.migrateWithAdvisoryLock(flyway))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("超时");
        verify(flyway, never()).migrate();
    }

    @Test
    void passesThroughDirectlyOnH2() throws Exception {
        Flyway flyway = mock(Flyway.class);
        Configuration cfg = mock(Configuration.class);
        DataSource ds = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        DatabaseMetaData meta = mock(DatabaseMetaData.class);

        when(flyway.getConfiguration()).thenReturn(cfg);
        when(cfg.getDataSource()).thenReturn(ds);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.getMetaData()).thenReturn(meta);
        when(meta.getDatabaseProductName()).thenReturn("H2");

        FlywayConfig.migrateWithAdvisoryLock(flyway);

        verify(flyway).migrate();
        verify(conn, never()).prepareStatement("SELECT GET_LOCK(?, ?)");
    }
}
