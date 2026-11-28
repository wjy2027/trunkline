# Model Capabilities

> See what each model is good at, so picking one is not guesswork. Operators and administrators only.

## What this page does

Many vendor models are connected — which one suits customer support, and which writes code best? This page gives every model a "résumé": filter by use case, vendor, service type and strength to answer "which model should I pick". Once picked, set it up in Virtual Models, or use the scenario templates in the access guide to build a pool in one click.

## When you need it

- A new use case (say a support bot) and you do not know which model to pick
- Comparing candidates on code / math / reasoning strength
- Checking which service types (chat / embedding / rerank) a vendor offers

## What is on this page

| What you see | What it means |
| --- | --- |
| Filters | By **use case** (customer support, code assistant...), vendor, service type, strength, maximum latency |
| Model / canonical ID | The connected name and its normalized ID |
| Strengths | A four-part rating for code, Chinese, math and reasoning |
| Source | Who wrote the résumé: **built-in baseline** = shipped with the system, only widely known public facts; unknowns are honestly shown as "unknown", never invented |

## Common tasks

**Pick a model for a new use case**

1. Open this page and pick a use case, e.g. customer support
2. Compare the candidates and their strength ratings
3. Attach them behind one name in Virtual Models for employees

**Compare candidates**

1. Set vendor to all and service type to chat
2. Compare the strengths column
3. Unsure? Attach all candidates to the pool and watch Team Usage to decide

**Set up a whole stack in one click**

1. Open the access guide from the question-mark icon and use scenario templates
2. Pick a purpose and preview the pools and rules
3. Click Apply; the pool name is the `model` value you will call with

Four built-in scenarios: customer support (one chat pool + rate-limit rule), document retrieval RAG (chat / embedding / rerank pools), code assistant (one chat pool + model-rewrite rule), cost guardrail (no pool, one global rate-limit rule).

## If something goes wrong

**A strength shows "unknown"**

Profiles only record public, well-established facts. Real-world performance is what your own calls show.

**Why no prices here**

Prices change too often to keep in a static profile. Billing uses the realized price of each call — check Team Usage and bills instead.

**The filtered model is not connected**

Profiles are reference data, not connections. Connect it in Providers and attach it in Virtual Models before employees can use it.

**Some items were skipped after Apply**

If a service type has no connected instance, its pool and dependent rules are skipped rather than writing an unusable rule; same-named pools are also skipped. The "Notes / skipped items" list tells you what was left out.

## Still stuck?

Share the use case, budget range and candidate models with your administrator; real usage data lives in Team Usage.
