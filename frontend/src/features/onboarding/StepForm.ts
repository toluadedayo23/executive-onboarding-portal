export function hasChanges(cleaned: unknown, original: unknown): boolean {
  return JSON.stringify(cleaned) !== JSON.stringify(original);
}

export type StepFormProps = {
  formId: string;
  initial: Record<string, unknown> | undefined;
  disabled: boolean;
  offline: boolean;
  onFile: { nationalId: boolean; referees: Record<string, { email: boolean; phone: boolean }> };
  onSubmit: (data: unknown, dirty: boolean) => void;
};
