{{/*
Fixed to the chart name (not release-name-prefixed) so other services in the same
umbrella release can reach it at a predictable in-cluster DNS name (e.g. "product-service").
This assumes one release per namespace/environment, which is this project's deployment model
(see deployable.md) -- a second concurrent release in the same namespace would collide.
*/}}
{{- define "product-service.fullname" -}}
{{- .Chart.Name -}}
{{- end -}}

{{- define "product-service.labels" -}}
app.kubernetes.io/name: {{ .Chart.Name }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}

{{- define "product-service.selectorLabels" -}}
app.kubernetes.io/name: {{ .Chart.Name }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "product-service.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- default (include "product-service.fullname" .) .Values.serviceAccount.name -}}
{{- else -}}
{{- default "default" .Values.serviceAccount.name -}}
{{- end -}}
{{- end -}}
