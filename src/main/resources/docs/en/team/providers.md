# Providers

> Add a vendor, bring in its models, set their prices.

## What this page does

Adding a new AI vendor happens here. "Providers" connects the vendor and discovers its models; "Model Management" sets prices, quotas, speed limits and circuit breaker settings.

## When you need it

- Adding or replacing a vendor
- A vendor rotated its API key
- Setting prices so bills stop showing zero
- Temporarily disabling a model

## What is on this page

| What you see | What it means |
| --- | --- |
| First time setup wizard | Four steps: pick a vendor, enter its key, create a virtual model, give employees their keys |
| Connected providers | Name, address, discovered model count, status (connected / error / not connected) |
| Add provider | Pick a preset, enter a name and the vendor API key, then discover models |
| Model Management | Model name, vendor, status, circuit breaker / rate limits, three-tier prices (input / output / cache), quota |

> Quota forms here look the same as in All Keys: the main field is the monthly Token limit; finer caps sit in "Advanced limits (rarely touched)".
>
> Advanced pricing (enterprise): a plain price is one flat rate per million tokens. Advanced pricing lets you set **tiered prices** (a single call with more input tokens gets a cheaper flat rate) or **time-window prices** (from procurement contracts). The entry point is in Model Management; the master switch is in Global Settings → Misc.

## Common tasks

**Add a vendor**

1. Click Add provider
2. Choose a preset
3. Enter a name and the vendor API key
4. Click save and discover models, then tick the models to use
5. Click add selected models

**Set prices**

1. Switch to Model Management
2. Fill in the input / output prices; if the vendor offers a discounted rate for cache hits, you can also fill the Cache field
3. Save; bills and team usage then show real amounts

> Note: The Cache price records the discounted rate for cache-hit calls. Bills are still calculated from input / output prices for now; the cache price is saved up front and will apply automatically once cache-token metering is added. Entering it today does not change or break your current amounts.

**Rotate a key**

1. Click View models on the provider row
2. Click Change key and enter the new one
3. Save

## If something goes wrong

**Employees cannot use a model I just added**

Connecting it is not enough. Create a virtual model name in Virtual Models and attach these models to it.

**"Video gateways are not supported"**

Models from that type of vendor cannot be discovered automatically; ask your administrator.

**Buttons are disabled / read-only**

Your account has view access only; prices, quotas and limits are set by an administrator.

## Still stuck?

Send the vendor name, the error text and the step you reached. **Never paste a vendor API key into a ticket or chat**.
