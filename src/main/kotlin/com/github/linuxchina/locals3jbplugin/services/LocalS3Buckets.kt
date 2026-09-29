package com.github.linuxchina.locals3jbplugin.services

import com.robothy.s3.core.exception.BucketNotExistException
import com.robothy.s3.core.service.manager.LocalS3Manager
import com.robothy.s3.rest.service.BucketNameValidator

/**
 * Creates [bucket] through the services of this manager unless it exists.
 *
 * The services accept any name, so it is validated like Local S3 does for the buckets it creates, rather than relying
 * on the caller, e.g. the settings page, to have done it.
 *
 * @return `true` if the bucket was created, `false` if it already existed.
 * @throws com.robothy.s3.core.exception.InvalidBucketNameException if the name isn't a valid bucket name.
 */
fun LocalS3Manager.createBucketIfAbsent(bucket: String): Boolean {
    val bucketService = bucketService()
    try {
        bucketService.getBucket(bucket)
        return false
    } catch (_: BucketNotExistException) {
        BucketNameValidator().validate(bucket)
        bucketService.createBucket(bucket)
        return true
    }
}
