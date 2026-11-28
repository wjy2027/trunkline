# Model Configuration

> Vendor presets, model categories, and custom connection formats.

## What this page does

Administrators only; operations staff see the entry but get a permission notice. Three base tabs: provider templates (used when adding a provider), model categories, and custom adapters. The enterprise edition adds **prompt templates** (store reusable prompts) and **adapter lifecycle** (bring connection formats online or offline). In most cases you do not need this page — connecting a vendor picks the right format automatically.

## When you need it

- Checking which vendor presets are still reachable
- Adding or adjusting a model category
- A vendor uses a non-standard interface and needs a custom adapter

## What is on this page

| What you see | What it means |
| --- | --- |
| Provider templates | Name, template ID, category, type, endpoint, common models, availability; test reachability and hide unavailable ones |
| Model categories | Category, adapter, balancing strategy, usage cap, automatic failure pause; blank values fall back to Global Settings |
| Custom adapters | Name, interface style, type, capabilities; includes a connection test |

## Common tasks

**Clean up dead presets**

1. Switch to Provider templates
2. Click Check availability
3. Click Hide unavailable

**Add a custom adapter**

1. Switch to Custom adapters
2. Click Add adapter, from a template or from scratch
3. Enter the endpoint and key
4. Click Test connection, then save

## If something goes wrong

**Built-in presets cannot be edited**

They ship with the system. Create a custom one instead.

**Test connection fails**

Check the endpoint and key. These are vendor-side details, not the employee gateway address.

**The entry is greyed out for operations staff**

Only administrators can change this page, by design.

## Still stuck?

Send the vendor name, interface style and the connection test error; never paste the vendor API key.
