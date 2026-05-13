**English** | [Русский](SECURITY.ru.md)

# Security policy

Sanctum Machina is a pre-alpha project maintained by one person.
Only the latest release gets patches.

## How to report a vulnerability

Open an Issue. If the details are sensitive (working exploit,
data leak) — use Private Vulnerability Reporting: Security tab
→ Report a vulnerability.

Useful to include: version, device and Android version,
reproduction steps, what went wrong.

## What I'm interested in

Anything that leaks local user data (chat history, attachments,
logs) or causes something the user didn't expect — for example,
opening third-party apps through chat content.

What I'm **not** interested in: LLM behaviour itself (jailbreaks,
prompt injection, hallucinations — that's about the model, not
the app), issues in upstream dependencies (report directly to
them), the need to reinstall between pre-alpha and stable
(documented in README, by design).

## Expectations

No SLA. I'll try to respond within a couple of weeks. Priority —
anything touching user data.
