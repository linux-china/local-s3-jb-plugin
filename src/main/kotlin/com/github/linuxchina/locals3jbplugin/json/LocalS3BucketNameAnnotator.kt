package com.github.linuxchina.locals3jbplugin.json

import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiElement
import com.robothy.s3.rest.service.BucketNameValidator

/**
 * Reports the bucket names of `.locals3.json` that Local S3 rejects, with its message, as they are typed.
 */
class LocalS3BucketNameAnnotator : Annotator, DumbAware {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        val nameElement = when {
            element is JsonStringLiteral && LocalS3ConfigPsi.isBucketsItem(element) -> element
            element is JsonProperty && LocalS3ConfigPsi.isSeedBucket(element) -> element.nameElement
            else -> return
        }
        // Like the configuration, which trims them and ignores the empty names of "buckets".
        val bucket = (nameElement as? JsonStringLiteral)?.value?.trim() ?: return
        if (bucket.isEmpty() && element is JsonStringLiteral) {
            return
        }
        val problem = try {
            BucketNameValidator().validate(bucket)
            return
        } catch (e: RuntimeException) {
            e.message ?: "Invalid bucket name"
        }
        holder.newAnnotation(HighlightSeverity.ERROR, problem).range(nameElement).create()
    }
}
