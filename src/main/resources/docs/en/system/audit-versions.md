# Audit & Versions

> Who changed what and when; roll back a bad config.

## What this page does

Administrators only. The audit log records sign-ins, key changes, authentication failures and suspicious activity — including changes to amount limits. Config versions keep a copy of each change so an earlier version can be applied again. The enterprise edition adds three tabs: **Compliance evidence** (audit records chained tamper-evidently for audits), **Compliance archive** (records locked into write-once storage) and **Security report** (a summary of security events over a period).

## When you need it

- Finding who changed a key or a setting
- Investigating unusual sign-ins and blocking a source
- Rolling back after a bad configuration change

## What is on this page

| What you see | What it means |
| --- | --- |
| Audit log | Time, user, action, object, client IP, risk level, result, description |
| Quick filters | All / key changes / sign-in and permission failures / suspicious and alerts |
| Export / Details / Blacklist | CSV, Excel, JSON; block an IP or a user |
| Config versions | Version, status (current / history), action, detail, time; View, Apply, Delete |

## Common tasks

**Investigate sign-ins**

1. Open Audit log, choose sign-in and permission failures
2. Set the time range and search
3. Open Details; block the IP if confirmed

**Roll back a config**

1. Open Config versions
2. Find the version from before the problem and click View
3. Click Apply

## If something goes wrong

**I cannot delete the current version**

Only historical versions can be deleted, and deletion cannot be undone.

**What does Apply do**

It makes the selected historical version the current configuration. Check it in View first.

## Still stuck?

For a security event, include the time, user, client IP and risk level.
