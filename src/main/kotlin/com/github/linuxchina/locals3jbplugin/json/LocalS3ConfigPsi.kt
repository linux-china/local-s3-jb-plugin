package com.github.linuxchina.locals3jbplugin.json

import com.github.linuxchina.locals3jbplugin.project.LocalS3ProjectConfig
import com.intellij.json.psi.JsonArray
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.psi.PsiElement

/**
 * Locates the parts of a [LocalS3ProjectConfig.FILE_NAME] in its PSI.
 */
internal object LocalS3ConfigPsi {
    /**
     * @return whether [literal] is a bucket name of `"buckets"`.
     */
    fun isBucketsItem(literal: JsonStringLiteral): Boolean {
        val array = literal.parent as? JsonArray ?: return false
        return isRootProperty(array.parent, "buckets")
    }

    /**
     * @return whether [property] names a bucket in `"seed"`.
     */
    fun isSeedBucket(property: JsonProperty): Boolean = isRootProperty(property.parent?.parent, "seed")

    /**
     * @return whether [literal] is a local path of `"seed"`, the value of an object path in a bucket.
     */
    fun isSeedLocalPath(literal: JsonStringLiteral): Boolean {
        val objectPath = literal.parent as? JsonProperty ?: return false
        if (objectPath.value !== literal) {
            return false
        }
        val bucket = objectPath.parent?.parent as? JsonProperty ?: return false
        return isSeedBucket(bucket)
    }

    private fun isRootProperty(element: PsiElement?, name: String): Boolean {
        val property = element as? JsonProperty ?: return false
        if (property.name != name) {
            return false
        }
        val root = property.parent as? JsonObject ?: return false
        val file = root.parent as? JsonFile ?: return false
        return file.name == LocalS3ProjectConfig.FILE_NAME
    }
}
