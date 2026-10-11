-- TR-032, VF-014, UAT-2: a violation without an identified driver, committed while a rental contract covered the
-- vehicle, goes to the renter's traffic file. The contract is kept with the violation; for a visitor renter without a
-- traffic file only the contract is kept and the owner stays responsible (Q-081). Both columns are facts: the
-- existing violation_guard trigger already refuses any change to them after recording.

alter table violation add column renter_file_number varchar(60);
alter table violation add column rental_contract_id uuid;
alter table violation add constraint ck_violation_renter
    check (renter_file_number is null or (rental_contract_id is not null and driver_file_number is null));
