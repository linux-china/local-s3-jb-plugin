package com.github.linuxchina.locals3jbplugin.json

import com.github.linuxchina.locals3jbplugin.project.LocalS3ProjectConfig
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.jetbrains.jsonSchema.extension.JsonSchemaFileProvider
import com.jetbrains.jsonSchema.extension.JsonSchemaProviderFactory
import com.jetbrains.jsonSchema.extension.SchemaType

/**
 * Provides the JSON Schema of [LocalS3ProjectConfig.FILE_NAME], for completion, documentation and validation.
 */
class LocalS3JsonSchemaProviderFactory : JsonSchemaProviderFactory {
    override fun getProviders(project: Project): List<JsonSchemaFileProvider> = listOf(LocalS3JsonSchemaFileProvider)
}

private object LocalS3JsonSchemaFileProvider : JsonSchemaFileProvider {
    override fun isAvailable(file: VirtualFile): Boolean = file.name == LocalS3ProjectConfig.FILE_NAME

    override fun getName(): String = LocalS3ProjectConfig.FILE_NAME

    override fun getSchemaFile(): VirtualFile? =
        JsonSchemaProviderFactory.getResourceFile(LocalS3JsonSchemaProviderFactory::class.java, "/schemas/locals3.schema.json")

    override fun getSchemaType(): SchemaType = SchemaType.embeddedSchema
}
