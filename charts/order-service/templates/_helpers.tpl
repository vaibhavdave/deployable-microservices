{{/*
Fixed to the chart name -- see product-service/templates/_helpers.tpl for why.
*/}}
{{- define "order-service.fullname" -}}
{{- .Chart.Name -}}
{{- end -}}

{{- define "order-service.labels" -}}
app.kubernetes.io/name: {{ .Chart.Name }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}

{{- define "order-service.selectorLabels" -}}
app.kubernetes.io/name: {{ .Chart.Name }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "order-service.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- default (include "order-service.fullname" .) .Values.serviceAccount.name -}}
{{- else -}}
{{- default "default" .Values.serviceAccount.name -}}
{{- end -}}
{{- end -}}
