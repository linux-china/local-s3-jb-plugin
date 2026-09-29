package com.github.linuxchina.locals3jbplugin.json

import com.github.linuxchina.locals3jbplugin.LocalS3Icons
import com.github.linuxchina.locals3jbplugin.project.LocalS3ProjectConfigIconProvider
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class LocalS3ProjectConfigEditingTest : BasePlatformTestCase() {
    fun testReportsInvalidBucketNames() {
        myFixture.configureByText(
            ".locals3.json",
            """
            {
              "buckets": ["demo-bucket", "Bad_Bucket", ""],
              "seed": {
                "ab": { "logo.png": "logo.png" },
                "seed-bucket": { "logo.png": "logo.png" }
              }
            }
            """.trimIndent(),
        )
        val errors = myFixture.doHighlighting(HighlightSeverity.ERROR).map { it.text }
        assertSameElements(errors, "\"Bad_Bucket\"", "\"ab\"")
    }

    fun testIgnoresOtherJsonFiles() {
        myFixture.configureByText("other.json", """{ "buckets": ["Bad_Bucket"] }""")
        assertEmpty(myFixture.doHighlighting(HighlightSeverity.ERROR))
    }

    fun testResolvesSeedPaths() {
        myFixture.addFileToProject("data/family.csv", "a,b")
        myFixture.configureByText(".locals3.json", """{ "seed": { "demo-bucket": { "family.csv": "data/fam<caret>ily.csv" } } }""")
        val target = myFixture.getReferenceAtCaretPositionWithAssertion().resolve()
        assertEquals("family.csv", (target as PsiFile).name)

        myFixture.configureByText(".locals3.json", """{ "seed": { "demo-bucket": { "data/": "da<caret>ta" } } }""")
        assertEquals("data", (myFixture.getReferenceAtCaretPositionWithAssertion().resolve() as PsiDirectory).name)
    }

    fun testCompletesSeedPaths() {
        myFixture.addFileToProject("data/family.csv", "a,b")
        myFixture.configureByText(".locals3.json", """{ "seed": { "demo-bucket": { "family.csv": "data/<caret>" } } }""")
        assertContainsElements(myFixture.completeBasic().orEmpty().map { it.lookupString }, "family.csv")
    }

    fun testCompletesProperties() {
        myFixture.configureByText(".locals3.json", """{ "<caret>" }""")
        assertContainsElements(myFixture.completeBasic().orEmpty().map { it.lookupString }, "buckets", "seed")
    }

    fun testIcon() {
        val file = myFixture.configureByText(".locals3.json", "{}").virtualFile
        assertSame(LocalS3Icons.Bucket, LocalS3ProjectConfigIconProvider().getIcon(file, 0, project))
    }
}
