# Build Local S3 plugin
build-plugin:
  ./gradlew patchPluginXml buildPlugin

# Rebuild Local S3 plugin
rebuild-plugin: clean
  ./gradlew patchPluginXml buildPlugin

# verify Local S3 plugin
verify: build-plugin
   ~/.jbang/bin/jbang org.jetbrains.intellij.plugins:verifier-cli:1.410:all check-plugin "build/distributions/local-s3-jb-plugin-0.1.0.zip" "$HOME/Applications/IntelliJ IDEA 2026.2.3.app/Contents"

# clean
clean:
  rm -rf build/distributions
  rm -rf build/resources
  rm -rf build/classes
  rm -rf build/instrumented
  rm -rf build/tmp