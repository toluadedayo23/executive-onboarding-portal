import type { CaseStatus } from './types';

export const STATUS_LABEL: Record<CaseStatus, string> = {
  DRAFT: 'Draft',
  SUBMITTED: 'Submitted',
  UNDER_REVIEW: 'Under review',
  MORE_INFO_REQUIRED: 'More info required',
  VERIFIED: 'Verified',
  REJECTED: 'Rejected',
};

export const ID_TYPE_LABEL: Record<string, string> = {
  NIN: 'National ID (NIN)',
  DRIVERS_LICENSE: "Driver's licence",
  PASSPORT: 'International passport',
  VOTERS_CARD: "Voter's card",
};

export function formatDate(value: string | null | undefined): string {
  if (!value) return '—';
  return new Date(value).toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' });
}

export function formatDateTime(value: string | null | undefined): string {
  if (!value) return '—';
  return new Date(value).toLocaleString(undefined, {
    day: 'numeric', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit',
  });
}

export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}
