import { describe, expect, it } from 'vitest';
import type { AuditEntry } from '../../lib/types';
import { describeAudit } from './auditText';

const base: AuditEntry = {
  id: 1, createdAt: '2026-01-01T00:00:00Z', actorId: 'a', actorEmail: 'consultant1@example.test', actorRole: 'CONSULTANT',
  action: 'VETTING_TRANSITION', entityType: 'VETTING_CASE', entityId: 'c', target: 'Ada Synthetic', detail: null, ip: null, hash: 'h',
};

describe('describeAudit', () => {
  it('reads review decisions as sentences', () => {
    expect(describeAudit({ ...base, detail: '{"from":"UNDER_REVIEW","to":"VERIFIED"}' })).toBe('consultant1@example.test verified Ada Synthetic');
    expect(describeAudit({ ...base, actorEmail: 'exec1@example.test', detail: '{"from":"MORE_INFO_REQUIRED","to":"UNDER_REVIEW"}' }))
      .toBe('exec1@example.test resubmitted the case for Ada Synthetic');
  });

  it('names the account an admin deactivated', () => {
    expect(describeAudit({ ...base, actorEmail: 'admin@example.test', action: 'USER_DEACTIVATED', target: 'consultant2@example.test', detail: '{"targetRole":"CONSULTANT"}' }))
      .toBe('admin@example.test deactivated consultant account consultant2@example.test');
  });
});
