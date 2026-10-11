-- Customer delay notice (CC-062: "notify the customer of delays"), loaded as data like the violation notices (V3).
-- workflow-service emits itms.workflow.item-overdue with the case's customerId when a step's SLA is exceeded; cases
-- without a customer are skipped by the consumer. The texts are drafts until the Authority approves them (Q-006).
-- Not mandatory: a delay notice is informational, so the customer's channel preferences apply (CC-073).
-- Placeholders use only the event's own fields (WorkflowService.snapshot): the reference and the service code.
insert into notification_template (code, version_no, category, mandatory, contents, published_at, published_by,
                                   published_by_username)
values
    ('CASE_DELAYED', 1, 'WORKFLOW', false, $json$
     {"SMS": {"bodyNl": "نعتذر عن التأخير: طلبك {{reference}} يستغرق وقتاً أطول من المعتاد. سنبلغك عند اكتماله.",
              "bodyEn": "We are sorry for the delay: your request {{reference}} is taking longer than usual. We will tell you when it is complete."},
      "INBOX": {"subjectNl": "تأخر في معالجة الطلب {{reference}}",
                "subjectEn": "Delay in processing request {{reference}}",
                "bodyNl": "نعتذر عن التأخير: طلبك {{reference}} يستغرق وقتاً أطول من المعتاد، وقد أُحيل إلى المشرف. سنبلغك عند اكتماله.",
                "bodyEn": "We are sorry for the delay: your request {{reference}} is taking longer than usual and has been referred to a supervisor. We will tell you when it is complete."}}
     $json$::jsonb, now(), 'system:flyway', 'V4__workflow_delay_notifications')
on conflict do nothing;

insert into event_subscription (event_type, template_code, recipient_field, reference_field, active, updated_at)
values
    ('itms.workflow.item-overdue', 'CASE_DELAYED', 'customerId', 'reference', true, now())
on conflict do nothing;
