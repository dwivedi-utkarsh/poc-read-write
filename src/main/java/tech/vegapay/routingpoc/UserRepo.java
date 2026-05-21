package tech.vegapay.routingpoc;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import javax.persistence.LockModeType;
import java.util.UUID;

public interface UserRepo extends JpaRepository<User, UUID> {

    // scenarios 1, 2
    User findByEmail(String email);

    @Query("SELECT u FROM User u WHERE u.email = :email")
    User findByEmailJpql(@Param("email") String email);

    // scenarios 3, 11
    // @Transactional here gives each modifying call its own brief tx so JPA's
    // contract is satisfied. Callers in UserService are intentionally NOT
    // @Transactional — the brief tx commits at the modifying-call boundary and
    // subsequent reads in the same caller still execute outside any tx, which
    // is what the "outside tx" scenarios are asserting against.
    @Transactional
    @Modifying
    @Query("UPDATE User u SET u.email = :email WHERE u.id = :id")
    int updateEmail(@Param("id") UUID id, @Param("email") String email);

    @Transactional
    @Modifying
    @Query(value = "INSERT INTO users (id, email, served_by) VALUES (:id, :email, 'PRIMARY')",
            nativeQuery = true)
    int insertNative(@Param("id") UUID id, @Param("email") String email);

    // scenario 7
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.id = :id")
    User findByIdForUpdate(@Param("id") UUID id);

    // scenario 10
    @Query(value = "SELECT u.* FROM users u WHERE u.email = :email", nativeQuery = true)
    User findByEmailNative(@Param("email") String email);
}
