-- Correctness checks to run after a load scenario has finished AND the system is quiet
-- (no requests in flight, the provider's retry window has passed). Each row is
--   kind | check | count
-- HARD rows must be 0: any other value is a broken guarantee. INFO rows are just numbers
-- for the report.
--
-- No "older than N minutes" filters on purpose: right after a short run such a filter
-- matches nothing and the check would pass without checking anything.
\set ON_ERROR_STOP on
with terminal as (
    select payment_id, count(*) as n
    from payment_transitions
    where to_status in ('SUCCEEDED', 'FAILED', 'EXPIRED', 'CANCELLED')
    group by payment_id
),
latest as (
    select distinct on (payment_id) payment_id, to_status
    from payment_transitions
    -- Tie-break for two transitions in the same microsecond: the one leaving PENDING is
    -- the LATER step (CREATED->PENDING comes first), so it sorts first; the very first row
    -- (from_status null) sorts last.
    order by payment_id, created_at desc, (from_status = 'PENDING') desc nulls last
),
success_events as (
    select processing_status, raw_body::jsonb ->> 'merchantRef' as merchant_ref
    from webhook_events
    where raw_body::jsonb ->> 'status' = 'SUCCESS'
)
-- A payment may reach a terminal state once. Two rows = two writers both "won".
select 'HARD' as kind, 'payments_with_more_than_one_terminal_transition' as check, count(*) as count
from terminal where n > 1
union all
-- The audit ledger and the payment row must tell the same story.
select 'HARD', 'payment_status_differs_from_latest_transition', count(*)
from payments p join latest l on l.payment_id = p.id where l.to_status <> p.status
union all
-- RECEIVED after quiescence = a delivery that never recorded its outcome.
select 'HARD', 'webhook_events_stuck_received', count(*)
from webhook_events where processing_status = 'RECEIVED'
union all
-- RETRYABLE after quiescence = the provider gave up redelivering, or the run was checked
-- before its retry window ended. Either way the run does not show a settled system.
select 'HARD', 'webhook_events_left_retryable', count(*)
from webhook_events where processing_status = 'RETRYABLE'
union all
-- The money-loss shape: the provider said SUCCESS, we did not apply it, and the payment
-- is neither SUCCEEDED nor deliberately CANCELLED by the merchant first.
select 'HARD', 'success_event_not_applied_and_payment_not_succeeded', count(*)
from success_events e join payments p on p.id::text = e.merchant_ref
where e.processing_status <> 'APPLIED' and p.status not in ('SUCCEEDED', 'CANCELLED')
union all
-- Every scenario uses one order per payment, so two live rows for one order is a duplicate
-- charge. A FAILED/EXPIRED/CANCELLED row does not count: after a failed or abandoned create
-- the merchant may legitimately create the order again.
select 'HARD', 'orders_with_more_than_one_live_payment', count(*)
from (select 1 from payments where status in ('CREATED', 'PENDING', 'SUCCEEDED')
      group by merchant_id, order_id having count(*) > 1) d
union all
select 'HARD', 'idempotency_keys_still_in_progress', count(*)
from idempotency_keys where status = 'IN_PROGRESS'
union all
-- A PENDING payment that the provider has already reported on: the event arrived and the
-- payment did not move. (PENDING with no event at all is not an error by itself: a
-- time-boxed run stops threads between "create" and "pay", and the burst never pays.
-- A paid payment whose webhook never arrived shows up in the mock's own counters,
-- mock.webhook.exhausted / mock.webhook.rejected, which must be 0 for a valid run.)
select 'HARD', 'pending_payments_that_have_a_webhook_event', count(*)
from payments p
where p.status = 'PENDING'
  and exists (select 1 from webhook_events e where e.raw_body::jsonb ->> 'merchantRef' = p.id::text)
union all
select 'INFO', 'payments_' || lower(status), count(*) from payments group by status
union all
select 'INFO', 'events_' || lower(processing_status) || coalesce('_' || lower(reject_reason), ''), count(*)
from webhook_events group by processing_status, reject_reason
order by 1, 2;
