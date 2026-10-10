create table geo_node (
    id uuid primary key,
    parent_id uuid references geo_node(id),
    node_type varchar(24) not null check (node_type in ('REGION','CITY','AREA','STREET','INTERSECTION','BRIDGE','TUNNEL','ROAD_SEGMENT','SERVICE_CENTRE')),
    code varchar(40) not null unique,
    name_en varchar(200) not null,
    name_ar varchar(200) not null,
    region varchar(40),
    latitude double precision check (latitude between -90 and 90),
    longitude double precision check (longitude between -180 and 180),
    speed_limit_kmh double precision check (speed_limit_kmh is null or speed_limit_kmh > 0),
    bridge_load_tonnes double precision check (bridge_load_tonnes is null or bridge_load_tonnes > 0),
    restrictions varchar(1000),
    opening_hours varchar(500),
    created_by varchar(120) not null,
    created_at timestamptz not null,
    check ((latitude is null)=(longitude is null)),
    check ((node_type='REGION' and parent_id is null) or (node_type<>'REGION' and parent_id is not null))
);
create index geo_node_parent on geo_node(parent_id,node_type,code);
