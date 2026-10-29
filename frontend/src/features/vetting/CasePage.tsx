import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { Link, useParams } from 'react-router-dom';
import { Alert, Field, Loading, PageHeader, StatusBadge } from '../../components/ui';
import { ApiError, errorMessage } from '../../lib/api';
import { addNote, caseDocumentUrl, getCase, transitionCase } from '../../lib/endpoints';
import { formatDate, formatDateTime, STATUS_LABEL } from '../../lib/format';
import type { CaseAction, CaseView, HistoryEvent } from '../../lib/types';
import { useUser } from '../auth/AuthProvider';
import { DocumentList } from '../onboarding/DocumentsStep';

type Item = Record<string, string | number | undefined>;
const items = (section: Record<string, unknown> | undefined) => (Array.isArray(section?.items) ? (section.items as Item[]) : []);

const ACTION_COPY: Record<string, { label: string; prompt: string; style: string; final?: boolean }> = {
  UNDER_REVIEW: { label: 'Pick up this case', prompt: 'Optional note', style: 'btn--primary' },
  VERIFIED: { label: 'Verify', prompt: 'Decision note (optional)', style: 'btn--primary', final: true },
  MORE_INFO_REQUIRED: { label: 'Request more information', prompt: 'Message to the executive. They will see exactly this text.', style: 'btn--secondary' },
  REJECTED: { label: 'Reject', prompt: 'Reason for rejection (recorded on the case)', style: 'btn--danger-outline', final: true },
};

export function CasePage() {
  const { id = '' } = useParams();
  const user = useUser();
  const query = useQuery({ queryKey: ['case', id], queryFn: () => getCase(id) });
  if (query.isLoading) return <Loading label="Loading case…" />;
  if (query.isError) {
    const notFound = query.error instanceof ApiError && query.error.status === 404;
    return (
      <div className="page page--narrow">
        <Alert tone={notFound ? 'info' : 'danger'} title={notFound ? 'Case not available' : undefined}>
          {notFound ? 'This case does not exist or is not assigned to you.' : errorMessage(query.error)}
          <div style={{ marginTop: 8 }}><Link to={user.role === 'ADMIN' ? '/queue' : '/'}>← Back to cases</Link></div>
        </Alert>
      </div>
    );
  }
  const c = query.data!;
  const profile = c.profile.profile ?? {};

  return (
    <div className="page">
      <p className="small" style={{ marginBottom: 'var(--space-3)' }}><Link to={user.role === 'ADMIN' ? '/queue' : '/'}>← Back to cases</Link></p>
      <PageHeader eyebrow="Vetting case" title={c.executiveName} description={String(profile.headline ?? '')}
        actions={<StatusBadge status={c.status} />} />
      <div className="split">
        <div className="stack--lg">
          <ProfileCard c={c} />
          <div className="card">
            <div className="card__header"><h2>Documents</h2><span className="small muted">Downloads are logged</span></div>
            <div className="card__body"><DocumentList documents={c.documents} hrefFor={(d) => caseDocumentUrl(c.id, d.id)} /></div>
          </div>
        </div>
        <aside className="stack--lg">
          <CaseFacts c={c} />
          <DecisionPanel c={c} />
          <HistoryPanel c={c} />
          <NotesPanel c={c} />
        </aside>
      </div>
    </div>
  );
}

function ProfileCard({ c }: { c: CaseView }) {
  const p = c.profile.profile ?? {};
  return (
    <div className="card">
      <div className="card__header"><h2>Executive profile</h2></div>
      <div className="card__body">
        <div className="profile-block">
          <dl className="dl">
            <dt>Location</dt><dd>{[p.city, p.country].filter(Boolean).join(', ') || '—'}</dd>
            <dt>Phone</dt><dd>{String(p.phone ?? '—')}</dd>
            <dt>National ID</dt><dd>{String(p.nationalId ?? 'Not provided')}</dd>
          </dl>
          {p.summary ? <p style={{ marginTop: 'var(--space-3)', whiteSpace: 'pre-wrap' }}>{String(p.summary)}</p> : null}
        </div>
        <div className="profile-block">
          <div className="section-title">Experience</div>
          {items(c.profile.experience).map((x, i) => (
            <div className="entry" key={i}>
              <div className="entry__title">{x.title} · {x.organisation}</div>
              <div className="small muted">{x.type === 'BOARD' ? 'Board seat' : 'Executive role'} · {formatDate(String(x.startDate ?? ''))} – {x.endDate ? formatDate(String(x.endDate)) : 'present'}</div>
              {x.description && <p className="small" style={{ marginTop: 4 }}>{x.description}</p>}
            </div>
          ))}
        </div>
        <div className="profile-block">
          <div className="section-title">Sector expertise</div>
          <div className="row">{items(c.profile.sectors).map((x, i) => (
            <span key={i} className="badge badge--DRAFT">{x.sector} · {x.yearsExperience} yrs</span>
          ))}</div>
        </div>
        <div className="profile-block">
          <div className="section-title">Qualifications</div>
          {items(c.profile.qualifications).map((x, i) => (
            <div className="entry" key={i}><span className="entry__title">{x.title}</span> <span className="muted small">· {x.institution}, {x.yearObtained}</span></div>
          ))}
        </div>
        <div className="profile-block">
          <div className="section-title">Referees</div>
          {items(c.profile.referees).map((x, i) => (
            <div className="entry" key={i}>
              <div className="entry__title">{x.name} <span className="muted small">· {x.relationship}</span></div>
              <div className="small">{x.email} · {x.phone}</div>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}

function CaseFacts({ c }: { c: CaseView }) {
  return (
    <div className="card">
      <div className="card__body stack">
        <dl className="dl">
          <dt>Submitted</dt><dd>{formatDateTime(c.submittedAt)}</dd>
          <dt>Reviewer</dt><dd>{c.assignedToMe ? 'You' : c.assignedConsultantEmail ?? 'Unassigned'}</dd>
          <dt>Last update</dt><dd>{formatDateTime(c.updatedAt)}</dd>
          {c.decidedAt && <><dt>Decided</dt><dd>{formatDateTime(c.decidedAt)}</dd></>}
        </dl>
        {c.moreInfoMessage && <Alert tone="warn" title="Waiting on the executive">“{c.moreInfoMessage}”</Alert>}
        {c.decisionReason && <Alert tone={c.status === 'REJECTED' ? 'danger' : 'ok'} title="Decision note">{c.decisionReason}</Alert>}
      </div>
    </div>
  );
}

const HISTORY_TEXT: Record<HistoryEvent, string> = {
  SUBMITTED: 'Submitted for vetting',
  PICKED_UP: 'Picked up for review',
  MORE_INFO_REQUESTED: 'More information requested',
  RESUBMITTED: 'Resubmitted with updates',
  NOTE_ADDED: 'Reviewer note added',
  VERIFIED: 'Verified',
  REJECTED: 'Rejected',
  OTHER: 'Case updated',
};

function HistoryPanel({ c }: { c: CaseView }) {
  return (
    <div className="card">
      <div className="card__header"><div><h2>Case history</h2><span className="small muted">From the tamper-evident audit trail</span></div></div>
      <div className="card__body">
        {c.history.length === 0 && <p className="small muted">No recorded events yet.</p>}
        <ol className="timeline timeline--compact" aria-label="Case history">
          {c.history.map((h, i) => (
            <li key={i}>
              <span className={`timeline__dot ${h.event === 'NOTE_ADDED' ? '' : 'timeline__dot--done'}`} />
              <div>
                <div style={{ fontWeight: 600 }}>{HISTORY_TEXT[h.event]}</div>
                <div className="small muted">{h.actorEmail ?? 'Unknown'}{h.actorRole ? ` · ${h.actorRole.toLowerCase()}` : ''}</div>
                <div className="small muted">{formatDateTime(h.at)}</div>
              </div>
            </li>
          ))}
        </ol>
      </div>
    </div>
  );
}

function DecisionPanel({ c }: { c: CaseView }) {
  const queryClient = useQueryClient();
  const [selected, setSelected] = useState<CaseAction | null>(null);
  const [message, setMessage] = useState('');
  const mutation = useMutation({
    mutationFn: (action: CaseAction) => transitionCase(c.id, action.status, message.trim() || undefined),
    onSuccess: (updated) => {
      queryClient.setQueryData(['case', c.id], updated);
      queryClient.invalidateQueries({ queryKey: ['queue'] });
      setSelected(null);
      setMessage('');
    },
  });

  if (c.actions.length === 0) {
    const why = c.status === 'VERIFIED' || c.status === 'REJECTED' ? 'This case is closed. Decisions are final.'
      : c.status === 'MORE_INFO_REQUIRED' ? 'Waiting for the executive to update and resubmit.'
      : c.assignedConsultantEmail ? `Only the assigned reviewer (${c.assignedConsultantEmail}) can act on this case.`
      : 'You can view this case but not change it.';
    return <div className="card"><div className="card__body"><h2 style={{ marginBottom: 8 }}>Decision</h2><p className="small muted">{why}</p></div></div>;
  }

  if (c.actions.length === 1 && !c.actions[0].messageRequired) {
    const only = c.actions[0];
    return (
      <div className="card">
        <div className="card__body stack">
          <h2>Decision</h2>
          <p className="small muted">Picking up this case makes you its reviewer. Only you will be able to decide it.</p>
          {mutation.isError && <Alert tone="danger">{errorMessage(mutation.error)}</Alert>}
          <button className="btn btn--primary" disabled={mutation.isPending} onClick={() => mutation.mutate(only)}>
            {mutation.isPending ? 'Saving…' : ACTION_COPY[only.status]?.label ?? STATUS_LABEL[only.status]}
          </button>
        </div>
      </div>
    );
  }

  const copy = selected ? ACTION_COPY[selected.status] : undefined;
  const needsMessage = selected?.messageRequired ?? false;
  return (
    <div className="card">
      <div className="card__header"><h2>Decision</h2></div>
      <div className="card__body stack">
        {!selected && (
          <div className="action-list">
            {c.actions.map((a) => (
              <button key={a.status} className={`btn ${ACTION_COPY[a.status]?.style ?? 'btn--secondary'}`} onClick={() => setSelected(a)}>
                {ACTION_COPY[a.status]?.label ?? STATUS_LABEL[a.status]}
              </button>
            ))}
          </div>
        )}
        {selected && (
          <form className="stack" onSubmit={(e: FormEvent) => { e.preventDefault(); mutation.mutate(selected); }}>
            <div className="eyebrow">{copy?.label}</div>
            {copy?.final && <Alert tone="warn">This decision is final and cannot be reopened.</Alert>}
            <Field label={copy?.prompt ?? 'Note'} hint={needsMessage ? 'Required' : undefined} htmlFor="decision-message">
              <textarea id="decision-message" className="textarea" maxLength={2000} required={needsMessage} autoFocus
                value={message} onChange={(e) => setMessage(e.target.value)} />
            </Field>
            {mutation.isError && <Alert tone="danger">{errorMessage(mutation.error)}</Alert>}
            <div className="action-list">
              <button className={`btn ${selected.status === 'REJECTED' ? 'btn--danger' : 'btn--primary'}`}
                disabled={mutation.isPending || (needsMessage && !message.trim())}>
                {mutation.isPending ? 'Saving…' : `Confirm: ${copy?.label ?? ''}`}
              </button>
              <button type="button" className="btn btn--ghost" onClick={() => { setSelected(null); setMessage(''); mutation.reset(); }}>Cancel</button>
            </div>
          </form>
        )}
      </div>
    </div>
  );
}

function NotesPanel({ c }: { c: CaseView }) {
  const user = useUser();
  const queryClient = useQueryClient();
  const [note, setNote] = useState('');
  const mutation = useMutation({
    mutationFn: () => addNote(c.id, note.trim()),
    onSuccess: (updated) => { queryClient.setQueryData(['case', c.id], updated); setNote(''); },
  });
  return (
    <div className="card">
      <div className="card__header"><div><h2>Reviewer notes</h2><span className="small muted">Internal. Never shown to the executive.</span></div></div>
      <div className="card__body stack">
        {c.notes.length === 0 && <p className="small muted">No notes yet.</p>}
        {c.notes.map((n) => (
          <div className="note" key={n.id}>
            <div style={{ whiteSpace: 'pre-wrap' }}>{n.note}</div>
            <div className="note__meta">{n.authorEmail} · {formatDateTime(n.createdAt)}</div>
          </div>
        ))}
        {user.role === 'CONSULTANT' ? (
          <form className="stack" onSubmit={(e) => { e.preventDefault(); if (note.trim()) mutation.mutate(); }}>
            <label className="visually-hidden" htmlFor="new-note">New note</label>
            <textarea id="new-note" className="textarea" style={{ minHeight: 80 }} maxLength={2000} placeholder="Add an internal note…"
              value={note} onChange={(e) => setNote(e.target.value)} />
            {mutation.isError && <Alert tone="danger">{errorMessage(mutation.error)}</Alert>}
            <div className="row row--end"><button className="btn btn--secondary btn--small" disabled={!note.trim() || mutation.isPending}>Add note</button></div>
          </form>
        ) : null}
      </div>
    </div>
  );
}
