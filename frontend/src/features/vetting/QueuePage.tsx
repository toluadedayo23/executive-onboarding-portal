import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Alert, Empty, Loading, PageHeader, StatusBadge } from '../../components/ui';
import { errorMessage } from '../../lib/api';
import { getQueue } from '../../lib/endpoints';
import { formatDate } from '../../lib/format';
import type { CaseStatus, QueueItem } from '../../lib/types';
import { useUser } from '../auth/AuthProvider';

type Tab = { key: string; label: string; filter: (c: QueueItem) => boolean };

const inReview = (c: QueueItem) => c.status === 'UNDER_REVIEW' || c.status === 'MORE_INFO_REQUIRED';
const decided = (c: QueueItem) => c.status === 'VERIFIED' || c.status === 'REJECTED';

const CONSULTANT_TABS: Tab[] = [
  { key: 'new', label: 'Awaiting pickup', filter: (c) => c.status === 'SUBMITTED' },
  { key: 'mine', label: 'Assigned to me', filter: (c) => c.assignedToMe && inReview(c) },
  { key: 'decided', label: 'My decisions', filter: (c) => c.assignedToMe && decided(c) },
];

const ADMIN_TABS: Tab[] = [
  { key: 'new', label: 'Awaiting pickup', filter: (c) => c.status === 'SUBMITTED' },
  { key: 'review', label: 'In review', filter: inReview },
  { key: 'decided', label: 'Decided', filter: decided },
  { key: 'all', label: 'All cases', filter: () => true },
];

export function QueuePage() {
  const user = useUser();
  const navigate = useNavigate();
  const queue = useQuery({ queryKey: ['queue'], queryFn: () => getQueue() });
  const tabs = user.role === 'ADMIN' ? ADMIN_TABS : CONSULTANT_TABS;
  const [chosen, setChosen] = useState<string | null>(null);

  const items = queue.data ?? [];
  const active = chosen ?? (tabs.find((t) => items.some(t.filter)) ?? tabs[0]).key;
  const setActive = setChosen;
  const tab = tabs.find((t) => t.key === active) ?? tabs[0];
  const visible = items.filter(tab.filter);

  return (
    <div className="page">
      <PageHeader eyebrow={user.role === 'ADMIN' ? 'Read-only oversight' : 'Consultant workspace'} title="Vetting cases"
        description={user.role === 'ADMIN'
          ? 'Administrators can view cases but cannot change their status (separation of duties).'
          : 'You see cases waiting for a reviewer and the cases you handle. Pick one up to become its reviewer; oldest first.'} />
      <div className="tabs" role="tablist">
        {tabs.map((t) => (
          <button key={t.key} role="tab" className="tab" aria-selected={t.key === active} onClick={() => setActive(t.key)}>
            {t.label}<span className="tab__count">{items.filter(t.filter).length}</span>
          </button>
        ))}
      </div>
      <div className="card">
        {queue.isLoading && <Loading />}
        {queue.isError && <div className="card__body"><Alert tone="danger">{errorMessage(queue.error)}</Alert></div>}
        {queue.isSuccess && visible.length === 0 && <Empty title="Nothing here">No cases match this view.</Empty>}
        {visible.length > 0 && (
          <div className="table-wrap">
            <table className="table">
              <thead>
                <tr><th>Executive</th><th>Status</th><th>Reviewer</th><th>Submitted</th><th>Last update</th></tr>
              </thead>
              <tbody>
                {visible.map((c) => (
                  <tr key={c.id} className="clickable" onClick={() => navigate(`/cases/${c.id}`)}>
                    <td>
                      <a href={`/cases/${c.id}`} className="cell-title" style={{ color: 'inherit', textDecoration: 'none' }}
                        onClick={(e) => { e.preventDefault(); navigate(`/cases/${c.id}`); }}>{c.executiveName}</a>
                      {c.headline && <div className="cell-sub">{c.headline}</div>}
                    </td>
                    <td><StatusBadge status={c.status as CaseStatus} /></td>
                    <td className="small">{c.assignedToMe ? 'You' : c.assignedConsultantEmail ?? <span className="muted">Unassigned</span>}</td>
                    <td className="small">{formatDate(c.submittedAt)}</td>
                    <td className="small">{formatDate(c.updatedAt)}</td>
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
