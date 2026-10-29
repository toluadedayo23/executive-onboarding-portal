import { zodResolver } from '@hookform/resolvers/zod';
import { useForm } from 'react-hook-form';
import { FieldInput } from './FieldInput';
import { fromFormRecord, objectSchema, PROFILE_FIELDS, toFormRecord, type FormRecord } from './stepConfig';
import { hasChanges, type StepFormProps } from './StepForm';

const schema = objectSchema(PROFILE_FIELDS);

export function ProfileStep({ formId, initial, disabled, offline, onFile, onSubmit }: StepFormProps) {
  const defaults = toFormRecord(PROFILE_FIELDS, initial);
  const form = useForm<FormRecord>({ resolver: zodResolver(schema), defaultValues: defaults });
  const { errors } = form.formState;
  return (
    <form id={formId} noValidate onSubmit={form.handleSubmit((values) => {
      const cleaned = fromFormRecord(PROFILE_FIELDS, values);
      onSubmit(cleaned, hasChanges(cleaned, fromFormRecord(PROFILE_FIELDS, defaults)));
    })}>
      <div className="form-grid">
        {PROFILE_FIELDS.map((def) => (
          <FieldInput key={def.name} def={def} id={`profile-${def.name}`} disabled={disabled}
            offline={offline} onFile={def.name === 'nationalId' && onFile.nationalId}
            registration={form.register(def.name)} error={errors[def.name]?.message} />
        ))}
      </div>
    </form>
  );
}
