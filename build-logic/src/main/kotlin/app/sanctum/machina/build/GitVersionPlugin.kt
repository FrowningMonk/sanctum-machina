package app.sanctum.machina.build

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.provider.Provider

/**
 * Holds the lazy git-derived version-name Provider published as the
 * `gitVersion` extension on the applying project.
 *
 * `versionName.orNull` returns null when git is unavailable or HEAD has no
 * describe output (shallow-clone, missing `.git/`, missing `git` on PATH);
 * callers must supply a hardcoded fallback in that case.
 */
open class GitVersionExtension(val versionName: Provider<String>)

class GitVersionPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val versionProvider = computeGitVersion(project)
        project.extensions.create("gitVersion", GitVersionExtension::class.java, versionProvider)
    }

    private fun computeGitVersion(project: Project): Provider<String> {
        val execOutput = project.providers.exec {
            commandLine("git", "describe", "--tags", "--always", "--dirty=-dev")
            isIgnoreExitValue = true
        }
        return project.providers.provider {
            try {
                val stdout = execOutput.standardOutput.asText.get()
                val exitCode = execOutput.result.get().exitValue
                gitVersionParse(stdout, exitCode)
            } catch (_: Exception) {
                null
            }
        }
    }
}
