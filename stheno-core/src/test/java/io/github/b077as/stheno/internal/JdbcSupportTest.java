package io.github.b077as.stheno.internal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcSupportTest {

    private static final String COLUMNS = "at <timestamp>, note <text>(10), id VARCHAR(5)";

    @Test
    void columnTypesFollowTheDatabase() {
        assertThat(JdbcSupport.forDatabase(COLUMNS, "PostgreSQL")).isEqualTo("at TIMESTAMP, note VARCHAR(10), id VARCHAR(5)");
        assertThat(JdbcSupport.forDatabase(COLUMNS, "MySQL")).isEqualTo("at DATETIME(6), note VARCHAR(10), id VARCHAR(5)");
        assertThat(JdbcSupport.forDatabase(COLUMNS, "MariaDB")).startsWith("at DATETIME(6)");
        assertThat(JdbcSupport.forDatabase(COLUMNS, "Microsoft SQL Server"))
                .isEqualTo("at DATETIME2(6), note NVARCHAR(10), id VARCHAR(5)");
        assertThat(JdbcSupport.forDatabase(COLUMNS, null)).startsWith("at TIMESTAMP");
    }

    @Test
    void textIsNotCutInsideACharacter() {
        assertThat(JdbcSupport.cut("abcdef", 3)).isEqualTo("abc");
        assertThat(JdbcSupport.cut("ab🙂cd", 3)).isEqualTo("ab");
        assertThat(JdbcSupport.cut("ab🙂cd", 4)).isEqualTo("ab🙂");
        assertThat(JdbcSupport.cut(null, 3)).isNull();
    }
}
