import { keepPreviousData, useMutation, useQuery } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { Alert, Empty, Loading, PageHeader } from '../../components/ui';
import { errorMessage } from '../../lib/api';
import { getAudit, verifyAudit, type AuditCategory, type AuditFilters } from '../../lib/endpoints';
import { formatDateTime } from '../../lib/format';
import { describeAudit } from './auditText';

const VIEWS: { key: AuditCategory; label: string; hint: string }[] = [
  { key: 'KEY', label: 'Key events', hint: 'Submissions, review decisions and account changes' },
  { key: 'SECURITY', label: 'Security', hint: 'Failed sign-ins, lockouts, password changes and document downloads' },
  { key: 'ALL', label: 'Everything', hint: 'The complete chain, including routine sign-ins and uploads' },
];

const NO_FILTERS: AuditFilters = { user: '', from: '', to: '' };

export function AuditPage() {
  const [page, setPage] = useState(0);
  const [view, setView] = useState<AuditCategory>('KEY');
  const [draft, setDraft] = useState<AuditFilters>(NO_FILTERS);
  const [filters, setFilters] = useState<AuditFilters>(NO_FILTERS);
  const log = useQuery({ queryKey: ['audit', view, page, filters], queryFn: () => getAudit(page, view, filters), placeholderData: keepPreviousData });
  const filtering = Boolean(filters.user || filters.from || filters.to);
  const badRange = Boolean(draft.from && draft.to && draft.from > draft.to);

  function apply(event: FormEvent) {
    event.preventDefault();
    if (badRange) return;
    setFilters(draft);
    setPage(0);
  }

  function clear() {
    setDraft(NO_FILTERS);
    setFilters(NO_FILTERS);
    setPage(0);
  }
  const verify = useMutation({ mutationFn: verifyAudit });
  const rows = log.data?.content ?? [];

  return (
    <div className="page">
      <PageHeader eyebrow="Administration" title="Audit trail"
        description="Append-only and hash-chained: each entry includes the hash of the one before, so any edit breaks the chain."
        actions={<button className="btn btn--primary" disabled={verify.isPending} onClick={() => verify.mutate()}>
          {verify.isPending ? 'Verifying…' : 'Verify chain integrity'}</button>} />

      {verify.data && (
        <div style={{ marginBottom: 'var(--space-4)' }}>
          {verify.data.valid
            ? <Alert tone="ok" title="Chain intact">All {verify.data.entriesChecked} entries checked at {formatDateTime(verify.data.checkedAt)}. No tampering detected.</Alert>
            : <Alert tone="danger" title="Chain broken">First broken entry #{verify.data.firstBrokenId} ({verify.data.reason}).</Alert>}
        </div>
      )}
      {verify.isError && <Alert tone="danger">{errorMessage(verify.error)}</Alert>}

      <div className="tabs" role="tablist">
        {VIEWS.map((v) => (
          <button key={v.key} role="tab" className="tab" aria-selected={v.key === view} title={v.hint}
            onClick={() => { setView(v.key); setPage(0); }}>{v.label}</button>
        ))}
      </div>

      <form className="card audit-filters" onSubmit={apply} aria-label="Filter the audit trail">
        <div className="card__body">
          <div className="audit-filters__row">
            <label className="field">
              <span className="field__label">User</span>
              <input className="input" type="search" placeholder="Email, e.g. consultant1" maxLength={254}
                value={draft.user} onChange={(e) => setDraft({ ...draft, user: e.target.value })} />
              <span className="field__hint">Who acted, or whose account was acted on</span>
            </label>
            <label className="field">
              <span className="field__label">From</span>
              <input className="input" type="date" value={draft.from} onChange={(e) => setDraft({ ...draft, from: e.target.value })} />
            </label>
            <label className="field">
              <span className="field__label">To</span>
              <input className="input" type="date" value={draft.to} onChange={(e) => setDraft({ ...draft, to: e.target.value })}
                aria-invalid={badRange || undefined} />
              {badRange && <span className="field__error">"To" is before "From"</span>}
            </label>
            <div className="row audit-filters__actions">
              <button className="btn btn--primary" disabled={badRange}>Apply</button>
              {filtering && <button type="button" className="btn btn--ghost" onClick={clear}>Clear</button>}
            </div>
          </div>
        </div>
      </form>

      <div className="card">
        <div className="card__header">
          <span className="small muted">{VIEWS.find((v) => v.key === view)?.hint}</span>
          <div className="row">
            <button className="btn btn--secondary btn--small" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>Newer</button>
            <span className="small muted">Page {page + 1}</span>
            <button className="btn btn--secondary btn--small" disabled={rows.length < 25} onClick={() => setPage((p) => p + 1)}>Older</button>
          </div>
        </div>
        {log.isLoading && <Loading />}
        {log.isError && <div className="card__body"><Alert tone="danger">{errorMessage(log.error)}</Alert></div>}
        {log.isSuccess && rows.length === 0 && (
          <Empty title="No entries">{filtering ? 'Nothing matches these filters in this view.' : undefined}</Empty>
        )}
        {rows.length > 0 && (
          <div className="table-wrap">
            <table className="table">
              <thead><tr><th>When</th><th>What happened</th><th>Actor role</th><th>From</th><th>Entry</th></tr></thead>
              <tbody>
                {rows.map((r) => (
                  <tr key={r.id}>
                    <td className="small" style={{ whiteSpace: 'nowrap' }}>{formatDateTime(r.createdAt)}</td>
                    <td><div className="cell-title small">{describeAudit(r)}</div><div className="cell-sub mono">{r.action}</div></td>
                    <td className="small">{r.actorRole ?? '—'}</td>
                    <td className="mono">{r.ip ?? '—'}</td>
                    <td className="mono muted" title={`hash ${r.hash}`}>#{r.id} · {r.hash.slice(0, 8)}…</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  );
}
