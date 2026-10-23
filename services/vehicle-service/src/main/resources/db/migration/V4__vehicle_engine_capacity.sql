-- VL-001: engine capacity (cc) and origin joined the vehicle's technical data. Both are optional (existing records
-- and clients stay valid); the checks hold when present. Origin is format-checked only until reference data
-- (CC-110); nothing else changes.

alter table vehicle add column engine_capacity_cc integer;
alter table vehicle add column origin varchar(40);

alter table vehicle add constraint ck_vehicle_engine_capacity
    check (engine_capacity_cc is null or (engine_capacity_cc between 1 and 20000));
alter table vehicle add constraint ck_vehicle_origin
    check (origin is null or origin ~ '^[A-Za-z][A-Za-z ]{1,39}$');
