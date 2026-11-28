# Accounts

> Who can sign in, and what each person is allowed to do.

## What this page does

Administrators only. Create employee accounts, assign roles, fill in departments (used to group Team Usage), disable accounts, or import several at once. Department registration lives on this page: when editing an account the department must be picked from the dropdown, and "Add department" registers a new one first (registration assigns a stable department ID that usage grouping and per-department limits rely on). The enterprise edition adds an "Organization" tab as a full management page, which can also show org structure synced from your directory (SCIM).

## When you need it

- A new employee needs an account
- Someone changed role
- Someone left and their account should be disabled
- Preparing departments so usage can be grouped

## What is on this page

| What you see | What it means |
| --- | --- |
| Total / Enabled / Disabled / Administrators | Four counters |
| Username / Name / Department / Role / Status / Created | Account list; roles are Administrator, Operations, User, Guest |
| Create account / Bulk import / Refresh / Search | Main actions |
| Status switch | Disable or enable without deleting |

## Common tasks

**Create an account**

1. Click Create account
2. Fill username, name, department, pick a role and set a password
3. Confirm

**Disable someone**

1. Find the row
2. Flip the status switch to disabled
3. Safer than deleting; reversible at any time

**Before grouping usage by department**

1. Click "Add department" in the account editor to register departments first (enterprise edition can also manage them in the Organization tab)
2. Then pick each account's department from the dropdown
3. Team Usage can then group by department, and per-department amount limits rely on these entries too

## If something goes wrong

**Leaving the password blank when editing**

Means the password is unchanged.

**I cannot delete the default administrator**

It is protected by the system.

**Which role should I pick**

User sees only their own quota, keys and Playground; Operations also manages team usage, providers, keys and call history; Administrator manages everything, including accounts.

**I want to just type a department name**

That is not supported, by design: departments must be registered before use. A hand-typed name gets no stable department ID, so usage grouping and per-department limits cannot recognize it. Click "Add department" first.

## Still stuck?

Send the username and the on-screen message to your administrator.
