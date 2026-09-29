package com.github.linuxchina.locals3jbplugin.json

import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.io.FileUtil
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFileSystemItem
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.psi.impl.source.resolve.reference.impl.providers.FileReferenceSet
import com.intellij.util.ProcessingContext

/**
 * Completes and navigates to the local paths of `"seed"` in `.locals3.json`, relative to its directory.
 */
class LocalS3SeedPathReferenceContributor : PsiReferenceContributor() {
    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(JsonStringLiteral::class.java),
            LocalS3SeedPathReferenceProvider,
        )
    }
}

private object LocalS3SeedPathReferenceProvider : PsiReferenceProvider() {
    override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
        val literal = element as? JsonStringLiteral ?: return PsiReference.EMPTY_ARRAY
        if (!LocalS3ConfigPsi.isSeedLocalPath(literal)) {
            return PsiReference.EMPTY_ARRAY
        }
        // The text between the quotes, as the references are ranges of it: skip the paths with escapes.
        val text = literal.text
        if (text.length < 2 || text.contains('\\')) {
            return PsiReference.EMPTY_ARRAY
        }
        val path = text.substring(1, text.length - 1)
        // Absolute and home paths are outside the project.
        if (path.startsWith("~") || FileUtil.isAbsolute(path)) {
            return PsiReference.EMPTY_ARRAY
        }
        val directory = literal.containingFile.originalFile.containingDirectory ?: return PsiReference.EMPTY_ARRAY
        val references = object : FileReferenceSet(path, literal, 1, this, SystemInfo.isFileSystemCaseSensitive, false) {
            override fun computeDefaultContexts(): Collection<PsiFileSystemItem> = listOf(directory)

            // A missing seed file is reported when the configuration is applied.
            override fun isSoft(): Boolean = true
        }
        return references.allReferences.map { it as PsiReference }.toTypedArray()
    }
}
