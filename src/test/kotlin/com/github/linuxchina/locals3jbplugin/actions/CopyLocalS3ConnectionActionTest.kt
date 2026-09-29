package com.github.linuxchina.locals3jbplugin.actions

import com.github.linuxchina.locals3jbplugin.settings.LocalS3Settings
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class CopyLocalS3ConnectionActionTest : BasePlatformTestCase() {
    private fun settings() = LocalS3Settings().apply {
        host = "0.0.0.0"
        port = 29090
        accessKeyId = "admin"
        secretAccessKey = "it's"
        buckets = "demo-bucket"
    }

    private fun anonymousSettings() = settings().apply {
        accessKeyId = ""
        secretAccessKey = ""
    }

    fun testDotEnv() {
        assertEquals(
            """
            AWS_ENDPOINT_URL=http://127.0.0.1:29090
            AWS_ENDPOINT_URL_S3=http://127.0.0.1:29090
            AWS_REGION=us-east-1
            AWS_DEFAULT_REGION=us-east-1
            AWS_ACCESS_KEY_ID=admin
            AWS_SECRET_ACCESS_KEY=it's
            AWS_S3_BUCKET=demo-bucket
            
            """.trimIndent(),
            CopyLocalS3DotEnvAction.dotEnv(settings())
        )
    }

    fun testDuckDbSecretDisablesSslAndEscapesQuotes() {
        assertEquals(
            """
            CREATE OR REPLACE SECRET locals3 (
                TYPE s3,
                KEY_ID 'admin',
                SECRET 'it''s',
                REGION 'us-east-1',
                ENDPOINT '127.0.0.1:29090',
                URL_STYLE 'path',
                USE_SSL false
            );
            
            """.trimIndent(),
            CopyLocalS3DuckDbSecretAction.duckDbSecret(settings().apply { tlsEnabled = true })
        )
    }

    fun testSpringBootProperties() {
        assertEquals(
            """
            spring.cloud.aws.s3.endpoint=http://127.0.0.1:29090
            spring.cloud.aws.s3.path-style-access-enabled=true
            spring.cloud.aws.region.static=us-east-1
            spring.cloud.aws.credentials.access-key=admin
            spring.cloud.aws.credentials.secret-key=it's
            
            """.trimIndent(),
            CopyLocalS3SpringBootPropertiesAction.springBootProperties(settings())
        )
    }

    fun testAwsCliProfile() {
        assertEquals(
            """
            [profile locals3]
            region = us-east-1
            endpoint_url = http://127.0.0.1:29090
            aws_access_key_id = admin
            aws_secret_access_key = it's
            s3 =
                addressing_style = path
            
            """.trimIndent(),
            CopyLocalS3AwsCliProfileAction.awsCliProfile(settings())
        )
    }

    fun testOmitsCredentialsForAnonymousAccess() {
        val settings = anonymousSettings()
        assertFalse(CopyLocalS3DotEnvAction.dotEnv(settings).contains("AWS_ACCESS_KEY_ID"))
        assertFalse(CopyLocalS3DuckDbSecretAction.duckDbSecret(settings).contains("KEY_ID"))
        assertFalse(CopyLocalS3SpringBootPropertiesAction.springBootProperties(settings).contains("credentials"))
        assertFalse(CopyLocalS3AwsCliProfileAction.awsCliProfile(settings).contains("aws_access_key_id"))
    }
}
