FROM golang:1.26.5-bookworm AS build
WORKDIR /src
COPY webapp/go/go.mod webapp/go/go.sum ./
RUN go mod download
COPY docker/local/tests/main.go ./main.go
RUN CGO_ENABLED=0 go build -trimpath -o /verify main.go
FROM debian:bookworm-slim
COPY --from=build /verify /usr/local/bin/verify
USER 65532:65532
ENTRYPOINT ["/usr/local/bin/verify"]
