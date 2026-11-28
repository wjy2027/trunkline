# Enterprise

> The enterprise edition home: licensing, reconciliation, guardrails and approvals. The menu shows a grey lock in the community edition.

## What this page does

Enterprise-only features live here, one tab each. The menu appears for administrators; the community edition shows an upgrade notice instead. Every tab opens with a one-line explanation of what it manages.

## When you need it

- Replacing the license, or checking which features it unlocks
- Reconciling spend against a vendor bill, or attributing cost to a tenant or department
- Turning on data-loss prevention, key vaulting or content moderation
- Adding an approval step before keys are issued
- Managing where alerts are delivered (email, webhooks...)

## What is on this page

| Tab | What it means |
| --- | --- |
| License | The enterprise "authorization letter": validity and feature flags (multi-tenant, billing, security...). A feature that stays locked is often missing here |
| Reconciliation | Match call spend against bills and attribute each amount to a tenant or department |
| Agent tools | Helper capabilities for automated programs (agents) |
| Guardrails | Three things: **data-loss prevention** (block or mask sensitive content such as ID numbers in requests/responses), **key vault** (vendor keys stored encrypted, never in plain text), **content moderation** (block replies scoring below the threshold) |
| Alert channels | Where notifications go when something happens: recipients such as email or webhooks |
| Key approval | When enabled, an employee's key request goes to an approver before a key is issued |
| Usage overview | The cross-tenant usage dashboard for multi-tenant deployments |

## Common tasks

**Find out why a feature is locked**

1. Check the License tab for the matching feature flag
2. If the flag is there, check the switch inside the feature's own tab
3. Example: Advanced pricing is grey in Model Management — check the billing flag in the license, then the master switch in Global Settings → Misc

**Turn on data-loss prevention**

1. Open Guardrails
2. Enable the switch, adjust the blocking threshold and excluded models as needed
3. It takes effect immediately; verify with a test request

**Add approval to key requests**

1. Open Key approval
2. Enable the workflow and pick approvers
3. Requests submitted in My Keys now wait for approval first

## If something goes wrong

**The whole menu is grey with an upgrade notice**

The deployment has no enterprise module or the license is not active; confirm the license file is imported and within its validity.

**Guardrails block legitimate requests**

A strict threshold errs on the side of blocking. Check the blocked records and their scores in the audit log, loosen the threshold, or add the model to the excluded list.

**No alert was delivered**

Use the test button in Alert channels to verify delivery first; then confirm the triggering condition actually occurred.

## Still stuck?

License questions go to your vendor or account manager; for a wrongly blocked request, send the **time, model and blocking reason** to your administrator.
