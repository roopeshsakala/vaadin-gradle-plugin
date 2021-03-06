/**
 *    Copyright 2000-2020 Vaadin Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.vaadin.gradle

import com.vaadin.flow.server.Constants
import com.vaadin.flow.server.frontend.FrontendUtils
import com.vaadin.flow.server.frontend.NodeTasks
import elemental.json.Json
import elemental.json.JsonObject
import org.gradle.api.DefaultTask
import org.gradle.api.Task
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.bundling.War
import java.io.File
import java.nio.file.Files

/**
 * This task checks that node and npm tools are installed, copies frontend
 * resources available inside `.jar` dependencies to `node_modules`, and creates
 * or updates `package.json` and `webpack.config.json` files.
 */
open class VaadinPrepareFrontendTask : DefaultTask() {

    init {
        group = "Vaadin"
        description = "checks that node and npm tools are installed, copies frontend resources available inside `.jar` dependencies to `node_modules`, and creates or updates `package.json` and `webpack.config.json` files."

        // Maven's task run in the LifecyclePhase.PROCESS_RESOURCES phase

        project.tasks.getByName("processResources").mustRunAfter("vaadinPrepareFrontend")

        // if the vaadinPrepareNode task is going to be invoked, it needs to run before this task,
        // in order to prepare the local copy of node.js
        mustRunAfter("vaadinPrepareNode")

        // make sure all dependent projects have finished building their jars, otherwise
        // the Vaadin classpath scanning will not work properly. See
        // https://github.com/vaadin/vaadin-gradle-plugin/issues/38
        // for more details.
        val dependentProjectTasks: List<Task> = project.configurations.getByName("runtimeClasspath")
                .allDependencies
                .withType(ProjectDependency::class.java)
                .toList()
                .map { it.dependencyProject }
                .mapNotNull { it.tasks.findByName("assemble") }
        dependsOn(*dependentProjectTasks.toTypedArray())
    }

    @TaskAction
    fun vaadinPrepareFrontend() {
        val extension: VaadinFlowPluginExtension = VaadinFlowPluginExtension.get(project)

        Files.createDirectories(extension.frontendDirectory.toPath())
        Files.createDirectories(extension.buildOutputDirectory.toPath())
        Files.createDirectories(extension.webpackOutputDirectory!!.toPath())

        propagateBuildInfo(extension)

        FrontendUtils.getNodeExecutable(extension.npmFolder.absolutePath)
        FrontendUtils.getNpmExecutable(extension.npmFolder.absolutePath)
        FrontendUtils.validateNodeAndNpmVersion(extension.npmFolder.absolutePath)

        // produce target/frontend/package.json
        Files.createDirectories(extension.generatedFolder.toPath())

        val builder: NodeTasks.Builder = NodeTasks.Builder(getClassFinder(project), extension.npmFolder,
                extension.generatedFolder, extension.frontendDirectory)
                .withWebpack(extension.webpackOutputDirectory!!, extension.webpackTemplate, extension.webpackGeneratedTemplate)
                .createMissingPackageJson(true)
                .enableImportsUpdate(false)
                .enablePackagesUpdate(false)
                .runNpmInstall(false)

        // If building a jar project copy jar artifact contents now as we might
        // not be able to read files from jar path.
        val isJarPackaging: Boolean = project.tasks.withType(War::class.java).isEmpty()
        if (isJarPackaging) {
            val jarFiles: Set<File> = project.configurations
                    .getByName("runtimeClasspath")
                    .resolve()
                    .filter { it.name.endsWith(".jar") }
                    .toSet()
            builder.copyResources(jarFiles)
        }

        builder.build().execute()
    }

    private fun propagateBuildInfo(extension: VaadinFlowPluginExtension) {
        val configFolder = File("${extension.buildOutputDirectory}/META-INF/VAADIN/config")
        Files.createDirectories(configFolder.toPath())
        val buildInfo: JsonObject = Json.createObject().apply {
            put(Constants.SERVLET_PARAMETER_COMPATIBILITY_MODE, false)
            put(Constants.SERVLET_PARAMETER_PRODUCTION_MODE, extension.productionMode)
            put(Constants.NPM_TOKEN, extension.npmFolder.absolutePath)
            put(Constants.GENERATED_TOKEN, extension.generatedFolder.absolutePath)
            put(Constants.FRONTEND_TOKEN, extension.frontendDirectory.absolutePath)
        }
        buildInfo.writeToFile(File(configFolder, "flow-build-info.json"))
    }
}
