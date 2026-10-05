-- transaction-service schema, part 4: Draft expiry (CC-024; ADR-031).

-- CC-023: a change the system makes on its own schedule records the channel SYSTEM. Only the system actor may use
-- it, and a transaction is never opened through it.
alter table service_transaction drop constraint ck_transaction_channels;
alter table service_transaction add constraint ck_transaction_channels check (
    channel in ('PORTAL', 'APP', 'BACKOFFICE', 'PARTNER', 'KIOSK', 'CALLCENTRE', 'FIELD', 'ASSISTANT', 'AGENT')
    and status_channel in ('PORTAL', 'APP', 'BACKOFFICE', 'PARTNER', 'KIOSK', 'CALLCENTRE', 'FIELD', 'ASSISTANT',
                           'AGENT', 'SYSTEM')
    and (status_channel <> 'SYSTEM' or status_actor_id = 'SYSTEM'));

-- CC-024: an expired Draft is expired by the system, through the SYSTEM channel, with reason EXPIRED.
alter table service_transaction add constraint ck_transaction_draft_expiry check (
    status <> 'EXPIRED' or submitted_at is not null
    or (status_reason = 'EXPIRED' and status_actor_id = 'SYSTEM' and status_channel = 'SYSTEM'));

-- The expiry job reads Drafts oldest first; only Drafts are in the index, so it stays small.
create index ix_transaction_draft_created on service_transaction (created_at) where status = 'DRAFT';
