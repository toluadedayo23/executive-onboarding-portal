import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { Alert, Field, Loading, PageHeader } from '../../components/ui';
import { errorMessage } from '../../lib/api';
import { createStaffUser, getUsers, setUserActive } from '../../lib/endpoints';
import { formatDate } from '../../lib/format';
import { useUser } from '../auth/AuthProvider';

export function UsersPage() {
  const me = useUser();
  const queryClient = useQueryClient();
  const users = useQuery({ queryKey: ['users'], queryFn: () => getUsers() });
  const toggle = useMutation({
    mutationFn: ({ id, active }: { id: string; active: boolean }) => setUserActive(id, active),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['users'] }),
  });

  return (
    <div className="page">
      <PageHeader eyebrow="Administration" title="Users"
        description="Executives register themselves. Consultants and administrators are created here." />
      <div className="split">
        <div className="card">
          {users.isLoading && <Loading />}
          {users.isError && <div className="card__body"><Alert tone="danger">{errorMessage(users.error)}</Alert></div>}
          {toggle.isError && <div className="card__body"><Alert tone="danger">{errorMessage(toggle.error)}</Alert></div>}
          {users.data && (
            <div className="table-wrap">
              <table className="table">
                <thead><tr><th>Email</th><th>Role</th><th>Status</th><th>Created</th><th /></tr></thead>
                <tbody>
                  {users.data.content.map((u) => (
                    <tr key={u.id}>
                      <td className="cell-title">{u.email}{u.id === me.id && <span className="muted small"> (you)</span>}</td>
                      <td><span className={`role-badge role-badge--${u.role}`}>{u.role.charAt(0) + u.role.slice(1).toLowerCase()}</span></td>
                      <td>{u.active ? <span className="badge badge--VERIFIED">Active</span> : <span className="badge badge--DRAFT">Deactivated</span>}</td>
                      <td className="small">{formatDate(u.createdAt)}</td>
                      <td style={{ textAlign: 'right' }}>
                        {u.id !== me.id && (
                          <button className={`btn btn--small ${u.active ? 'btn--secondary' : 'btn--primary'}`} disabled={toggle.isPending}
                            onClick={() => toggle.mutate({ id: u.id, active: !u.active })}>
                            {u.active ? 'Deactivate' : 'Reactivate'}
                          </button>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
        <CreateStaffForm />
      </div>
    </div>
  );
}

function CreateStaffForm() {
  const queryClient = useQueryClient();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [role, setRole] = useState<'CONSULTANT' | 'ADMIN'>('CONSULTANT');
  const create = useMutation({
    mutationFn: () => createStaffUser(email, password, role),
    onSuccess: () => { setEmail(''); setPassword(''); return queryClient.invalidateQueries({ queryKey: ['users'] }); },
  });
  function submit(event: FormEvent) {
    event.preventDefault();
    create.mutate();
  }
  return (
    <form className="card" onSubmit={submit}>
      <div className="card__header"><h2>Add staff account</h2></div>
      <div className="card__body stack">
        <Field label="Email" htmlFor="staff-email">
          <input id="staff-email" className="input" type="email" required value={email} onChange={(e) => setEmail(e.target.value)} />
        </Field>
        <Field label="Temporary password" hint="12+ characters" htmlFor="staff-password">
          <input id="staff-password" className="input" type="password" autoComplete="new-password" required minLength={12} maxLength={72}
            value={password} onChange={(e) => setPassword(e.target.value)} />
        </Field>
        <Field label="Role" htmlFor="staff-role">
          <select id="staff-role" className="select" value={role} onChange={(e) => setRole(e.target.value as 'CONSULTANT' | 'ADMIN')}>
            <option value="CONSULTANT">Consultant (reviewer)</option>
            <option value="ADMIN">Administrator</option>
          </select>
        </Field>
        {create.isSuccess && <Alert tone="ok">Account created.</Alert>}
        {create.isError && <Alert tone="danger">{errorMessage(create.error)}</Alert>}
        <button className="btn btn--primary" disabled={create.isPending}>{create.isPending ? 'Creating…' : 'Create account'}</button>
      </div>
    </form>
  );
}
