{{/*
Expand the name of the chart.
*/}}
{{- define "tiering-kv.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Create a default fully qualified app name.
*/}}
{{- define "tiering-kv.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{/*
Create chart name and version as used by the chart label.
*/}}
{{- define "tiering-kv.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Common labels.
*/}}
{{- define "tiering-kv.labels" -}}
helm.sh/chart: {{ include "tiering-kv.chart" . }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: {{ include "tiering-kv.name" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
{{- end }}

{{/*
Component selector labels.
*/}}
{{- define "tiering-kv.selectorLabels" -}}
app.kubernetes.io/name: {{ include "tiering-kv.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
Console secret name.
*/}}
{{- define "tiering-kv.consoleSecretName" -}}
{{- if .Values.consoleServer.security.existingSecret }}
{{- .Values.consoleServer.security.existingSecret }}
{{- else }}
{{- printf "%s-console-secret" (include "tiering-kv.fullname" .) }}
{{- end }}
{{- end }}

{{/*
Effective login attempt store: automatically switches to db for HA multi-replica deployments.
*/}}
{{- define "tiering-kv.loginAttemptStore" -}}
{{- if .Values.consoleServer.security.loginAttemptStore }}
{{- .Values.consoleServer.security.loginAttemptStore }}
{{- else if ge (int .Values.consoleServer.replicaCount) 2 }}
{{- "db" }}
{{- else }}
{{- "memory" }}
{{- end }}
{{- end }}

{{/*
Default data-plane bootstrap instance address for console-server.
*/}}
{{- define "tiering-kv.bootstrapInstance" -}}
{{- if .Values.consoleServer.bootstrapInstance }}
{{- .Values.consoleServer.bootstrapInstance }}
{{- else if eq .Values.tieringKv.mode "cluster" }}
{{- printf "cluster-gateway@%s-gateway:%d" (include "tiering-kv.fullname" .) (int .Values.tieringKv.ports.resp) }}
{{- else }}
{{- printf "local-6379@%s-core:%d" (include "tiering-kv.fullname" .) (int .Values.tieringKv.ports.resp) }}
{{- end }}
{{- end }}

{{/*
Effective console MySQL JDBC URL with configurable sslMode.
*/}}
{{- define "tiering-kv.consoleDbUrl" -}}
{{- $url := .Values.consoleServer.database.url -}}
{{- if or (contains "sslMode=" $url) (contains "useSSL=" $url) -}}
{{- $url -}}
{{- else -}}
{{- $sep := "?" -}}
{{- if contains "?" $url -}}{{- $sep = "&" -}}{{- end -}}
{{- printf "%s%ssslMode=%s" $url $sep (default "PREFERRED" .Values.consoleServer.database.sslMode) -}}
{{- end -}}
{{- end }}
