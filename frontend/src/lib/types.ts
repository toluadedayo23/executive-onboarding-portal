export type Role = 'EXECUTIVE' | 'CONSULTANT' | 'ADMIN';
export type User = { id: string; email: string; role: Role };

export type CaseStatus = 'DRAFT' | 'SUBMITTED' | 'UNDER_REVIEW' | 'MORE_INFO_REQUIRED' | 'VERIFIED' | 'REJECTED';
export type StepKey = 'profile' | 'experience' | 'sectors' | 'qualifications' | 'referees';
export type StepState = 'NOT_STARTED' | 'IN_PROGRESS' | 'COMPLETE';

export type Draft = {
  status: CaseStatus;
  editable: boolean;
  version: number;
  currentStep: number | null;
  stepStatus: Record<StepKey, StepState>;
  submittedAt: string | null;
  moreInfoMessage: string | null;
  data: Partial<Record<StepKey, Record<string, unknown>>>;
  problems: { step: string; field: string; code: string; item?: number | null }[];
};

export type ConsentStatus = { granted: boolean; notice: string };

export type DocumentType = 'CV' | 'ID' | 'CERTIFICATE';
export type DocumentView = {
  id: string;
  type: DocumentType;
  originalName: string;
  contentType: string;
  sizeBytes: number;
  uploadedAt: string;
  identificationType: string | null;
  certificationName: string | null;
};

export type QueueItem = {
  id: string;
  executiveName: string;
  headline: string | null;
  status: CaseStatus;
  assignedConsultantEmail: string | null;
  assignedToMe: boolean;
  submittedAt: string;
  updatedAt: string;
};

export type CaseAction = { status: CaseStatus; messageRequired: boolean };
export type HistoryEvent = 'SUBMITTED' | 'PICKED_UP' | 'MORE_INFO_REQUESTED' | 'RESUBMITTED' | 'VERIFIED' | 'REJECTED' | 'NOTE_ADDED' | 'OTHER';
export type HistoryEntry = { at: string; event: HistoryEvent; actorEmail: string | null; actorRole: Role | null };
export type CaseNote = { id: string; authorEmail: string; note: string; createdAt: string };
export type CaseView = {
  id: string;
  status: CaseStatus;
  executiveName: string;
  assignedConsultantId: string | null;
  assignedConsultantEmail: string | null;
  assignedToMe: boolean;
  submittedAt: string;
  updatedAt: string;
  decidedAt: string | null;
  moreInfoMessage: string | null;
  decisionReason: string | null;
  profile: Partial<Record<StepKey, Record<string, unknown>>>;
  documents: DocumentView[];
  notes: CaseNote[];
  history: HistoryEntry[];
  actions: CaseAction[];
};

export type AdminUser = { id: string; email: string; role: Role; active: boolean; createdAt: string };
export type AuditEntry = {
  id: number;
  createdAt: string;
  actorId: string | null;
  actorEmail: string | null;
  actorRole: Role | null;
  action: string;
  entityType: string | null;
  entityId: string | null;
  target: string | null;
  detail: string | null;
  ip: string | null;
  hash: string;
};
export type AuditVerifyResult = {
  valid: boolean;
  entriesChecked: number;
  firstBrokenId: number | null;
  reason: string | null;
  headId: number | null;
  checkedAt: string;
};
export type Page<T> = { content: T[]; page: number; size: number; totalElements?: number };
