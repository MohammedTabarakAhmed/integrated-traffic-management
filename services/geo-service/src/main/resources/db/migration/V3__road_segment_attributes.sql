alter table geo_node add column road_type varchar(24);
alter table geo_node add column lane_count smallint;
alter table geo_node add column direction varchar(12);
alter table geo_node add column owning_authority varchar(80);
alter table geo_node add constraint ck_geo_road_segment_attributes check (
    node_type <> 'ROAD_SEGMENT' or
    (road_type is not null and lane_count is not null and lane_count between 1 and 32
     and direction is not null and direction in ('BIDIRECTIONAL','FORWARD','REVERSE')
     and owning_authority is not null)
);
