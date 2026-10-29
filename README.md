# Executive Onboarding & Vetting Portal

Proof of concept for RiskTech & Advisory's Executive Platform. Executives onboard themselves, consultants vet them
through a controlled workflow, and every key action is recorded in a tamper-evident audit trail.

**Synthetic data only.** Every seeded account uses an `@example.test` address.

Design pack (architecture, data model, technology choices, risk register): [deliverables/design-pack.pdf](deliverables/design-pack.pdf).

## Setup

Requires Docker Desktop (running) and Git. On Windows, keep the project outside OneDrive-synced folders.

```sh
git clone <repository-url>
cd executive-onboarding-portal
```

Then paste the single line for your shell. It creates `.env`, enables the demo accounts and starts the app.

**Windows Command Prompt (cmd)**

```bat
copy /Y .env.example .env && echo APP_SEED_ENABLED=true>>.env && docker compose up --build
```

**Windows PowerShell**

```powershell
Copy-Item .env.example .env -Force; Add-Content .env "APP_SEED_ENABLED=true"; docker compose up --build
```

**macOS / Linux**

```sh
cp .env.example .env && echo "APP_SEED_ENABLED=true" >> .env && docker compose up --build
```

If you have run setup before, delete the old `.env` first (`del .env` in cmd, `rm .env` elsewhere) so the lines
aren't duplicated.

The first build takes a few minutes. When the logs settle, open http://localhost:5173.

Sign in with any account below. The password for all of them is `dev-only-change-me-12` (dev only; seeding is
refused in production). Executives see a consent screen on first sign-in.

| Account | Role | Use it to |
|---|---|---|
| `admin@example.test` | Admin | Manage users and read the audit trail |
| `consultant1@example.test`, `consultant2@example.test` | Consultant | Review cases |
| `exec1@example.test`, `exec4@example.test` | Executive | Start onboarding from scratch (nothing filled in) |
| `exec2@example.test` | Executive | See an incomplete step: four steps done, Referees needs a second referee |
| `exec3@example.test` | Executive | Submit quickly: all five steps done; upload a CV and an ID, then submit |

**Stop:** `docker compose down`

**Full reset** (wipes data, re-seeds accounts): `docker compose down -v`, then run the setup line for your shell again.

**Troubleshooting**

- *Cannot connect to the Docker daemon:* start Docker Desktop and wait until it says it's running.
- *Port already in use:* close whatever is using port 5173.
- *Login fails:* delete `.env`, then do a full reset.

**Quick tour:** exec3 submits → consultant1 picks up the case and requests more information → exec3 resubmits →
consultant1 verifies → admin reads the case history and verifies the audit chain.

**Tests:** `cd backend && mvn test` (needs Docker) and `cd frontend && corepack enable && yarn install --immutable && yarn test`.

**Configuration:** all secrets come from environment variables; see `.env.example`. Production refuses to start
without a strong `JWT_SECRET`, a 32-byte `ENCRYPTION_KEY` and VAPID keys, or with seeding enabled. The VAPID pair in
`.env.example` is for local use only.

## Stack

Java 21, Spring Boot 3.3, PostgreSQL 16 (Flyway) · React 18, TypeScript, Vite · nginx · Docker Compose.

## Assumptions

- Executives register themselves; consultants are created by an admin; the first admin is seeded. One role per user.
- One vetting case per executive, created at first submission. `VERIFIED` and `REJECTED` are final.
- The consultant who picks up a case owns it. Admins oversee but never decide (separation of duties).
- A CV, one ID document and two referees are required to submit. Files: PDF, JPG or PNG up to 5 MB.
- Consent is the lawful basis and is captured before any data is saved.
- Notifications only say that something changed; details are read after signing in.
- Offline use is for executives completing the form, not for reviewers or admins.

## Known limitations

- **Sessions:** no MFA, password reset or email verification. The login rate limiter is in memory, per instance.
- **Privacy:** consent versioning, withdrawal and data export are left for production (the schema supports withdrawal).
  Retention periods and a DPIA need legal review. Encryption keys cannot be rotated yet.
- **Audit:** a database owner could rebuild the whole hash chain; production needs a least-privilege runtime user and
  an external copy of the latest hash.
- **Vetting:** no reassignment, so a deactivated consultant's open cases are stuck; no reopening of decided cases.
- **Documents:** stored on local disk; virus scanning is a stub.
- **Push:** best effort, no retry; iOS requires the app on the Home Screen.
- **Offline:** syncs only while the app is open; the device draft is not encrypted, so ID numbers and referee
  contacts are never stored offline; conflicts are resolved for the whole draft.
- **Out of scope:** analytics dashboard, role matching, real email delivery, multi-tenancy.
