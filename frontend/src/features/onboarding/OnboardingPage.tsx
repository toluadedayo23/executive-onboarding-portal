import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef, useState } from 'react';
import { Alert, Loading, PageHeader, StatusBadge } from '../../components/ui';
import { errorMessage, isNetworkError } from '../../lib/api';
import { getConsent, getDraft } from '../../lib/endpoints';
import type { Draft, StepKey } from '../../lib/types';
import { syncEngine, useSyncState } from '../../offline';
import type { LocalDraft } from '../../offline/localStore';
import type { SyncPhase } from '../../offline/syncEngine';
import { ConflictDialog } from './ConflictDialog';
import { ConsentGate } from './ConsentGate';
import { DocumentsStep } from './DocumentsStep';
import { ListStep } from './ListStep';
import { ProfileStep } from './ProfileStep';
import { describeProblem, LIST_STEPS, WIZARD_STEPS } from './stepConfig';
import { SubmittedView } from './SubmittedView';

export function OnboardingPage() {
  const queryClient = useQueryClient();
  const sync = useSyncState();
  const consent = useQuery({ queryKey: ['consent'], queryFn: getConsent });
  const server = useQuery({ queryKey: ['onboarding'], queryFn: getDraft, refetchOnWindowFocus: true });

  useEffect(() => { if (server.data) void syncEngine.adoptServerDraft(server.data); }, [server.data]);
  useEffect(() => { if (consent.data) void syncEngine.rememberConsent(consent.data.granted); }, [consent.data]);
  useEffect(() => {
    if (sync.online) void queryClient.invalidateQueries({ predicate: (q) => q.queryKey[0] === 'consent' || q.queryKey[0] === 'onboarding' });
  }, [sync.online, queryClient]);
  useEffect(() => { if (sync.lastSynced.at) void queryClient.invalidateQueries({ queryKey: ['onboarding'] }); }, [sync.lastSynced.at, queryClient]);

  const offline = !sync.online || isNetworkError(server.error) || isNetworkError(consent.error);
  if (!offline && (consent.isLoading || server.isLoading)) return <Loading label="Loading your onboarding…" />;
  if (!offline && (consent.isError || server.isError)) {
    return <div className="page"><Alert tone="danger">{errorMessage(consent.error ?? server.error)}</Alert></div>;
  }
  if (consent.data && !consent.data.granted) return <ConsentGate notice={consent.data.notice} />;

  const draft = sync.draft;
  if (!draft) {
    return offline ? <OfflineNotice text="Your draft is not on this device yet. Reconnect to continue." /> : <Loading label="Loading your onboarding…" />;
  }
  if (!draft.editable) {
    return server.data ? <SubmittedView draft={server.data} />
      : <OfflineNotice text="Your onboarding has been submitted. Reconnect to see its latest status." />;
  }
  return (
    <>
      <Wizard draft={draft} server={server.data} offline={offline} />
      {sync.phase === 'conflict' && <ConflictDialog steps={sync.conflictSteps} />}
    </>
  );
}

function OfflineNotice({ text }: { text: string }) {
  return <div className="page page--narrow"><Alert tone="info" title="You're offline">{text}</Alert></div>;
}

function withMaskedValues(step: StepKey, local: Record<string, unknown> | undefined, server: Draft | undefined) {
  const remote = server?.data[step];
  if (!local || !remote) return local;
  if (step === 'profile') return { ...local, nationalId: remote.nationalId };
  if (step === 'referees' && Array.isArray(local.items) && Array.isArray(remote.items)) {
    const byId = new Map((remote.items as Record<string, unknown>[]).map((r) => [String(r.id), r]));
    return { ...local, items: (local.items as Record<string, unknown>[]).map((item) => {
      const r = byId.get(String(item.id));
      return r ? { ...item, email: r.email, phone: r.phone } : item;
    }) };
  }
  return local;
}

const SAVE_LABEL: Partial<Record<SyncPhase, string>> = {
  syncing: 'Syncing…', waiting: 'Saved on this device', 'paused-auth': 'Sync paused: sign in again',
  'paused-consent': 'Sync paused: consent needed', conflict: 'Needs your decision', blocked: 'Sync stopped',
};

function Wizard({ draft, server, offline }: { draft: LocalDraft; server: Draft | undefined; offline: boolean }) {
  const sync = useSyncState();
  const [index, setIndex] = useState(() => {
    if (draft.status === 'MORE_INFO_REQUIRED') return 0;
    const allComplete = WIZARD_STEPS.every((s) => s.key === 'documents' || draft.stepStatus[s.key as StepKey] === 'COMPLETE');
    return allComplete ? WIZARD_STEPS.length - 1 : Math.min(Math.max(draft.currentStep ?? 0, 0), WIZARD_STEPS.length - 1);
  });
  const [error, setError] = useState<unknown>(null);
  const [discarding, setDiscarding] = useState(false);
  const target = useRef<number | null>(null);

  const step = WIZARD_STEPS[index];
  const formId = `step-${step.key}`;
  const isDocuments = step.key === 'documents';
  const completeCount = WIZARD_STEPS.filter((s) => s.key !== 'documents' && draft.stepStatus[s.key as StepKey] === 'COMPLETE').length;

  function goTo(next: number) {
    if (isDocuments) return setIndex(next);
    target.current = next;
    (document.getElementById(formId) as HTMLFormElement | null)?.requestSubmit();
  }

  async function onStepSubmit(data: unknown, dirty: boolean) {
    const next = target.current ?? index;
    target.current = null;
    if (dirty) {
      setError(null);
      try {
        await syncEngine.saveStep(step.key as StepKey, data as Record<string, unknown>, Math.min(next, WIZARD_STEPS.length - 1));
      } catch (e) {
        setError(e);
        return;
      }
    }
    setIndex(next);
    window.scrollTo({ top: 0 });
  }

  async function discard() {
    setDiscarding(true);
    try {
      await syncEngine.discard();
    } catch (e) {
      setError(e);
    } finally {
      setDiscarding(false);
    }
  }

  const stepProblems = (server?.problems ?? []).filter((p) => p.step === step.key);
  const showProblems = !isDocuments && stepProblems.length > 0 && draft.stepStatus[step.key as StepKey] === 'IN_PROGRESS' && sync.pending === 0;
  const saveLabel = SAVE_LABEL[sync.phase] ?? (sync.pending > 0 ? 'Saved on this device' : 'All changes saved');
  const stepProps = {
    formId, offline, onFile: draft.onFile, disabled: false, onSubmit: onStepSubmit,
    initial: offline ? draft.data[step.key as StepKey] : withMaskedValues(step.key as StepKey, draft.data[step.key as StepKey], server),
  };

  return (
    <div className="page">
      <PageHeader eyebrow="My onboarding" title="Build your executive profile"
        description="Your progress is saved as you move between steps, even without a connection."
        actions={<StatusBadge status={draft.status} />} />

      {sync.lostSensitive && (
        <div style={{ marginBottom: 'var(--space-5)' }}>
          <Alert tone="warn" title="Not saved, please re-enter">
            An ID number or referee contact you typed was not saved before the page closed. For your security these are
            never stored on this device. Please enter it again.
          </Alert>
        </div>
      )}
      {sync.phase === 'blocked' && (
        <div style={{ marginBottom: 'var(--space-5)' }}>
          <Alert tone="danger" title="Some changes on this device could not be saved">
            The server did not accept them{sync.blockedCode === 'NOT_EDITABLE' ? ' because your onboarding is no longer editable' : ''}.
            <div style={{ marginTop: 8 }}><button className="btn btn--secondary btn--small" disabled={discarding || offline} onClick={discard}>Discard offline changes</button></div>
          </Alert>
        </div>
      )}
      {sync.phase === 'paused-auth' && (
        <div style={{ marginBottom: 'var(--space-5)' }}><Alert tone="warn" title="Sync paused">Sign in again to sync the changes saved on this device.</Alert></div>
      )}
      {draft.status === 'MORE_INFO_REQUIRED' && (
        <div style={{ marginBottom: 'var(--space-5)' }}>
          <Alert tone="warn" title="Your reviewer has asked for more information">
            <p style={{ marginTop: 4 }}>“{draft.moreInfoMessage}”</p>
            <p className="small" style={{ marginTop: 8 }}>Update the relevant steps, then resubmit from the final step.</p>
          </Alert>
        </div>
      )}

      <div className="wizard">
        <nav className="card" aria-label="Onboarding steps">
          <div style={{ padding: 'var(--space-4) var(--space-4) 0' }}>
            <div className="small muted">{completeCount} of 5 sections complete</div>
            <div className="progress" style={{ marginTop: 8 }}><span style={{ width: `${(completeCount / 5) * 100}%` }} /></div>
          </div>
          <ol className="stepper">
            {WIZARD_STEPS.map((s, i) => {
              const state = s.key === 'documents' ? 'NOT_STARTED' : draft.stepStatus[s.key as StepKey] ?? 'NOT_STARTED';
              return (
                <li key={s.key}>
                  <button type="button" aria-current={i === index ? 'step' : undefined} onClick={() => i !== index && goTo(i)}>
                    <span className={`step-dot step-dot--${state}`} aria-hidden>{state === 'COMPLETE' ? '✓' : i + 1}</span>
                    <span>{s.label}
                      <span className="step-meta">{s.key === 'documents' ? 'Final step' : state === 'COMPLETE' ? 'Complete' : state === 'IN_PROGRESS' ? 'In progress' : 'Not started'}</span>
                    </span>
                  </button>
                </li>
              );
            })}
          </ol>
        </nav>

        <section className="card" aria-labelledby="step-title">
          <div className="card__header">
            <div>
              <div className="eyebrow">Step {index + 1} of {WIZARD_STEPS.length}</div>
              <h2 id="step-title">{isDocuments ? 'Documents & submit' : step.key === 'profile' ? 'Professional profile' : LIST_STEPS[step.key as Exclude<StepKey, 'profile'>].title}</h2>
              <p className="small muted">{isDocuments ? 'Upload your CV and ID, then submit your profile for vetting.'
                : step.key === 'profile' ? 'How you would like to be introduced to boards and clients.'
                : LIST_STEPS[step.key as Exclude<StepKey, 'profile'>].intro}</p>
            </div>
            <span className="save-state" aria-live="polite">{saveLabel}</span>
          </div>

          <div className="card__body stack">
            {error !== null && <Alert tone="danger" title="Could not save">{errorMessage(error)}</Alert>}
            {showProblems && (
              <Alert tone="warn" title="To complete this section">
                <ul>{stepProblems.map((p, i) => <li key={i}>{describeProblem(p)}</li>)}</ul>
              </Alert>
            )}
            {step.key === 'profile' && <ProfileStep key={`profile-${offline}`} {...stepProps} />}
            {!isDocuments && step.key !== 'profile' && (
              <ListStep key={`${step.key}-${offline}`} config={LIST_STEPS[step.key as Exclude<StepKey, 'profile'>]} {...stepProps} />
            )}
            {isDocuments && (offline || !server
              ? <Alert tone="info" title="Needs a connection">Uploading documents and submitting your profile need a connection. Your answers are saved on this device and will sync when you reconnect.</Alert>
              : <DocumentsStep draft={server} />)}
          </div>

          {!isDocuments && (
            <div className="card__footer row row--between">
              <button className="btn btn--ghost" disabled={index === 0} onClick={() => goTo(index - 1)}>← Back</button>
              <div className="row">
                <button className="btn btn--secondary" onClick={() => goTo(index)}>Save draft</button>
                <button className="btn btn--primary" onClick={() => goTo(index + 1)}>Save &amp; continue →</button>
              </div>
            </div>
          )}
        </section>
      </div>
    </div>
  );
}
