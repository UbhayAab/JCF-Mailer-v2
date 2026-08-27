# Campaign Studio machine API

Base URL `https://mailer.jarurat.care/api/v1`

Everything here is authenticated with an API key, created under **Administration >
API keys** in the console. Send it either way:

```
Authorization: Bearer jcf_live_...
X-API-Key: jcf_live_...
```

**The key must never reach a browser.** There is deliberately no CORS configuration on
`/api/v1/**` and there must never be one. This key can send DKIM-signed mail from
`jarurat.care` to any address in the world, so a key in front-end JavaScript is a
published key and a phishing platform with our reputation attached. Call these
endpoints from your server only.

---

## 1. One time codes

The OTP flow is three calls from your server, plus one to redeem the proof.

### `POST /otp/request`

```json
{
  "email": "priya@example.com",
  "purpose": "LOGIN",
  "data": { "APP_NAME": "Jarurat ID" }
}
```

| Field | Required | Notes |
|---|---|---|
| `email` | yes | |
| `purpose` | yes | `LOGIN`, `REGISTER`, `RESET_PASSWORD`, `VERIFY_EMAIL`, `STEP_UP` |
| `template` | no | a template slug to override the default for this purpose |
| `format` | no | `digits6` (default) or `alnum8` |
| `ttlSeconds` | no | 60 to 1800, default 600 |
| `data` | no | extra merge values for the template, up to 20 keys |

**202 Accepted**

```json
{
  "status": "accepted",
  "challengeId": "otp_8Fq2vKcR7pLmXd3NwYs4Tg",
  "expiresAt": "2026-08-25T12:34:56Z",
  "resendAvailableAt": "2026-08-25T12:26:56Z",
  "codeLength": 6
}
```

**202 is the only success, and you get it for every address.** A real user, an address
we have never seen, one that hard-bounced and one that is over its rate limit all
produce the identical body. That is deliberate: a caller who can tell them apart can
enumerate your user base one request at a time. **Do not branch your UI on this
response.** Whether a message actually went out is recorded server side and visible to
an operator in the console.

`OTP_CODE` and `OTP_TTL_MINUTES` are injected by the platform and cannot be supplied
through `data`. A compromised integration cannot email somebody a code of its choosing.

### `POST /otp/verify`

```json
{ "challengeId": "otp_8Fq2vKcR7pLmXd3NwYs4Tg", "code": "483920" }
```

Or, if your server keeps no state between the two calls:

```json
{ "email": "priya@example.com", "purpose": "LOGIN", "code": "483920" }
```

**200 OK**

```json
{
  "status": "verified",
  "email": "priya@example.com",
  "purpose": "LOGIN",
  "verifiedAt": "2026-08-25T12:29:11Z",
  "verificationToken": "otpv_3kR9...",
  "verificationTokenExpiresAt": "2026-08-25T12:34:11Z"
}
```

| Status | `error.code` | When |
|---|---|---|
| 400 | `OTP_MALFORMED` | no code supplied, or a nonsense address or purpose |
| 401 | `OTP_INVALID` | wrong code, unknown challenge, or one already used. Carries `attemptsRemaining` |
| 410 | `OTP_EXPIRED` | only when you supplied `challengeId` and that challenge has expired |
| 423 | `OTP_LOCKED` | attempts exhausted, or the address is locked out. Carries `retryAfterSeconds` |
| 429 | `RATE_LIMITED` | too many requests from this key or IP. Honour `Retry-After` |

Expired is only distinguished from invalid when you hold the challenge id, because
holding it already proves you made the request. Resolving by address collapses both to
`OTP_INVALID` so the endpoint cannot be used to discover which addresses exist.

### `POST /otp/resend`

```json
{ "challengeId": "otp_8Fq2vKcR7pLmXd3NwYs4Tg" }
```

Same 202 body as `/request`, plus a `note`.

**A resend sends a NEW code and retires the previous one.** It has to: only a keyed
hash of the original is stored and it is genuinely unrecoverable, which is the whole
point. Tell your user that the newest email is the one that works. The original expiry
is not extended, and the cooldown is 60 seconds with a maximum of 3 sends per
challenge.

### `POST /otp/redeem`

```json
{ "verificationToken": "otpv_3kR9..." }
```

Call this from your server after a successful verify, then mint your own session.
It works exactly once; a second call returns `401 OTP_TOKEN_SPENT`.

**This is the point of the token.** Without it, a compromised front end could simply
assert "the OTP passed" to your own backend. With it, your backend confirms the claim
with us before trusting it.

### Limits

| Scope | Limit | Visible to you? |
|---|---|---|
| Per address and purpose | 3 per 15 minutes, 10 per day | No. Silently not sent |
| Verify attempts per challenge | 5 | Yes, via `attemptsRemaining` |
| Failed verifies per address | 10 per hour, then locked 30 minutes | Yes, via `OTP_LOCKED` |
| Per API key | 60 requests per minute | Yes, `429` |
| Per IP | 20 per hour | Yes, `429` |
| Whole platform | 500 sends per hour | No. Logged and alerted |

### What the code itself is

Six digits, drawn with `SecureRandom` using rejection sampling so there is no modulo
bias. Stored only as `HMAC-SHA256`, keyed with a server-side pepper held in the
environment rather than the database, and bound to the challenge id and the address so
a hash lifted from one row cannot be tested against another. Compared in constant time.

A six digit code is about twenty bits, so no hashing work factor could protect it from
someone holding the table. Keeping the key out of the table is what does, and the five
attempt limit is what makes online guessing hopeless.

### Deliverability

OTP mail carries no unsubscribe footer, no open pixel and no click rewriting. A
security message with a tracking pixel is a security message with a third party
watching it, and an unsubscribe link would be a lie because nobody can opt out of their
own login.

A marketing unsubscribe never blocks a code. A hard bounce or a spam complaint does,
because that address is dead or hostile and SES would reject it anyway.

### Integration, server side only

```js
// lib/jcf-otp.js  -  Node 18+, a Next.js route handler, an Express handler.
// NEVER import this into client code.
const BASE = "https://mailer.jarurat.care/api/v1";
const KEY  = process.env.JCF_API_KEY;        // never NEXT_PUBLIC_*

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
  // Show the same screen whatever happens. The response is identical for an address
  // we do not know, and your UI must not leak the difference either.
  return { challengeId: r.challengeId, expiresAt: r.expiresAt };
}

export async function verifyOtp(challengeId, code) {
  const r = await jcf("/otp/verify", { challengeId, code });
  switch (r.status) {
    case 200: return { ok: true, email: r.email, token: r.verificationToken };
    case 401: return { ok: false, reason: "invalid", left: r.error?.attemptsRemaining ?? 0 };
    case 410: return { ok: false, reason: "expired" };
    case 423: return { ok: false, reason: "locked", retryAfter: r.error?.retryAfterSeconds };
    case 429: return { ok: false, reason: "rate_limited", retryAfter: r.error?.retryAfterSeconds };
    default:  return { ok: false, reason: "error" };
  }
}

export async function redeemOtp(token) {
  const r = await jcf("/otp/redeem", { verificationToken: token });
  return r.status === 200;
}
```

```bash
curl -sS -X POST https://mailer.jarurat.care/api/v1/otp/request \
  -H "Authorization: Bearer $JCF_API_KEY" -H "Content-Type: application/json" \
  -d '{"email":"priya@example.com","purpose":"LOGIN","data":{"APP_NAME":"Jarurat ID"}}'

curl -sS -X POST https://mailer.jarurat.care/api/v1/otp/verify \
  -H "Authorization: Bearer $JCF_API_KEY" -H "Content-Type: application/json" \
  -d '{"challengeId":"otp_8Fq2...","code":"483920"}'
```

---

## 2. Transactional mail

### `POST /transactional/send`

```json
{
  "template": "interview-round-1",
  "to": "candidate@example.com",
  "data": {
    "CANDIDATE_NAME": "Priya",
    "ROLE": "Program Manager",
    "INTERVIEW_DATE": "21 Aug 2026",
    "SENDER_NAME": "People Team"
  }
}
```

`200 {"status":"sent","messageId":"..."}` or `400` with an error.

`subject` may be supplied to override the template's own.

### `GET /templates`

Lists the transactional templates you can address by slug.

### `GET /ping`

Confirms the key works and echoes which key it is.

---

## 3. Seeded OTP templates

Created automatically on first boot, editable under **Templates** in the console.

| Slug | Used for |
|---|---|
| `otp-login` | `LOGIN`, and the fallback for anything else |
| `otp-register` | `REGISTER` |
| `otp-reset-password` | `RESET_PASSWORD` |
| `otp-verify-email` | `VERIFY_EMAIL` |
| `otp-step-up` | `STEP_UP` |

Each uses `{{OTP_CODE}}`, `{{OTP_TTL_MINUTES}}` and `{{APP_NAME}}`. Keep the first two
exactly as they are; edit the wording around them freely.

---

## 4. Known gaps, stated plainly

These are real and worth knowing before you build against this.

- **No `Idempotency-Key` support yet.** A retried call sends a second message. Make
  your client retry only on a network error, not on a 5xx that may have succeeded.
- **No batch endpoint.** One address per call.
- **No delivery webhook.** A `200` means SES accepted the message, not that it arrived.
  Per-message delivery events need an SES configuration set, which is not wired up:
  `aws.ses.configurationSet` is blank, so bounces are learned from a 15 minute mirror
  of the SES account suppression list rather than reported per message.
- **API keys are not scoped.** Any key can call any `/api/v1` endpoint. A key issued to
  the login system can also send the HR templates. Use separate keys per integration
  and revoke aggressively; per-key scopes and quotas are the obvious next change.
- **`otp.pepper` defaults to a per-process key.** Set `OTP_PEPPER` in
  `/etc/jcfmailer.env` or a restart invalidates every outstanding code. The service
  logs a warning at startup when it is unset.
