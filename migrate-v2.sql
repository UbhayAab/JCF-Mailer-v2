-- ---------------------------------------------------------------------------
-- JCF Mailer v1 -> v2 data migration
--
-- v1 stored a separate contact row per campaign, so the same person existed
-- many times over. v2 has one global subscriber, reusable lists, and a
-- per-send recipient snapshot. This lifts the old data into the new shape.
--
-- Run AFTER the v2 jar has booted once and Hibernate has created the new
-- tables, and AFTER the v1 tables have been renamed to legacy_*.
-- Idempotent: every insert skips rows that already exist.
-- ---------------------------------------------------------------------------

BEGIN;

-- 1. One subscriber per distinct email ---------------------------------------
INSERT INTO subscriber (email, first_name, last_name, source, status,
                        unsubscribe_token, created_at, updated_at,
                        total_sent, total_opened, total_clicked)
SELECT
    lower(t.email),
    split_part(t.name, ' ', 1),
    NULLIF(trim(substr(t.name, length(split_part(t.name, ' ', 1)) + 1)), ''),
    'legacy import',
    CASE
        WHEN EXISTS (SELECT 1 FROM global_suppression g WHERE g.email = lower(t.email)) THEN 'UNSUBSCRIBED'
        WHEN t.any_unsub THEN 'UNSUBSCRIBED'
        ELSE 'SUBSCRIBED'
    END,
    gen_random_uuid()::text,
    now(), now(),
    t.sent_total, t.opened_total, t.clicked_total
FROM (
    SELECT
        email,
        max(name)                                                   AS name,
        bool_or(status = 'UNSUBSCRIBED')                            AS any_unsub,
        count(*) FILTER (WHERE status = 'SENT')::int                AS sent_total,
        count(*) FILTER (WHERE opened_at IS NOT NULL)::int          AS opened_total,
        count(*) FILTER (WHERE clicked_url IS NOT NULL)::int        AS clicked_total
    FROM legacy_contact
    WHERE email IS NOT NULL AND email <> ''
    GROUP BY email
) t
ON CONFLICT (email) DO NOTHING;

-- 2. One list per old campaign name ------------------------------------------
INSERT INTO mailing_list (name, description, kind, created_at, created_by)
SELECT DISTINCT
    lc.campaign_name,
    'Imported from the v1 campaign of the same name',
    'IMPORT',
    now(),
    'migration'
FROM legacy_contact lc
WHERE lc.campaign_name IS NOT NULL AND lc.campaign_name <> ''
ON CONFLICT (name) DO NOTHING;

-- 3. Attach people to those lists --------------------------------------------
INSERT INTO list_member (list_id, subscriber_id, added_at)
SELECT DISTINCT ml.id, s.id, now()
FROM legacy_contact lc
JOIN mailing_list ml ON ml.name = lc.campaign_name
JOIN subscriber   s  ON s.email = lower(lc.email)
ON CONFLICT (list_id, subscriber_id) DO NOTHING;

-- 4. Campaigns ---------------------------------------------------------------
INSERT INTO campaign (name, subject, html_body, list_id, status,
                      created_at, created_by, total_recipients, sent_count,
                      failed_count, track_opens, track_clicks)
SELECT
    lgc.name,
    lgc.subject,
    lgc.html_body,
    ml.id,
    COALESCE(NULLIF(lgc.status, ''), 'SENT'),
    now(),
    'migration',
    COALESCE(counts.total, 0),
    COALESCE(counts.sent, 0),
    0,
    true,
    true
FROM legacy_campaign lgc
LEFT JOIN mailing_list ml ON ml.name = lgc.name
LEFT JOIN (
    SELECT campaign_name,
           count(*)::int                                 AS total,
           count(*) FILTER (WHERE status = 'SENT')::int  AS sent
    FROM legacy_contact GROUP BY campaign_name
) counts ON counts.campaign_name = lgc.name
ON CONFLICT (name) DO NOTHING;

-- 5. Per-recipient send history ----------------------------------------------
INSERT INTO campaign_recipient (campaign_id, subscriber_id, email, name, status,
                                token, sent_at, opened_at, last_clicked_at,
                                open_count, click_count)
SELECT
    c.id,
    s.id,
    lower(lc.email),
    lc.name,
    CASE lc.status
        WHEN 'SENT'         THEN 'SENT'
        WHEN 'FAILED'       THEN 'FAILED'
        WHEN 'UNSUBSCRIBED' THEN 'SKIPPED'
        ELSE 'PENDING'
    END,
    COALESCE(lc.unsubscribe_token, gen_random_uuid()::text),
    lc.sent_at,
    lc.opened_at,
    lc.last_clicked_at,
    COALESCE(lc.open_count, 0),
    COALESCE(lc.click_count, 0)
FROM legacy_contact lc
JOIN campaign   c ON c.name  = lc.campaign_name
JOIN subscriber s ON s.email = lower(lc.email)
ON CONFLICT (campaign_id, subscriber_id) DO NOTHING;

-- 6. Click history ------------------------------------------------------------
INSERT INTO click_event (campaign_id, subscriber_id, email, url, timestamp)
SELECT c.id, s.id, lower(lce.email), lce.url, COALESCE(lce.timestamp, now())
FROM legacy_click_event lce
JOIN campaign   c ON c.name  = lce.campaign_name
JOIN subscriber s ON s.email = lower(lce.email);

-- 7. Marketing templates saved in the v1 library ------------------------------
INSERT INTO email_template (name, slug, subject, html_body, type, created_at, updated_at, created_by)
SELECT
    lt.name,
    regexp_replace(regexp_replace(lower(lt.name), '[^a-z0-9]+', '-', 'g'), '(^-+|-+$)', '', 'g'),
    lt.subject,
    lt.html_body,
    'MARKETING',
    COALESCE(lt.created_at, now()),
    now(),
    'migration'
FROM legacy_template lt
WHERE lt.name IS NOT NULL AND lt.name <> ''
ON CONFLICT (slug) DO NOTHING;

-- 8. Keep subscriber status consistent with the suppression list --------------
UPDATE subscriber s
SET status = CASE g.reason
                 WHEN 'BOUNCE'    THEN 'BOUNCED'
                 WHEN 'COMPLAINT' THEN 'COMPLAINED'
                 ELSE 'UNSUBSCRIBED'
             END
FROM global_suppression g
WHERE g.email = s.email AND s.status = 'SUBSCRIBED';

COMMIT;

-- Verification ----------------------------------------------------------------
SELECT 'subscribers'         AS entity, count(*) FROM subscriber
UNION ALL SELECT 'lists',              count(*) FROM mailing_list
UNION ALL SELECT 'list members',       count(*) FROM list_member
UNION ALL SELECT 'campaigns',          count(*) FROM campaign
UNION ALL SELECT 'recipients',         count(*) FROM campaign_recipient
UNION ALL SELECT 'click events',       count(*) FROM click_event
UNION ALL SELECT 'templates',          count(*) FROM email_template
UNION ALL SELECT 'suppressions',       count(*) FROM global_suppression
UNION ALL SELECT 'legacy contacts',    count(*) FROM legacy_contact
UNION ALL SELECT 'legacy campaigns',   count(*) FROM legacy_campaign;
