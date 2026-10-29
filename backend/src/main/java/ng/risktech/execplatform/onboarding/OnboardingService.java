package ng.risktech.execplatform.onboarding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ng.risktech.execplatform.audit.AuditAction;
import ng.risktech.execplatform.audit.AuditEntityType;
import ng.risktech.execplatform.audit.AuditEvent;
import ng.risktech.execplatform.audit.AuditRecorder;
import ng.risktech.execplatform.auth.AuthenticatedUser;
import ng.risktech.execplatform.common.ApiException;
import ng.risktech.execplatform.consent.ConsentService;
import ng.risktech.execplatform.onboarding.OnboardingDtos.DraftResponse;
import ng.risktech.execplatform.onboarding.OnboardingDtos.Problem;
import ng.risktech.execplatform.onboarding.OnboardingDtos.RefereeContact;
import ng.risktech.execplatform.onboarding.OnboardingDtos.SaveRequest;
import ng.risktech.execplatform.onboarding.OnboardingDtos.SaveResponse;
import ng.risktech.execplatform.onboarding.OnboardingDtos.SensitiveView;
import ng.risktech.execplatform.onboarding.OnboardingDtos.SubmitResponse;
import ng.risktech.execplatform.vetting.VettingService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class OnboardingService {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final DraftProtector protector;
    private final SubmissionValidator validator;
    private final ConsentService consent;
    private final VettingService vetting;
    private final AuditRecorder audit;
    private final IdempotencyService idempotency;

    public OnboardingService(JdbcTemplate db, ObjectMapper json, DraftProtector protector, SubmissionValidator validator,
                             ConsentService consent, VettingService vetting, AuditRecorder audit,
                             IdempotencyService idempotency) {
        this.db = db;
        this.json = json;
        this.protector = protector;
        this.validator = validator;
        this.consent = consent;
        this.vetting = vetting;
        this.audit = audit;
        this.idempotency = idempotency;
    }

    @Transactional
    public DraftResponse get(AuthenticatedUser user) {
        ProfileRow row = loadProfile(user.id(), false);
        JsonNode plain = protector.decrypt(row.draft());
        List<Problem> problems = validator.validate(plain, user.email()).stream()
                .filter(problem -> plain.has(problem.step())).toList();
        return new DraftResponse(row.status(), row.editable(), row.version(), row.currentStep(),
                stepStatus(plain, user.email()), row.submittedAt(), row.moreInfoMessage(),
                protector.maskForOwner(row.draft()), problems);
    }

    @Transactional
    public IdempotencyService.Outcome saveStep(AuthenticatedUser user, String step, SaveRequest request, UUID idempotencyKey) {
        if (!DraftShape.isStep(step)) throw ApiException.notFound();
        consent.requireConsent(user.id());
        // Lock the profile first so identical retries serialise and the second one replays
        ProfileRow row = loadProfile(user.id(), true);
        String hmac = idempotencyKey == null ? null : idempotency.requestHmac(step, request);
        return idempotency.run(user.id(), idempotencyKey, hmac, () -> applyStep(user, step, request, row));
    }

    private SaveResponse applyStep(AuthenticatedUser user, String step, SaveRequest request, ProfileRow row) {
        requireEditable(row);
        requireVersion(row, request.version());

        ObjectNode stepData = protector.protectStep(step, DraftShape.clean(step, request.data()), row.draft());
        ObjectNode draft = row.draft().deepCopy();
        draft.set(step, stepData);
        db.update("UPDATE executive_profiles SET draft_data = CAST(? AS jsonb), version = version + 1, "
                        + "current_step = COALESCE(?, current_step) WHERE id = ?",
                draft.toString(), request.currentStep(), row.profileId());
        Integer currentStep = request.currentStep() != null ? request.currentStep() : row.currentStep();
        return new SaveResponse(row.version() + 1, currentStep, stepStatus(protector.decrypt(draft), user.email()));
    }

    @Transactional
    public SubmitResponse submit(AuthenticatedUser user, int version) {
        consent.requireConsent(user.id());
        ProfileRow row = loadProfile(user.id(), true);
        requireEditable(row);
        requireVersion(row, version);

        JsonNode plain = protector.decrypt(row.draft());
        List<Problem> problems = new ArrayList<>(validator.validate(plain, user.email()));
        if (!hasRequiredDocuments(row.profileId())) problems.add(new Problem("documents", "CV_AND_ID", "REQUIRED"));
        if (!problems.isEmpty()) throw new OnboardingValidationException(problems);

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        writeStructuredRecords(row.profileId(), plain);
        db.update("UPDATE executive_profiles SET submitted_at = ?, version = version + 1 WHERE id = ?", now, row.profileId());

        String status;
        if (row.caseId() == null) {
            UUID caseId = vetting.openCase(row.profileId(), now);
            audit.record(AuditEvent.of(AuditAction.ONBOARDING_SUBMITTED, AuditEntityType.EXECUTIVE_PROFILE,
                    row.profileId().toString(), Map.of("caseId", caseId.toString())));
            status = "SUBMITTED";
        } else {
            vetting.resubmit(user, row.caseId());
            audit.record(AuditEvent.of(AuditAction.ONBOARDING_RESUBMITTED, AuditEntityType.EXECUTIVE_PROFILE,
                    row.profileId().toString(), Map.of("caseId", row.caseId().toString())));
            status = "UNDER_REVIEW";
        }
        return new SubmitResponse(status, now);
    }

    @Transactional
    public SensitiveView sensitive(AuthenticatedUser user) {
        JsonNode plain = protector.decrypt(loadProfile(user.id(), false).draft());
        List<RefereeContact> referees = new ArrayList<>();
        for (JsonNode r : plain.path("referees").path("items")) {
            referees.add(new RefereeContact(r.path("name").asText(null), r.path("relationship").asText(null),
                    r.path("email").asText(null), r.path("phone").asText(null)));
        }
        return new SensitiveView(plain.path("profile").path("nationalId").asText(null), referees);
    }

    private Map<String, String> stepStatus(JsonNode plain, String email) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String step : DraftShape.STEPS) {
            if (!plain.has(step)) result.put(step, "NOT_STARTED");
            else if (validator.validateStep(step, plain, email).isEmpty()) result.put(step, "COMPLETE");
            else result.put(step, "IN_PROGRESS");
        }
        return result;
    }

    private boolean hasRequiredDocuments(UUID profileId) {
        Integer types = db.queryForObject("SELECT count(DISTINCT type) FROM documents "
                + "WHERE profile_id = ? AND type IN ('CV', 'ID') AND deleted_at IS NULL", Integer.class, profileId);
        return types != null && types == 2;
    }

    private void writeStructuredRecords(UUID profileId, JsonNode plain) {
        for (String table : List.of("experiences", "sector_expertise", "qualifications", "referees")) {
            db.update("DELETE FROM " + table + " WHERE profile_id = ?", profileId);
        }
        for (JsonNode x : plain.path("experience").path("items")) {
            String end = x.path("endDate").asText("");
            db.update("INSERT INTO experiences(profile_id, type, organisation, title, start_date, end_date, description) VALUES (?,?,?,?,?,?,?)",
                    profileId, x.path("type").asText(), x.path("organisation").asText(), x.path("title").asText(),
                    java.sql.Date.valueOf(x.path("startDate").asText()), end.isBlank() ? null : java.sql.Date.valueOf(end),
                    x.path("description").asText());
        }
        for (JsonNode x : plain.path("sectors").path("items")) {
            db.update("INSERT INTO sector_expertise(profile_id, sector, years_experience) VALUES (?,?,?)",
                    profileId, x.path("sector").asText(), x.path("yearsExperience").asInt());
        }
        for (JsonNode x : plain.path("qualifications").path("items")) {
            db.update("INSERT INTO qualifications(profile_id, title, institution, year_obtained) VALUES (?,?,?,?)",
                    profileId, x.path("title").asText(), x.path("institution").asText(), x.path("yearObtained").asInt());
        }
        for (JsonNode x : plain.path("referees").path("items")) {
            db.update("INSERT INTO referees(profile_id, name, relationship, email_encrypted, phone_encrypted) VALUES (?,?,?,?,?)",
                    profileId, x.path("name").asText(), x.path("relationship").asText(),
                    protector.encryptRefereeEmail(x.path("email").asText()), protector.encryptRefereePhone(x.path("phone").asText()));
        }
        String nationalId = plain.path("profile").path("nationalId").asText("").trim();
        db.update("UPDATE executive_profiles SET national_id_encrypted = ?, national_id_last4 = ? WHERE id = ?",
                nationalId.isEmpty() ? null : protector.encryptNationalId(nationalId),
                nationalId.length() < 4 ? null : nationalId.substring(nationalId.length() - 4), profileId);
    }

    private static void requireEditable(ProfileRow row) {
        if (!row.editable()) throw ApiException.conflict("NOT_EDITABLE", "This onboarding has been submitted and is locked");
    }

    private static void requireVersion(ProfileRow row, int version) {
        if (row.version() != version) {
            throw ApiException.conflict("VERSION_CONFLICT", "Your draft was changed elsewhere. Reload to get the latest version.");
        }
    }

    private record ProfileRow(UUID profileId, ObjectNode draft, int version, Integer currentStep,
                              OffsetDateTime submittedAt, UUID caseId, String caseStatus, String moreInfoMessage) {
        String status() {
            return caseStatus == null ? "DRAFT" : caseStatus;
        }

        boolean editable() {
            return caseStatus == null || caseStatus.equals("MORE_INFO_REQUIRED");
        }
    }

    private ProfileRow loadProfile(UUID userId, boolean forUpdate) {
        db.update("INSERT INTO executive_profiles(user_id) VALUES (?) ON CONFLICT (user_id) DO NOTHING", userId);
        String sql = """
                SELECT p.id, p.draft_data::text, p.version, p.current_step, p.submitted_at,
                       c.id, c.status, c.more_info_message
                FROM executive_profiles p
                LEFT JOIN vetting_cases c ON c.profile_id = p.id
                WHERE p.user_id = ?
                """ + (forUpdate ? " FOR UPDATE OF p" : "");
        return db.queryForObject(sql, (rs, n) -> new ProfileRow(
                rs.getObject(1, UUID.class), parse(rs.getString(2)), rs.getInt(3), (Integer) rs.getObject(4),
                rs.getObject(5, OffsetDateTime.class), rs.getObject(6, UUID.class), rs.getString(7), rs.getString(8)), userId);
    }

    private ObjectNode parse(String text) {
        try {
            JsonNode node = json.readTree(text == null ? "{}" : text);
            return node instanceof ObjectNode object ? object : json.createObjectNode();
        } catch (Exception e) {
            throw new IllegalStateException("Stored draft is not valid JSON");
        }
    }
}
