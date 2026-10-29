package ng.risktech.execplatform.audit;

public final class AuditAction {
    public static final String USER_REGISTERED = "USER_REGISTERED";
    public static final String LOGIN_SUCCESS = "LOGIN_SUCCESS";
    public static final String LOGIN_FAILURE = "LOGIN_FAILURE";
    public static final String ACCOUNT_LOCKED = "ACCOUNT_LOCKED";
    public static final String PASSWORD_CHANGED = "PASSWORD_CHANGED";
    public static final String USER_CREATED = "USER_CREATED";
    public static final String USER_ACTIVATED = "USER_ACTIVATED";
    public static final String USER_DEACTIVATED = "USER_DEACTIVATED";
    public static final String ACCOUNT_DELETED = "ACCOUNT_DELETED";

    public static final String CONSENT_GRANTED = "CONSENT_GRANTED";

    public static final String ONBOARDING_SUBMITTED = "ONBOARDING_SUBMITTED";
    public static final String ONBOARDING_RESUBMITTED = "ONBOARDING_RESUBMITTED";
    public static final String DOCUMENT_UPLOADED = "DOCUMENT_UPLOADED";
    public static final String DOCUMENT_DOWNLOADED = "DOCUMENT_DOWNLOADED";
    public static final String DOCUMENT_DELETED = "DOCUMENT_DELETED";

    public static final String PUSH_SUBSCRIBED = "PUSH_SUBSCRIBED";
    public static final String PUSH_UNSUBSCRIBED = "PUSH_UNSUBSCRIBED";

    public static final String VETTING_TRANSITION = "VETTING_TRANSITION";
    public static final String VETTING_NOTE_ADDED = "VETTING_NOTE_ADDED";

    public static final java.util.Set<String> KEY_EVENTS = java.util.Set.of(
            ONBOARDING_SUBMITTED, ONBOARDING_RESUBMITTED, VETTING_TRANSITION, VETTING_NOTE_ADDED,
            USER_REGISTERED, USER_CREATED, USER_ACTIVATED, USER_DEACTIVATED, CONSENT_GRANTED, ACCOUNT_DELETED);

    public static final java.util.Set<String> SECURITY_EVENTS = java.util.Set.of(
            LOGIN_FAILURE, ACCOUNT_LOCKED, PASSWORD_CHANGED, DOCUMENT_DOWNLOADED);

    private AuditAction() {
    }
}
