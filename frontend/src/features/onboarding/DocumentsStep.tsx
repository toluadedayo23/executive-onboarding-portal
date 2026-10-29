import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState, type ChangeEvent } from 'react';
import { Alert } from '../../components/ui';
import { ApiError, errorMessage, type Problem } from '../../lib/api';
import { syncEngine } from '../../offline';
import { deleteDocument, listDocuments, ownDocumentUrl, submitDraft, uploadDocument } from '../../lib/endpoints';
import { formatBytes, formatDate, ID_TYPE_LABEL } from '../../lib/format';
import type { DocumentType, DocumentView, Draft } from '../../lib/types';
import { describeProblem, WIZARD_STEPS } from './stepConfig';

const MAX_BYTES = 5 * 1024 * 1024;
const ACCEPT = 'application/pdf,image/jpeg,image/png,.pdf,.jpg,.jpeg,.png';

function documentLabel(d: DocumentView): string {
  if (d.type === 'CV') return 'CV / résumé';
  if (d.type === 'ID') return `Identification · ${ID_TYPE_LABEL[d.identificationType ?? ''] ?? 'ID'}`;
  return `Certificate · ${d.certificationName ?? ''}`;
}

export function DocumentList({ documents, hrefFor }: { documents: DocumentView[]; hrefFor: (d: DocumentView) => string }) {
  if (documents.length === 0) return <p className="muted small">No documents uploaded yet.</p>;
  return (
    <div>
      {documents.map((d) => (
        <div className="doc-row" key={d.id}>
          <div className="doc-row__icon" aria-hidden>{d.contentType === 'application/pdf' ? 'PDF' : 'IMG'}</div>
          <div className="doc-row__main">
            <div className="doc-row__name">{documentLabel(d)}</div>
            <div className="small muted">{d.originalName} · {formatBytes(d.sizeBytes)} · {formatDate(d.uploadedAt)}</div>
          </div>
          <a className="btn btn--secondary btn--small" href={hrefFor(d)}>Download</a>
        </div>
      ))}
    </div>
  );
}

function FilePicker({ label, disabled, onFile }: { label: string; disabled?: boolean; onFile: (file: File) => void }) {
  function change(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    event.target.value = '';
    if (file) onFile(file);
  }
  return (
    <label className={`btn btn--secondary btn--small file-btn ${disabled ? 'is-disabled' : ''}`}>
      {label}
      <input type="file" accept={ACCEPT} disabled={disabled} onChange={change} />
    </label>
  );
}

function useUpload(type: DocumentType) {
  const queryClient = useQueryClient();
  const [error, setError] = useState('');
  const mutation = useMutation({
    mutationFn: ({ file, extra }: { file: File; extra: { identificationType?: string; certificationName?: string } }) =>
      uploadDocument(type, file, extra),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['documents'] }),
    onError: (e) => setError(errorMessage(e)),
  });
  function upload(file: File, extra: { identificationType?: string; certificationName?: string } = {}) {
    setError('');
    if (file.size > MAX_BYTES) return setError('Files must be 5 MB or smaller.');
    mutation.mutate({ file, extra });
  }
  return { upload, busy: mutation.isPending, error };
}

function SlotFiles({ docs }: { docs: DocumentView[] }) {
  const queryClient = useQueryClient();
  const [confirming, setConfirming] = useState<string | null>(null);
  const remove = useMutation({
    mutationFn: (id: string) => deleteDocument(id),
    onSuccess: () => { setConfirming(null); return queryClient.invalidateQueries({ queryKey: ['documents'] }); },
  });
  return (
    <>
      {docs.map((d) => (
        <div className="slot-file" key={d.id}>
          <span className="doc-row__icon" aria-hidden>{d.contentType === 'application/pdf' ? 'PDF' : 'IMG'}</span>
          <span className="slot-file__name">{d.type === 'CERTIFICATE' ? d.certificationName : d.originalName}</span>
          <span className="small muted">{d.type === 'ID' ? `${ID_TYPE_LABEL[d.identificationType ?? '']} · ` : ''}{formatBytes(d.sizeBytes)}</span>
          <a className="link-btn small" href={ownDocumentUrl(d.id)}>Download</a>
          {confirming === d.id ? (
            <span className="row" style={{ gap: 8 }}>
              <button type="button" className="link-btn link-btn--danger small" disabled={remove.isPending} onClick={() => remove.mutate(d.id)}>
                {remove.isPending ? 'Removing…' : 'Confirm remove'}</button>
              <button type="button" className="link-btn small" onClick={() => setConfirming(null)}>Keep</button>
            </span>
          ) : (
            <button type="button" className="link-btn link-btn--danger small" onClick={() => setConfirming(d.id)}
              aria-label={`Remove ${d.originalName}`}>Remove</button>
          )}
        </div>
      ))}
      {remove.isError && <Alert tone="danger">{errorMessage(remove.error)}</Alert>}
    </>
  );
}

function RequiredSlot({ type, title, description, docs }: { type: 'CV' | 'ID'; title: string; description: string; docs: DocumentView[] }) {
  const { upload, busy, error } = useUpload(type);
  const [idType, setIdType] = useState('NIN');
  const done = docs.length > 0;
  return (
    <div className={`doc-slot ${done ? 'doc-slot--done' : ''}`}>
      <div className="doc-slot__head">
        <span className={`tick tick--${done ? 'yes' : 'no'}`} aria-hidden>{done ? '✓' : ''}</span>
        <div className="doc-slot__title">
          <strong>{title}</strong>
          <span className="small muted">{description}</span>
        </div>
        {type === 'ID' && (
          <select className="select select--compact" aria-label="Identification type" value={idType} onChange={(e) => setIdType(e.target.value)}>
            {Object.entries(ID_TYPE_LABEL).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
          </select>
        )}
        <FilePicker label={busy ? 'Uploading…' : done ? 'Upload another' : 'Choose file'} disabled={busy}
          onFile={(file) => upload(file, type === 'ID' ? { identificationType: idType } : {})} />
      </div>
      {done && <SlotFiles docs={docs} />}
      {error && <Alert tone="danger">{error}</Alert>}
    </div>
  );
}

function CertificateSlot({ docs }: { docs: DocumentView[] }) {
  const { upload, busy, error } = useUpload('CERTIFICATE');
  const [name, setName] = useState('');
  return (
    <div className="doc-slot">
      <div className="doc-slot__head">
        <span className="tick tick--optional" aria-hidden>+</span>
        <div className="doc-slot__title">
          <strong>Certificates</strong>
          <span className="small muted">Optional. Name the certificate, then choose its file.</span>
        </div>
        <input className="input input--compact" placeholder="e.g. ACCA" aria-label="Certification name" maxLength={160}
          value={name} onChange={(e) => setName(e.target.value)} />
        <FilePicker label={busy ? 'Uploading…' : 'Choose file'} disabled={busy || !name.trim()}
          onFile={(file) => { upload(file, { certificationName: name.trim() }); setName(''); }} />
      </div>
      {docs.length > 0 && <SlotFiles docs={docs} />}
      {error && <Alert tone="danger">{error}</Alert>}
    </div>
  );
}

export function DocumentsStep({ draft }: { draft: Draft }) {
  const queryClient = useQueryClient();
  const documents = useQuery({ queryKey: ['documents'], queryFn: listDocuments });
  const [problems, setProblems] = useState<Problem[]>([]);
  const submit = useMutation({
    mutationFn: async () => {
      await syncEngine.flush();
      if (!syncEngine.canSubmit()) throw new ApiError('Some changes have not synced yet. Try again in a moment.', 0, 'NOT_SYNCED');
      return submitDraft(syncEngine.getState().draft!.version);
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['onboarding'] }),
    onError: (e) => setProblems(e instanceof ApiError && Array.isArray(e.problems) ? (e.problems as Problem[]) : []),
  });

  const docs = documents.data ?? [];
  const ofType = (t: DocumentType) => docs.filter((d) => d.type === t);
  const checklist = [
    ...WIZARD_STEPS.filter((s) => s.key !== 'documents').map((s) => ({
      label: s.label, done: draft.stepStatus[s.key as keyof Draft['stepStatus']] === 'COMPLETE',
    })),
    { label: 'CV', done: ofType('CV').length > 0 },
    { label: 'Identification document', done: ofType('ID').length > 0 },
  ];
  const remaining = checklist.filter((c) => !c.done);
  const resubmitting = draft.status === 'MORE_INFO_REQUIRED';

  return (
    <div className="stack--lg">
      <section className="stack">
        <p className="small muted">PDF, JPG or PNG, up to 5 MB each. Files are checked by their content, stored privately and only shared with your reviewer.</p>
        <RequiredSlot type="CV" title="CV / résumé" description="Required" docs={ofType('CV')} />
        <RequiredSlot type="ID" title="Identification" description="Required · government-issued ID" docs={ofType('ID')} />
        <CertificateSlot docs={ofType('CERTIFICATE')} />
      </section>

      <section className="submit-panel">
        <div>
          <h3>{remaining.length === 0 ? 'Ready to submit' : 'Almost there'}</h3>
          <p className="small muted">
            {remaining.length === 0
              ? 'Everything is complete. Once submitted, your answers are locked while a consultant reviews them.'
              : draft.problems?.length ? 'Still needed:' : 'Complete the unticked items below.'}
          </p>
          {remaining.length > 0 && (draft.problems?.length ?? 0) > 0 && (
            <ul className="small" style={{ margin: '8px 0 0' }}>
              {draft.problems.map((p, i) => <li key={i}>{describeProblem(p)}</li>)}
            </ul>
          )}
          <ul className="checklist checklist--compact">
            {checklist.map((c) => (
              <li key={c.label}><span className={`tick tick--${c.done ? 'yes' : 'no'}`} aria-hidden>{c.done ? '✓' : ''}</span>{c.label}</li>
            ))}
          </ul>
        </div>
        {submit.isError && (
          <Alert tone="danger" title="Your submission needs attention">
            {problems.length > 0 ? <ul>{problems.map((p, i) => <li key={i}>{describeProblem(p)}</li>)}</ul> : errorMessage(submit.error)}
          </Alert>
        )}
        <div className="row row--end">
          <button className="btn btn--primary" disabled={remaining.length > 0 || submit.isPending} onClick={() => submit.mutate()}>
            {submit.isPending ? 'Submitting…' : resubmitting ? 'Resubmit for review' : 'Submit for vetting'}
          </button>
        </div>
      </section>
    </div>
  );
}
