# local-s3-jb-plugin


<!-- Plugin description -->
**JetBrains Local S3 Plugin** is a plugin to Add S3 capabilities in JetBrains IDEs,
and you can test S3 service quickly during development.

After project opened, and an S3 server will be started automatically.

Features:

- Light and fast: only Netty Jackson 3, and H2 MVStore dependencies 
- S3 compatible API: `http://127.0.0.1:29090`
- Credential support
- TLS support
- IceBerg REST Catalog: `http://127.0.0.1:29090/iceberg`
- Admin UI: `http://127.0.0.1:29090/_admin/ui`
- Web Hosting: `http://127.0.0.1:29090`
- Big Data Tools compatible

<!-- Plugin description end -->

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

