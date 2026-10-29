package ng.risktech.execplatform.audit;

public interface AuditRecorder {
    void record(AuditEvent event);

    void recordStandalone(AuditEvent event);
}
