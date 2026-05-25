package tech.vegapay.routingpoc.hybrid;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tech.vegapay.readwriteseperationlibrary.annotation.ForceMasterRead;
import tech.vegapay.routingpoc.UserRepo;

/**
 * Class-level @ForceMasterRead — every public method should route reads to
 * primary via the @within pointcut in ForceMasterReadAspect.
 *
 * Used by scenario 27.
 */
@Service
@ForceMasterRead
@RequiredArgsConstructor
public class ClassLevelForceMasterService {

    private final UserRepo repo;

    public String readByEmail(String email) {
        return repo.findByEmail(email).getServedBy();
    }
}
