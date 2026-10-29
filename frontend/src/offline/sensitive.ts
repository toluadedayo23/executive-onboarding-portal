import type { StepKey } from '../lib/types';

export type SensitiveValues = { nationalId?: string; referees?: Record<string, { email?: string; phone?: string }> };
export type OnFile = { nationalId: boolean; referees: Record<string, { email: boolean; phone: boolean }> };
type Data = Record<string, unknown>;

const isMasked = (value: unknown) => typeof value === 'string' && (value.startsWith('••••') || value === 'On file');
const isNewValue = (value: unknown): value is string => typeof value === 'string' && value.trim() !== '' && !isMasked(value);

export function splitSensitive(step: StepKey, data: Data): { safe: Data; sensitive: SensitiveValues | null } {
  if (step === 'profile') {
    const { nationalId, ...safe } = data;
    return { safe, sensitive: isNewValue(nationalId) ? { nationalId } : null };
  }
  if (step === 'referees' && Array.isArray(data.items)) {
    const referees: NonNullable<SensitiveValues['referees']> = {};
    const items = (data.items as Data[]).map(({ email, phone, ...rest }) => {
      const id = String(rest.id ?? '');
      if (id && (isNewValue(email) || isNewValue(phone))) {
        referees[id] = { ...(isNewValue(email) ? { email } : {}), ...(isNewValue(phone) ? { phone } : {}) };
      }
      return rest;
    });
    return { safe: { ...data, items }, sensitive: Object.keys(referees).length ? { referees } : null };
  }
  return { safe: data, sensitive: null };
}

export function mergeSensitive(step: StepKey, data: Data, sensitive: SensitiveValues | undefined): Data {
  if (!sensitive) return data;
  if (step === 'profile' && sensitive.nationalId) return { ...data, nationalId: sensitive.nationalId };
  if (step === 'referees' && sensitive.referees && Array.isArray(data.items)) {
    return { ...data, items: (data.items as Data[]).map((item) => ({ ...item, ...sensitive.referees?.[String(item.id)] })) };
  }
  return data;
}

const KEEP_MARKER = 'On file';

// To the server a missing sensitive field means "remove"; "On file" means "keep"
export function markKeptValues(step: StepKey, data: Data, onFile: OnFile): Data {
  if (step === 'profile') {
    return onFile.nationalId && data.nationalId === undefined ? { ...data, nationalId: KEEP_MARKER } : data;
  }
  if (step === 'referees' && Array.isArray(data.items)) {
    return { ...data, items: (data.items as Data[]).map((item) => {
      const kept = onFile.referees[String(item.id)];
      if (!kept) return item;
      return {
        ...item,
        ...(kept.email && item.email === undefined ? { email: KEEP_MARKER } : {}),
        ...(kept.phone && item.phone === undefined ? { phone: KEEP_MARKER } : {}),
      };
    }) };
  }
  return data;
}

export function stripDraftData(data: Partial<Record<StepKey, Data>>): { data: Partial<Record<StepKey, Data>>; onFile: OnFile } {
  const onFile: OnFile = { nationalId: false, referees: {} };
  const out: Partial<Record<StepKey, Data>> = { ...data };
  if (data.profile) {
    const { nationalId, ...profile } = data.profile;
    onFile.nationalId = typeof nationalId === 'string' && nationalId !== '';
    out.profile = profile;
  }
  if (data.referees && Array.isArray(data.referees.items)) {
    out.referees = {
      ...data.referees,
      items: (data.referees.items as Data[]).map(({ email, phone, ...rest }) => {
        onFile.referees[String(rest.id ?? '')] = { email: Boolean(email), phone: Boolean(phone) };
        return rest;
      }),
    };
  }
  return { data: out, onFile };
}
