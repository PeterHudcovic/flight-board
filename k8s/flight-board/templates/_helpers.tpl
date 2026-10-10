{{- define "flight-board.labels" -}}
app.kubernetes.io/part-of: flight-board
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version }}
{{- end -}}

{{- define "flight-board.tag" -}}
{{- required "image.tag (git SHA) is required" .Values.image.tag -}}
{{- end -}}

{{- define "flight-board.podSecurity" -}}
runAsNonRoot: true
seccompProfile:
  type: RuntimeDefault
{{- end -}}

{{- define "flight-board.containerSecurity" -}}
allowPrivilegeEscalation: false
readOnlyRootFilesystem: true
capabilities:
  drop: ["ALL"]
{{- end -}}
