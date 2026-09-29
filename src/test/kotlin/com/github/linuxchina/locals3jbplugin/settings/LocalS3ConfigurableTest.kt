package com.github.linuxchina.locals3jbplugin.settings

import junit.framework.TestCase

class LocalS3ConfigurableTest : TestCase() {
    fun testAcceptsLocalhostAndIpAddressesOnly() {
        listOf("localhost", "LocalHost", "127.0.0.1", "0.0.0.0", "::", "[::]", "::1", "[::1]", "192.168.1.10").forEach {
            assertTrue(it, LocalS3Configurable.isValidHost(it))
        }
        listOf("example.com", "256.0.0.1", "127.0.0", "abc", "[127.0.0.1").forEach {
            assertFalse(it, LocalS3Configurable.isValidHost(it))
        }
    }

    fun testAcceptsIcebergWarehouseOfValidBucketOnly() {
        listOf("s3://warehouse/", "s3://warehouse", " s3://lake.house/tables/ ", "s3a://warehouse/").forEach {
            assertTrue(it, LocalS3Configurable.isValidIcebergWarehouse(it))
        }
        listOf("", "warehouse", "s3:///", "s3://Ware_House/", "file:///tmp/warehouse").forEach {
            assertFalse(it, LocalS3Configurable.isValidIcebergWarehouse(it))
        }
    }

    fun testReportsInvalidBucketNames() {
        assertEquals(emptyList<String>(), LocalS3Configurable.invalidBucketNames(" demo-bucket, my.bucket.1, abc, ,"))
        assertEquals(
            listOf("Demo", "my_bucket", "ab", "-abc", "abc.", "a..b", "192.168.1.1", "a".repeat(64)),
            LocalS3Configurable.invalidBucketNames(
                "Demo, my_bucket, ab, -abc, abc., a..b, 192.168.1.1, ${"a".repeat(64)}, Demo"
            )
        )
    }
}
