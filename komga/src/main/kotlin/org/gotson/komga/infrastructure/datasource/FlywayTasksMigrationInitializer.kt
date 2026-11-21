package org.gotson.komga.infrastructure.datasource

import org.flywaydb.core.Flyway
import org.springframework.beans.factory.InitializingBean
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import javax.sql.DataSource

@Component
class FlywayTasksMigrationInitializer(
  @Qualifier("tasksDataSourceRW")
  private val tasksDataSource: DataSource,
  // CUSTOM FORK: the tasks database gets its own Flyway instance and therefore does NOT inherit
  // spring.flyway.out-of-order from the main datasource. When the fork adds a task migration
  // newer than an upstream one (fork V20261122 vs upstream V20261002), an existing installation
  // fails validation at startup. Follow the same switch as the main database.
  @param:Value("\${spring.flyway.out-of-order:false}")
  private val outOfOrder: Boolean,
) : InitializingBean {
  // by default Spring Boot will perform migration only on the @Primary datasource
  override fun afterPropertiesSet() {
    Flyway
      .configure()
      .locations("classpath:tasks/migration/sqlite")
      .outOfOrder(outOfOrder)
      .dataSource(tasksDataSource)
      .load()
      .apply {
        migrate()
      }
  }
}
