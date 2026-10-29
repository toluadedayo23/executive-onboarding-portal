import { useState } from 'react';
import { Alert } from '../../components/ui';
import { errorMessage } from '../../lib/api';
import { syncEngine } from '../../offline';
import type { StepKey } from '../../lib/types';
import { WIZARD_STEPS } from './stepConfig';

export function ConflictDialog({ steps }: { steps: StepKey[] }) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const labels = steps.map((s) => WIZARD_STEPS.find((w) => w.key === s)?.label ?? s);

  async function choose(choice: 'mine' | 'server') {
    setBusy(true);
    setError(null);
    try {
      await syncEngine.resolveConflict(choice);
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="modal-backdrop">
      <section className="card modal" role="alertdialog" aria-modal="true" aria-labelledby="conflict-title" aria-describedby="conflict-body">
        <div className="card__body stack">
          <div className="eyebrow">Needs your decision</div>
          <h2 id="conflict-title">Your draft was changed somewhere else</h2>
          <div id="conflict-body" className="stack">
            <p className="small">Another device or tab saved this draft after the changes on this device were made. Your unsynced changes are in:</p>
            <ul className="small" style={{ margin: 0 }}>{labels.map((l) => <li key={l}>{l}</li>)}</ul>
            <p className="small muted"><strong>Keep mine</strong> overwrites only these sections with this device's version.
              <strong> Use server's</strong> discards this device's unsynced changes.</p>
          </div>
          {error !== null && <Alert tone="danger">{errorMessage(error)}</Alert>}
          <div className="row row--end">
            <button className="btn btn--secondary" disabled={busy} onClick={() => choose('server')}>Use server's</button>
            <button className="btn btn--primary" disabled={busy} onClick={() => choose('mine')} autoFocus>Keep mine</button>
          </div>
        </div>
      </section>
    </div>
  );
}
