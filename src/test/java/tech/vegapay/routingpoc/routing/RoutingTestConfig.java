package tech.vegapay.routingpoc.routing;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import tech.vegapay.routingpoc.routing.aspect.ForceMasterReadAspect;
import tech.vegapay.routingpoc.routing.aspect.HintManagerBridgeAspect;
import tech.vegapay.routingpoc.routing.aspect.StickyReadAspect;
import tech.vegapay.routingpoc.routing.aspect.StickyWriteRecorderAspect;
import tech.vegapay.routingpoc.routing.config.StickyWriteProperties;

/**
 * Wires the ShardingSphere-only variant: ShardingSphere as the routing engine
 * via {@link ShardingSphereTestConfig}, plus the local annotation+aspect bits
 * that handle annotations and sticky-window state:
 *
 *   - {@link ForceMasterReadAspect} / {@link StickyReadAspect} drive the
 *     per-thread RoutingContext signals from @ForceMasterRead / @StickyRead.
 *   - {@link HintManagerBridgeAspect} reads RoutingContext at repository
 *     method entry and emits ShardingSphere HintManager hints, so routing
 *     intent flows through to ShardingSphere's routing decision.
 *   - {@link StickyWriteRecorderAspect} refreshes RoutingContext.lastWriteTime
 *     after every repository write.
 */
@TestConfiguration
@Import({
        ShardingSphereTestConfig.class,
        ForceMasterReadAspect.class,
        StickyReadAspect.class
})
@EnableConfigurationProperties(StickyWriteProperties.class)
public class RoutingTestConfig {

    @Bean
    public HintManagerBridgeAspect hintManagerBridgeAspect(StickyWriteProperties stickyProps) {
        return new HintManagerBridgeAspect(stickyProps);
    }

    @Bean
    public StickyWriteRecorderAspect stickyWriteRecorderAspect() {
        return new StickyWriteRecorderAspect();
    }
}
