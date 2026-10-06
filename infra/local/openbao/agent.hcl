# OpenBao agent, local development (SEC-004, ADR-039). Fetches each service's short-lived database login from the
# database secrets engine and writes it as two files per service (username, password); it renews the lease and
# writes a new login before the old one expires. Each service mounts only its own sub-directory, read-only, at
# /run/secrets/spring/datasource (Spring Boot configtree, ADR-038), and DatabaseCredentialRotation applies changes.
# One shared agent is a local simplification; in Kubernetes each pod gets its own agent or the external-secrets
# operator, with a per-service identity.

pid_file = "/tmp/bao-agent.pid"

vault {
  address = "http://openbao:8200"
}

auto_auth {
  method "token_file" {
    config = {
      token_file_path = "/run/secrets/bao_agent_token"
    }
  }
}

template_config {
  exit_on_retry_failure = true
}

template {
  contents    = "{{ with secret \"database/creds/iam-service\" }}{{ .Data.username }}{{ end }}"
  destination = "/run/db-credentials/iam-service/username"
  perms       = "0444"
}
template {
  contents    = "{{ with secret \"database/creds/iam-service\" }}{{ .Data.password }}{{ end }}"
  destination = "/run/db-credentials/iam-service/password"
  perms       = "0444"
}

template {
  contents    = "{{ with secret \"database/creds/reference-data-service\" }}{{ .Data.username }}{{ end }}"
  destination = "/run/db-credentials/reference-data-service/username"
  perms       = "0444"
}
template {
  contents    = "{{ with secret \"database/creds/reference-data-service\" }}{{ .Data.password }}{{ end }}"
  destination = "/run/db-credentials/reference-data-service/password"
  perms       = "0444"
}

template {
  contents    = "{{ with secret \"database/creds/transaction-service\" }}{{ .Data.username }}{{ end }}"
  destination = "/run/db-credentials/transaction-service/username"
  perms       = "0444"
}
template {
  contents    = "{{ with secret \"database/creds/transaction-service\" }}{{ .Data.password }}{{ end }}"
  destination = "/run/db-credentials/transaction-service/password"
  perms       = "0444"
}

template {
  contents    = "{{ with secret \"database/creds/hold-eligibility-service\" }}{{ .Data.username }}{{ end }}"
  destination = "/run/db-credentials/hold-eligibility-service/username"
  perms       = "0444"
}
template {
  contents    = "{{ with secret \"database/creds/hold-eligibility-service\" }}{{ .Data.password }}{{ end }}"
  destination = "/run/db-credentials/hold-eligibility-service/password"
  perms       = "0444"
}

template {
  contents    = "{{ with secret \"database/creds/traffic-file-service\" }}{{ .Data.username }}{{ end }}"
  destination = "/run/db-credentials/traffic-file-service/username"
  perms       = "0444"
}
template {
  contents    = "{{ with secret \"database/creds/traffic-file-service\" }}{{ .Data.password }}{{ end }}"
  destination = "/run/db-credentials/traffic-file-service/password"
  perms       = "0444"
}

template {
  contents    = "{{ with secret \"database/creds/fees-service\" }}{{ .Data.username }}{{ end }}"
  destination = "/run/db-credentials/fees-service/username"
  perms       = "0444"
}
template {
  contents    = "{{ with secret \"database/creds/fees-service\" }}{{ .Data.password }}{{ end }}"
  destination = "/run/db-credentials/fees-service/password"
  perms       = "0444"
}

template {
  contents    = "{{ with secret \"database/creds/rules-service\" }}{{ .Data.username }}{{ end }}"
  destination = "/run/db-credentials/rules-service/username"
  perms       = "0444"
}
template {
  contents    = "{{ with secret \"database/creds/rules-service\" }}{{ .Data.password }}{{ end }}"
  destination = "/run/db-credentials/rules-service/password"
  perms       = "0444"
}

template {
  contents    = "{{ with secret \"database/creds/workflow-service\" }}{{ .Data.username }}{{ end }}"
  destination = "/run/db-credentials/workflow-service/username"
  perms       = "0444"
}
template {
  contents    = "{{ with secret \"database/creds/workflow-service\" }}{{ .Data.password }}{{ end }}"
  destination = "/run/db-credentials/workflow-service/password"
  perms       = "0444"
}

template {
  contents    = "{{ with secret \"database/creds/notification-service\" }}{{ .Data.username }}{{ end }}"
  destination = "/run/db-credentials/notification-service/username"
  perms       = "0444"
}
template {
  contents    = "{{ with secret \"database/creds/notification-service\" }}{{ .Data.password }}{{ end }}"
  destination = "/run/db-credentials/notification-service/password"
  perms       = "0444"
}

template {
  contents    = "{{ with secret \"database/creds/document-service\" }}{{ .Data.username }}{{ end }}"
  destination = "/run/db-credentials/document-service/username"
  perms       = "0444"
}
template {
  contents    = "{{ with secret \"database/creds/document-service\" }}{{ .Data.password }}{{ end }}"
  destination = "/run/db-credentials/document-service/password"
  perms       = "0444"
}

template {
  contents    = "{{ with secret \"database/creds/appointment-service\" }}{{ .Data.username }}{{ end }}"
  destination = "/run/db-credentials/appointment-service/username"
  perms       = "0444"
}
template {
  contents    = "{{ with secret \"database/creds/appointment-service\" }}{{ .Data.password }}{{ end }}"
  destination = "/run/db-credentials/appointment-service/password"
  perms       = "0444"
}

template {
  contents    = "{{ with secret \"database/creds/audit-service\" }}{{ .Data.username }}{{ end }}"
  destination = "/run/db-credentials/audit-service/username"
  perms       = "0444"
}
template {
  contents    = "{{ with secret \"database/creds/audit-service\" }}{{ .Data.password }}{{ end }}"
  destination = "/run/db-credentials/audit-service/password"
  perms       = "0444"
}
