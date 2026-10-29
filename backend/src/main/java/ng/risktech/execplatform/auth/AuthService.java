package ng.risktech.execplatform.auth;

import ng.risktech.execplatform.audit.AuditAction;
import ng.risktech.execplatform.audit.AuditEntityType;
import ng.risktech.execplatform.audit.AuditEvent;
import ng.risktech.execplatform.audit.AuditRecorder;
import ng.risktech.execplatform.common.ApiException;
import ng.risktech.execplatform.document.DocumentService;
import ng.risktech.execplatform.notification.PushSubscriptionEraser;
import ng.risktech.execplatform.onboarding.IdempotencyRepository;
import ng.risktech.execplatform.user.User;
import ng.risktech.execplatform.user.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class AuthService {
    private static final String DUMMY = "$2a$12$C6UzMDM.H6dfI/f/IKcEe.8M1Z9Z3QqHqXzqgQp2b8J8dY1u8Qm1e";
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final PasswordPolicy policy;
    private final AuditRecorder audit;
    private final JdbcTemplate db;
    private final DocumentService documents;
    private final PushSubscriptionEraser pushEraser;
    private final IdempotencyRepository idempotencyKeys;
    private final int max = 5, lockMinutes = 15;

    public AuthService(UserRepository u, PasswordEncoder e, PasswordPolicy p, AuditRecorder a, JdbcTemplate db, DocumentService documents,
                       PushSubscriptionEraser pushEraser, IdempotencyRepository idempotencyKeys) {
        users = u;
        encoder = e;
        policy = p;
        audit = a;
        this.db = db;
        this.documents = documents;
        this.pushEraser = pushEraser;
        this.idempotencyKeys = idempotencyKeys;
    }

    public static String normalize(String e) {
        return e.trim().toLowerCase(Locale.ROOT);
    }

    public User create(String email, String password, String role) {
        String em = normalize(email);
        policy.validate(password, em);
        try {
            User u = new User(UUID.randomUUID(), em, encoder.encode(password), role);
            return users.saveAndFlush(u);
        } catch (DataIntegrityViolationException ex) {
            throw ApiException.conflict("REGISTRATION_FAILED", "Unable to create an account with these details");
        }
    }

    public User login(String email, String password) {
        String em = normalize(email);
        User u = users.findByEmailIgnoreCase(em).orElse(null);
        boolean match = encoder.matches(password, u == null ? DUMMY : u.getPasswordHash());
        if (u == null || !u.isActive() || (u.getLockedUntil() != null && u.getLockedUntil().isAfter(OffsetDateTime.now())) || !match) {
            if (u != null && u.isActive() && (u.getLockedUntil() == null || u.getLockedUntil().isBefore(OffsetDateTime.now()))) {
                int next = u.getFailedLoginCount() + 1;
                u.setFailedLoginCount(next);
                if (next >= max) {
                    u.setLockedUntil(OffsetDateTime.now().plusMinutes(lockMinutes));
                    audit.recordStandalone(new AuditEvent(AuditAction.ACCOUNT_LOCKED, AuditEntityType.USER, u.getId().toString(), null, null, null));
                }
                users.save(u);
            }
            audit.recordStandalone(new AuditEvent(AuditAction.LOGIN_FAILURE, AuditEntityType.USER, u == null ? null : u.getId().toString(), null, null, null));
            throw new SecurityException("Invalid credentials");
        }
        u.setFailedLoginCount(0);
        u.setLockedUntil(null);
        users.save(u);
        audit.recordStandalone(new AuditEvent(AuditAction.LOGIN_SUCCESS, AuditEntityType.USER, u.getId().toString(), null, u.getId(), u.getRole()));
        return u;
    }

    public User current(UUID id) {
        return users.findById(id).filter(User::isActive).orElseThrow(() -> new SecurityException("Unauthenticated"));
    }

    public void logout(User u) {
        u.setTokenVersion(u.getTokenVersion() + 1);
        users.save(u);
    }

    @Transactional
    public void deleteExecutive(User u, String password) {
        if (password == null || !encoder.matches(password, u.getPasswordHash()))
            throw new SecurityException("Invalid credentials");
        UUID id = u.getId();
        documents.purgeForUser(id);
        pushEraser.eraseFor(id);
        idempotencyKeys.deleteAllForUser(id);
        db.update("DELETE FROM experiences WHERE profile_id IN (SELECT id FROM executive_profiles WHERE user_id=?)", id);
        db.update("DELETE FROM sector_expertise WHERE profile_id IN (SELECT id FROM executive_profiles WHERE user_id=?)", id);
        db.update("DELETE FROM qualifications WHERE profile_id IN (SELECT id FROM executive_profiles WHERE user_id=?)", id);
        db.update("DELETE FROM referees WHERE profile_id IN (SELECT id FROM executive_profiles WHERE user_id=?)", id);
        db.update("UPDATE executive_profiles SET draft_data='{}'::jsonb,national_id_encrypted=NULL,national_id_last4=NULL,current_step=NULL,submitted_at=NULL,version=version+1 WHERE user_id=?", id);
        u.setEmail("deleted+" + id + "@invalid.example");
        u.setPasswordHash(encoder.encode(UUID.randomUUID().toString()));
        u.setActive(false);
        u.setTokenVersion(u.getTokenVersion() + 1);
        users.saveAndFlush(u);
        audit.record(new AuditEvent(AuditAction.ACCOUNT_DELETED, AuditEntityType.USER, id.toString(), null, id, u.getRole()));
    }

    @Transactional
    public void changePassword(User u, String current, String next) {
        if (current == null || !encoder.matches(current, u.getPasswordHash()))
            throw new SecurityException("Invalid credentials");
        policy.validate(next, u.getEmail());
        u.setPasswordHash(encoder.encode(next));
        u.setTokenVersion(u.getTokenVersion() + 1);
        users.saveAndFlush(u);
        audit.record(new AuditEvent(AuditAction.PASSWORD_CHANGED, AuditEntityType.USER, u.getId().toString(), null, u.getId(), u.getRole()));
    }

    @Transactional
    public void setActive(UUID id, boolean active, UUID actor) {
        if (id.equals(actor))
            throw ApiException.conflict("SELF_DEACTIVATION", "Admins cannot deactivate their own account");
        User u = users.findById(id).orElseThrow(ApiException::notFound);
        u.setActive(active);
        if (!active) {
            u.setTokenVersion(u.getTokenVersion() + 1);
            pushEraser.eraseFor(id);
        }
        users.save(u);
        audit.record(AuditEvent.of(active ? AuditAction.USER_ACTIVATED : AuditAction.USER_DEACTIVATED, AuditEntityType.USER,
                id.toString(), Map.of("targetRole", u.getRole())));
    }
}
