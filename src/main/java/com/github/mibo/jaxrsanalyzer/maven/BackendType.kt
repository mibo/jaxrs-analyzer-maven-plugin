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
package com.github.mibo.jaxrsanalyzer.maven

import java.util.*
import java.util.stream.Collectors
import java.util.stream.Stream

/**
 * The backend types available for the Maven plugin.
 *
 * @author Sebastian Daschner
 */
internal enum class BackendType constructor(val fileLocation: String) {

  PLAINTEXT("rest-resources.txt"),

  ASCIIDOC("rest-resources.adoc"),

  SWAGGER("swagger.json");


  companion object {

    fun fromString(value: String): BackendType {
      try {
        return BackendType.valueOf(value.toUpperCase(Locale.US))
      } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException("Backend $value not valid! Valid values are: " +
                Stream.of(*BackendType.values())
                        .map { it.name.toLowerCase() }
                        .collect(Collectors.joining(",")))
      }

    }
  }
}
