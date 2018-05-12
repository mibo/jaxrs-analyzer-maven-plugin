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
class JaxRsDocMojo : AbstractMojo() {

  /**
   * The chosen backend(s) format. Defaults to plaintext.
   * Support multiple backends as comma separated list (e.g. `asciidoc,swagger`)
   *
   * @parameter default-value="plaintext" property="jaxrs-doc.backend"
   */
  private val backend: String? = null

  /**
   * The domain where the project will be deployed.
   *
   * @parameter default-value="" property="jaxrs-doc.deployedDomain"
   */
  private val deployedDomain: String? = null

  /**
   * The Swagger schemes.
   *
   * @parameter default-value="http" property="jaxrs-doc.swaggerSchemes"
   */
  private val swaggerSchemes: Array<String>? = null

  /**
   * Specifies if Swagger tags should be generated.
   *
   * @parameter default-value="false" property="jaxrs-doc.renderSwaggerTags"
   */
  private val renderSwaggerTags: Boolean? = null

  /**
   * The number at which path position the Swagger tags should be extracted.
   *
   * @parameter default-value="0" property="jaxrs-doc.swaggerTagsPathOffset"
   */
  private val swaggerTagsPathOffset: Int? = null

  /**
   * For plaintext and asciidoc backends, should they try to prettify inline JSON representation of requests/responses.
   *
   * @parameter default-value="true" property="jaxrs-doc.inlinePrettify"
   */
  private val inlinePrettify: Boolean? = null

  /**
   * @parameter property="project.build.outputDirectory"
   * @required
   * @readonly
   */
  private val outputDirectory: File? = null

  /**
   * @parameter property="project.build.sourceDirectory"
   * @required
   * @readonly
   */
  private val sourceDirectory: File? = null

  /**
   * @parameter property="project.build.directory"
   * @required
   * @readonly
   */
  private val buildDirectory: File? = null

  /**
   * @parameter property="project.build.sourceEncoding"
   */
  private val encoding: String? = null

  /**
   * @parameter property="project"
   * @required
   * @readonly
   */
  private val project: MavenProject? = null

  /**
   * The entry point to Aether.
   *
   * @component
   */
  private val repoSystem: RepositorySystem? = null

  /**
   * The current repository/network configuration of Maven.
   *
   * @parameter property="repositorySystemSession"
   * @required
   * @readonly
   */
  private val repoSession: RepositorySystemSession? = null

  /**
   * The project's remote repositories to use for the resolution of plugins and their dependencies.
   *
   * @parameter property="project.remotePluginRepositories"
   * @required
   * @readonly
   */
  private val remoteRepos: List<RemoteRepository>? = null


  /**
   * Path, relative to outputDir, to generate resources
   *
   * @parameter default-value="jaxrs-doc" property="jaxrs-doc.resourcesDir"
   */
  private val resourcesDir: String? = null

  private val backendTypes: List<BackendType>
    get() {
      val backends = backend!!.split(",".toRegex()).dropLastWhile({ it.isEmpty() }).toTypedArray()
      return Arrays.stream<String>(backends)
              .map { BackendType.fromString(it) }
              .collect(Collectors.toList())
    }

  private// Java EE 7 and JAX-RS Analyzer API is needed internally
  val dependencies: Set<Path>
    @Throws(MojoExecutionException::class)
    get() {
      project!!.setArtifactFilter { a -> true }

      var artifacts = project.artifacts
      if (artifacts.isEmpty()) {
        artifacts = project.dependencyArtifacts
      }

      val dependencies = artifacts.stream()
              .filter { a -> a.scope != Artifact.SCOPE_TEST }
              .map { it.file }
              .filter { Objects.nonNull(it) }
              .map { it.toPath() }
              .collect(Collectors.toSet())

      val analyzerVersion = project.pluginArtifactMap["com.github.mibo:jaxrs-doc-maven-plugin"]?.getVersion()
      dependencies.plus(fetchDependency("javax:javaee-api:7.0"))
      dependencies.plus(fetchDependency("com.github.mibo:jaxrs-doc:$analyzerVersion"))
      return dependencies
    }

  @Throws(MojoExecutionException::class)
  override fun execute() {
    injectMavenLoggers()

    // avoid execution if output directory does not exist
    if (!outputDirectory!!.exists() || !outputDirectory.isDirectory) {
      LogProvider.info("skipping non existing directory $outputDirectory")
      return
    }

    val backendTypes = backendTypes
    LogProvider.info("analyzing JAX-RS resources, using $backendTypes (from backend => $backend)")
    for (backendType in backendTypes) {
      val backend = configureBackend(backendType)

      LogProvider.info("analyzing JAX-RS resources, using " + backend.name + " backends")

      // add dependencies to analysis class path
      val classPaths = dependencies
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
      JAXRSAnalyzer(projectPaths, sourcePaths, classPaths, project!!.name, project.version, backend, fileLocation).analyze()
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
      result = repoSystem!!.resolveArtifact(repoSession, request)
    } catch (e: ArtifactResolutionException) {
      throw MojoExecutionException(e.message, e)
    }

    LogProvider.debug("Resolved artifact " + artifact + " to " + result.artifact.file + " from " + result.repository)
    return result.artifact.file.toPath()
  }

}
