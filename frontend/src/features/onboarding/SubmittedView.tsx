import { useQuery } from '@tanstack/react-query';
import { PageHeader, StatusBadge } from '../../components/ui';
import { listDocuments, ownDocumentUrl } from '../../lib/endpoints';
import { formatDateTime } from '../../lib/format';
import type { Draft } from '../../lib/types';
import { NotificationsCard } from '../notifications/NotificationsCard';
import { DocumentList } from './DocumentsStep';

const STAGES = ['SUBMITTED', 'UNDER_REVIEW', 'DECISION'] as const;

function stageIndex(status: Draft['status']) {
  return status === 'VERIFIED' || status === 'REJECTED' ? 3 : 1;
}

const OUTCOME: Partial<Record<Draft['status'], string>> = {
  SUBMITTED: 'Your profile is in the queue. A consultant will pick it up shortly.',
  UNDER_REVIEW: 'A consultant is reviewing your profile and may contact your referees.',
  VERIFIED: 'Your profile has been verified. Our team will be in touch about suitable opportunities.',
  REJECTED: 'Your profile was not verified on this occasion. Contact RiskTech if you have questions.',
};

export function SubmittedView({ draft }: { draft: Draft }) {
  const documents = useQuery({ queryKey: ['documents'], queryFn: listDocuments });
  const current = stageIndex(draft.status);
  const labels = ['Submitted', draft.status === 'SUBMITTED' ? 'Waiting for a reviewer' : 'Under review',
    draft.status === 'REJECTED' ? 'Not verified' : 'Verified'];

  return (
    <div className="page page--narrow stack--lg">
      <PageHeader eyebrow="My onboarding" title="Your submission" actions={<StatusBadge status={draft.status} />}
        description={`Submitted ${formatDateTime(draft.submittedAt)}`} />
      <div className="card">
        <div className="card__body stack">
          <p>{OUTCOME[draft.status]}</p>
          <ol className="timeline" aria-label="Vetting progress">
            {STAGES.map((stage, i) => (
              <li key={stage}>
                <span className={`timeline__dot ${i < current ? 'timeline__dot--done' : i === current ? 'timeline__dot--current' : ''}`} />
                <div>
                  <div style={{ fontWeight: 600 }}>{labels[i]}</div>
                  <div className="small muted">{i < current ? 'Done' : i === current ? 'Current stage' : 'Upcoming'}</div>
                </div>
              </li>
            ))}
          </ol>
          <p className="small muted">Check back here for updates. Your answers are locked while under review.</p>
        </div>
      </div>
      <NotificationsCard />
      <div className="card">
        <div className="card__header"><h2>Documents on file</h2></div>
        <div className="card__body">
          <DocumentList documents={documents.data ?? []} hrefFor={(d) => ownDocumentUrl(d.id)} />
        </div>
      </div>
    </div>
  );
}
