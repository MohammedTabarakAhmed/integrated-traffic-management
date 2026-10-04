-- transaction-service schema, part 3: tracking an applicant's transactions (CC-025; ADR-030). Newest first, in the
-- order of the cursor (created_at, id), so a page is one index range scan.
create index ix_transaction_applicant on service_transaction (applicant_file_number, created_at desc, id desc);
