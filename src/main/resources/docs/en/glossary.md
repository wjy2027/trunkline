# Plain-English Glossary

> Every bit of jargon on screen, explained in one sentence.

| Term | What it really means | Where you see it |
| --- | --- | --- |
| Gateway / access address | The company single entry point for AI. Your tool connects here, not to the vendor | Quick Start, All Keys |
| Key / API Key | Your access pass to company AI. Do not share it | My Keys, All Keys |
| Masked key | A partly hidden key, only for identifying which key it is; **it cannot make calls** | My Quota, My Keys |
| Virtual model | The shared model name employees use, e.g. company-chat. Put this in your tool | Almost every page |
| Real model / vendor model / instance | The actual model doing the work behind a virtual model | Providers, Virtual Models |
| Primary / backup | Normally the primary is used; if it fails, the backup takes over | Virtual Models |
| Provider / vendor | The company supplying the AI model | Providers, Model Configuration |
| Adapter | A setting that translates a vendor interface into the common format; usually chosen automatically | Model Configuration, Providers |
| Token | The unit AI billing is based on, roughly a word or character count; input and output are priced separately | Bills, Team Usage |
| Price | Cost per thousand tokens. Without a price, bills show zero | Providers, My Bills |
| Quota / limit | How much you may use in a period: per minute, hour, day or month | My Quota, All Keys |
| Resets on the 1st | Monthly quota starts over at the beginning of each month | My Quota |
| Rate limiting | Too many calls in a short time are held back, with a 429 message | All Keys, Virtual Models |
| Circuit breaker | A model that keeps failing is temporarily taken out of rotation | Global Settings, Providers |
| Failure threshold | How many consecutive errors trigger that temporary pause | Global Settings |
| Trace ID | A unique number for each call. Give it to support to locate the call instantly | Call History |
| Tracing | The step-by-step story of one request | Call History |
| Department | A field on the account, used to group Team Usage by department | Accounts, Team Usage |
| Role | User sees only their own data; operations staff also manage the team; administrators manage everything | Accounts |
| Shared storage / state | Runtime data shared by all gateway instances in a multi-server setup | State & Storage |
| fail-open | Whether to allow or block requests when the quota service is unavailable | Global Settings |
| Config version | A saved copy of each config change, so a bad change can be rolled back | Audit & Versions |
| Audit log | A record of who logged in or changed keys and configuration, and when | Audit & Versions |
| Amount limit | A cap on "how much money per month", alongside the token limit; exceeding it blocks calls | All Keys, Tenants |
| Advanced pricing | Finer pricing than a flat rate: tiered (bigger single calls get cheaper rates) and time-window prices | Providers, Global Settings |
| PII masking | Replacing phone or ID numbers with 138****5678 **before writing logs**; what the model and user see is unchanged | Global Settings → Misc |
| Data-loss prevention | Blocking or masking sensitive content found in requests or responses | Enterprise → Guardrails |
| Key vault | Vendor API keys stored encrypted, never shown in plain text | Enterprise → Guardrails |
| Content moderation | Scoring model replies and blocking those likely to violate policy | Enterprise → Guardrails |
| Tenant | One isolated workspace: accounts, keys and bills are invisible across tenants | Tenants (enterprise) |
| Compliance evidence / archive | Audit records chained tamper-evidently and locked into write-once storage for audits | Audit & Versions (enterprise) |
| Department registry | Departments must be registered first, each with a stable ID, for grouping and per-department limits; the account editor's dropdown only accepts registered departments | Accounts → Add department (enterprise: Organization tab) |

> Numbers like 401 / 403 / 404 after a term: 401 means the key is wrong, 403 means no permission, 404 means that name does not exist. Those three cover most cases.
