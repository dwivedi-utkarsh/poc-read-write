package tech.vegapay.routingpoc.hybrid;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import tech.vegapay.readwriteseperationlibrary.aspect.ForceMasterReadAspect;
import tech.vegapay.readwriteseperationlibrary.aspect.StickyReadAspect;
import tech.vegapay.readwriteseperationlibrary.config.properties.StickyWriteProperties;

/**
 * Wires the hybrid: ShardingSphere as the routing engine, the in-house
 * read-write-seperation-library as the annotation surface and sticky-window
 * mechanism on top.
 *
 *   - {@link ShardingSphereTestConfig} provides the @Primary DataSource.
 *   - {@link StickyWriteProperties} carries the sticky-window config (read
 *     from spring.datasource.routing.sticky-writes.* in application-test.yml).
 *   - {@link ForceMasterReadAspect} / {@link StickyReadAspect} — the
 *     library's own annotation-driven aspects, brought in as beans. They
 *     populate the library's ThreadLocals exactly as they would in a
 *     library-native deployment.
 *   - {@link HintManagerBridgeAspect} — reads those ThreadLocals at
 *     repository method entry and emits ShardingSphere HintManager hints,
 *     so the routing intent set by @ForceMasterRead / @StickyRead /
 *     sticky-window actually flows through to ShardingSphere's routing
 *     decision.
 *   - {@link StickyWriteRecorderAspect} — refreshes
 *     StickyWriteContext.lastWriteTime after every repository write,
 *     replacing the role of the library's afterCommit hook.
 *
 * The library's DataSourceRoutingConfig is gated on
 * {@code spring.datasource.routing.enabled=true} and we never set that, so
 * its RoutingDataSource bean is never created. ShardingSphere remains
 * the @Primary DataSource.
 */
@TestConfiguration
@Import({
        ShardingSphereTestConfig.class,
        ForceMasterReadAspect.class,
        StickyReadAspect.class
})
@EnableConfigurationProperties(StickyWriteProperties.class)
public class HybridTestConfig {

    @Bean
    public HintManagerBridgeAspect hintManagerBridgeAspect(StickyWriteProperties stickyProps) {
        return new HintManagerBridgeAspect(stickyProps);
    }

    @Bean
    public StickyWriteRecorderAspect stickyWriteRecorderAspect() {
        return new StickyWriteRecorderAspect();
    }
}
