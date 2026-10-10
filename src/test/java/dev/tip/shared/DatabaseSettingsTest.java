package dev.tip.shared;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import dev.tip.IntegrationTest;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class DatabaseSettingsTest extends IntegrationTest {

    @Autowired
    DataSource dataSource;

    @Test
    void connectionPoolIsSmallEnoughForASmallDatabase() {
        assertThat(dataSource).isInstanceOf(HikariDataSource.class);
        assertThat(((HikariDataSource) dataSource).getMaximumPoolSize()).isEqualTo(5);
    }
}
