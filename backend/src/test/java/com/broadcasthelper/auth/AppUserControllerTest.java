package com.broadcasthelper.auth;

import com.broadcasthelper.auth.AppUserController.AppUser;
import com.broadcasthelper.auth.AppUserController.CreateRequest;
import com.broadcasthelper.auth.AppUserController.RoleRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guard and CRUD behavior of the user roster, called directly on the bean
 * (SeriesChampionshipControllerTest pattern) against the real local Postgres;
 * {@code @Transactional} rolls each test back. Each guard test clears app_user
 * first (inside the rolled-back tx) so "last admin" is deterministic whatever
 * the local DB holds. The rollbacks leave the singleton UserDirectory snapshot
 * caching rows that never committed, so the class ends with a reload.
 */
@SpringBootTest
@Transactional
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AppUserControllerTest {

    @Autowired
    private AppUserController controller;

    @Autowired
    private UserDirectory directory;

    @Autowired
    private JdbcClient db;

    @AfterAll
    void restoreSnapshot() {
        directory.reload();
    }

    private void clearUsers() {
        db.sql("DELETE FROM app_user").update();
    }

    private static OidcUser caller(String email) {
        return new DefaultOidcUser(List.of(),
                OidcIdToken.withTokenValue("t").claim("sub", "x").claim("email", email).build());
    }

    private static void assertStatus(HttpStatus expected, Runnable call) {
        ResponseStatusException e = assertThrows(ResponseStatusException.class, call::run);
        assertEquals(expected, e.getStatusCode());
    }

    @Test
    void createNormalizesAndListsOrdered() {
        clearUsers();
        AppUser created = controller.create(new CreateRequest("  Zed@Example.TEST ", "viewer"));
        assertEquals("zed@example.test", created.email());
        assertEquals("VIEWER", created.role());
        assertNotNull(created.createdAt());
        controller.create(new CreateRequest("ann@example.test", "ADMIN"));

        List<AppUser> users = controller.list();
        assertEquals(List.of("ann@example.test", "zed@example.test"),
                users.stream().map(AppUser::email).toList());
    }

    @Test
    void duplicateEmailIsConflictEvenCaseVariant() {
        clearUsers();
        controller.create(new CreateRequest("dupe@example.test", "VIEWER"));
        assertStatus(HttpStatus.CONFLICT,
                () -> controller.create(new CreateRequest("DUPE@example.test", "VIEWER")));
    }

    @Test
    void badRoleAndBadEmailAreUnprocessable() {
        assertStatus(HttpStatus.UNPROCESSABLE_ENTITY,
                () -> controller.create(new CreateRequest("x@example.test", "OWNER")));
        assertStatus(HttpStatus.UNPROCESSABLE_ENTITY,
                () -> controller.create(new CreateRequest("not-an-email", "VIEWER")));
    }

    @Test
    void promoteAndDemoteRoundTrip() {
        clearUsers();
        controller.create(new CreateRequest("boss@example.test", "ADMIN"));
        AppUser viewer = controller.create(new CreateRequest("crew@example.test", "VIEWER"));

        AppUser promoted = controller.setRole(viewer.id(), new RoleRequest("ADMIN"), null);
        assertEquals("ADMIN", promoted.role());
        AppUser demoted = controller.setRole(viewer.id(), new RoleRequest("VIEWER"), null);
        assertEquals("VIEWER", demoted.role());
    }

    @Test
    void lastAdminCannotBeDemotedOrDeleted() {
        clearUsers();
        AppUser only = controller.create(new CreateRequest("only@example.test", "ADMIN"));
        controller.create(new CreateRequest("crew@example.test", "VIEWER"));

        assertStatus(HttpStatus.UNPROCESSABLE_ENTITY,
                () -> controller.setRole(only.id(), new RoleRequest("VIEWER"), null));
        assertStatus(HttpStatus.UNPROCESSABLE_ENTITY,
                () -> controller.delete(only.id(), null));
    }

    @Test
    void viewerDeletableWhileSoleAdminRemains() {
        clearUsers();
        controller.create(new CreateRequest("only@example.test", "ADMIN"));
        AppUser crew = controller.create(new CreateRequest("crew@example.test", "VIEWER"));

        controller.delete(crew.id(), null);
        assertTrue(controller.list().stream().noneMatch(u -> u.id() == crew.id()));
    }

    @Test
    void adminDeletableWhileAnotherAdminRemains() {
        clearUsers();
        controller.create(new CreateRequest("boss@example.test", "ADMIN"));
        AppUser second = controller.create(new CreateRequest("second@example.test", "ADMIN"));

        controller.delete(second.id(), null);
        assertTrue(controller.list().stream().noneMatch(u -> u.id() == second.id()));
    }

    @Test
    void selfDemotionAndSelfDeletionAreBlocked() {
        clearUsers();
        AppUser self = controller.create(new CreateRequest("me@example.test", "ADMIN"));
        controller.create(new CreateRequest("other@example.test", "ADMIN"));

        assertStatus(HttpStatus.UNPROCESSABLE_ENTITY,
                () -> controller.setRole(self.id(), new RoleRequest("VIEWER"), caller("Me@Example.TEST")));
        assertStatus(HttpStatus.UNPROCESSABLE_ENTITY,
                () -> controller.delete(self.id(), caller("me@example.test")));
    }

    @Test
    void creatingAUserClearsTheirDeniedRecord() {
        clearUsers();
        db.sql("DELETE FROM denied_login").update();
        db.sql("INSERT INTO denied_login (email, attempt_count) VALUES ('waiting@example.test', 3)").update();

        controller.create(new CreateRequest("WAITING@Example.TEST", "VIEWER"));
        assertTrue(controller.denied().isEmpty());
    }

    @Test
    void deniedListIsNewestFirst() {
        db.sql("DELETE FROM denied_login").update();
        db.sql("""
                INSERT INTO denied_login (email, last_attempt_at) VALUES
                ('old@example.test', now() - interval '2 hours'),
                ('new@example.test', now() - interval '1 hour')
                """).update();

        assertEquals(List.of("new@example.test", "old@example.test"),
                controller.denied().stream().map(DeniedLogins.DeniedLogin::email).toList());
    }

    @Test
    void dismissDeniedIs204Then404() {
        db.sql("DELETE FROM denied_login").update();
        db.sql("INSERT INTO denied_login (email) VALUES ('drop@example.test')").update();
        long id = controller.denied().get(0).id();

        controller.dismissDenied(id);
        assertStatus(HttpStatus.NOT_FOUND, () -> controller.dismissDenied(id));
    }

    @Test
    void unknownIdIsNotFound() {
        assertStatus(HttpStatus.NOT_FOUND,
                () -> controller.setRole(999_999_999L, new RoleRequest("VIEWER"), null));
        assertStatus(HttpStatus.NOT_FOUND,
                () -> controller.delete(999_999_999L, null));
    }
}
