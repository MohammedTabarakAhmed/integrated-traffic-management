-- CC-100, CC-102 (Increment 10): unified staff search. search.execute only opens the search bar; what a user finds is
-- decided by each owning service with the user's own permissions and data scope. Arabic texts are DRAFT (Q-006).

insert into permission (code, service, description_nl, description_en) values
    ('search.execute', 'search-service', 'البحث الموحد في السجلات',
     'Search across traffic files, licences, vehicles, violations, accidents and transactions');

insert into role (code, name_nl, name_en, description, active, permissions, version, created_at, created_by,
                  updated_at, updated_by) values
    ('SEARCH_USER', 'مستخدم البحث الموحد', 'Unified search user',
     'Uses the unified search bar; results follow the user''s own read permissions and data scope (CC-100, CC-102).',
     true, array['search.execute'], 1, now(), 'SYSTEM_MIGRATION', now(), 'SYSTEM_MIGRATION');
