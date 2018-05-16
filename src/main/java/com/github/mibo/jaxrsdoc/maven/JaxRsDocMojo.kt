/*
 * Copyright 2018 Michael Bolz
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.mibo.jaxrsdoc.maven

import com.github.mibo.jaxrsdoc.JAXRSAnalyzer
import com.github.mibo.jaxrsdoc.LogProvider
import com.github.mibo.jaxrsdoc.backend.Backend
import com.github.mibo.jaxrsdoc.backend.StringBackend
import com.github.mibo.jaxrsdoc.backend.swagger.SwaggerOptions
import org.apache.maven.artifact.Artifact
import org.apache.maven.plugin.AbstractMojo
import org.apache.maven.plugin.MojoExecutionException
import org.apache.maven.plugins.annotations.Component
import org.apache.maven.plugins.annotations.LifecyclePhase
import org.apache.maven.plugins.annotations.Mojo
import org.apache.maven.plugins.annotations.Parameter
import org.apache.maven.project.MavenProject
import org.eclipse.aether.RepositorySystem
import org.eclipse.aether.RepositorySystemSession
import org.eclipse.aether.artifact.DefaultArtifact
import org.eclipse.aether.repository.RemoteRepository
import org.eclipse.aether.resolution.ArtifactRequest
import org.eclipse.aether.resolution.ArtifactResolutionException
import org.eclipse.aether.resolution.ArtifactResult

import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*
import java.util.stream.Collectors
import java.util.stream.Stream


/**
 * Maven goal which analyzes JAX-RS resources and generate according documentation (e.g. swagger, adoc, ...).
 *
 * @author Sebastian Daschner, mibo
 * @goal generate-doc
 * @phase process-test-classes
 * @requiresDependencyResolution compile
 */
@Mojo(name = "generate-doc", defaultPhase = LifecyclePhase.PROCESS_TEST_CLASSES)
class JaxRsDocMojo : AbstractMojo() {

  /**
   * The chosen backend(s) format. Defaults to plaintext.
   * Support multiple backends as comma separated list (e.g. `asciidoc,swagger`)
   */
  @Parameter( property="jaxrs-doc.backend", defaultValue = "swagger" )
  private val backend: String? = null

  /**
   * The domain where the project will be deployed.
   */
  @Parameter( property="jaxrs-doc.deployedDomain", defaultValue = "" )
  private val deployedDomain: String? = null

  /**
   * The used project name (if not set the project.name is used).
   */
  @Parameter( property="jaxrs-doc.projectName", defaultValue = "\${project.name}" )
  private val projectName: String? = null

  /**
   * The base path (if not set the project.name is used).
   */
  @Parameter( property="jaxrs-doc.basePath", defaultValue = "/" )
  private val basePath: String? = null

  /**
   * The Swagger schemes.
   */
  @Parameter( property="jaxrs-doc.swaggerSchemes", defaultValue = "http" )
  private val swaggerSchemes: Array<String>? = null

  /**
   * Specifies if Swagger tags should be generated.
   */
  @Parameter( property="jaxrs-doc.renderSwaggerTags", defaultValue = "false" )
  private val renderSwaggerTags: Boolean? = null

  /**
   * The number at which path position the Swagger tags should be extracted.
   */
  @Parameter( property = "jaxrs-doc.swaggerTagsPathOffset", defaultValue = "0" )
  private val swaggerTagsPathOffset: Int? = null

  /**
   * For plaintext and asciidoc backends, should they try to prettify inline JSON representation of requests/responses.
   */
  @Parameter( property="jaxrs-doc.inlinePrettify", defaultValue = "true" )
  private val inlinePrettify: Boolean? = null

  /**
   */
  @Parameter( property = "project.build.outputDirectory", readonly = true, required = true )
  private val outputDirectory: File? = null

  /**
   */
  @Parameter( property = "project.build.sourceDirectory", readonly = true, required = true )
  private val sourceDirectory: File? = null

  /**
   */
  @Parameter( property = "project.build.directory", readonly = true, required = true )
  private val buildDirectory: File? = null

  /**
   */
  @Parameter( property = "project.build.sourceEncoding" )
  private val encoding: String? = null

  /**
   */
  @Parameter( property = "project", readonly = true, required = true)
  private val project: MavenProject? = null

  /**
   * The entry point to Aether.
   */
  @Component
  private val repoSystem: RepositorySystem? = null

  /**
   * The current repository/network configuration of Maven.
   */
  @Parameter( property = "repositorySystemSession", readonly = true, required = true )
  private val repoSession: RepositorySystemSession? = null

  /**
   * The project's remote repositories to use for the resolution of plugins and their dependencies.
   */
  @Parameter( property = "project.remotePluginRepositories", readonly = true, required = true )
  private val remoteRepos: List<RemoteRepository>? = null


  /**
   * Path, relative to outputDir, to generate resources
   */
  @Parameter( defaultValue = "jaxrs-doc", property = "jaxrs-doc.resourcesDir" )
  private val resourcesDir: String? = null

//  private val backendTypes: List<BackendType>
  private fun getBackendTypes(): List<BackendType> {
    val backends = backend!!.split(",".toRegex()).dropLastWhile({ it.isEmpty() }).toTypedArray()
    return Arrays.stream<String>(backends)
            .map { BackendType.fromString(it) }
            .collect(Collectors.toList())
  }

//  private val dependencies: Set<Path>
  // Java EE 7 and JAX-RS Analyzer API is needed internally
  @Throws(MojoExecutionException::class)
  private fun getDependencies(): Set<Path> {
    project!!.setArtifactFilter { a -> true }

    var artifacts = project.artifacts
    LogProvider.debug("Artifacts (pre): $artifacts")
    if (artifacts.isEmpty()) {
      artifacts = project.dependencyArtifacts
    }
    LogProvider.debug("Artifacts (post): $artifacts")

    val dependencies = artifacts.stream()
//            .peek { LogProvider.debug("Artifacts debug stream start: " + it) }
            .filter { it.scope != Artifact.SCOPE_TEST }
//            .peek { LogProvider.debug("Artifacts debug: " + it) }
            .map { it.file }
            .filter { Objects.nonNull(it) }
            .map { it.toPath() }
            .collect(Collectors.toSet())

    LogProvider.debug("Dependencies (pre fetch/plus): $dependencies")
//    val analyzerVersion = "1.0.0"// project.pluginArtifactMap["com.github.mibo:jaxrs-doc-maven-plugin"]?.getVersion()
    dependencies.add(fetchDependency("com.github.mibo:jaxrsdoc:" + getJaxRsDocVersion()))
    dependencies.add(fetchDependency("javax:javaee-api:7.0"))
    LogProvider.debug("Dependencies (post fetch/plus): $dependencies")
    return dependencies
  }

  private fun getJaxRsDocVersion(): String? {
    val p = Properties()
    // TODO: improve this (exception handling?)
    p.load(Thread.currentThread().contextClassLoader.getResourceAsStream("jaxrsdoc.properties"))
    return p.getProperty("jaxrs-doc.version")
  }

  @Throws(MojoExecutionException::class)
  override fun execute() {
    injectMavenLoggers()

    // avoid execution if output directory does not exist
    if (!outputDirectory!!.exists() || !outputDirectory.isDirectory) {
      LogProvider.info("skipping non existing directory $outputDirectory")
      return
    }

    val backendTypes = getBackendTypes()
    LogProvider.info("analyzing JAX-RS resources, using $backendTypes (from backend => $backend)")
    for (backendType in backendTypes) {
      val backend = configureBackend(backendType)

      LogProvider.info("analyzing JAX-RS resources, using " + backend.name + " backends")

      // add dependencies to analysis class path
      val classPaths = getDependencies()
      LogProvider.debug("Dependency class paths are: $classPaths")

      val projectPaths = setOf<Path>(outputDirectory.toPath())
      LogProvider.debug("Project paths are: $projectPaths")

      val sourcePaths = setOf<Path>(sourceDirectory!!.toPath())
      LogProvider.debug("Source paths are: $sourcePaths")

      handleSourceEncoding()

      // create target sub-directory
      val resourcesDirectory = Paths.get(buildDirectory!!.path, resourcesDir).toFile()
      if (!resourcesDirectory.exists() && !resourcesDirectory.mkdirs())
        throw MojoExecutionException("Could not create directory $resourcesDirectory")

      val fileLocation = resourcesDirectory.toPath().resolve(backendType.fileLocation)

      LogProvider.info("Generating resources at " + fileLocation.toAbsolutePath())

      // start analysis
      val start = System.currentTimeMillis()
      JAXRSAnalyzer(projectPaths, sourcePaths, classPaths, projectName, project!!.version, basePath, backend, fileLocation).analyze()
      LogProvider.debug("Analysis took " + (System.currentTimeMillis() - start) + " ms")
    }
  }

  private fun handleSourceEncoding() {
    if (encoding != null && System.getProperty("project.build.sourceEncoding") == null)
      System.setProperty("project.build.sourceEncoding", encoding)
  }

  @Throws(IllegalArgumentException::class)
  private fun configureBackend(backendType: BackendType): Backend {
    val config = HashMap<String, String>()
    config[SwaggerOptions.SWAGGER_SCHEMES] = Stream.of(*swaggerSchemes!!).collect(Collectors.joining(","))
    config[SwaggerOptions.DOMAIN] = deployedDomain.toString()
    config[SwaggerOptions.RENDER_SWAGGER_TAGS] = renderSwaggerTags!!.toString()
    config[SwaggerOptions.SWAGGER_TAGS_PATH_OFFSET] = swaggerTagsPathOffset!!.toString()
    config[StringBackend.INLINE_PRETTIFY] = inlinePrettify!!.toString()

    val backend = JAXRSAnalyzer.constructBackend(backendType.name)
    backend.configure(config)

    return backend
  }

  private fun injectMavenLoggers() {
    LogProvider.injectInfoLogger( { log.info(it) })
    LogProvider.injectDebugLogger( { log.debug(it) })
    LogProvider.injectErrorLogger( { log.error(it) })
  }

  @Throws(MojoExecutionException::class)
  private fun fetchDependency(artifactIdentifier: String): Path {
    val request = ArtifactRequest()
    val artifact = DefaultArtifact(artifactIdentifier)
    request.artifact = artifact
    request.repositories = remoteRepos

    LogProvider.debug("Resolving artifact $artifact from $remoteRepos")

    val result: ArtifactResult
    try {
      if (repoSystem != null) {
        result = repoSystem.resolveArtifact(repoSession, request)
      } else {
        throw IllegalStateException("No Repo System found")
      }
    } catch (e: ArtifactResolutionException) {
      throw MojoExecutionException(e.message, e)
    }

    LogProvider.debug("Resolved artifact " + artifact + " to " + result.artifact.file + " from " + result.repository)
    return result.artifact.file.toPath()
  }

}
