<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# local-s3-jb-plugin Changelog

## [Unreleased]

### Added

- Copy Connection As...: `.env`, DuckDB S3 secret, Spring Boot `application.properties` (Spring Cloud AWS) and AWS CLI profile
- Project buckets and seed data declared in `.locals3.json` at the project root
- Iceberg REST catalog settings: enable/disable and warehouse location
- Copy Connection As...: PyIceberg `.pyiceberg.yaml`, Spark Iceberg catalog properties and DuckDB Iceberg `ATTACH`

### Fixed

- Iceberg REST catalog was not served, although it was advertised

## 0.1.0 - 2026-09-27

### Added

- Init version: plugin settings, Big Data tools compatible
- Light and fast: only Netty Jackson 3, and H2 MVStore dependencies
- S3 compatible API: http://127.0.0.1:29090
- Credential support
- TLS support
- Iceberg REST Catalog
- Admin UI: http://127.0.0.1:29090/_admin/ui
- Web Hosting: http://127.0.0.1:29090

