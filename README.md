# local-s3-jb-plugin


<!-- Plugin description -->
**JetBrains Local S3 Plugin** is a plugin to Add S3 capabilities in JetBrains IDEs,
and you can test S3 service quickly during development.

After project opened, and an S3 server will be started automatically.

Features:

- Light and fast: only Netty, Jackson 3, and H2 MVStore dependencies 
- S3 compatible API: `http://127.0.0.1:29090`
- Credential support
- TLS support
- Iceberg REST Catalog: `http://127.0.0.1:29090/iceberg`
- Admin UI: `http://127.0.0.1:29090/_admin/ui`
- Web Hosting: `http://127.0.0.1:29090`
- Big Data Tools compatible
- Project buckets and seed data: `.locals3.json` at the project root

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

## Project buckets and seed data

Commit a `.locals3.json` file to the project root to declare the buckets and seed data the project needs:

```json
{
  "buckets": ["demo-bucket", "demo2-bucket"],
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

- Only into the Local S3 started by this IDE, not into one shared with another IDE: a notification tells when they are skipped.
- A file is imported as the object path, or under it if the object path ends with `/`.
- The files of a directory, recursively and without hidden files, are imported under the object path, at most 256 per directory.
- Local paths are relative to the project root; absolute paths and `~/` are accepted.
- Existing objects are overwritten with the content of their seed files, so that changes to the seed files are imported.

## Installation

- Using the IDE built-in plugin system:

  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>Marketplace</kbd> > <kbd>Search for "Local S3"</kbd> >
  <kbd>Install</kbd>

- Using JetBrains Marketplace:

  Go to [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID) and install it by clicking the <kbd>Install to ...</kbd> button in case your IDE is running.

  You can also download the [latest release](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID/versions) from JetBrains Marketplace and install it manually using
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>

- Manually:

  Download the [latest release](https://github.com/linux-china/local-s3-jb-plugin/releases/latest) and install it manually using
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>

  
## References

