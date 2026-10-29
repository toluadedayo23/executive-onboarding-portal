import { api } from './api';
import type {
  AdminUser, AuditEntry, AuditVerifyResult, CaseStatus, CaseView, ConsentStatus, DocumentType, DocumentView,
  Draft, Page, QueueItem, StepKey, StepState, User,
} from './types';

export const getMe = () => api<User>('/api/auth/me');
export const login = (email: string, password: string) => api<User>('/api/auth/login', { json: { email, password } });
export const register = (email: string, password: string) => api<User>('/api/auth/register', { json: { email, password } });
export const logout = () => api<void>('/api/auth/logout', { method: 'POST' });
export const changePassword = (currentPassword: string, newPassword: string) =>
  api<void>('/api/auth/password', { json: { currentPassword, newPassword, confirmation: newPassword } });
export const deleteAccount = (currentPassword: string) =>
  api<void>('/api/auth/delete', { json: { currentPassword, confirmed: true } });

export const getConsent = () => api<ConsentStatus>('/api/consent');
export const grantConsent = () => api<void>('/api/consent', { json: { granted: true } });

export const getDraft = () => api<Draft>('/api/onboarding');
export const saveStep = (step: StepKey, version: number, currentStep: number, data: unknown) =>
  api<{ version: number; currentStep: number; stepStatus: Record<StepKey, StepState> }>(
    `/api/onboarding/steps/${step}`, { method: 'PUT', json: { version, currentStep, data } });
export const submitDraft = (version: number) =>
  api<{ status: CaseStatus; submittedAt: string }>('/api/onboarding/submit', { json: { version } });
export const getSensitive = () =>
  api<{ nationalId: string | null; referees: { name: string; relationship: string; email: string; phone: string }[] }>(
    '/api/onboarding/sensitive');

export const listDocuments = () => api<DocumentView[]>('/api/documents');
export function uploadDocument(type: DocumentType, file: File, extra: { identificationType?: string; certificationName?: string }) {
  const form = new FormData();
  form.append('type', type);
  if (extra.identificationType) form.append('identificationType', extra.identificationType);
  if (extra.certificationName) form.append('certificationName', extra.certificationName);
  form.append('file', file);
  return api<DocumentView>('/api/documents', { form });
}
export const ownDocumentUrl = (id: string) => `/api/documents/${id}/download`;
export const deleteDocument = (id: string) => api<void>(`/api/documents/${id}`, { method: 'DELETE' });

export const getQueue = (status?: CaseStatus) =>
  api<QueueItem[]>(`/api/vetting/queue${status ? `?status=${status}` : ''}`);
export const getCase = (id: string) => api<CaseView>(`/api/vetting/cases/${id}`);
export const transitionCase = (id: string, status: CaseStatus, reason?: string) =>
  api<CaseView>(`/api/vetting/cases/${id}/transition`, { json: { status, reason } });
export const addNote = (id: string, note: string) => api<CaseView>(`/api/vetting/cases/${id}/notes`, { json: { note } });
export const caseDocumentUrl = (caseId: string, documentId: string) =>
  `/api/vetting/cases/${caseId}/documents/${documentId}/download`;

export const getUsers = (page = 0) => api<Page<AdminUser>>(`/api/admin/users?page=${page}&size=50`);
export const createStaffUser = (email: string, password: string, role: 'CONSULTANT' | 'ADMIN') =>
  api<User>('/api/admin/users', { json: { email, password, role } });
export const setUserActive = (id: string, active: boolean) =>
  api<void>(`/api/admin/users/${id}/active`, { method: 'PATCH', json: { active } });
export type AuditCategory = 'KEY' | 'SECURITY' | 'ALL';
export type AuditFilters = { user: string; from: string; to: string };
export function getAudit(page: number, category: AuditCategory, filters: AuditFilters) {
  const query = new URLSearchParams({ page: String(page), size: '25', category });
  if (filters.user.trim()) query.set('user', filters.user.trim());
  if (filters.from) query.set('from', filters.from);
  if (filters.to) query.set('to', filters.to);
  return api<Page<AuditEntry>>(`/api/admin/audit?${query}`);
}
export const verifyAudit = () => api<AuditVerifyResult>('/api/admin/audit/verify');

export const getPushPublicKey = () => api<{ publicKey: string }>('/api/push/public-key');
export const savePushSubscription = (endpoint: string, p256dh: string, auth: string) =>
  api<{ id: string }>('/api/push/subscriptions', { json: { endpoint, keys: { p256dh, auth } } });
export const deletePushSubscription = (endpoint: string) =>
  api<void>('/api/push/subscriptions', { method: 'DELETE', json: { endpoint } });
