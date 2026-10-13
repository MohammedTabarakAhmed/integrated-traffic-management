/// <reference types="vite/client" />

import { create } from 'zustand';
import { persist } from 'zustand/middleware';

interface User {
  id: string;
  username: string;
  email: string;
  fullName: string;
  fullNameAr?: string;
  roles: string[];
  scopes: {
    regions: string[];
    centres: string[];
    modules: string[];
  };
  channel: string;
  language: 'en' | 'ar';
}

interface AuthState {
  user: User | null;
  accessToken: string | null;
  refreshToken: string | null;
  isAuthenticated: boolean;
  isLoading: boolean;
  loading: boolean; // alias for isLoading
  login: (username: string, password: string, channel?: string) => Promise<void>;
  loginWithNationalId: (nationalId: string, channel?: string) => Promise<void>;
  registerVisitor: (data: VisitorRegistrationData) => Promise<void>;
  logout: () => void;
  refreshAccessToken: () => Promise<void>;
  setLanguage: (lang: 'en' | 'ar') => void;
  hasPermission: (permission: string, scope?: { region?: string; centre?: string; module?: string }) => boolean;
  hasRole: (role: string) => boolean;
}

interface VisitorRegistrationData {
  passportNumber: string;
  passportCountry: string;
  givenNameEn: string;
  familyNameEn: string;
  givenNameAr?: string;
  familyNameAr?: string;
  nationality: string;
  birthDate: string;
  gender: 'MALE' | 'FEMALE';
  mobile: string;
  email?: string;
}

const API_BASE = import.meta.env.VITE_API_BASE || '/v1';
const TOKEN_URL = import.meta.env.VITE_TOKEN_URL || 'http://localhost:8180/realms/itms/protocol/openid-connect/token';
const CLIENT_ID = import.meta.env.VITE_CLIENT_ID || 'itms-portal';

export const useAuth = create<AuthState>()(
  persist(
    (set, get) => ({
      user: null,
      accessToken: null,
      refreshToken: null,
      isAuthenticated: false,
      isLoading: false,
      loading: false,

      login: async (username: string, password: string, channel = 'PORTAL') => {
        set({ isLoading: true, loading: true });
        try {
          const params = new URLSearchParams({
            grant_type: 'password',
            client_id: CLIENT_ID,
            scope: 'openid',
            username,
            password,
          });

          const response = await fetch(TOKEN_URL, {
            method: 'POST',
            headers: {
              'Content-Type': 'application/x-www-form-urlencoded',
              'X-Channel': channel,
            },
            body: params.toString(),
          });

          if (!response.ok) {
            const error = await response.json();
            throw new Error(error.error_description || 'Login failed');
          }

          const data = await response.json();
          const user = await getUserInfo(data.access_token);
          set({
            user,
            accessToken: data.access_token,
            refreshToken: data.refresh_token,
            isAuthenticated: true,
            isLoading: false,
            loading: false,
          });
        } catch (error) {
          set({ isLoading: false, loading: false });
          throw error;
        }
      },

      loginWithNationalId: async (nationalId: string, channel = 'PORTAL') => {
        set({ isLoading: true, loading: true });
        try {
          const response = await fetch(`${API_BASE}/auth/national-id`, {
            method: 'POST',
            headers: {
              'Content-Type': 'application/json',
              'X-Channel': channel,
            },
            body: JSON.stringify({ nationalId }),
          });

          if (!response.ok) {
            throw new Error('National ID login failed');
          }

          const data = await response.json();
          const user = await getUserInfo(data.access_token);
          set({
            user,
            accessToken: data.access_token,
            refreshToken: data.refresh_token,
            isAuthenticated: true,
            isLoading: false,
            loading: false,
          });
        } catch (error) {
          set({ isLoading: false, loading: false });
          throw error;
        }
      },

      registerVisitor: async (data: VisitorRegistrationData) => {
        set({ isLoading: true, loading: true });
        try {
          const response = await fetch(`${API_BASE}/traffic-files/individuals`, {
            method: 'POST',
            headers: {
              'Content-Type': 'application/json',
              'X-Channel': 'PORTAL',
            },
            body: JSON.stringify({
              document: {
                type: 'PASSPORT',
                passportNo: data.passportNumber,
                passportCountry: data.passportCountry,
              },
              category: 'INDIVIDUAL',
              type: 'VISITOR',
              regionOpened: 'DXB',
              visitorIdentity: {
                name: {
                  givenEn: data.givenNameEn,
                  familyEn: data.familyNameEn,
                  givenAr: data.givenNameAr,
                  familyAr: data.familyNameAr,
                },
                nationality: data.nationality,
                birthDate: data.birthDate,
                gender: data.gender,
              },
              profile: {
                mobile: data.mobile,
                email: data.email,
                languages: ['EN', 'AR'],
              },
            }),
          });

          if (!response.ok) {
            const error = await response.json();
            throw new Error(error.detail || 'Registration failed');
          }

          set({ isLoading: false, loading: false });
        } catch (error) {
          set({ isLoading: false, loading: false });
          throw error;
        }
      },

      logout: () => {
        const { refreshToken } = get();
        if (refreshToken) {
          fetch(TOKEN_URL, {
            method: 'POST',
            headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
            body: new URLSearchParams({
              grant_type: 'revoke_token',
              client_id: CLIENT_ID,
              token: refreshToken,
            }),
          }).catch(() => {});
        }
        set({
          user: null,
          accessToken: null,
          refreshToken: null,
          isAuthenticated: false,
        });
      },

      refreshAccessToken: async () => {
        const { refreshToken } = get();
        if (!refreshToken) {
          get().logout();
          return;
        }

        try {
          const params = new URLSearchParams({
            grant_type: 'refresh_token',
            client_id: CLIENT_ID,
            refresh_token: refreshToken,
          });

          const response = await fetch(TOKEN_URL, {
            method: 'POST',
            headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
            body: params.toString(),
          });

          if (!response.ok) {
            get().logout();
            return;
          }

          const data = await response.json();
          const user = await getUserInfo(data.access_token);
          set({
            user,
            accessToken: data.access_token,
            refreshToken: data.refresh_token,
          });
        } catch {
          get().logout();
        }
      },

      setLanguage: (lang: 'en' | 'ar') => {
        const { user } = get();
        if (user) {
          set({ user: { ...user, language: lang } });
        }
        localStorage.setItem('i18nextLng', lang);
      },

      hasPermission: (permission: string, _scope?) => {
        const { user } = get();
        if (!user) return false;
        return user.roles.some(role => role.includes(permission)) || user.roles.includes('SUPER_ADMIN');
      },

      hasRole: (role: string) => {
        const { user } = get();
        if (!user) return false;
        return user.roles.includes(role);
      },
    }),
    {
      name: 'itms-auth',
      partialize: (state) => ({
        user: state.user,
        accessToken: state.accessToken,
        refreshToken: state.refreshToken,
        isAuthenticated: state.isAuthenticated,
      }),
    }
  )
);

async function getUserInfo(accessToken: string): Promise<User> {
  const response = await fetch(`${API_BASE}/me`, {
    headers: {
      Authorization: `Bearer ${accessToken}`,
    },
  });

  if (!response.ok) {
    throw new Error('Failed to fetch user info');
  }

  return response.json();
}