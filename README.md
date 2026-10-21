# local-s3-jb-plugin

<!-- Plugin description -->
**JetBrains Local S3 Plugin** adds S3 capabilities to JetBrains IDEs,
so you can test against an S3 service quickly during development.

After a project is opened, an S3 server is started automatically.

Features:

- Light and fast: only Netty, Jackson 3, and H2 MVStore as dependencies
- S3-compatible API: `http://127.0.0.1:29090` in IntelliJ IDEA; each IDE product has its own default port, e.g. `29094`
  in PyCharm
- TLS/Credential support
- Iceberg REST Catalog: `http://127.0.0.1:29090/iceberg`, with `s3://warehouse/` as the default bucket. It can be
  disabled in the settings.
- Admin UI: `http://127.0.0.1:29090/_admin/ui`
- Web Hosting: `http://127.0.0.1:29090`
- Compatible with data tools: DuckDB, DataFusion, OpenDAL, Bun, s5cmd, boto3, S3 CLI, etc.
- Local S3 widget icon: ops management and convenient actions, e.g. copying `.env` for boto3, S3 CLI, s5cmd, Bun,
  OpenDAL, DataFusion, etc.
- Project buckets and seed data: `.locals3.json` at the project root
- One Local S3 per IDE product: different products run separate Local S3 instances with different ports and storage.

`.locals3.json` file example:

```json
{
  "buckets": [
    "demo-bucket"
  ],
  "seed": {
    "demo-bucket": {
      "dataset": "src/test/dataset",
      "family.csv": "family.csv"
    }
  }
}
```

<!-- Plugin description end -->

## One Local S3 per IDE product

Each IDE product starts its own Local S3, on its own default port and with its own data path, so that IntelliJ IDEA
and PyCharm, for example, don't see each other's buckets:

| IDE product   | Product code | Default port | Default data path                |
|---------------|--------------|--------------|----------------------------------|
| IntelliJ IDEA | `IU`, `IC`   | `29090`      | `~/.locals3/IU`, `~/.locals3/IC` |
| CLion         | `CL`         | `29091`      | `~/.locals3/CL`                  |
| DataGrip      | `DB`         | `29092`      | `~/.locals3/DB`                  |
| GoLand        | `GO`         | `29093`      | `~/.locals3/GO`                  |
| PyCharm       | `PY`, `PC`   | `29094`      | `~/.locals3/PY`, `~/.locals3/PC` |
| PhpStorm      | `PS`         | `29095`      | `~/.locals3/PS`                  |
| RubyMine      | `RM`         | `29096`      | `~/.locals3/RM`                  |
| RustRover     | `RR`         | `29097`      | `~/.locals3/RR`                  |
| Rider         | `RD`         | `29098`      | `~/.locals3/RD`                  |
| WebStorm      | `WS`         | `29099`      | `~/.locals3/WS`                  |
| Other IDEs    |              | `29100`      | `~/.locals3/<code>`              |

Several instances of the same product, e.g. IntelliJ IDEA 2026.2 and 2026.3 opened at the same time, share one Local S3:
the first one starts it, and the others reuse it, shown as **Shared** in the status bar. When the IDE that started it
is closed, another one takes over with the same port and data path, so the data is kept. Use the same access key and
secret key in all of them, so that their connection settings work with the shared Local S3.

To share one Local S3 between different products, set the same port and data path in
<kbd>Settings</kbd> > <kbd>Tools</kbd> > <kbd>Local S3</kbd> of each of them.

## Project buckets and seed data

Commit a `.locals3.json` file to the project root to declare the buckets and seed data the project needs:

```json
{
  "buckets": [
    "demo-bucket",
    "demo2-bucket"
  ],
  "seed": {
    "demo-bucket": {
      "dataset": "src/test/dataset",
      "family.csv": "family.csv"
    }
  }
}
```

**Attention**: `MAX_FILES_PER_DIRECTORY` is 256

When the project is opened, and each time Local S3 starts afterward, the buckets are created and the seed data imported:

- Only into the Local S3 started by this IDE, not into one shared with another IDE: a notification tells when they are
  skipped.
- A file is imported as the object path, or under it if the object path ends with `/`.
- The files of a directory, recursively and without hidden files, are imported under the object path, at most 256 per
  directory.
- Local paths are relative to the project root; absolute paths and `~/` are accepted.
- Existing objects are overwritten with the content of their seed files, so that changes to the seed files are imported.
- Only the `.locals3.json` in the project root (the project base path) is applied automatically: those in the
  subdirectories of a multi-module project, in other content roots or in attached projects are ignored. Use **Sync
  .locals3.json** to apply one of them, its local paths being then relative to its own directory.

`.locals3.json` is completed and validated with its JSON Schema, and invalid bucket names are reported as you type. The
seed local paths are completed and navigable. Right-click it in the editor or the Project view and choose **Sync
.locals3.json** to apply it again.

## Installation

- Using the IDE built-in plugin system:

  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>Marketplace</kbd> > <kbd>Search for "Local S3"</kbd> >
  <kbd>Install</kbd>

- Using JetBrains Marketplace:

  Go to [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID) and install it by clicking
  the <kbd>Install to ...</kbd> button in case your IDE is running.

  You can also download the [latest release](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID/versions) from
  JetBrains Marketplace and install it manually using
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>

- Manually:

  Download the [latest release](https://github.com/linux-china/local-s3-jb-plugin/releases/latest) and install it
  manually using
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>

## References

* [Local S3](https://github.com/linux-china/local-s3): An Amazon S3 service implementation based on Netty: S3, IceBerge
  REST Catalog, Admin UI etc.