package com.broadcasthelper.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * The user roster behind Manage → Users. Two roles: VIEWER may sign in and
 * read, ADMIN may also write (enforced per request by {@link LiveAuthorization}).
 * The table is the only access list — there is no env fallback — so the guards
 * here (no self-removal, no removing the last admin) are what stand between an
 * admin and locking everyone out; past them, recovery is a psql UPDATE.
 *
 * <p>Writes are deliberately not {@code @Transactional}: each is a single
 * statement that autocommits, after which {@code directory.reload()} reads the
 * committed state. Reloading inside a transaction would cache uncommitted —
 * still rollback-able — rows.
 */
@RestController
@RequestMapping("/api/users")
public class AppUserController {

    private final JdbcClient db;
    private final UserDirectory directory;

    public AppUserController(JdbcClient db, UserDirectory directory) {
        this.db = db;
        this.directory = directory;
    }

    public record AppUser(long id, String email, String role, OffsetDateTime createdAt) {
    }

    public record CreateRequest(@NotBlank String email, @NotBlank String role) {
    }

    public record RoleRequest(@NotBlank String role) {
    }

    @GetMapping
    public List<AppUser> list() {
        return db.sql("SELECT id, email, role, created_at FROM app_user ORDER BY email")
                .query(AppUserController::mapUser)
                .list();
    }

    @PostMapping
    public AppUser create(@Valid @RequestBody CreateRequest request) {
        String role = requireRole(request.role());
        String email = request.email().trim().toLowerCase();
        if (!email.contains("@")) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Enter a full email address");
        }
        try {
            AppUser created = db.sql("""
                            INSERT INTO app_user (email, role) VALUES (:email, :role)
                            RETURNING id, email, role, created_at
                            """)
                    .param("email", email)
                    .param("role", role)
                    .query(AppUserController::mapUser)
                    .single();
            directory.reload();
            return created;
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    email + " is already a user");
        }
    }

    @PatchMapping("/{id}")
    public AppUser setRole(@PathVariable long id,
                           @Valid @RequestBody RoleRequest request,
                           @AuthenticationPrincipal OidcUser caller) {
        String role = requireRole(request.role());
        AppUser target = find(id);
        if (isSelf(target, caller) && !"ADMIN".equals(role)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "You cannot demote yourself");
        }
        // The last-admin guard lives inside the statement so the count and the
        // write cannot interleave: a demotion only lands if another admin
        // remains.
        int updated = db.sql("""
                        UPDATE app_user SET role = :role
                        WHERE id = :id
                          AND (:role = 'ADMIN' OR role <> 'ADMIN'
                               OR EXISTS (SELECT 1 FROM app_user o
                                          WHERE o.role = 'ADMIN' AND o.id <> :id))
                        """)
                .param("role", role)
                .param("id", id)
                .update();
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Cannot demote the last remaining admin");
        }
        directory.reload();
        return new AppUser(target.id(), target.email(), role, target.createdAt());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id, @AuthenticationPrincipal OidcUser caller) {
        AppUser target = find(id);
        if (isSelf(target, caller)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "You cannot remove yourself");
        }
        int deleted = db.sql("""
                        DELETE FROM app_user
                        WHERE id = :id
                          AND (role <> 'ADMIN'
                               OR EXISTS (SELECT 1 FROM app_user o
                                          WHERE o.role = 'ADMIN' AND o.id <> :id))
                        """)
                .param("id", id)
                .update();
        if (deleted == 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Cannot remove the last remaining admin");
        }
        directory.reload();
    }

    private AppUser find(long id) {
        return db.sql("SELECT id, email, role, created_at FROM app_user WHERE id = :id")
                .param("id", id)
                .query(AppUserController::mapUser)
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such user"));
    }

    private static AppUser mapUser(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new AppUser(rs.getLong("id"), rs.getString("email"), rs.getString("role"),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    private static boolean isSelf(AppUser target, OidcUser caller) {
        return caller != null && caller.getEmail() != null
                && caller.getEmail().trim().toLowerCase().equals(target.email().toLowerCase());
    }

    private static String requireRole(String raw) {
        String role = raw.trim().toUpperCase();
        if (!role.equals("ADMIN") && !role.equals("VIEWER")) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Role must be ADMIN or VIEWER");
        }
        return role;
    }
}
