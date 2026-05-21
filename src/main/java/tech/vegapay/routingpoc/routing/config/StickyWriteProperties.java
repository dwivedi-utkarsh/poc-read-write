package tech.vegapay.routingpoc.routing.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "spring.datasource.routing.sticky-writes")
@Getter
@Setter
public class StickyWriteProperties {
    private boolean enabled = false;
    private long windowMs = 0;
}
