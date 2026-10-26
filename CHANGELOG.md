<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# local-s3-jb-plugin Changelog

## [Unreleased]

## 0.1.1 - 2026-10-26

### Fixed

- Fix some compatible bugs with Iceberg REST Catalog
- Fix some S3 Vectors/Tables bugs

## 0.1.0 - 2026-10-21

### Added

- Init version: plugin settings, Big Data tools compatible
- Light and fast: only Netty Jackson 3, and H2 MVStore dependencies
- S3 compatible API: http://127.0.0.1:39090
- Credential support
- TLS support
- Iceberg REST Catalog
- Admin UI: http://127.0.0.1:39090/_admin/ui
- Web Hosting: http://127.0.0.1:39090
- Project buckets and seed data: `.locals3.json` at the project root
- Copy Connection As...: `.env`, DuckDB S3 secret, Spring Boot `application.properties` (Spring Cloud AWS) and AWS CLI profile
- Project buckets and seed data declared in `.locals3.json` at the project root
- Iceberg REST catalog settings: enable/disable and warehouse location
- Copy Connection As...: PyIceberg `.pyiceberg.yaml`, Spark Iceberg catalog properties and DuckDB Iceberg `ATTACH`
- `.locals3.json` editing: JSON Schema completion and validation, invalid bucket name errors, completion and navigation
  of seed local paths, and a file icon
- Sync `.locals3.json` from the Project view popup menu

