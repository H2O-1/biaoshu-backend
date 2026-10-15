package com.bidding.worker;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.bind.annotation.RestController;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Worker 进程装配契约测试。
 */
class BiddingWorkerApplicationTest {

    @Test
    void mainClassScansBusinessModulesWithoutWebLayer() throws Exception {
        assertNotNull(BiddingWorkerApplication.class.getAnnotation(SpringBootConfiguration.class));
        assertNotNull(BiddingWorkerApplication.class.getAnnotation(EnableScheduling.class));

        EnableAutoConfiguration auto =
                BiddingWorkerApplication.class.getAnnotation(EnableAutoConfiguration.class);
        assertNotNull(auto);
        List<String> excludes = new ArrayList<>();
        for (Class<?> excluded : auto.exclude()) {
            excludes.add(excluded.getSimpleName());
        }
        assertTrue(excludes.contains("SecurityAutoConfiguration"));
        assertTrue(excludes.contains("UserDetailsServiceAutoConfiguration"));

        ComponentScan scan = BiddingWorkerApplication.class.getAnnotation(ComponentScan.class);
        assertNotNull(scan);
        assertEquals(List.of("com.bidding"), List.of(scan.basePackages()));

        boolean hasRestControllerExclude = false;
        boolean hasSecurityRegex = false;
        for (ComponentScan.Filter filter : scan.excludeFilters()) {
            if (filter.type() == FilterType.ANNOTATION) {
                for (Class<?> c : filter.classes()) {
                    if (c.equals(RestController.class)) {
                        hasRestControllerExclude = true;
                    }
                }
            }
            if (filter.type() == FilterType.REGEX) {
                List<String> patterns = List.of(filter.pattern());
                assertTrue(patterns.stream().anyMatch(p -> p.contains("SecurityConfig")));
                hasSecurityRegex = true;
            }
        }
        assertTrue(hasRestControllerExclude, "须排除 @RestController，避免 Worker 装 HTTP 入口");
        assertTrue(hasSecurityRegex, "须 REGEX 排除 SecurityConfig");
    }

    @Test
    void mainRunsWithoutWebServer() throws Exception {
        String source = Files.readString(
                Path.of("src/main/java/com/bidding/worker/BiddingWorkerApplication.java"));
        assertTrue(source.contains("WebApplicationType.NONE"));
    }

    @Test
    void webSecurityConfigsAreServletOnly() throws Exception {
        Path security = Path.of("..", "bidding-identity", "src", "main", "java",
                "com", "bidding", "identity", "infrastructure", "security", "SecurityConfig.java");
        assertTrue(Files.exists(security), security.toAbsolutePath().toString());
        String securitySrc = Files.readString(security);
        assertTrue(securitySrc.contains("ConditionalOnWebApplication"));
        assertTrue(securitySrc.contains("PasswordEncoderConfig")
                        || !securitySrc.contains("passwordEncoder()"),
                "PasswordEncoder 不得只挂在 Web 专用 SecurityConfig 上");
    }

    @Test
    void workerYmlKeepsJvmAliveWithoutWebServer() {
        Map<String, Object> root = loadYamlFromClasspath("application.yml");
        @SuppressWarnings("unchecked")
        Map<String, Object> spring = (Map<String, Object>) root.get("spring");
        @SuppressWarnings("unchecked")
        Map<String, Object> main = (Map<String, Object>) spring.get("main");
        assertNotNull(main, "spring.main 配置段缺失");
        assertEquals(Boolean.TRUE, main.get("keep-alive"),
                "无 Web 的 Worker 必须 keep-alive，否则启动后立刻退出");
    }

    @Test
    void workerYmlEnablesAllPollersByDefault() {
        Map<String, Object> bidding = biddingSection(loadYamlFromClasspath("application.yml"));
        assertEquals(true, valueAt(bidding, "index", "worker-enabled"));
        assertEquals(true, valueAt(bidding, "checklist", "worker-enabled"));
        assertEquals(true, valueAt(bidding, "review", "worker-enabled"));
        assertEquals(true, valueAt(bidding, "tender-analysis", "worker-enabled"));
        assertEquals(true, valueAt(bidding, "bid-simulation-audit", "worker-enabled"));
        assertEquals(true, valueAt(bidding, "ai-review-pipeline", "worker-enabled"));
    }

    @Test
    void workerYmlKeepsBootstrapAdminDisabledByDefault() {
        Map<String, Object> bidding = biddingSection(loadYamlFromClasspath("application.yml"));
        assertEquals(Boolean.FALSE, valueAt(bidding, "bootstrap-admin", "enabled"));
    }

    @Test
    void workerYmlDefaultsToSharedS3Storage() {
        Map<String, Object> bidding = biddingSection(loadYamlFromClasspath("application.yml"));
        assertEquals("${STORAGE_TYPE:s3}", valueAt(bidding, "storage", "type"));
    }

    @Test
    void workerYmlSharesFlywayMigrationsWithApi() {
        Map<String, Object> root = loadYamlFromClasspath("application.yml");
        @SuppressWarnings("unchecked")
        Map<String, Object> spring = (Map<String, Object>) root.get("spring");
        @SuppressWarnings("unchecked")
        Map<String, Object> flyway = (Map<String, Object>) spring.get("flyway");
        assertEquals(Boolean.TRUE, flyway.get("enabled"));
        assertEquals("classpath:db/migration", flyway.get("locations").toString());
    }

    @Test
    void apiYmlDisablesAllPollersByDefault() throws Exception {
        Path apiYml = Path.of("..", "bidding-api", "src", "main", "resources", "application.yml");
        assertTrue(Files.exists(apiYml));
        Map<String, Object> bidding;
        try (InputStream in = Files.newInputStream(apiYml)) {
            bidding = biddingSection(new Yaml().load(in));
        }
        assertEquals("${INDEX_WORKER_ENABLED:false}", valueAt(bidding, "index", "worker-enabled"));
        assertEquals("${CHECKLIST_WORKER_ENABLED:false}", valueAt(bidding, "checklist", "worker-enabled"));
        assertEquals("${REVIEW_WORKER_ENABLED:false}", valueAt(bidding, "review", "worker-enabled"));
        assertEquals("${TENDER_ANALYSIS_WORKER_ENABLED:false}",
                valueAt(bidding, "tender-analysis", "worker-enabled"));
        assertEquals("${BID_SIMULATION_AUDIT_WORKER_ENABLED:false}",
                valueAt(bidding, "bid-simulation-audit", "worker-enabled"));
        assertEquals("${AI_REVIEW_PIPELINE_WORKER_ENABLED:false}",
                valueAt(bidding, "ai-review-pipeline", "worker-enabled"));
    }

    private static Map<String, Object> loadYamlFromClasspath(String name) {
        try (InputStream in = BiddingWorkerApplicationTest.class
                .getClassLoader().getResourceAsStream(name)) {
            assertNotNull(in, "classpath 上应存在 " + name);
            return new Yaml().load(in);
        } catch (java.io.IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> biddingSection(Map<String, Object> root) {
        return (Map<String, Object>) root.get("bidding");
    }

    @SuppressWarnings("unchecked")
    private static Object valueAt(Map<String, Object> bidding, String section, String key) {
        Map<String, Object> node = (Map<String, Object>) bidding.get(section);
        assertNotNull(node, "bidding." + section + " 配置段缺失");
        Object value = node.get(key);
        assertNotNull(value, "bidding." + section + "." + key + " 缺失");
        return value;
    }
}
