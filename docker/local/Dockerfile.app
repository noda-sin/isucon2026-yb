FROM golang:1.26.5-bookworm AS build
WORKDIR /src
COPY webapp/go/go.mod webapp/go/go.sum ./
RUN go mod download
COPY webapp/go/ ./
RUN CGO_ENABLED=0 go build -trimpath -o /out/isureal ./cmd/isureal

FROM alpine:3.22.2
WORKDIR /app/go
COPY --from=build /out/isureal /app/go/isureal
USER 65532:65532
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=10s --start-period=30s --retries=5 CMD wget -q -O /dev/null http://127.0.0.1:8080/healthz || exit 1
ENTRYPOINT ["/app/go/isureal"]
