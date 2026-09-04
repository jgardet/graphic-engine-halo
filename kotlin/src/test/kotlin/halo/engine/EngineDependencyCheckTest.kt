package halo.engine

import kotlin.test.Test
import kotlin.test.assertTrue
import java.io.File

/**
 * S0-03: Architectural dependency checks for halo-engine (kotlin module).
 *
 * Verifies that the Halo engine does not import any higher-level
 * agent, application, or product types. The engine must remain
 * unaware of dsh, Gemma, agent tools, workflows, transcripts,
 * product templates, and agent-senses contracts.
 */
class EngineDependencyCheckTest {

    private val forbiddenImports = listOf(
        "com.nyooran.agent.senses",
        "com.nyooran.dshandroid",
        "gemma",
        "dsh",
        "ktor",
        "compose",
    )

    private val engineSourceDir = File("src/main/kotlin")

    @Test
    fun engineHasNoForbiddenImports() {
        val violations = mutableListOf<String>()
        walkKotlinFiles(engineSourceDir).forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("import ")) {
                    val importPath = trimmed.removePrefix("import ").substringBefore(" as ").trim()
                    forbiddenImports.forEach { forbidden ->
                        if (importPath.startsWith(forbidden)) {
                            violations += "${file.relativeTo(engineSourceDir)}:${index + 1}: $importPath"
                        }
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(),
            "Forbidden imports found in halo-engine:\n${violations.joinToString("\n")}")
    }

    private fun walkKotlinFiles(dir: File): List<File> {
        if (!dir.exists()) return emptyList()
        return dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }
}
