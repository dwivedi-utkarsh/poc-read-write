package tech.vegapay.routingpoc.hybrid;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tech.vegapay.routingpoc.UserRepo;

import java.util.UUID;

/**
 * Sibling service so {@link Transactional#propagation()} = REQUIRES_NEW
 * actually opens a fresh JDBC tx (Spring's @Transactional doesn't apply to
 * self-invocations within the same bean).
 *
 * Used by scenario 22.
 */
@Service
@RequiredArgsConstructor
public class NestedTxService {

    private final UserRepo repo;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String requiresNewRead(UUID id) {
        return repo.findById(id).orElseThrow(IllegalStateException::new).getServedBy();
    }
}
