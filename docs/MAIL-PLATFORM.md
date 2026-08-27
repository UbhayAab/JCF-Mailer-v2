# Jarurat Care mail platform: the operator and integrator handbook

Written 2026-08-25. This is the document to read first in a fresh session, or to hand
to another project that wants to send mail through this platform.

If you are an AI assistant picking this up cold: everything you need to answer
"how do I send OTPs / transactional mail / campaigns through Jarurat's mailer" is
here. `docs/API.md` has the endpoint reference; this file has the architecture, the
operations, and the things that will bite you.

---

## 1. What actually exists

Three separate systems share one domain and one box. Confusing them is the most
common mistake.

| System | What it does | Where it runs | Sends via |
|---|---|---|---|
| **Campaign Studio** | The Spring app. Campaigns, journeys, templates, transactional API, OTP, analytics | `mailer.jarurat.care` port 8081 behind nginx | Amazon SES |
| **Stalwart** | The actual mail server. Real mailboxes for people, inbound MX, IMAP | Same box, ports 25 / 465 / 993 / 995 / 4190, admin on 8443 | Relays outbound through SES |
| **Amazon SES** | The thing that actually puts mail on the internet | ap-south-1, account <AWS_ACCOUNT_ID> | - |

One box: `<INSTANCE_ID>`, t4g.small, 2 vCPU, 1.8GB RAM, Elastic IP
`<ELASTIC_IP>`. Postgres, Spring, Stalwart and nginx all live on it.

**Why outbound goes through SES and not direct:** EC2 blocks outbound port 25.
Verified 2026-08-25: a direct connection from the box to `gmail-smtp-in.l.google.com:25`
is refused. Stalwart therefore has an MtaRoute pointing at SES on port 587. If you
ever see mail queueing with connection timeouts, this is why, and the fix is the route,
not the firewall.

---

## 2. Sending OTPs from another project

This is the most common integration and the one to get right.

### The shape of it

```
your app                    Campaign Studio                 the user
   |                              |                            |
   |-- POST /otp/request -------->|                            |
   |                              |-- email with the code ---->|
   |<-- 202 + challengeId --------|                            |
   |                              |                            |
   |          (user types the code into your UI)               |
   |                              |                            |
   |-- POST /otp/verify --------->|                            |
   |<-- 200 + verificationToken --|                            |
   |                              |                            |
   |-- POST /otp/redeem --------->|   spend it, server to server
   |<-- 200 ----------------------|
   |
   |-- now mint YOUR OWN session
```

### What you need before you write any code

1. **An API key.** Console > Administration > API keys > New key. It is shown once.
   Store it as `JCF_API_KEY` in your server environment.
2. **Nothing else.** No SMTP credentials, no SES access, no DNS changes.

### The four calls

Base URL `https://mailer.jarurat.care/api/v1`. Auth header
`Authorization: Bearer jcf_live_...` on every call.

```
POST /otp/request   {"email","purpose","data":{"APP_NAME":"..."}}  -> 202 {challengeId,...}
POST /otp/verify    {"challengeId","code"}                          -> 200 {verificationToken}
POST /otp/redeem    {"verificationToken"}                           -> 200, once only
POST /otp/resend    {"challengeId"}                                 -> 202, new code
```

`purpose` is one of `LOGIN`, `REGISTER`, `RESET_PASSWORD`, `VERIFY_EMAIL`, `STEP_UP`.

### The five things integrators get wrong

1. **202 does not mean "we sent it".** It is returned identically for a real address,
   an unknown one, a bounced one and one over its rate limit. That is deliberate
   anti-enumeration. **Never branch your UI on it** and never show the user a different
   screen for "we don't know that email".

2. **The API key must never reach a browser.** There is no CORS on `/api/v1/**` and
   there must never be one. This key sends DKIM-signed mail from `jarurat.care` to any
   address on earth. In a browser it is a published phishing platform with our
   reputation attached. Server-side only, always.

3. **Redeem the verification token.** Without it a compromised front end just tells
   your backend "the OTP passed". The token is the proof, it is single use, and it
   expires in five minutes.

4. **A resend issues a NEW code.** Only a keyed hash of the original is stored and it
   is genuinely unrecoverable, which is the point. Tell the user the newest email is
   the one that works.

5. **Rate limits you can see and ones you cannot.** Per-key (60/min) and per-IP
   (20/hour) return `429`. Per-address (3 per 15 min, 10 per day) is silent: you still
   get a 202 and no message is sent. Do not build a retry loop around the 202.

### Working client

```js
// lib/jcf-otp.js  -  server side only. Never import into client code.
const BASE = "https://mailer.jarurat.care/api/v1";
const KEY  = process.env.JCF_API_KEY;          // never NEXT_PUBLIC_*

async function jcf(path, body) {
  const res = await fetch(BASE + path, {
    method: "POST",
    headers: { Authorization: `Bearer ${KEY}`, "Content-Type": "application/json" },
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(8000),
  });
  return { status: res.status, ...(await res.json().catch(() => ({}))) };
}

export async function requestOtp(email, purpose = "LOGIN") {
  const r = await jcf("/otp/request", { email, purpose, data: { APP_NAME: "Jarurat ID" } });
  if (r.status !== 202) throw new Error(r.error?.code ?? "OTP_REQUEST_FAILED");
  return { challengeId: r.challengeId, expiresAt: r.expiresAt };
}

export async function verifyOtp(challengeId, code) {
  const r = await jcf("/otp/verify", { challengeId, code });
  switch (r.status) {
    case 200: return { ok: true, email: r.email, token: r.verificationToken };
    case 401: return { ok: false, reason: "invalid", left: r.error?.attemptsRemaining ?? 0 };
    case 410: return { ok: false, reason: "expired" };
    case 423: return { ok: false, reason: "locked",  retryAfter: r.error?.retryAfterSeconds };
    case 429: return { ok: false, reason: "limited", retryAfter: r.error?.retryAfterSeconds };
    default:  return { ok: false, reason: "error" };
  }
}

export const redeemOtp = async (t) =>
  (await jcf("/otp/redeem", { verificationToken: t })).status === 200;
```

### Customising the email

Five templates are seeded and editable in Console > Templates: `otp-login`,
`otp-register`, `otp-reset-password`, `otp-verify-email`, `otp-step-up`.

`{{OTP_CODE}}` and `{{OTP_TTL_MINUTES}}` are injected by the platform and **cannot be
supplied by the caller** - a compromised integration cannot email somebody a code of
its own choosing. `{{APP_NAME}}` and anything else you put in `data` are yours.

OTP mail deliberately carries no unsubscribe footer, no open pixel and no click
rewriting. A security message with a tracking pixel is a security message with a third
party watching it.

---

## 3. Sending ordinary transactional mail

Same API key, same base URL.

```
POST /transactional/send  {"template":"interview-round-1","to":"...","data":{...}}
GET  /templates           list what you can address by slug
GET  /ping                confirm the key works
```

Create the template in Console > Templates with type TRANSACTIONAL, give it a slug,
use `{{MERGE_TAGS}}` freely. `GET /templates` tells the caller which tags a template
expects, so the integration is self documenting.

---

## 4. Mailboxes: the human side

Real mailboxes for real people live in **Stalwart**, not in Campaign Studio. Campaign
Studio's Inbox screen is a client that talks to Stalwart over JMAP; it is not the
store.

### The admin interface

Stalwart's web admin runs on port **8443**, which is deliberately **not exposed to the
internet**. Reach it over an SSH tunnel:

```powershell
ssh -i <path-to-key>.pem -L 8443:127.0.0.1:8443 ubuntu@<ELASTIC_IP>
```

Then open `https://127.0.0.1:8443` and accept the certificate warning (the cert is for
the public name, not for localhost).

### Adding a mailbox

Admin UI > Directory > Accounts > Create. You need:

- **Login / name**: the local part, e.g. `firstname.lastname`
- **Email**: the full address, e.g. `firstname.lastname@jarurat.care`
- **Type**: Individual
- **Password**: generate a long random one; the user changes it after first sign in
- **Quota**: set one. Unlimited quotas on a 38GB disk end badly.

### Adding an alias

An alias is an extra address on an existing account, not a new account. In the admin
UI open the account and add to its **Email** list; the first entry is the primary, the
rest are aliases. All of them deliver to the same mailbox and any of them can be used
as a From address.

This matters for the migration: `hr@` has 29 aliases in Zoho and `partnership@` has 23.
Those are addresses people already write to, and every one that is missing here is mail
that hard-bounces.

### Checking your work, without guessing

The single most useful command. It asks the server the same question a real sender
asks, sends nothing, and needs no credentials:

```bash
python3 - <<'PY'
import smtplib
for addr in ["hr@jarurat.care", "firstname.lastname@jarurat.care", "postmaster@jarurat.care"]:
    with smtplib.SMTP("127.0.0.1", 25, timeout=10) as s:
        s.ehlo("probe.local"); s.mail("postmaster@jarurat.care")
        code, reply = s.rcpt(addr); s.rset()
        print(f"{addr:<34} {code} {'EXISTS' if code == 250 else 'MISSING'}")
PY
```

Run it on the box. **Use port 25, not 465** - 465 is the submission port and refuses
`MAIL FROM` until you authenticate, which produces a misleading `503 MAIL is required
first` for every address. Always include a deliberately fake address as a control; if
that one comes back 250 your probe is broken, not your server.

---

## 5. Operations that will come up

### Deploying a code change

The old "build on the server" rule is retired. Build locally against JDK 21 and ship
the jar:

```powershell
# on Windows, with JAVA_HOME pointing at a JDK 21
./mvnw -B package -DskipTests
scp -i <key> target/mailer-0.0.1-SNAPSHOT.jar ubuntu@<ELASTIC_IP>:/home/ubuntu/mailer-new.jar
ssh -i <key> ubuntu@<ELASTIC_IP> "bash /home/ubuntu/deploy.sh"
```

`deploy.sh` lives at the repo root. It backs up Postgres, generates `OTP_PEPPER` if
absent, keeps the previous jar for rollback, restarts, and verifies. About 40 seconds.

### PowerShell mangles quotes passed to ssh

Anything with nested quotes, `$(...)` or parentheses fails with `unexpected EOF`.
Write the remote work to a `.sh` file, `scp` it, then `ssh ... "bash /path.sh"`. This
will waste an hour if you do not know it.

### The SSH key's Windows ACL

`Permissions for the key file are too open` means `BUILTIN\Users` inherited access.
Fix in this order, so the file is never left unusable:

```powershell
icacls <key> /grant:r "$($env:USERNAME):(F)"
icacls <key> /inheritance:r
```

### Where things live

| Thing | Location |
|---|---|
| App jar | `/home/ubuntu/mailer-0.0.1-SNAPSHOT.jar`, unit `jcfmailer` |
| App secrets | `/etc/jcfmailer.env`, root, 0600 |
| Postgres | database `jarurat_mailer`, `sudo -u postgres psql -d jarurat_mailer` |
| Stalwart config | `/etc/stalwart/config.json` |
| Stalwart data | `/var/lib/stalwart` (RocksDB) |
| Stalwart logs | `/var/log/stalwart/stalwart.YYYY-MM-DD` (date suffixed, not `.log`) |
| nginx | owns 80/443, Let's Encrypt via certbot |

---

## 6. DNS, and the one that is easy to get wrong

Managed in **Squarespace** (Domains > jarurat.care > DNS), even though the nameservers
answer from `googledomains.com`. Squarespace bought Google Domains and still runs that
infrastructure. There is no GCP project to find.

**The Host field takes the label only.** Type `_dmarc`, not `_dmarc.jarurat.care`.
Getting this wrong once created a record living at `_dmarc.jarurat.care.jarurat.care`
that silently did nothing.

Current records, all verified live 2026-08-25:

| Type | Host | Value |
|---|---|---|
| A | `@` | `216.198.79.1` (Vercel) |
| CNAME | `www` | `68bcd95b8ad3427f.vercel-dns-017.com` |
| A | `mailer` | `<ELASTIC_IP>` |
| MX | `@` | `10 mailer.jarurat.care` |
| TXT | `@` | `v=spf1 ip4:<ELASTIC_IP> include:amazonses.com ~all` |
| TXT | `_dmarc` | `v=DMARC1; p=none; rua=...; ruf=...; sp=none; adkim=r; aspf=r` |
| MX | `ses` | `10 feedback-smtp.ap-south-1.amazonses.com` |
| TXT | `ses` | `v=spf1 include:amazonses.com ~all` |
| CNAME x3 | `<token>._domainkey` | SES DKIM, tokens below |

SES DKIM tokens are deliberately not recorded in this public repo. Read the live
values from SES (command below) or from the DNS zone. They are published as:
`<dkim-token-1>`, `<dkim-token-2>`,
`<dkim-token-3>`, each as `<token>._domainkey` CNAME
`<token>.dkim.amazonses.com`.

Get them from source with:
```bash
aws --profile jarurat sesv2 get-email-identity --email-identity jarurat.care --region ap-south-1
```
SES caches its own checks, so a `SUCCESS` with a stale `LastCheckedTimestamp` proves
nothing.

### Known gap: reverse DNS

PTR for `<ELASTIC_IP>` is still the AWS default
`ec2-<dashed-elastic-ip>.ap-south-1.compute.amazonaws.com`, not `mailer.jarurat.care`. Mail
sent **directly from the box** will be junked or rejected by the large providers until
a reverse DNS request is raised with AWS for the Elastic IP. Mail sent through SES is
unaffected, which is currently everything Campaign Studio sends.

---

## 7. Honest limits

Things people ask for that this platform does not do yet. Say so rather than improvising.

- **API keys are not scoped.** Every key can call every `/api/v1` endpoint. A key
  issued to a login system can also send the HR templates to anyone. Use one key per
  integration and revoke aggressively. This is the most valuable next change.
- **No idempotency keys.** A retried call sends a second email.
- **No batch endpoint.** One recipient per call.
- **No delivery webhook.** A 200 means SES accepted it, not that it arrived.
- **Inbound auto-triggering does not exist.** `MessageLogService.recordInbound` has no
  callers. The deliverable version is an explicit API trigger plus a signed inbound
  webhook, not a mailbox poller.
- **Reply detection is not wired up**, so the REPLIED journey condition never fires.
