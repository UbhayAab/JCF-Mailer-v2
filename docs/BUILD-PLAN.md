# Campaign Studio - build plan and status

Started 2026-08-24 from a full read of the working tree. Updated 2026-08-25.
Status of every item is marked. 65 tests pass; the whole app boots and was driven
end to end over HTTP against H2 before this was written.

---

## 0. Baseline: what was already there

Verified by reading the code, not by assumption. Nothing below was rebuilt.

- Open and click tracking with a real classifier: `analytics/OpenClassifier` buckets
  every pixel hit into HUMAN / APPLE_MPP / PROXY / BOT, and only HUMAN moves a rate.
  Raw signals are kept so history can be reclassified. Better than most paid tools.
- `AnalyticsService`: delivered, open rate, click rate, CTOR, bounce and complaint
  rates, a daily series, top links, client split, plus an `inflationFactor` showing
  what the naive number would have claimed.
- Single-email campaigns end to end, with a 12/sec SES rate gate, virtual threads,
  retry with backoff, a suppression re-check at send time, and one-click unsubscribe.
- Lists, subscribers, suppression, and a genuinely good CSV importer
  (`CsvImportService`: BOM, CRLF, delimiter sniffing, quoted newlines, per-row reasons).
- Templates, transactional send by slug, API-key auth, verification, message log,
  webmail, roles, audit.

**So the honest framing of the ask:** analytics was not missing, it was unexposed at
the person level. Journeys, A/B, dynamic test fields, composer CSV and OTP were
genuinely absent.

---

## Defects found and fixed along the way

These were live in production code and none of them was in the brief. Each is covered
by a test.

- **[FIXED] A campaign subject was never merged.** `sendOne` passed
  `campaign.getSubject()` straight to SES while only the body went through
  `renderMarketing`. A subject reading `{{FIRST_NAME}}, your slot is confirmed` shipped
  with the braces intact to every inbox, while the test send and the preview both
  rendered it correctly and hid the bug.
- **[FIXED] A send could only ever fill three merge fields.** The map held NAME, EMAIL
  and FIRST_NAME, so `{{COMPANY}}`, `{{LAST_NAME}}` and `{{PHONE}}` rendered blank for
  everyone however well populated the subscriber table was. The subscriber row was
  already being loaded a few lines later, so widening it cost no extra query.
- **[FIXED] `SesSender.escape` did not escape quotes.** Merge values are substituted
  before links are rewritten, so a value landing inside `href="..."` could close the
  attribute and add its own. A value of `" onmouseover="alert(1)` was a script
  injection into every recipient's mail, sourced from whatever fed the subscriber row.
- **[FIXED] The merge-tag pattern existed in three separate copies.** Now one
  definition in `merge/MergeTags`, which the sender, the composer, the template
  library and the journey nodes all read.
- **[FIXED] The Analytics campaign picker was filled once per page load,** so a
  campaign created afterwards never appeared and the most recent send was always the
  one you could not look at.
- **[FIXED] Sub-second timestamps made scheduling unreliable.** Postgres truncates and
  H2 rounds, so `due at or before now` could miss a row by a microsecond and behave
  differently in test and in production. All scheduling is now on whole seconds.

---

## 1. Analytics at the person level - DONE

- [x] **1.1** Engagement segments as first-class queryable groups: CLICKED,
      OPENED_NOT_CLICKED, PRIVACY_UNKNOWN, NOT_OPENED, BOUNCED, UNSUBSCRIBED,
      COMPLAINED, FAILED, SKIPPED. One parameterised query, so the tile and the list
      behind it cannot disagree.
- [x] **1.2** Every tile is clickable and lists the actual people, paged and searchable.
- [x] **1.3** Three actions per segment: view, export CSV, and **save as a mailing
      list**. A bounced group refuses to become a list, because handing someone an
      audience guaranteed to bounce again is how a sending domain loses its reputation.
- [x] **1.5** Time-to-open distribution, bucketed, with the median.
- [x] **1.6** The ceiling stated in the UI. The open rate is shown **as a range**
      whenever more than 10% of possible openers sit behind a privacy proxy, with the
      size of the doubt named. The word "reading" appears nowhere.
- [ ] **1.4** A drawn funnel. The numbers behind it all exist; only the chart is missing.
- [ ] **1.7** Per-recipient timeline across campaigns.
- [ ] **1.8** SES configuration set, so DELIVERED is observed rather than inferred.
      This is configuration, not code, and it is the single highest-value remaining
      item: without it soft bounces are invisible and bounces cannot be attributed to
      a campaign. Blocked on the IAM user being denied `SNS:CreateTopic`.
- [ ] **1.9** Rollup tables. Not needed until the account-wide year view gets slow.

## 2. Multi-email journeys - DONE

- [x] **2.1** Clicking New campaign asks single or multi before anything else.
- [x] **2.2** N base sheets, each a root on the canvas.
- [x] **2.3** Flow canvas in plain HTML plus one SVG wire layer. No library, no build
      step. Drag, connect, select, keyboard-connect with `c`, live region announcements.
- [x] **2.4** SOURCE, EMAIL, SPLIT, CONDITION, WAIT, EXIT.
- [x] **2.5** An email node opens the same fields the single composer has, including
      its own merge-value test panel. A journey email is always scheduled.
- [x] **2.6** Stages, with several emails on one stage as an A/B split.
- [x] **2.7** A fixed condition catalogue, each entry mapping to one exact predicate.
- [x] **2.8** Conditional sheets, read straight off the participant rows so they cannot
      drift from the flow. A rank ladder settles "opened AND clicked" as clicked and
      "opened AND unsubscribed" as unsubscribed, whatever order the signals arrive in.
- [x] **2.9** Loops, with three independent caps: passes per person, emails per person,
      and an absolute deadline. Plus a per-tick circuit breaker that pauses the journey
      rather than sending if a pass would advance an implausible number of people.
- [x] **2.10** Copy a whole branch onto another base sheet, deep-copied and editable.
- [x] **2.11** Per-participant clocks. "48h after it reached this person", not after the
      campaign started. Quiet hours, shifted forward only.
- [x] **2.12** A tick that is idempotent, crash-safe and shares the existing SES gate.
- [x] **2.13** The shape is frozen while people are in flight; wording and timing stay
      editable. Caps can be tightened live but never loosened without pausing.
- [x] **2.15** Per-node occupancy, an event log that answers "why did this person not
      get the second email", and per-variant comparison.
- [ ] **2.14** A dry-run simulator that reports who would land where without sending.
      The event log covers the diagnosis case; this would cover the rehearsal case.

## 3. A/B testing - DONE

- [x] **3.1** Deterministic assignment: SHA-256 over node key, subscriber id and a
      configured salt. Proven stable across retries and restarts, balanced at n=4000,
      and independent between stages.
- [x] **3.2** Weights normalised rather than validated. 40/40/30 becomes 36/36/27
      instead of an error, and a 0% arm is legal and receives nobody.
- [x] **3.3** Vary subject, preheader, from name, reply-to, body and timing per arm.
- [x] **3.5** Per-arm comparison under the same honest classification.
- [x] **3.6** Statistical honesty. Below 300 delivered per arm the report refuses to
      name a winner and states the smallest difference the sample could detect.
- [ ] **3.4** A variant column on `tracking_event`. Arm attribution currently comes
      from the participant row, which is correct but makes hit-level per-arm queries
      a join.
- [ ] **3.7** Auto-promotion of a winner. Deliberately not built: on lists of a few
      hundred it would be superstition with a cron job attached.

## 4. Test send with real merge values - DONE

- [x] **4.1** One labelled input per merge tag actually used, rebuilt as you type.
      Reserved tags are never offered.
- [x] **4.2** The test sends with those values. Subject included.
- [x] **4.3** The on-the-wire tab uses the same values, so it shows the message that
      was tested rather than a different one.
- [x] **4.4** The same panel inside every journey email node.
- [x] **4.5** Values remembered per campaign in localStorage.

## 5. Composer audience import - DONE

- [x] **5.1** Upload a CSV in the composer itself.
- [x] **5.2** Paste: one per line, comma separated, `Name <a@b.com>`, or a block copied
      out of Excel. Detected in that order, each with its own message.
- [x] **5.3** A column mapping table with the importer's guess preselected.
- [x] **5.4** A discrepancy report: blockers, warnings and notes, with the copy for each.
- [x] **5.5** Total, valid, duplicate, invalid and suppressed counts from a real dry run.
- [x] **5.6** `CsvImportService` extended rather than duplicated.
- [ ] The legacy `SubscriberService.importCsv` behind the Lists modal is still the
      weaker parser. Pointing that modal at the good one is a small, worthwhile change.

## 6. Transactional and OTP - MOSTLY DONE

- [x] **6.2** OTP as a first-class subsystem: request, verify, resend, redeem.
      HMAC-SHA256 with an environment-held pepper, bound to the row and the address.
      Rejection-sampled codes. Ten minute expiry, single use, five attempts, lockout.
      Limits per address, per key, per IP and globally.
- [x] **6.3** Enumeration resistance, proven by test: an unknown address, a bounced one
      and one over its limit all return the identical 202.
- [x] **6.4** OTP mail carries no unsubscribe footer, no pixel and no click rewriting.
- [x] **6.9** A written API reference for the calling project: `docs/API.md`.
- [ ] **6.1 / 6.10** The transactional audit turned up real issues not yet fixed:
      unbounded string writes that can 500, two log rows written for a request that
      never became a message, and the subject being HTML-escaped so `Ram & Co` ships as
      `Ram &amp;amp; Co`.
- [ ] **6.5** Idempotency keys. A retried call currently sends twice.
- [ ] **6.6 / 6.7** Batch send and per-key scopes and quotas. **The scoping gap is the
      one worth acting on:** any key can call any `/api/v1` endpoint, so a key issued
      to the login system can also send the HR templates.
- [ ] **6.8** Auto-trigger on inbound mail. Honestly assessed as not deliverable as
      asked: `MessageLogService.recordInbound` has no callers, the JMAP client holds
      its mailbox password only in heap and needs a human to type it after every
      restart, and there is no `since` cursor. The deliverable version is an explicit
      API trigger plus a signed inbound webhook feeding the same rule engine, with the
      mailbox poller added later as an adapter.

## 7. Cross-cutting

- [x] **7.2** 65 tests where there was one. Bucket ladder, allocator determinism,
      quiet hours, merge tags, the full journey flow against a real database, the
      engagement segments, and the OTP security properties.
- [x] **7.3** JDK 21 unpacked locally, so the build no longer has to happen on the
      server first. `mvnw test` runs the whole suite on H2 in about 40 seconds.
- [ ] **7.1** A forward-only migration script. Hibernate `ddl-auto=update` will create
      the new tables on the live box, but the new indexes deserve a reviewed script.
- [ ] **7.4** Deploy: build on the box with JDK 21, restart `jcfmailer`, smoke test.

---

## Verified how

- `mvnw test`: 65 tests, all passing. Includes a ten-person journey run end to end
  against a real database, with the loop proven to terminate and every person proven
  to land in exactly one sheet.
- The packaged jar was booted on H2 and driven over HTTP: logged in, created a list and
  subscribers, built a six-node flowchart with a loop-back edge (auto-detected as a
  loop by the server, not trusted from the client), validated it, activated it, and ran
  a pass. Five people were admitted and advanced, a campaign was materialised, five
  recipients were enrolled, and each send failed with "unable to load credentials",
  which is exactly right with no AWS key present locally. The failure was caught per
  person and logged with its reason rather than breaking the tick.
- Quiet hours were observed doing their job: at 00:51 local the first send was deferred
  to 08:00 rather than firing at midnight.
- The OTP endpoints were exercised with a real API key: request, wrong code with
  `attemptsRemaining`, an unknown address returning a byte-identical shape, malformed
  input, and an unauthenticated call.

## What is not verified

- Nothing has run against Postgres. The native SQL in `findAdmissible` and
  `queueFromList` uses `||` concatenation and `limit`, which H2 in PostgreSQL mode
  accepts, but the production database is the only place that proves it.
- No message has actually been delivered by SES from this code. Every send path was
  exercised with SES mocked or failing.
- The canvas has not been opened in a browser. The JavaScript parses and the endpoints
  it calls all answer correctly, but the drag, connect and redraw behaviour is
  unexercised until someone loads the page.
