# State & Storage

> Where runtime data lives, whether it syncs, and how to recover it.

## What this page does

Despite the menu name, this page is a storage self-check, not a live failure dashboard. It shows where gateway state (circuit breaker state, routing state, quotas) is stored, which store is active and healthy, and lets you sync or restore. **Single-server setups normally do not need it**. The enterprise edition adds a "Security posture" tab summarizing moderation and sensitive-data blocking — a security overview for administrators, unrelated to storage.

## When you need it

- Running several gateways and confirming they share one store
- State looks wrong after a restart
- Checking how many state records exist

## What is on this page

| What you see | What it means |
| --- | --- |
| Where data lives | Redis shared cache / built-in database / local file, each card marked active or healthy |
| Record counts | Circuit breaker, routing state, quota, pending sync |
| Management actions | Refresh, one-click restore, switch store, sync now |
| Detail list | Service, stored item, health, last sync |

## Common tasks

**Routine check**

1. Open the page
2. Confirm which store is marked active and healthy

**Sync manually**

1. Click Sync now
2. Read the result

**Recover**

1. Click One-click restore
2. Or click Restore on the relevant row

## If something goes wrong

**Do I need this on a single server**

No. The page itself says single-server setups can ignore it.

**"Data already lives there"**

You are already on the target store; no switch is needed.

**"This state type cannot be restored individually"**

Use One-click restore or contact your administrator.

## Still stuck?

Contact your administrator before changing the store; include the store currently marked active and any error text.
