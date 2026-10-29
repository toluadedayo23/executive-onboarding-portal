package ng.risktech.execplatform;

import ng.risktech.execplatform.auth.AuthService;
import ng.risktech.execplatform.auth.AuthenticatedUser;
import ng.risktech.execplatform.common.ApiException;
import ng.risktech.execplatform.document.DocumentService;
import ng.risktech.execplatform.user.User;
import ng.risktech.execplatform.vetting.VettingService;
import ng.risktech.execplatform.vetting.VettingStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static ng.risktech.execplatform.vetting.VettingStatus.MORE_INFO_REQUIRED;
import static ng.risktech.execplatform.vetting.VettingStatus.REJECTED;
import static ng.risktech.execplatform.vetting.VettingStatus.SUBMITTED;
import static ng.risktech.execplatform.vetting.VettingStatus.UNDER_REVIEW;
import static ng.risktech.execplatform.vetting.VettingStatus.VERIFIED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest
@Transactional
class VettingIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("DB_URL", postgres::getJdbcUrl);
        r.add("DB_USERNAME", postgres::getUsername);
        r.add("DB_PASSWORD", postgres::getPassword);
    }

    @Autowired JdbcTemplate db;
    @Autowired AuthService auth;
    @Autowired VettingService vetting;
    @Autowired DocumentService documents;

    AuthenticatedUser exec1, exec2, consultant, otherConsultant, admin;

    @BeforeEach
    void seed() {
        exec1 = principal(auth.create("exec1@example.test", "VaultExecOne2", "EXECUTIVE"));
        exec2 = principal(auth.create("exec2@example.test", "VaultExecTwo2", "EXECUTIVE"));
        consultant = principal(auth.create("consultant1@example.test", "VaultConsult12", "CONSULTANT"));
        otherConsultant = principal(auth.create("consultant2@example.test", "VaultConsult22", "CONSULTANT"));
        admin = principal(auth.create("admin2@example.test", "VaultAdminTwo2", "ADMIN"));
    }

    @Test
    void queueIsOldestFirstAndFiltersByStatus() {
        UUID older = caseFor(exec1, SUBMITTED, null);
        UUID newer = caseFor(exec2, UNDER_REVIEW, consultant);
        assertThat(vetting.queue(consultant, null)).extracting("id").containsExactly(older, newer);
        assertThat(vetting.queue(admin, SUBMITTED)).extracting("id").containsExactly(older);
    }

    @Test
    void executivesHaveNoReviewerAccess() {
        UUID caseId = caseFor(exec1, SUBMITTED, null);
        assertThatThrownBy(() -> vetting.queue(exec1, null)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> vetting.detail(exec1, caseId)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> vetting.transition(exec1, caseId, UNDER_REVIEW, null)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void adminIsReadOnly() {
        UUID caseId = caseFor(exec1, SUBMITTED, null);
        assertThat(vetting.detail(admin, caseId).actions()).isEmpty();
        assertThatThrownBy(() -> vetting.transition(admin, caseId, UNDER_REVIEW, null)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void pickingUpAssignsAndOnlyTheAssigneeCanAct() {
        UUID caseId = caseFor(exec1, SUBMITTED, null);
        var view = vetting.transition(consultant, caseId, UNDER_REVIEW, null);
        assertThat(view.status()).isEqualTo("UNDER_REVIEW");
        assertThat(view.assignedConsultantId()).isEqualTo(consultant.id());

        assertThatThrownBy(() -> vetting.transition(otherConsultant, caseId, VERIFIED, null))
                .isInstanceOf(ApiException.class).extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void moreInfoRequiresMessageAndExecutiveResubmitReturnsToSameReviewer() {
        UUID caseId = caseFor(exec1, UNDER_REVIEW, consultant);
        assertThatThrownBy(() -> vetting.transition(consultant, caseId, MORE_INFO_REQUIRED, "  "))
                .isInstanceOf(ApiException.class).extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);

        var view = vetting.transition(consultant, caseId, MORE_INFO_REQUIRED, "Please add board experience");
        assertThat(view.moreInfoMessage()).isEqualTo("Please add board experience");
        assertThatThrownBy(() -> vetting.transition(consultant, caseId, UNDER_REVIEW, null))
                .isInstanceOf(ApiException.class).extracting("status").isEqualTo(HttpStatus.CONFLICT);

        vetting.resubmit(exec1, caseId);
        view = vetting.detail(consultant, caseId);
        assertThat(view.status()).isEqualTo("UNDER_REVIEW");
        assertThat(view.assignedConsultantId()).isEqualTo(consultant.id());
        assertThat(view.moreInfoMessage()).isNull();
    }

    @Test
    void decisionsAreTerminalAndRejectionNeedsReason() {
        UUID rejected = caseFor(exec1, UNDER_REVIEW, consultant);
        assertThatThrownBy(() -> vetting.transition(consultant, rejected, REJECTED, ""))
                .isInstanceOf(ApiException.class).extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);
        var view = vetting.transition(consultant, rejected, REJECTED, "Does not meet board criteria");
        assertThat(view.decisionReason()).isEqualTo("Does not meet board criteria");
        assertThat(view.decidedAt()).isNotNull();

        UUID verified = caseFor(exec2, UNDER_REVIEW, consultant);
        vetting.transition(consultant, verified, VERIFIED, null);
        for (UUID id : new UUID[]{rejected, verified}) {
            assertThatThrownBy(() -> vetting.transition(consultant, id, UNDER_REVIEW, null))
                    .isInstanceOf(ApiException.class).extracting("status").isEqualTo(HttpStatus.CONFLICT);
        }
    }

    @Test
    void transitionsAndNotesAreAudited() {
        UUID caseId = caseFor(exec1, SUBMITTED, null);
        vetting.transition(consultant, caseId, UNDER_REVIEW, null);
        vetting.addNote(consultant, caseId, "Checking references");

        var rows = db.queryForList("SELECT action, detail->>'from' AS from_status, detail->>'to' AS to_status FROM audit_log WHERE entity_id = ? ORDER BY id", caseId.toString());
        assertThat(rows).extracting(r -> r.get("action")).containsExactly("VETTING_TRANSITION", "VETTING_NOTE_ADDED");
        assertThat(rows.get(0)).containsEntry("from_status", "SUBMITTED").containsEntry("to_status", "UNDER_REVIEW");
        assertThat(vetting.detail(consultant, caseId).notes()).extracting("note").containsExactly("Checking references");
    }

    @Test
    void consultantsSeeOnlyCasesAwaitingPickupAndTheirOwnWhileAdminsSeeAll() {
        UUID waiting = caseFor(exec1, SUBMITTED, null);
        UUID mine = caseFor(exec2, UNDER_REVIEW, consultant);

        assertThat(vetting.queue(consultant, null)).extracting("id").containsExactly(waiting, mine);
        assertThat(vetting.queue(otherConsultant, null)).extracting("id").containsExactly(waiting);
        assertThat(vetting.queue(admin, null)).extracting("id").containsExactly(waiting, mine);

        assertThat(vetting.detail(otherConsultant, waiting).status()).isEqualTo("SUBMITTED");
        for (var attempt : List.<Runnable>of(
                () -> vetting.detail(otherConsultant, mine),
                () -> vetting.addNote(otherConsultant, mine, "Should not be possible"),
                () -> vetting.requireVisible(otherConsultant, mine))) {
            assertThatThrownBy(attempt::run).isInstanceOf(ApiException.class).extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
        }
        assertThat(vetting.detail(admin, mine).actions()).isEmpty();
    }

    @Test
    void caseHistoryTellsTheReviewStoryFromTheAuditTrail() {
        UUID caseId = caseFor(exec1, SUBMITTED, null);
        vetting.transition(consultant, caseId, UNDER_REVIEW, null);
        vetting.transition(consultant, caseId, MORE_INFO_REQUIRED, "Add your current role");
        vetting.resubmit(exec1, caseId);
        vetting.addNote(consultant, caseId, "Referees confirmed");
        vetting.transition(consultant, caseId, VERIFIED, null);

        var history = vetting.detail(admin, caseId).history();
        assertThat(history).extracting("event")
                .containsExactly("PICKED_UP", "MORE_INFO_REQUESTED", "RESUBMITTED", "NOTE_ADDED", "VERIFIED");
        assertThat(history.get(0).actorEmail()).isEqualTo("consultant1@example.test");
    }

    @Test
    void caseDocumentDownloadIsScopedToTheCase() {
        UUID case1 = caseFor(exec1, UNDER_REVIEW, consultant);
        UUID case2 = caseFor(exec2, UNDER_REVIEW, consultant);
        UUID doc = UUID.randomUUID();
        db.update("INSERT INTO documents(id, profile_id, type, original_name, stored_name, content_type, size_bytes, sha256) "
                + "VALUES (?, (SELECT profile_id FROM vetting_cases WHERE id = ?), 'CV', 'cv.pdf', ?, 'application/pdf', 10, ?)",
                doc, case1, doc + ".pdf", "a".repeat(64));

        assertThat(vetting.detail(consultant, case1).documents()).extracting("originalName").containsExactly("cv.pdf");
        assertThatThrownBy(() -> documents.downloadForCase(case2, doc))
                .isInstanceOf(ApiException.class).extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
        assertThatThrownBy(() -> documents.downloadOwn(exec2, doc))
                .isInstanceOf(ApiException.class).extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
    }

    private static AuthenticatedUser principal(User u) {
        return new AuthenticatedUser(u.getId(), u.getEmail(), u.getRole());
    }

    private int sequence = 0;

    private UUID caseFor(AuthenticatedUser exec, VettingStatus status, AuthenticatedUser assignee) {
        db.update("INSERT INTO executive_profiles(user_id) VALUES (?) ON CONFLICT (user_id) DO NOTHING", exec.id());
        UUID profile = db.queryForObject("SELECT id FROM executive_profiles WHERE user_id = ?", UUID.class, exec.id());
        UUID caseId = UUID.randomUUID();
        OffsetDateTime submitted = OffsetDateTime.now().minusMinutes(100 - sequence++);
        db.update("INSERT INTO vetting_cases(id, profile_id, status, assigned_consultant_id, submitted_at) VALUES (?,?,?,?,?)",
                caseId, profile, status.name(), assignee == null ? null : assignee.id(), submitted);
        return caseId;
    }
}
