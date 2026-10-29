import { zodResolver } from '@hookform/resolvers/zod';
import { useFieldArray, useForm } from 'react-hook-form';
import { z } from 'zod';
import { FieldInput } from './FieldInput';
import { fromFormRecord, objectSchema, toFormRecord, type FormRecord, type ListStepConfig } from './stepConfig';
import { hasChanges, type StepFormProps } from './StepForm';

type ListValues = { items: FormRecord[] };

export function ListStep({ formId, config, initial, disabled, offline, onFile, onSubmit }: StepFormProps & { config: ListStepConfig }) {
  const keep = ['id'];
  const schema = z.object({ items: z.array(objectSchema(config.fields).extend({ id: z.string() })).max(config.max) });
  const initialItems = Array.isArray(initial?.items) ? (initial.items as Record<string, unknown>[]) : [];
  const blank = toFormRecord(config.fields, undefined, keep);
  const newItem = () => ({ ...blank, id: crypto.randomUUID() });
  const startCount = Math.max(initialItems.length, config.min);
  const defaults = Array.from({ length: startCount }, (_, i) =>
    initialItems[i] ? toFormRecord(config.fields, initialItems[i], keep) : newItem());

  const form = useForm<ListValues>({ resolver: zodResolver(schema), defaultValues: { items: defaults } });
  const { fields, append, remove } = useFieldArray({ control: form.control, name: 'items' });
  const errors = form.formState.errors.items;

  return (
    <form id={formId} noValidate className="stack" onSubmit={form.handleSubmit((values) => {
      const clean = (items: FormRecord[]) => ({
        items: items.map((item) => fromFormRecord(config.fields, item, keep)).filter((item) => Object.keys(item).some((k) => k !== 'id')),
      });
      const cleaned = clean(values.items);
      onSubmit(cleaned, hasChanges(cleaned, clean(defaults)));
    })}>
      {fields.map((field, index) => (
        <fieldset key={field.id} className="repeat-item" disabled={disabled} style={{ margin: 0 }}>
          <div className="repeat-item__head">
            <legend className="section-title" style={{ margin: 0 }}>{config.itemLabel} {index + 1}</legend>
            {!disabled && fields.length > 1 && (
              <button type="button" className="link-btn link-btn--danger" onClick={() => remove(index)}>Remove</button>
            )}
          </div>
          <div className="form-grid">
            {config.fields.map((def) => (
              <FieldInput key={def.name} def={def} id={`${formId}-${index}-${def.name}`}
                offline={offline} onFile={Boolean(onFile.referees[String(form.getValues(`items.${index}.id`))]?.[def.name as 'email' | 'phone'])}
                registration={form.register(`items.${index}.${def.name}`)}
                error={errors?.[index]?.[def.name]?.message} />
            ))}
          </div>
        </fieldset>
      ))}
      {!disabled && fields.length < config.max && (
        <div>
          <button type="button" className="btn btn--secondary" onClick={() => append(newItem())}>
            + Add {config.itemLabel.toLowerCase()}
          </button>
        </div>
      )}
      {config.min > 1 && <p className="small muted">At least {config.min} are required before you submit.</p>}
    </form>
  );
}
