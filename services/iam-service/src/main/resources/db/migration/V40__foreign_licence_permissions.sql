-- Foreign licence exchange, temporary permits and international permits (DL-054..DL-056, DL-063).
insert into permission (code, service, description_nl, description_en) values
    ('licensing.exchange-country.submit', 'licensing-service', 'Landregel voor omwisseling indienen', 'Submit exchange-country rules'),
    ('licensing.exchange-country.approve', 'licensing-service', 'Landregel voor omwisseling beoordelen', 'Approve exchange-country rules'),
    ('licensing.foreign.record', 'licensing-service', 'Buitenlands rijbewijs registreren', 'Record foreign licences'),
    ('licensing.licence.exchange', 'licensing-service', 'Buitenlands rijbewijs omwisselen', 'Request a foreign licence exchange'),
    ('licensing.permit.issue', 'licensing-service', 'Tijdelijk rijbewijs afgeven', 'Issue or cancel a temporary permit'),
    ('licensing.idp.issue', 'licensing-service', 'Internationaal rijbewijs afgeven', 'Request an international driving permit'),
    ('licensing.idp.print', 'licensing-service', 'Internationaal rijbewijs afdrukken', 'Record international permit printing');

update role set permissions = permissions || array['licensing.foreign.record', 'licensing.licence.exchange',
        'licensing.permit.issue', 'licensing.idp.issue', 'licensing.idp.print'],
    version = version + 1, updated_at = now(), updated_by = 'SYSTEM_MIGRATION'
where code = 'LICENSING_OFFICER';

update role set permissions = permissions || array['licensing.exchange-country.submit'],
    version = version + 1, updated_at = now(), updated_by = 'SYSTEM_MIGRATION'
where code = 'LICENCE_CLASS_MAKER';

update role set permissions = permissions || array['licensing.exchange-country.approve'],
    version = version + 1, updated_at = now(), updated_by = 'SYSTEM_MIGRATION'
where code = 'LICENCE_CLASS_CHECKER';
