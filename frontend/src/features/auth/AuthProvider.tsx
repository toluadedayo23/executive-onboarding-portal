import { createContext, useContext, type ReactNode } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { ApiError, isNetworkError } from '../../lib/api';
import { getMe, login as loginRequest, logout as logoutRequest } from '../../lib/endpoints';
import type { User } from '../../lib/types';
import { syncEngine } from '../../offline';
import { unsubscribeOnLogout } from '../notifications/push';

type AuthContextValue = {
  user: User | null;
  loading: boolean;
  unavailable: boolean;
  offline: boolean;
  login: (email: string, password: string) => Promise<User>;
  logout: () => Promise<void>;
};

const AuthContext = createContext<AuthContextValue | null>(null);

async function prepareDevice(user: User) {
  if (user.role === 'EXECUTIVE') await syncEngine.start(user);
  else await syncEngine.wipe();
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient();
  const me = useQuery({
    queryKey: ['me'],
    queryFn: async () => {
      try {
        const user = await getMe();
        await prepareDevice(user);
        return user;
      } catch (e) {
        if (e instanceof ApiError && e.status === 401) return null;
        if (isNetworkError(e)) {
          const session = await syncEngine.offlineSession();
          if (session) {
            const user: User = { id: session.userId, email: session.email, role: session.role };
            await syncEngine.start(user, { offline: true });
            return user;
          }
        }
        throw e;
      }
    },
  });

  function switchUser(user: User | null) {
    queryClient.removeQueries({ predicate: (query) => query.queryKey[0] !== 'me' });
    queryClient.setQueryData(['me'], user);
  }

  async function login(email: string, password: string) {
    const user = await loginRequest(email, password);
    await prepareDevice(user);
    switchUser(user);
    return user;
  }

  async function logout() {
    if (me.data?.role === 'EXECUTIVE') await unsubscribeOnLogout();
    await logoutRequest().catch(() => undefined);
    await syncEngine.wipe();
    switchUser(null);
  }

  return (
    <AuthContext.Provider value={{ user: me.data ?? null, loading: me.isLoading, unavailable: me.isError, offline: me.isError && isNetworkError(me.error), login, logout }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  const value = useContext(AuthContext);
  if (!value) throw new Error('useAuth must be used inside AuthProvider');
  return value;
}

export function useUser(): User {
  const { user } = useAuth();
  if (!user) throw new Error('No signed-in user');
  return user;
}
