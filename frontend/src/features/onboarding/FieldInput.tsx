import type { FocusEvent } from 'react';
import type { UseFormRegisterReturn } from 'react-hook-form';
import { Field } from '../../components/ui';
import type { FieldDef } from './stepConfig';

export function FieldInput({ def, id, registration, error, disabled, offline, onFile }: {
  def: FieldDef; id: string; registration: UseFormRegisterReturn; error?: string; disabled?: boolean;
  offline?: boolean;
  onFile?: boolean;
}) {
  const locked = Boolean(offline && def.sensitive);
  const common = { id, disabled: disabled || locked, 'aria-invalid': error ? true : undefined, ...registration,
    ...(locked ? { placeholder: onFile ? 'On file' : 'Not provided yet' } : {}) };
  const selectIfMasked = (e: FocusEvent<HTMLInputElement>) => {
    if (def.sensitive && e.currentTarget.value.startsWith('••••')) e.currentTarget.select();
  };
  let control;
  if (def.kind === 'textarea') {
    control = <textarea className="textarea" maxLength={def.max} {...common} />;
  } else if (def.kind === 'select') {
    control = (
      <select className="select" {...common}>
        <option value="">Select…</option>
        {def.options?.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
      </select>
    );
  } else {
    const type = def.kind === 'number' ? 'number' : def.kind === 'date' ? 'date' : def.kind === 'tel' ? 'tel' : 'text';
    control = <input className="input" type={type} maxLength={def.kind === 'number' ? undefined : def.max}
      autoComplete="off" inputMode={def.kind === 'number' ? 'numeric' : undefined} min={def.range?.[0]} max={def.range?.[1]} onFocus={selectIfMasked} {...common} />;
  }
  return (
    <Field label={def.label} hint={locked ? 'Needs a connection, for your security' : def.hint ?? (def.sensitive ? 'Encrypted · shown masked once saved; type to replace' : undefined)} error={error} htmlFor={id} className={def.wide ? 'wide' : undefined}>
      {control}
    </Field>
  );
}
