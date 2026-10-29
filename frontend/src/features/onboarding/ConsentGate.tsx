import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Alert } from '../../components/ui';
import { errorMessage } from '../../lib/api';
import { grantConsent } from '../../lib/endpoints';

export function ConsentGate({ notice }: { notice: string }) {
  const queryClient = useQueryClient();
  const [agreed, setAgreed] = useState(false);
  const grant = useMutation({
    mutationFn: grantConsent,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['consent'] }),
  });

  return (
    <div className="page page--narrow">
      <div className="card">
        <div className="card__body stack">
          <div className="eyebrow">Before you begin</div>
          <h1>How we use your information</h1>
          <p>{notice}</p>
          <div className="dl" role="list">
            <dt>Purpose</dt><dd>Assessing your suitability for executive and board opportunities</dd>
            <dt>Who sees it</dt><dd>RiskTech vetting consultants and administrators only</dd>
            <dt>Protection</dt><dd>ID numbers and referee contacts are encrypted; every access is audited</dd>
            <dt>Your rights</dt><dd>See the protected details we hold and delete your account at any time from Account &amp; privacy</dd>
          </div>
          <label className="checkbox">
            <input type="checkbox" checked={agreed} onChange={(e) => setAgreed(e.target.checked)} />
            <span>I have read this notice and consent to RiskTech processing my personal data for executive vetting.</span>
          </label>
          {grant.isError && <Alert tone="danger">{errorMessage(grant.error)}</Alert>}
        </div>
        <div className="card__footer row row--end">
          <button className="btn btn--primary" disabled={!agreed || grant.isPending} onClick={() => grant.mutate()}>
            {grant.isPending ? 'Recording…' : 'Agree and continue'}
          </button>
        </div>
      </div>
    </div>
  );
}
