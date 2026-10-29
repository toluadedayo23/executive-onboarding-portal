package ng.risktech.execplatform.vetting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ng.risktech.execplatform.audit.AuditAction;
import ng.risktech.execplatform.audit.AuditEntityType;
import ng.risktech.execplatform.audit.AuditEvent;
import ng.risktech.execplatform.audit.AuditRecorder;
import ng.risktech.execplatform.auth.AuthenticatedUser;
import ng.risktech.execplatform.common.ApiException;
import ng.risktech.execplatform.document.DocumentService;
import ng.risktech.execplatform.onboarding.DraftProtector;
import ng.risktech.execplatform.vetting.VettingDtos.ActionView;
import ng.risktech.execplatform.vetting.VettingDtos.CaseView;
import ng.risktech.execplatform.vetting.VettingDtos.HistoryEntry;
import ng.risktech.execplatform.vetting.VettingDtos.NoteView;
import ng.risktech.execplatform.vetting.VettingDtos.QueueItem;
import ng.risktech.execplatform.vetting.VettingStateMachine.Actor;
import ng.risktech.execplatform.vetting.VettingStateMachine.Rule;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class VettingService {
    private final JdbcTemplate db;
    private final AuditRecorder audit;
    private final DraftProtector protector;
    private final DocumentService documents;
    private final ObjectMapper json;
    private final ApplicationEventPublisher events;

    public VettingService(JdbcTemplate db, AuditRecorder audit, DraftProtector protector, DocumentService documents,
                          ObjectMapper json, ApplicationEventPublisher events) {
        this.db = db;
        this.audit = audit;
        this.protector = protector;
        this.documents = documents;
        this.json = json;
        this.events = events;
    }

    static String historyEvent(String action, String from, String to) {
        if (AuditAction.ONBOARDING_SUBMITTED.equals(action)) return "SUBMITTED";
        if (AuditAction.VETTING_NOTE_ADDED.equals(action)) return "NOTE_ADDED";
        if (!AuditAction.VETTING_TRANSITION.equals(action) || to == null) return "OTHER";
        return switch (to) {
            case "UNDER_REVIEW" -> "MORE_INFO_REQUIRED".equals(from) ? "RESUBMITTED" : "PICKED_UP";
            case "MORE_INFO_REQUIRED" -> "MORE_INFO_REQUESTED";
            default -> to;
        };
    }

    // Cases a viewer cannot see are reported as 404 so their existence is not confirmed
    static boolean canSee(AuthenticatedUser viewer, CaseRow c) {
        if ("ADMIN".equals(viewer.role())) return true;
        return "CONSULTANT".equals(viewer.role())
                && (c.status() == VettingStatus.SUBMITTED || viewer.id().equals(c.assignedConsultantId()));
    }

    private static void requireStaff(AuthenticatedUser viewer) {
        if (!"CONSULTANT".equals(viewer.role()) && !"ADMIN".equals(viewer.role())) {
            throw new AccessDeniedException("Reviewer access only");
        }
    }

    private static String nameOrDefault(String name) {
        return name == null || name.isBlank() ? "Unnamed executive" : name;
    }

    public List<QueueItem> queue(AuthenticatedUser viewer, VettingStatus status) {
        requireStaff(viewer);
        String sql = """
                SELECT c.id, p.draft_data->'profile'->>'fullName' AS full_name,
                       p.draft_data->'profile'->>'headline' AS headline, c.status,
                       c.assigned_consultant_id, u.email AS consultant_email, c.submitted_at, c.updated_at
                FROM vetting_cases c
                JOIN executive_profiles p ON p.id = c.profile_id
                LEFT JOIN users u ON u.id = c.assigned_consultant_id
                WHERE (CAST(? AS varchar) IS NULL OR c.status = CAST(? AS varchar))
                  AND (CAST(? AS boolean) OR c.status = 'SUBMITTED' OR c.assigned_consultant_id = ?)
                ORDER BY c.submitted_at ASC, c.id
                """;
        String filter = status == null ? null : status.name();
        boolean seesAll = "ADMIN".equals(viewer.role());
        return db.query(sql, (rs, n) -> new QueueItem(
                rs.getObject("id", UUID.class),
                nameOrDefault(rs.getString("full_name")),
                rs.getString("headline"),
                rs.getString("status"),
                rs.getString("consultant_email"),
                viewer.id().equals(rs.getObject("assigned_consultant_id", UUID.class)),
                rs.getObject("submitted_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class)), filter, filter, seesAll, viewer.id());
    }

    public CaseView detail(AuthenticatedUser viewer, UUID caseId) {
        CaseRow c = requireVisible(viewer, caseId);
        JsonNode profile = protector.reviewerView(parse(c.draftJson()));
        List<NoteView> notes = db.query("""
                SELECT n.id, u.email, n.note, n.created_at FROM vetting_notes n
                JOIN users u ON u.id = n.author_id WHERE n.case_id = ? ORDER BY n.created_at
                """, (rs, i) -> new NoteView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                rs.getObject(4, OffsetDateTime.class)), caseId);
        boolean assignedToMe = viewer.id().equals(c.assignedConsultantId());
        return new CaseView(caseId, c.status().name(), nameOrDefault(profile.path("profile").path("fullName").asText(null)),
                c.assignedConsultantId(), c.assignedConsultantEmail(), assignedToMe,
                c.submittedAt(), c.updatedAt(), c.decidedAt(), c.moreInfoMessage(), c.decisionReason(), profile,
                documents.listForProfile(c.profileId()), notes, history(caseId, c.profileId()), actionsFor(viewer, c));
    }

    @Transactional
    public CaseView transition(AuthenticatedUser actor, UUID caseId, VettingStatus target, String message) {
        if (!"CONSULTANT".equals(actor.role()))
            throw new AccessDeniedException("Only consultants change vetting status");
        CaseRow c = loadCase(caseId, true);
        if (!canSee(actor, c)) throw ApiException.notFound();
        Rule rule = VettingStateMachine.require(c.status(), target, Actor.CONSULTANT);
        if (c.status() != VettingStatus.SUBMITTED && !actor.id().equals(c.assignedConsultantId())) {
            throw ApiException.forbidden("NOT_ASSIGNED", "Only the assigned consultant can act on this case");
        }
        String text = message == null || message.isBlank() ? null : message.trim();
        if (rule.messageRequired() && text == null)
            throw ApiException.badRequest("A message is required for this action");

        switch (target) {
            case UNDER_REVIEW -> db.update(
                    "UPDATE vetting_cases SET status = ?, assigned_consultant_id = ? WHERE id = ?",
                    target.name(), actor.id(), caseId);
            case MORE_INFO_REQUIRED -> db.update(
                    "UPDATE vetting_cases SET status = ?, more_info_message = ? WHERE id = ?",
                    target.name(), text, caseId);
            case VERIFIED, REJECTED -> db.update(
                    "UPDATE vetting_cases SET status = ?, decision_reason = ?, decided_at = now() WHERE id = ?",
                    target.name(), text, caseId);
            default -> throw new IllegalStateException("Unhandled transition");
        }
        recordTransition(actor, caseId, c.status(), target);
        events.publishEvent(new VettingStatusChangedEvent(c.executiveUserId(), target));
        return detail(actor, caseId);
    }

    @Transactional
    public void addNote(AuthenticatedUser actor, UUID caseId, String note) {
        if (!"CONSULTANT".equals(actor.role())) throw new AccessDeniedException("Only consultants add notes");
        requireVisible(actor, caseId);
        db.update("INSERT INTO vetting_notes(case_id, author_id, note) VALUES (?, ?, ?)", caseId, actor.id(), note.trim());
        audit.record(new AuditEvent(AuditAction.VETTING_NOTE_ADDED, AuditEntityType.VETTING_CASE, caseId.toString(), null,
                actor.id(), actor.role()));
    }

    public UUID openCase(UUID profileId, OffsetDateTime submittedAt) {
        UUID caseId = UUID.randomUUID();
        db.update("INSERT INTO vetting_cases(id, profile_id, status, submitted_at) VALUES (?, ?, ?, ?)",
                caseId, profileId, VettingStatus.SUBMITTED.name(), submittedAt);
        return caseId;
    }

    public void resubmit(AuthenticatedUser executive, UUID caseId) {
        CaseRow c = loadCase(caseId, true);
        VettingStateMachine.require(c.status(), VettingStatus.UNDER_REVIEW, Actor.EXECUTIVE);
        db.update("UPDATE vetting_cases SET status = ?, more_info_message = NULL WHERE id = ?",
                VettingStatus.UNDER_REVIEW.name(), caseId);
        recordTransition(executive, caseId, c.status(), VettingStatus.UNDER_REVIEW);
    }

    List<HistoryEntry> history(UUID caseId, UUID profileId) {
        return db.query("""
                        SELECT a.created_at, a.action, a.detail->>'to' AS to_status, a.detail->>'from' AS from_status,
                               u.email, a.actor_role
                        FROM audit_log a LEFT JOIN users u ON u.id = a.actor_id
                        WHERE (a.entity_type = 'VETTING_CASE' AND a.entity_id = ?)
                           OR (a.action = 'ONBOARDING_SUBMITTED' AND a.entity_id = ?)
                        ORDER BY a.id
                        """, (rs, n) -> new HistoryEntry(rs.getObject(1, OffsetDateTime.class),
                        historyEvent(rs.getString(2), rs.getString(4), rs.getString(3)), rs.getString(5), rs.getString(6)),
                caseId.toString(), profileId.toString());
    }

    private List<ActionView> actionsFor(AuthenticatedUser viewer, CaseRow c) {
        if (!"CONSULTANT".equals(viewer.role())) return List.of();
        boolean canAct = c.status() == VettingStatus.SUBMITTED || viewer.id().equals(c.assignedConsultantId());
        if (!canAct) return List.of();
        return VettingStateMachine.available(c.status(), Actor.CONSULTANT).stream()
                .map(r -> new ActionView(r.to().name(), r.messageRequired())).toList();
    }

    private void recordTransition(AuthenticatedUser actor, UUID caseId, VettingStatus from, VettingStatus to) {
        audit.record(new AuditEvent(AuditAction.VETTING_TRANSITION, AuditEntityType.VETTING_CASE, caseId.toString(),
                Map.of("from", from.name(), "to", to.name()), actor.id(), actor.role()));
    }

    public CaseRow requireVisible(AuthenticatedUser viewer, UUID caseId) {
        requireStaff(viewer);
        CaseRow c = loadCase(caseId, false);
        if (!canSee(viewer, c)) throw ApiException.notFound();
        return c;
    }

    private CaseRow loadCase(UUID caseId, boolean forUpdate) {
        String sql = """
                SELECT c.profile_id, p.user_id, c.status, c.assigned_consultant_id, u.email, c.more_info_message,
                       c.decision_reason, c.submitted_at, c.updated_at, c.decided_at, p.draft_data::text
                FROM vetting_cases c
                JOIN executive_profiles p ON p.id = c.profile_id
                LEFT JOIN users u ON u.id = c.assigned_consultant_id
                WHERE c.id = ?
                """ + (forUpdate ? " FOR UPDATE OF c" : "");
        List<CaseRow> rows = db.query(sql, (rs, n) -> new CaseRow(
                rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), VettingStatus.valueOf(rs.getString(3)),
                rs.getObject(4, UUID.class), rs.getString(5), rs.getString(6), rs.getString(7),
                rs.getObject(8, OffsetDateTime.class), rs.getObject(9, OffsetDateTime.class),
                rs.getObject(10, OffsetDateTime.class), rs.getString(11)), caseId);
        if (rows.isEmpty()) throw ApiException.notFound();
        return rows.get(0);
    }

    private JsonNode parse(String text) {
        try {
            return json.readTree(text == null ? "{}" : text);
        } catch (Exception e) {
            throw new IllegalStateException("Stored draft is not valid JSON");
        }
    }

    record CaseRow(UUID profileId, UUID executiveUserId, VettingStatus status, UUID assignedConsultantId,
                   String assignedConsultantEmail,
                   String moreInfoMessage, String decisionReason, OffsetDateTime submittedAt,
                   OffsetDateTime updatedAt, OffsetDateTime decidedAt, String draftJson) {
    }
}
