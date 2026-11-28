# Tenants

> One system, many isolated workspaces — one per company or team. Enterprise edition.

## What this page does

A "tenant" is an **isolated workspace**: the accounts, keys, virtual models, configuration and bills inside it cannot see or affect any other tenant. Useful when a group serves several subsidiaries, or when each external customer needs their own space. Only the platform super administrator sees this menu. Create tenants on the left, review them on the right, and switch which tenant you are managing with one click.

## When you need it

- Giving subsidiary A and B each their own isolated workspace
- Opening a workspace for an external customer and disabling it later
- Setting "at most this much per month" for one tenant

## What is on this page

| What you see | What it means |
| --- | --- |
| Create tenant | Enter an English ID (e.g. acme) and a display name; creating it makes a new isolated workspace |
| Isolation note | Explains the isolation; the "current tenant" buttons switch which one you manage |
| Tenant list | ID, name, created time; row actions are Quota and Disable |
| Quota dialog | Set a **monthly Token limit** and a **monthly amount (¥)**; finer caps live in "Advanced limits (rarely touched)" |

## Common tasks

**Open a workspace for a subsidiary**

1. Fill the English ID (immutable after creation) and display name
2. Click create
3. Create employee accounts for them in Accounts

**Set a monthly cap for a tenant**

1. Click Quota on the row
2. Fill the monthly Token limit; with enterprise billing unlocked you can also set a monthly amount
3. Save; calls above the cap are blocked with a 429 message

**Disable a tenant**

1. Click Disable on the row
2. Accounts in that workspace stop working; data is kept, nothing is deleted

## If something goes wrong

**The tenant shown top-left is not the one I want to edit**

Click the right tenant in "current tenant" first — the whole console follows that switch.

**Created the wrong tenant**

The ID cannot be renamed; disable it and create another. Disabling keeps the data.

**The quota does not block**

The quota ledger must be enabled in Global Settings → Quota; amount limits additionally need billing unlocked in the license.

## Still stuck?

Tenants are platform-level operations; when unsure, confirm the tenant ID and the intended change with the platform administrator first.
