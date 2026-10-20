package tech.starryapi.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import tech.starryapi.admin.LoginThrottle;
import tech.starryapi.platform.openai.OpenAiResponseStateStore;
import tech.starryapi.proxy.ProxyBodyBudgetFilter;

/**
 * Unit tests construct these classes by hand, which hides the "No default constructor found" failure Spring
 * reports at startup when a component has several constructors and none is marked for injection.
 */
class NewComponentsWireUpTest {

    @Test
    void componentsWithSeveralConstructorsCanBeCreatedBySpring() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getBeanFactory().registerSingleton("objectMapper", new ObjectMapper());
            context.getBeanFactory().registerSingleton("proxyProperties", new ProxyProperties());
            context.register(ProxyBodyBudgetFilter.class, LoginThrottle.class, OpenAiResponseStateStore.class, PublicSiteUrl.class);
            context.refresh();

            assertThat(context.getBean(ProxyBodyBudgetFilter.class)).isNotNull();
            assertThat(context.getBean(LoginThrottle.class)).isNotNull();
            assertThat(context.getBean(OpenAiResponseStateStore.class)).isNotNull();
            assertThat(context.getBean(PublicSiteUrl.class).configuredBase()).isNull();
        }
    }
}
