# Virtual Models

> The model name employees use can point to several real models.

## What this page does

Employees do not type vendor model names. They type a company-wide virtual model name such as company-chat. Here you decide which real models sit behind that name, which is primary and which is backup, how traffic is shared, and what the usage limits are. **Without a virtual model, employees have no usable model name**.

## When you need it

- Creating a name after connecting a new vendor
- Adding a backup model for automatic failover
- Setting per-minute / daily / monthly limits
- Changing how traffic is split across models

## What is on this page

| What you see | What it means |
| --- | --- |
| What is a virtual model? | An explanation card at the top |
| Employee model name / attached models | Primary and backup labels plus pricing status |
| Strategy | Weighted random, round robin, least connections, sticky per user, consistent hash |
| Price status / quota / status / calls this month | Whether pricing is set, limits, enabled state and usage |
| Add / Edit / Quota / Delete | Main actions on each row |

## Common tasks

**Create one**

1. Click Add virtual model
2. Enter a name, e.g. company-chat
3. Click Add model and tick real models of the same category
4. Order primary and backup with the arrows
5. Save; employees can now use the name

**Add a backup**

1. Click Edit on that row
2. Add another model of the same category and adjust the order
3. Save

**Set limits**

1. Click Quota
2. The main field is the **monthly Token limit** — usually the only one you need
3. Per-minute / hourly / daily caps sit in the "Advanced limits (rarely touched)" section, two fields per row
4. Adjust the alert threshold (at what usage percentage to warn)
5. Save quota

## If something goes wrong

**What happens if I delete one**

Requests using that name will return "model not found" (404). Check nobody is using it first.

**"No instances in this category"**

Connect the vendor models in Providers first.

**Only same-category models can be selected**

A virtual model name cannot mix chat and image models, for example.

## Still stuck?

Tell your administrator the virtual model name, the real models you want behind it, and the sharing strategy.
