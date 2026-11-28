# Global Settings

> Gateway-wide defaults. To adjust one model, use Model Management.

## What this page does

Administrators only. The five tabs are named in plain language: how much logging, how traffic is split, what happens when a model keeps failing, how quota is recorded, and Misc. The first four change the **defaults for every model**; for a single model use Providers → Model Management. Misc holds switches that are not per-model (the advanced-pricing master switch and PII masking). Each tab explains what it affects and when to change it.

## When you need it

- Setting how much request content is kept
- Changing the default traffic splitting strategy
- Adjusting when a failing model is paused
- Enabling the quota ledger so limits actually block
- Toggling advanced pricing, or privacy masking in logs

## What is on this page

| What you see | What it means |
| --- | --- |
| How much logging | Metadata only / masked summary / full encrypted (administrators only) |
| How traffic is split | A global default strategy plus per-category overrides |
| When a model keeps failing | Default failure threshold, success threshold, timeout; advanced adaptive thresholds and retention |
| How quota is recorded | Enabled flag, ledger backend, fail-open, flush interval; some fields are read-only and need a restart |
| Misc | ① Advanced pricing master switch (enterprise): turn it on before configuring tiered prices in Model Management; ② Privacy masking: phone and ID numbers are replaced with `138****5678` **before being written to call records**; what the model and users receive is unchanged |

## Common tasks

**Change the logging level**

1. Open How much logging
2. Pick an option and save
3. This touches employee request content, so confirm with your policy first

**Change the default split**

1. Open How traffic is split
2. Pick a strategy in the global card and save
3. For one category only, click Configure on that row

**Make quota actually enforce limits**

1. Open How quota is recorded
2. Enable quota
3. Click Save runtime config

**Toggle privacy masking**

1. Open Misc
2. Flip the two switches: mask request records / mask response records
3. Effective immediately, no restart; new log entries follow the new rules right away

## If something goes wrong

**My change did not take effect**

Some fields are marked "requires restart"; those need an administrator to schedule a restart. The Misc switches take effect immediately.

**The advanced-pricing switch is grey**

Advanced pricing is an enterprise feature. Community deployments show a grey lock; an enterprise deployment additionally needs billing unlocked in the license.

**Restore factory defaults**

Click Restore defaults on the "when a model keeps failing" tab.

**The entry is greyed out for operations staff**

Only administrators can save; the menu item is intentionally visible but disabled.

## Still stuck?

Gateway-wide defaults affect every model. When unsure, send screenshots of the five tabs to your administrator.
