import type { AuditEntry } from '../../lib/types';

export function describeAudit(entry: AuditEntry): string {
  const who = entry.actorEmail ?? 'Someone (not signed in)';
  const target = entry.target ?? 'a record';
  const detail = parseDetail(entry.detail);
  switch (entry.action) {
    case 'VETTING_TRANSITION': {
      const verb: Record<string, string> = {
        UNDER_REVIEW: detail.from === 'MORE_INFO_REQUIRED' ? 'resubmitted the case for' : 'picked up the case for',
        MORE_INFO_REQUIRED: 'requested more information from',
        VERIFIED: 'verified',
        REJECTED: 'rejected',
      };
      return `${who} ${verb[detail.to ?? ''] ?? 'changed the case status for'} ${target}`;
    }
    case 'VETTING_NOTE_ADDED': return `${who} added a reviewer note on ${target}`;
    case 'ONBOARDING_SUBMITTED': return `${who} submitted their onboarding for vetting`;
    case 'ONBOARDING_RESUBMITTED': return `${who} resubmitted their onboarding`;
    case 'USER_CREATED': return `${who} created ${roleWord(detail.targetRole)} account ${target}`;
    case 'USER_DEACTIVATED': return `${who} deactivated ${roleWord(detail.targetRole)} account ${target}`;
    case 'USER_ACTIVATED': return `${who} reactivated ${roleWord(detail.targetRole)} account ${target}`;
    case 'USER_REGISTERED': return `${target} registered as an executive`;
    case 'CONSENT_GRANTED': return `${who} gave consent to data processing`;
    case 'ACCOUNT_DELETED': return 'An executive deleted their account (data erased)';
    case 'LOGIN_SUCCESS': return `${who} signed in`;
    case 'LOGIN_FAILURE': return `Failed sign-in attempt${entry.target ? ` for ${entry.target}` : ' (unknown email)'}`;
    case 'ACCOUNT_LOCKED': return `${target} was locked after repeated failed sign-ins`;
    case 'PASSWORD_CHANGED': return `${who} changed their password`;
    case 'DOCUMENT_UPLOADED': return `${who} uploaded a ${detail.type ?? ''} document`;
    case 'DOCUMENT_DOWNLOADED': return `${who} downloaded a document`;
    default: return `${who}: ${entry.action}`;
  }
}

function roleWord(role: string | undefined) {
  if (role === 'CONSULTANT') return 'consultant';
  if (role === 'ADMIN') return 'admin';
  if (role === 'EXECUTIVE') return 'executive';
  return 'the';
}

function parseDetail(detail: string | null): Record<string, string> {
  if (!detail) return {};
  try {
    return JSON.parse(detail) as Record<string, string>;
  } catch {
    return {};
  }
}
