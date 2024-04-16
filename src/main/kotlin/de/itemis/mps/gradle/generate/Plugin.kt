package de.itemis.mps.gradle.generate

import de.itemis.mps.gradle.*
import de.itemis.mps.gradle.launcher.MpsBackendBuilder
import de.itemis.mps.gradle.launcher.MpsBackendLauncher
import org.apache.tools.ant.taskdefs.Java
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.JavaExec
import org.gradle.jvm.toolchain.JavaLauncher
import org.gradle.kotlin.dsl.newInstance
import org.gradle.process.CommandLineArgumentProvider
import java.io.File

open class GeneratePluginExtensions(objectFactory: ObjectFactory): BasePluginExtensions(objectFactory){
    var models: List<String> = emptyList()
    var modules: List<String> = emptyList()
    var excludeModels: List<String> = emptyList()
    var excludeModules: List<String> = emptyList()
    var parallelGenerationThreads: Int = 0
}

open class GenerateMpsProjectPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        project.run {
            val extensionName = "generate"
            val extension = extensions.create(extensionName, GeneratePluginExtensions::class.java)
            val generate = tasks.register("generate", JavaExec::class.java)
            val fake = tasks.register("fakeBuildNumber", FakeBuildNumberTask::class.java)

            afterEvaluate {
                val mpsVersion = extension.getMPSVersion(extensionName)

                val genConfig = extension.backendConfig ?: createDetachedBackendConfig(project)

                if(mpsVersion.substring(0..3).toInt() < 2020) {
                    throw GradleException(MPS_SUPPORT_MSG)
                }

                val mpsLocation = extension.mpsLocation ?: File(project.buildDir, "mps")
                val resolveMps = if (extension.mpsConfig != null) {
                    tasks.register("resolveMpsForGeneration", Copy::class.java) {
                        from({ extension.mpsConfig!!.resolve().map(::zipTree) })
                        into(mpsLocation)
                    }
                } else if (extension.mpsLocation != null) {
                    tasks.register("resolveMpsForGeneration")
                } else {
                    throw GradleException(ErrorMessages.mustSetConfigOrLocation(extensionName))
                }

                /*
                * The problem here is is that for some reason the ApplicationInfo isn't initialised properly.
                * That causes PluginManagerCore.BUILD_NUMBER to be null.
                * In this case the PluginManagerCore resorts to BuildNumber.currentVersion() which finally
                * calls into BuildNumber.fromFile().
                *
                * This behaviour allows us to place a build.txt in the root of the home path (see PathManager.getHomePath()).
                * The file is then used to load the build number.
                *
                * TODO: Since MPS 2018.2 a newer version of the platform allows to get a similar behaviour via setting idea.plugins.compatible.build property.
                *
                */
                fake.configure {
                    mpsDir = mpsLocation
                    dependsOn(resolveMps)
                }

                generate.configure {
                    val backendBuilder: MpsBackendBuilder = project.objects.newInstance(MpsBackendBuilder::class)
                    backendBuilder.withMpsHome(mpsLocation).withMpsVersion(mpsVersion).configure(this)

                    dependsOn(fake)

                    argumentProviders.add(argsFromBaseExtension(extension))
                    argumentProviders.add(CommandLineArgumentProvider {
                        mutableListOf<String>().apply {
                            addAll(extension.models.map { "--model=$it" })
                            addAll(extension.modules.map { "--module=$it" })
                            addAll(extension.excludeModels.map { "--exclude-model=$it" })
                            addAll(extension.excludeModules.map { "--exclude-module=$it" })
                            add("--parallel-generation-threads=${extension.parallelGenerationThreads}")
                        }
                    })

                    if (extension.javaExec != null) {
                        javaLauncher.set(null as JavaLauncher?)
                        executable(extension.javaExec!!)
                    } else {
                        validateDefaultJvm()
                    }

                    group = "build"
                    description = "Generates models in the project"
                    classpath(fileTree(File(mpsLocation, "/lib")).include("**/*.jar"))
                    // add only minimal number of plugins jars that are required by the generate code
                    // (to avoid conflicts with plugin classloader if custom configured plugins are loaded)
                    // git4idea: has to be on classpath as bundled plugin to be loaded (since 2019.3)
                    classpath(fileTree(File(mpsLocation, "/plugins")).include("git4idea/**/*.jar"))
                    classpath(genConfig)
                    debug = extension.debug
                    mainClass.set("de.itemis.mps.gradle.generate.MainKt")

                    if (extension.maxHeap != null) {
                        maxHeapSize = extension.maxHeap!!
                    }
                }
            }
        }
    }

    private fun createDetachedBackendConfig(project: Project): Configuration {
        val dep = project.dependencies.create("de.itemis.mps.build-backends:execute-generators:${MPS_BUILD_BACKENDS_VERSION}")
        val genConfig = project.configurations.detachedConfiguration(dep)
        return genConfig
    }
}
