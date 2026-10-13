-- ai-service schema, part 1: AI models and deployments (AI-001, AI-009, AI-010, AI-011)

create table ai_model (
    id                  uuid          primary key,
    name                varchar(100)  not null,
    version             varchar(40)   not null,
    description         varchar(500),
    type                varchar(30)   not null,
    status              varchar(20)   not null,
    owner               varchar(100)  not null,
    framework           varchar(50),
    artifact_uri        varchar(500),
    hyperparameters     jsonb         not null default '{}',
    metrics             jsonb         not null default '{}',
    accuracy_targets    jsonb         not null default '{}',
    created_at          timestamptz   not null,
    updated_at          timestamptz   not null,
    created_by          varchar(100)  not null,
    constraint uq_ai_model_name_version unique (name, version),
    constraint ck_ai_model_type check (type in ('CLASSIFICATION', 'REGRESSION', 'ANOMALY_DETECTION', 'NLP', 'COMPUTER_VISION', 'ENSEMBLE', 'RULE_BASED')),
    constraint ck_ai_model_status check (status in ('DRAFT', 'TRAINING', 'VALIDATING', 'ACTIVE', 'DEPRECATED', 'ARCHIVED', 'BLOCKED'))
);

create index ix_ai_model_status on ai_model (status);
create index ix_ai_model_type on ai_model (type);
create index ix_ai_model_owner on ai_model (owner);

create table ai_model_deployment (
    id                  uuid          primary key,
    model_id            uuid          not null references ai_model (id),
    environment         varchar(40)   not null,
    version             varchar(40)   not null,
    status              varchar(20)   not null,
    deployed_by         varchar(100)  not null,
    deployed_at         timestamptz   not null,
    rolled_back_at      timestamptz,
    rollback_reason     varchar(500),
    constraint uq_ai_deployment_model_env unique (model_id, environment, version),
    constraint ck_ai_deployment_status check (status in ('PENDING', 'DEPLOYING', 'ACTIVE', 'ROLLING_BACK', 'ROLLED_BACK', 'FAILED'))
);

create index ix_ai_deployment_model on ai_model_deployment (model_id);
create index ix_ai_deployment_env_status on ai_model_deployment (environment, status);

-- ai-service schema, part 2: Use cases (AI-002, AI-003, AI-012, AI-013, AI-014, AI-019)

create table ai_use_case (
    id                  uuid          primary key,
    code                varchar(40)   not null unique,
    name                varchar(100)  not null,
    description         varchar(500),
    requirement_refs    jsonb         not null default '[]',
    status              varchar(20)   not null,
    owner               varchar(100)  not null,
    model_id            uuid          references ai_model (id),
    human_review        jsonb,
    kill_switch         jsonb,
    data_readiness      jsonb,
    created_at          timestamptz   not null,
    updated_at          timestamptz   not null,
    constraint ck_ai_use_case_status check (status in ('PROPOSED', 'APPROVED', 'IN_DEVELOPMENT', 'IN_TESTING', 'DEPLOYED', 'SUSPENDED', 'RETIRED'))
);

create index ix_ai_use_case_status on ai_use_case (status);
create index ix_ai_use_case_model on ai_use_case (model_id);
create index ix_ai_use_case_owner on ai_use_case (owner);

-- ai-service schema, part 3: Inference requests and responses (AI-005)

create table ai_inference_request (
    id                  uuid          primary key,
    use_case_id         uuid          not null references ai_use_case (id),
    correlation_id      varchar(100),
    input               jsonb         not null,
    context             jsonb         not null default '{}',
    requested_at        timestamptz   not null,
    requested_by        varchar(100)  not null
);

create index ix_ai_inference_req_usecase on ai_inference_request (use_case_id, requested_at desc);
create index ix_ai_inference_req_correlation on ai_inference_request (correlation_id);

create table ai_inference_response (
    id                  uuid          primary key,
    use_case_id         uuid          not null references ai_use_case (id),
    model_id            uuid          references ai_model (id),
    model_version       varchar(40),
    output              jsonb         not null,
    confidence          double precision not null,
    metadata            jsonb         not null default '{}',
    status              varchar(30)   not null,
    completed_at        timestamptz   not null,
    latency_ms          bigint        not null,
    constraint ck_ai_inference_status check (status in ('SUCCESS', 'LOW_CONFIDENCE', 'HUMAN_REVIEW_REQUIRED', 'KILL_SWITCH_ACTIVE', 'MODEL_ERROR', 'VALIDATION_FAILED'))
);

create index ix_ai_inference_resp_usecase on ai_inference_response (use_case_id, completed_at desc);
create index ix_ai_inference_resp_model on ai_inference_response (model_id, completed_at desc);
create index ix_ai_inference_resp_status on ai_inference_response (status, completed_at desc);

-- ai-service schema, part 4: Human review cases (AI-012, AI-013)

create table ai_review_case (
    id                      uuid          primary key,
    inference_request_id    uuid          not null references ai_inference_request (id),
    use_case_id             uuid          not null references ai_use_case (id),
    status                  varchar(20)   not null,
    assigned_reviewer       varchar(100),
    inference_output        jsonb         not null,
    reviewer_decision       jsonb,
    reviewer_notes          varchar(2000),
    created_at              timestamptz   not null,
    reviewed_at             timestamptz,
    reviewed_by             varchar(100)
);

create index ix_ai_review_status on ai_review_case (status, created_at desc);
create index ix_ai_review_assignee on ai_review_case (assigned_reviewer, created_at desc);
create index ix_ai_review_usecase on ai_review_case (use_case_id, created_at desc);

-- ai-service schema, part 5: Drift monitoring (AI-006, AI-007)

create table ai_drift_metrics (
    id                      bigint generated always as identity primary key,
    model_id                uuid          not null references ai_model (id),
    window_start            timestamptz   not null,
    window_end              timestamptz   not null,
    psi                     double precision not null,
    feature_drift_score     double precision not null,
    prediction_drift_score  double precision not null,
    concept_drift_score     double precision not null,
    feature_drift_details   jsonb         not null default '{}',
    status                  varchar(20)   not null,
    constraint ck_ai_drift_status check (status in ('STABLE', 'WARNING', 'CRITICAL', 'BLOCKED'))
);

create index ix_ai_drift_model_window on ai_drift_metrics (model_id, window_end desc);
create index ix_ai_drift_status on ai_drift_metrics (status);

-- ai-service schema, part 6: Bias testing (AI-015)

create table ai_bias_test (
    id                          bigint generated always as identity primary key,
    model_id                    uuid          not null references ai_model (id),
    protected_attribute         varchar(50)   not null,
    reference_group             varchar(100)  not null,
    comparison_group            varchar(100)  not null,
    demographic_parity_diff     double precision not null,
    equal_opportunity_diff      double precision not null,
    equalized_odds_diff         double precision not null,
    status                      varchar(20)   not null,
    tested_at                   timestamptz   not null,
    constraint ck_ai_bias_status check (status in ('PASS', 'WARNING', 'FAIL'))
);

create index ix_ai_bias_model on ai_bias_test (model_id, tested_at desc);
create index ix_ai_bias_attr_groups on ai_bias_test (model_id, protected_attribute, reference_group, comparison_group, tested_at desc);
create index ix_ai_bias_status on ai_bias_test (status);

-- ai-service schema, part 7: Adversarial testing (AI-016)

create table ai_adversarial_test (
    id                      bigint generated always as identity primary key,
    model_id                uuid          not null references ai_model (id),
    attack_type             varchar(50)   not null,
    robustness_score        double precision not null,
    samples_tested          integer       not null,
    samples_failed          integer       not null,
    status                  varchar(20)   not null,
    tested_at               timestamptz   not null,
    constraint ck_ai_adv_status check (status in ('ROBUST', 'VULNERABLE', 'CRITICAL'))
);

create index ix_ai_adv_model on ai_adversarial_test (model_id, tested_at desc);
create index ix_ai_adv_type_model on ai_adversarial_test (attack_type, model_id, tested_at desc);
create index ix_ai_adv_status on ai_adversarial_test (status);

-- ai-service schema, part 8: Explanations (AI-008)

create table ai_explanation (
    inference_request_id    uuid          primary key references ai_inference_request (id),
    method                  varchar(30)   not null,
    feature_importance      jsonb         not null,
    local_explanation       jsonb         not null,
    global_summary          varchar(2000),
    generated_at            timestamptz   not null,
    constraint ck_ai_explanation_method check (method in ('SHAP', 'LIME', 'PERMUTATION', 'INTEGRATED_GRADIENTS', 'RULE_BASED'))
);

create index ix_ai_explanation_generated on ai_explanation (generated_at desc);

-- ai-service schema, part 9: Feature store (for training/serving)

create table ai_feature_vector (
    entity_type             varchar(50)   not null,
    entity_id               varchar(100)  not null,
    features                jsonb         not null,
    valid_from              timestamptz   not null,
    primary key (entity_type, entity_id, valid_from)
);

create index ix_ai_feature_entity on ai_feature_vector (entity_type, entity_id, valid_from desc);