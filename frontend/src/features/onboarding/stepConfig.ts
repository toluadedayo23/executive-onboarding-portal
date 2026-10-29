import { z } from 'zod';
import type { StepKey } from '../../lib/types';

type FieldKind = 'text' | 'textarea' | 'date' | 'number' | 'select' | 'email' | 'tel';
export type FieldDef = {
  name: string;
  label: string;
  kind: FieldKind;
  max: number;
  hint?: string;
  wide?: boolean;
  range?: [number, number];
  sensitive?: boolean;
  options?: { value: string; label: string }[];
};

export const PROFILE_FIELDS: FieldDef[] = [
  { name: 'fullName', label: 'Full name', kind: 'text', max: 120 },
  { name: 'headline', label: 'Professional headline', kind: 'text', max: 120, hint: 'e.g. Chief Risk Officer, Non-executive Director' },
  { name: 'summary', label: 'Executive summary', kind: 'textarea', max: 2000, wide: true, hint: 'At least 50 characters' },
  { name: 'phone', label: 'Phone', kind: 'tel', max: 20 },
  { name: 'country', label: 'Country', kind: 'text', max: 80 },
  { name: 'city', label: 'City', kind: 'text', max: 80, hint: 'Optional' },
  { name: 'nationalId', label: 'National ID number', kind: 'text', max: 32, sensitive: true, hint: 'Optional · encrypted and shown masked once saved' },
];

export type ListStepConfig = { title: string; intro: string; itemLabel: string; min: number; max: number; fields: FieldDef[] };

export const LIST_STEPS: Record<Exclude<StepKey, 'profile'>, ListStepConfig> = {
  experience: {
    title: 'Executive & board experience',
    intro: 'List the executive and board roles that best show your leadership. Most recent first.',
    itemLabel: 'Role', min: 1, max: 20,
    fields: [
      { name: 'type', label: 'Role type', kind: 'select', max: 20, options: [
        { value: 'EXECUTIVE', label: 'Executive role' }, { value: 'BOARD', label: 'Board seat' }] },
      { name: 'title', label: 'Title', kind: 'text', max: 120 },
      { name: 'organisation', label: 'Organisation', kind: 'text', max: 120, wide: true },
      { name: 'startDate', label: 'Start date', kind: 'date', max: 10 },
      { name: 'endDate', label: 'End date', kind: 'date', max: 10, hint: 'Leave blank if current' },
      { name: 'description', label: 'Responsibilities & impact', kind: 'textarea', max: 2000, wide: true },
    ],
  },
  sectors: {
    title: 'Sector expertise',
    intro: 'The sectors you know well and how long you have worked in each.',
    itemLabel: 'Sector', min: 1, max: 10,
    fields: [
      { name: 'sector', label: 'Sector', kind: 'text', max: 80, hint: 'e.g. Banking, Energy, Telecoms' },
      { name: 'yearsExperience', label: 'Years of experience', kind: 'number', max: 3, range: [0, 60] },
    ],
  },
  qualifications: {
    title: 'Qualifications',
    intro: 'Degrees and professional certifications. You can attach certificates in the Documents step.',
    itemLabel: 'Qualification', min: 1, max: 15,
    fields: [
      { name: 'title', label: 'Qualification', kind: 'text', max: 120, hint: 'e.g. MBA, ACCA, CFA' },
      { name: 'institution', label: 'Institution', kind: 'text', max: 120 },
      { name: 'yearObtained', label: 'Year obtained', kind: 'number', max: 4, range: [1950, new Date().getFullYear()] },
    ],
  },
  referees: {
    title: 'Referees',
    intro: 'Two or more professional referees. Contact details are encrypted and only shown to your reviewer.',
    itemLabel: 'Referee', min: 2, max: 5,
    fields: [
      { name: 'name', label: 'Full name', kind: 'text', max: 120 },
      { name: 'relationship', label: 'Relationship', kind: 'text', max: 80, hint: 'e.g. Former board chair' },
      { name: 'email', label: 'Email', kind: 'email', max: 254, sensitive: true },
      { name: 'phone', label: 'Phone', kind: 'tel', max: 20, sensitive: true },
    ],
  },
};

export const WIZARD_STEPS: { key: StepKey | 'documents'; label: string }[] = [
  { key: 'profile', label: 'Professional profile' },
  { key: 'experience', label: 'Experience' },
  { key: 'sectors', label: 'Sector expertise' },
  { key: 'qualifications', label: 'Qualifications' },
  { key: 'referees', label: 'Referees' },
  { key: 'documents', label: 'Documents & submit' },
];

export type FormRecord = Record<string, string>;

const EMAIL = /^[^@\s]+@[^@\s]+\.[^@\s]+$/;

export function objectSchema(fields: FieldDef[]) {
  return z.object(Object.fromEntries(fields.map((f) => {
    let rule = z.string().trim().max(f.max, `Maximum ${f.max} characters`);
    if (f.range) {
      const [min, max] = f.range;
      rule = rule.refine((v) => v === '' || (/^\d+$/.test(v) && Number(v) >= min && Number(v) <= max), `Enter a number from ${min} to ${max}`) as never;
    }
    if (f.kind === 'email') {
      rule = rule.refine((v) => v === '' || v.startsWith('••••') || EMAIL.test(v), 'Enter a valid email address') as never;
    }
    return [f.name, rule];
  })));
}

export function toFormRecord(fields: FieldDef[], source: Record<string, unknown> | undefined, keep: string[] = []): FormRecord {
  const record: FormRecord = {};
  for (const name of [...fields.map((f) => f.name), ...keep]) {
    const value = source?.[name];
    record[name] = value === undefined || value === null ? '' : String(value);
  }
  return record;
}

export function fromFormRecord(fields: FieldDef[], values: FormRecord, keep: string[] = []): Record<string, string | number> {
  const result: Record<string, string | number> = {};
  for (const name of [...fields.map((f) => f.name), ...keep]) {
    const raw = (values[name] ?? '').trim();
    if (raw === '') continue;
    const kind = fields.find((f) => f.name === name)?.kind;
    result[name] = kind === 'number' && !Number.isNaN(Number(raw)) ? Number(raw) : raw;
  }
  return result;
}

export function describeProblem(p: { step: string; field: string; code: string; item?: number | null }): string {
  if (p.step === 'documents') return 'Documents: upload both a CV and an identification document';
  const isList = p.step !== 'profile';
  const config = isList ? LIST_STEPS[p.step as Exclude<StepKey, 'profile'>] : undefined;
  const fields = isList ? config?.fields ?? [] : PROFILE_FIELDS;
  const def = fields.find((f) => f.name === p.field);
  const field = def?.label ?? p.field;
  const where = p.item != null && config ? `${config.itemLabel} ${p.item + 1}` : WIZARD_STEPS.find((s) => s.key === p.step)?.label ?? p.step;

  let text: string;
  switch (p.code) {
    case 'REQUIRED': text = `${field} is required`; break;
    case 'TOO_SHORT': text = p.field === 'summary' ? 'Executive summary needs at least 50 characters' : `${field} is too short`; break;
    case 'TOO_LONG': text = `${field} is too long`; break;
    case 'OUT_OF_RANGE': text = def?.range ? `${field} must be from ${def.range[0]} to ${def.range[1]}` : `${field} is out of range`; break;
    case 'INVALID_FORMAT': text = `${field} is not in a valid format`; break;
    case 'DUPLICATE': text = p.field === 'email' ? 'use a different email from the other referees' : `this ${field.toLowerCase()} is listed twice`; break;
    case 'SELF_REFERENCE': text = 'a referee cannot use your own email'; break;
    case 'IN_FUTURE': text = `${field} cannot be in the future`; break;
    case 'BEFORE_START': text = 'End date is before the start date'; break;
    case 'MIN_ITEMS': text = config ? `add at least ${config.min} ${config.itemLabel.toLowerCase()}${config.min > 1 ? 's' : ''}` : 'needs more entries'; break;
    case 'MAX_ITEMS': text = 'too many entries'; break;
    default: text = `${field} needs attention`;
  }
  return `${where}: ${text}`;
}
