# Frequently Asked Questions

> Find your symptom; no need to read top to bottom.

## Connection and errors

**"Invalid key" or 401**

Check three things in order: the address is the **company access address**, not a vendor address; the key is the **full key** from My Keys, not a masked value; the key status is Active and not expired or disabled. See [Quick Start](my/quickstart.md) and [All Keys](team/all-keys.md).

**"Model not found" or 404**

You entered a vendor name such as gpt-4o, or the virtual model was deleted. Check **the model names you can use** in My Quota or My Keys. Administrators see [Virtual Models](team/model-pools.md).

**"Too many requests" or 429**

Too many calls in a short time. Wait a moment and retry; if it keeps happening, ask your administrator to adjust the limit.

**Playground works, my tool does not**

The service is fine. Re-check the three fields in your tool: address, key, model name. See [Quick Start](my/quickstart.md).

## Keys

**Where is the full key**

In My Keys, click the copy icon on that row. The full key is shown only once, at creation time; everywhere else shows a masked value.

**My key leaked**

Ask your administrator to disable and reissue it immediately. Never paste it into chats, tickets or code.

**I need a new key**

In My Keys, click "Request a new key", describe the purpose, then "Copy request" and send that text to your administrator. There is no online approval yet.

## Cost and quota

**Bills and team usage show zero**

Prices have not been set for the vendor models. Set input / output prices in Providers → Model Management; calls are unaffected in the meantime.

**My quota is set but requests are not blocked**

The quota ledger is disabled by default, so limits do not actually block. An administrator must enable it in Global Settings → Quota.

**"Request more quota" does nothing**

Online approval is not connected. Ask your administrator directly.

**I want a "max spend per month" limit — where do I set it**

With enterprise billing unlocked: for a key, fill the monthly amount in the Quota dialog in All Keys; for a tenant, use the Quota dialog in Tenants. The amount is an estimate converted from model prices and stored as a Token limit; it cannot be filled until the models have prices.

## Menus and permissions

**Some menu items are missing**

Menus follow your role: employees see My, operations staff also see Team, administrators also see System. This is expected.

**An item is greyed out and says I have no permission**

It is visible but read-only for your role (for example Model Configuration for operations staff). Ask an administrator to make changes.

## Data looks wrong

**A call I just made is not in Call History**

Set the right time range, switch status back to All, then click Query. If it is still missing, send the **Trace ID** and time to your administrator.

**State looks wrong after a restart**

An administrator can use "Sync now" or "One-click restore" in State & Storage. Single-server setups normally do not need that page.

---

Still stuck? Send your administrator **what you were doing, what you clicked, and the exact error text**. For jargon, see the [Plain-English Glossary](glossary.md).
