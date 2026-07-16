package com.pixelpy.editor

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class SessionRecoveryTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun autosaveSurvivesActivityRecreation() {
        val (_, file) = prepareProject(
            "Phase2Rotation",
            mapOf("main.py" to "print('before')"),
            "main.py",
        )

        val edited = "print('autosave after rotation')"
        composeRule.onNodeWithTag("editor-input").performTextReplacement(edited)
        composeRule.waitUntil(10_000) { file.readText() == edited }
        composeRule.activityRule.scenario.recreate()

        composeRule.onNodeWithTag("editor-input").assertTextEquals(edited)
    }

    @Test
    fun selectedFileAndCursorSurviveActivityRecreation() {
        prepareProject(
            "Phase2Selection",
            mapOf("first.py" to "first", "second.py" to "0123456789"),
            "first.py",
        )

        composeRule.onNodeWithTag("editor-file-second.py").performClick()
        composeRule.waitUntil(10_000) { currentFileName() == "second.py" }
        composeRule.onNodeWithTag("editor-input").performClick()
        composeRule.onNodeWithTag("editor-input").performTextInputSelection(TextRange(4))
        composeRule.activityRule.scenario.recreate()

        composeRule.onNodeWithTag("current-file-name").assertTextEquals("second.py")
        val selection = composeRule.onNodeWithTag("editor-input")
            .fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange]
        assertEquals(TextRange(4), selection)
    }

    @Test
    fun selectedProjectSurvivesActivityRecreation() {
        val root = File(context.filesDir, "projects").apply { mkdirs() }
        root.resolve("Phase2ProjectB").apply {
            deleteRecursively()
            mkdirs()
            resolve("main.py").writeText("print('B')")
        }
        prepareProject(
            "Phase2ProjectA",
            mapOf("main.py" to "print('A')"),
            "main.py",
        )

        composeRule.onNodeWithTag("nav-projects").performClick()
        composeRule.onNodeWithTag("project-Phase2ProjectB").performClick()
        composeRule.waitUntil(10_000) { currentFileName() == "main.py" && currentProjectName() == "PHASE2PROJECTB" }
        composeRule.activityRule.scenario.recreate()

        composeRule.onNodeWithTag("current-project-name").assertTextEquals("PHASE2PROJECTB")
    }

    @Test
    fun immediateFileSwitchFlushesOnlyOutgoingFile() {
        val (project, fileA) = prepareProject(
            "Phase2Switch",
            mapOf("a.py" to "A0", "b.py" to "B0"),
            "a.py",
        )
        val editedA = "A edited immediately before switch"

        composeRule.onNodeWithTag("editor-input").performTextReplacement(editedA)
        composeRule.onNodeWithTag("editor-file-b.py").performClick()
        composeRule.waitUntil(10_000) { currentFileName() == "b.py" }
        composeRule.waitUntil(10_000) { fileA.readText() == editedA }

        assertEquals(editedA, fileA.readText())
        assertEquals("B0", project.resolve("b.py").readText())
        composeRule.onNodeWithTag("editor-input").assertTextEquals("B0")
    }

    @Test
    fun stoppingBeforeDebounceDoesNotLoseContent() {
        val (_, file) = prepareProject(
            "Phase2QuickStop",
            mapOf("main.py" to "before"),
            "main.py",
        )
        val edited = "saved while activity stops"

        composeRule.onNodeWithTag("editor-input").performTextReplacement(edited)
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        composeRule.waitUntil(10_000) { file.readText() == edited }
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)

        composeRule.onNodeWithTag("editor-input").assertTextContains(edited)
    }

    @Test
    fun rapidFileSelectionEndsOnLastTappedFile() {
        prepareProject(
            "Phase2RapidFiles",
            mapOf("a.py" to "A", "b.py" to "B", "c.py" to "C"),
            "a.py",
        )

        composeRule.onNodeWithTag("editor-file-b.py").performScrollTo().performClick()
        composeRule.onNodeWithTag("editor-file-c.py").performScrollTo().performClick()

        composeRule.waitUntil(10_000) { currentFileName() == "c.py" }
        composeRule.onNodeWithTag("editor-input").assertTextEquals("C")
    }

    @Test
    fun rapidProjectSelectionEndsOnLastTappedProject() {
        val root = File(context.filesDir, "projects").apply { mkdirs() }
        listOf("Phase2RapidProjectB", "Phase2RapidProjectC").forEach { name ->
            root.resolve(name).apply {
                deleteRecursively()
                mkdirs()
                resolve("main.py").writeText(name)
            }
        }
        prepareProject(
            "Phase2RapidProjectA",
            mapOf("main.py" to "A"),
            "main.py",
        )

        composeRule.onNodeWithTag("nav-projects").performClick()
        composeRule.onNodeWithTag("project-Phase2RapidProjectB").performScrollTo().performClick()
        composeRule.onNodeWithTag("project-Phase2RapidProjectC").performScrollTo().performClick()

        composeRule.waitUntil(10_000) {
            currentProjectName() == "PHASE2RAPIDPROJECTC"
        }
    }

    @Test
    fun executionUsesFileSelectedByConcurrentNavigation() {
        prepareProject(
            "Phase2RunNavigation",
            mapOf(
                "a.py" to "print('RUN A')",
                "b.py" to "print('RUN B')",
            ),
            "a.py",
        )

        composeRule.onNodeWithTag("editor-file-b.py").performScrollTo().performClick()
        composeRule.onNodeWithTag("run-button").performClick()

        composeRule.waitUntil(30_000) {
            composeRule.onAllNodesWithTag("console-output").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("current-file-name").assertTextEquals("b.py")
        composeRule.onNodeWithTag("console-output").assertTextContains("RUN B")
    }

    private fun prepareProject(
        name: String,
        sources: Map<String, String>,
        selectedFile: String,
    ): Pair<File, File> {
        val root = File(context.filesDir, "projects").apply { mkdirs() }
        val project = root.resolve(name).apply {
            deleteRecursively()
            mkdirs()
        }
        sources.forEach { (fileName, content) -> project.resolve(fileName).writeText(content) }
        val selected = project.resolve(selectedFile)
        composeRule.onNodeWithTag("nav-editor").performClick()
        composeRule.onNodeWithTag("nav-projects").performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("project-$name").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("project-$name").performScrollTo().performClick()
        composeRule.waitUntil(10_000) { currentProjectName() == name.uppercase() }
        composeRule.onNodeWithTag("nav-editor").performClick()
        if (currentFileName() != selectedFile) {
            composeRule.onNodeWithTag("editor-file-$selectedFile").performClick()
        }
        composeRule.waitUntil(10_000) { currentFileName() == selectedFile }
        return project to selected
    }

    private fun currentFileName(): String? = runCatching {
        composeRule.onNodeWithTag("current-file-name")
            .fetchSemanticsNode().config[SemanticsProperties.Text]
            .joinToString("") { it.text }
    }.getOrNull()

    private fun currentProjectName(): String? = runCatching {
        composeRule.onNodeWithTag("current-project-name")
            .fetchSemanticsNode().config[SemanticsProperties.Text]
            .joinToString("") { it.text }
    }.getOrNull()
}
