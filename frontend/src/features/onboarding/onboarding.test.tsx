import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { renderWithProviders } from '../../test/render';
import { ConsentGate } from './ConsentGate';
import { describeProblem, fromFormRecord, LIST_STEPS, objectSchema } from './stepConfig';

describe('ConsentGate', () => {
  it('starts unticked and only allows continuing after explicit agreement', async () => {
    renderWithProviders(<ConsentGate notice="Synthetic notice text" />);
    const checkbox = screen.getByRole('checkbox');
    const button = screen.getByRole('button', { name: /agree and continue/i });
    expect(checkbox).not.toBeChecked();
    expect(button).toBeDisabled();
    await userEvent.click(checkbox);
    expect(button).toBeEnabled();
  });
});

describe('step form conversion', () => {
  it('drops empty values and converts number fields back to numbers', () => {
    const fields = LIST_STEPS.sectors.fields;
    expect(fromFormRecord(fields, { sector: ' Banking ', yearsExperience: '12' })).toEqual({ sector: 'Banking', yearsExperience: 12 });
    expect(fromFormRecord(fields, { sector: '', yearsExperience: '' })).toEqual({});
  });

  it('describes server problems without needing the submitted value', () => {
    expect(describeProblem({ step: 'referees', field: 'items', code: 'MIN_ITEMS' })).toBe('Referees: add at least 2 referees');
    expect(describeProblem({ step: 'referees', field: 'email', code: 'DUPLICATE', item: 1 })).toBe('Referee 2: use a different email from the other referees');
    expect(describeProblem({ step: 'qualifications', field: 'yearObtained', code: 'OUT_OF_RANGE', item: 0 }))
      .toBe(`Qualification 1: Year obtained must be from 1950 to ${new Date().getFullYear()}`);
    expect(describeProblem({ step: 'documents', field: 'CV_AND_ID', code: 'REQUIRED' })).toMatch(/CV and an identification/);
  });
});

describe('ProfileStep change detection', () => {
  it('reports a change after the user types, and none when nothing changed', async () => {
    const { ProfileStep } = await import('./ProfileStep');
    const calls: [unknown, boolean][] = [];
    const { container } = renderWithProviders(
      <ProfileStep formId="t" initial={{ fullName: 'Ada' }} disabled={false} offline={false}
        onFile={{ nationalId: false, referees: {} }} onSubmit={(d, dirty) => calls.push([d, dirty])} />);
    const form = container.querySelector('form')!;

    form.requestSubmit();
    await vi.waitFor(() => expect(calls).toHaveLength(1));
    expect(calls[0][1]).toBe(false);

    await userEvent.type(screen.getByLabelText(/Country/), 'Nigeria');
    form.requestSubmit();
    await vi.waitFor(() => expect(calls).toHaveLength(2));
    expect(calls[1]).toEqual([{ fullName: 'Ada', country: 'Nigeria' }, true]);
  });
});

describe('field checks while typing', () => {
  it('rejects an out-of-range year and a malformed email, but allows empty and masked values', () => {
    const qualification = objectSchema(LIST_STEPS.qualifications.fields);
    expect(qualification.safeParse({ title: 'MBA', institution: 'X', yearObtained: '17' }).success).toBe(false);
    expect(qualification.safeParse({ title: 'MBA', institution: 'X', yearObtained: '2008' }).success).toBe(true);
    expect(qualification.safeParse({ title: '', institution: '', yearObtained: '' }).success).toBe(true);

    const referee = objectSchema(LIST_STEPS.referees.fields);
    const base = { name: 'Ref', relationship: 'Peer', phone: '' };
    expect(referee.safeParse({ ...base, email: 'not-an-email' }).success).toBe(false);
    expect(referee.safeParse({ ...base, email: '••••@example.test' }).success).toBe(true);
  });
});
