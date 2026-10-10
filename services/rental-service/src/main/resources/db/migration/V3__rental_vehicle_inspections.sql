-- Pickup and return inspection snapshots preserve rental vehicle condition and reported damage (TR-034).

create table rental_inspection (
    id              uuid          primary key,
    contract_id     uuid          not null references rental_contract(id),
    inspection_type varchar(8)    not null,
    inspected_at    timestamptz   not null,
    odometer_km     bigint        not null,
    fuel_percent    smallint      not null,
    damage_notes    text[]        not null,
    evidence_ids    varchar(64)[] not null,
    notes           varchar(1000),
    inspected_by    varchar(100)  not null,
    constraint uq_rental_inspection_type unique(contract_id,inspection_type),
    constraint ck_rental_inspection_type check (inspection_type in ('PICKUP','RETURN')),
    constraint ck_rental_inspection_odometer check (odometer_km >= 0),
    constraint ck_rental_inspection_fuel check (fuel_percent between 0 and 100),
    constraint ck_rental_inspection_damage_count check (cardinality(damage_notes) <= 100),
    constraint ck_rental_inspection_evidence_count check (cardinality(evidence_ids) <= 100)
);

create index ix_rental_inspection_contract on rental_inspection(contract_id,inspection_type);
