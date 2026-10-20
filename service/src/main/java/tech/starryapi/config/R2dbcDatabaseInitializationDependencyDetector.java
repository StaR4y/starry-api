package tech.starryapi.config;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitializationDetector;
import org.springframework.r2dbc.core.DatabaseClient;

public final class R2dbcDatabaseInitializationDependencyDetector implements DependsOnDatabaseInitializationDetector {

    @Override
    public Set<String> detect(ConfigurableListableBeanFactory beanFactory) {
        // Boot detects JDBC consumers automatically, but R2DBC clients also need Flyway to finish first.
        return new LinkedHashSet<>(Arrays.asList(
            beanFactory.getBeanNamesForType(DatabaseClient.class, true, false)
        ));
    }
}
