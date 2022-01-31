/*
 * Copyright 2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.integtests.resolve.catalog

import org.gradle.api.internal.catalog.problems.VersionCatalogErrorMessages
import org.gradle.api.internal.catalog.problems.VersionCatalogProblemId
import org.gradle.api.internal.catalog.problems.VersionCatalogProblemTestFor
import org.gradle.integtests.fixtures.UnsupportedWithConfigurationCache
import org.gradle.integtests.fixtures.configurationcache.ConfigurationCacheFixture
import org.gradle.integtests.fixtures.executer.GradleContextualExecuter
import org.gradle.integtests.fixtures.executer.GradleExecuter
import org.gradle.integtests.resolve.PluginDslSupport
import org.gradle.test.fixtures.file.TestFile
import org.gradle.test.fixtures.server.http.MavenHttpPluginRepository
import org.junit.Rule
import spock.lang.IgnoreIf
import spock.lang.Issue

class TomlDependenciesExtensionIntegrationTest extends AbstractVersionCatalogIntegrationTest implements PluginDslSupport, VersionCatalogErrorMessages {

    @Rule
    final MavenHttpPluginRepository pluginPortal = MavenHttpPluginRepository.asGradlePluginPortal(executer, mavenRepo)

    TestFile tomlFile = testDirectory.file("gradle/libs.versions.toml")

    @UnsupportedWithConfigurationCache(because = "the test uses an extension directly in the task body")
    def "dependencies declared in TOML file trigger the creation of an extension (notation=#notation)"() {
        tomlFile << """[libraries]
foo = "org.gradle.test:lib:1.0"
"""

        buildFile << """
            apply plugin: 'java-library'

            tasks.register("verifyExtension") {
                doLast {
                    def lib = libs.foo
                    assert lib instanceof Provider
                    def dep = lib.get()
                    assert dep instanceof MinimalExternalModuleDependency
                    assert dep.module.group == 'org.gradle.test'
                    assert dep.module.name == 'lib'
                    assert dep.versionConstraint.requiredVersion == '1.0'
                }
            }
        """

        when:
        run 'verifyExtension'

        then:
        operations.hasOperation("Executing generation of dependency accessors for libs")

        when: "no change in settings"
        run 'verifyExtension'

        then: "extension is not regenerated"
        !operations.hasOperation("Executing generation of dependency accessors for libs")

        when: "adding a library to the model"
        tomlFile << """
bar = {group="org.gradle.test", name="bar", version="1.0"}
"""
        run 'verifyExtension'
        then: "extension is regenerated"
        operations.hasOperation("Executing generation of dependency accessors for libs")

        when: "updating a version in the model"
        tomlFile.text = tomlFile.text.replace('{group="org.gradle.test", name="bar", version="1.0"}', '"org.gradle.test:bar:1.1"')
        run 'verifyExtension'

        then: "extension is not regenerated"
        !operations.hasOperation("Executing generation of dependency accessors for libs")
        outputContains 'Type-safe dependency accessors is an incubating feature.'
    }

    def "can use the generated extension to declare a dependency"() {
        tomlFile << """[libraries]
my-lib = {group = "org.gradle.test", name="lib", version.require="1.0"}
"""
        def lib = mavenHttpRepo.module("org.gradle.test", "lib", "1.0").publish()
        buildFile << """
            apply plugin: 'java-library'

            dependencies {
                implementation libs.my.lib
            }
        """

        when:
        lib.pom.expectGet()
        lib.artifact.expectGet()

        then:
        run ':checkDeps'

        then:
        resolve.expectGraph {
            root(":", ":test:") {
                module('org.gradle.test:lib:1.0')
            }
        }
    }

    def "can use the generated extension to declare a dependency and override the version"() {
        tomlFile << """[libraries]
my-lib = {group = "org.gradle.test", name="lib", version.require="1.0"}
"""
        def lib = mavenHttpRepo.module("org.gradle.test", "lib", "1.1").publish()
        buildFile << """
            apply plugin: 'java-library'

            dependencies {
                implementation(libs.my.lib) {
                    version {
                        require '1.1'
                    }
                }
            }
        """

        when:
        lib.pom.expectGet()
        lib.artifact.expectGet()

        then:
        run ':checkDeps'

        then:
        resolve.expectGraph {
            root(":", ":test:") {
                module('org.gradle.test:lib:1.1')
            }
        }
    }

    void "can add several dependencies at once using a bundle"() {
        tomlFile << """[libraries]
lib = {group = "org.gradle.test", name="lib", version.require="1.0"}
lib2.module = "org.gradle.test:lib2"
lib2.version = "1.0"

[bundles]
myBundle = ["lib", "lib2"]
"""
        def lib = mavenHttpRepo.module("org.gradle.test", "lib", "1.0").publish()
        def lib2 = mavenHttpRepo.module("org.gradle.test", "lib2", "1.0").publish()
        buildFile << """
            apply plugin: 'java-library'

            dependencies {
                implementation(libs.bundles.myBundle)
            }
        """

        when:
        lib.pom.expectGet()
        lib.artifact.expectGet()
        lib2.pom.expectGet()
        lib2.artifact.expectGet()

        then:
        run ':checkDeps'

        then:
        resolve.expectGraph {
            root(":", ":test:") {
                module('org.gradle.test:lib:1.0')
                module('org.gradle.test:lib2:1.0')
            }
        }
    }

    void "overriding the version of a bundle overrides the version of all dependencies of the bundle"() {
        tomlFile << """[libraries]
lib = {group = "org.gradle.test", name="lib", version.require="1.0"}
lib2.module = "org.gradle.test:lib2"
lib2.version = "1.0"

[bundles]
myBundle = ["lib", "lib2"]
"""
        def lib = mavenHttpRepo.module("org.gradle.test", "lib", "1.1").publish()
        def lib2 = mavenHttpRepo.module("org.gradle.test", "lib2", "1.1").publish()
        buildFile << """
            apply plugin: 'java-library'

            dependencies {
                implementation(libs.bundles.myBundle) {
                    version {
                        require '1.1'
                    }
                }
            }
        """

        when:
        lib.pom.expectGet()
        lib.artifact.expectGet()
        lib2.pom.expectGet()
        lib2.artifact.expectGet()

        then:
        run ':checkDeps'

        then:
        resolve.expectGraph {
            root(":", ":test:") {
                module('org.gradle.test:lib:1.1')
                module('org.gradle.test:lib2:1.1')
            }
        }
    }


    def "extension can be used in any subproject"() {
        tomlFile << """[libraries]
lib = {group = "org.gradle.test", name="lib", version.require="1.0"}
"""
        settingsFile << """
            include ':other'
        """
        def lib = mavenHttpRepo.module("org.gradle.test", "lib", "1.0").publish()
        buildFile << """
            apply plugin: 'java-library'

            dependencies {
                implementation project(":other")
            }
        """

        file("other/build.gradle") << """
            plugins {
                id 'java-library'
            }

            dependencies {
                implementation libs.lib
            }
        """

        when:
        lib.pom.expectGet()
        lib.artifact.expectGet()

        then:
        run ':checkDeps'

        then:
        resolve.expectGraph {
            root(":", ":test:") {
                project(":other", "test:other:") {
                    module('org.gradle.test:lib:1.0')
                }
            }
        }
    }

    def "libraries extension is not visible in buildSrc"() {
        tomlFile << """[libraries]
lib = "org.gradle.test:lib:1.0"
"""
        file("buildSrc/build.gradle") << """
            dependencies {
                implementation libs.lib
            }
        """

        when:
        fails ':help'

        then: "extension is not generated if there are no libraries defined"
        failure.assertHasCause("Could not get unknown property 'libs' for object of type org.gradle.api.internal.artifacts.dsl.dependencies.DefaultDependencyHandler")
    }

    def "libraries extension can be made visible to buildSrc"() {
        tomlFile << """[libraries]
lib = "org.gradle.test:lib:1.0"
"""
        file("buildSrc/settings.gradle") << """
            dependencyResolutionManagement {
                versionCatalogs {
                    libs {
                        from(files("../gradle/libs.versions.toml"))
                    }
                }
            }
        """
        file("buildSrc/build.gradle") << """
            repositories {
                maven { url "${mavenHttpRepo.uri}" }
            }
            dependencies {
                implementation libs.lib
            }
        """

        when:
        def lib = mavenHttpRepo.module('org.gradle.test', 'lib', '1.0').publish()
        lib.pom.expectGet()
        lib.artifact.expectGet()

        then: "extension is not generated if there are no libraries defined"
        succeeds 'help'
    }

    def "buildSrc and main project have different libraries extensions"() {
        tomlFile << """[libraries]
lib="org.gradle.test:lib:1.0"
"""
        file("buildSrc/gradle/libs.versions.toml") << """[libraries]
build-src-lib="org.gradle.test:buildsrc-lib:1.0"
"""
        file("buildSrc/build.gradle") << """
            repositories {
                maven { url "${mavenHttpRepo.uri}" }
            }

            dependencies {
                implementation libs.build.src.lib
            }
        """
        buildFile << """
            apply plugin: 'java-library'

            dependencies {
                implementation libs.lib
            }
        """

        def buildSrcLib = mavenHttpRepo.module('org.gradle.test', 'buildsrc-lib', '1.0').publish()
        def lib = mavenHttpRepo.module('org.gradle.test', 'lib', '1.0').publish()

        when:
        buildSrcLib.pom.expectGet()
        buildSrcLib.artifact.expectGet()
        lib.pom.expectGet()
        lib.artifact.expectGet()

        succeeds ':checkDeps'

        then:
        resolve.expectGraph {
            root(":", ":test:") {
                module('org.gradle.test:lib:1.0')
            }
        }
    }

    def "included builds use their own libraries extension"() {
        file("included/build.gradle") << """
            plugins {
                id 'java-library'
            }

            group = 'com.acme'
            version = 'zloubi'

            dependencies {
                implementation libs.from.included
            }
        """
        file("included/gradle/libs.versions.toml") << """[libraries]
from-included="org.gradle.test:other:1.1"
"""
        file("included/settings.gradle") << """
            rootProject.name = 'included'

            dependencyResolutionManagement {
                repositories {
                    maven { url "${mavenHttpRepo.uri}" }
                }
            }
        """

        settingsFile << """
            includeBuild 'included'
        """

        buildFile << """
            apply plugin: 'java-library'

            dependencies {
                implementation 'com.acme:included:1.0'
            }
        """
        def lib = mavenHttpRepo.module('org.gradle.test', 'other', '1.1').publish()

        when:
        lib.pom.expectGet()
        lib.artifact.expectGet()
        succeeds ':checkDeps'

        then:
        resolve.expectGraph {
            root(":", ":test:") {
                edge("com.acme:included:1.0", "project :included", "com.acme:included:zloubi") {
                    compositeSubstitute()
                    configuration = "runtimeElements"
                    module('org.gradle.test:other:1.1')
                }
            }
        }
    }

    def "model from TOML file and settings is merged if settings use the same extension name"() {
        tomlFile << """[libraries]
my-lib = {group = "org.gradle.test", name="lib", version.require="1.0"}
"""
        def lib = mavenHttpRepo.module("org.gradle.test", "lib", "1.0").publish()
        def other = mavenHttpRepo.module("org.gradle.test", "other", "1.0").publish()
        buildFile << """
            apply plugin: 'java-library'

            dependencies {
                implementation libs.my.lib
                implementation libs.other
            }
        """
        settingsFile << """
            dependencyResolutionManagement {
                versionCatalogs {
                    libs {
                        library('other', 'org.gradle.test:other:1.0')
                    }
                }
            }
        """
        when:
        lib.pom.expectGet()
        lib.artifact.expectGet()
        other.pom.expectGet()
        other.artifact.expectGet()

        then:
        run ':checkDeps'

        then:
        resolve.expectGraph {
            root(":", ":test:") {
                module('org.gradle.test:lib:1.0')
                module('org.gradle.test:other:1.0')
            }
        }
    }

    // documents the existing behavior
    def "TOML file wins over settings"() {
        tomlFile << """[libraries]
my-lib = {group = "org.gradle.test", name="lib", version.require="1.1"}
"""
        def lib = mavenHttpRepo.module("org.gradle.test", "lib", "1.1").publish()
        buildFile << """
            apply plugin: 'java-library'

            dependencies {
                implementation libs.my.lib
            }
        """
        settingsFile << """
            dependencyResolutionManagement {
                versionCatalogs {
                    libs {
                        library('my-lib', 'org.gradle.test:lib:1.0')
                    }
                }
            }
        """
        when:
        lib.pom.expectGet()
        lib.artifact.expectGet()

        then:
        run ':checkDeps'

        then:
        outputContains "Duplicate entry for alias 'my.lib': dependency {group='org.gradle.test', name='lib', version='1.0'} is replaced with dependency {group='org.gradle.test', name='lib', version='1.1'}"
        resolve.expectGraph {
            root(":", ":test:") {
                module('org.gradle.test:lib:1.1')
            }
        }
    }

    @IgnoreIf({ GradleContextualExecuter.configCache })
    // This test explicitly checks the configuration cache behavior
    def "changing the TOML file invalidates the configuration cache"() {
        def cc = new ConfigurationCacheFixture(this)
        tomlFile << """[libraries]
my-lib = {group = "org.gradle.test", name="lib", version.require="1.0"}
"""
        def lib = mavenHttpRepo.module("org.gradle.test", "lib", "1.0").publish()
        buildFile.text = """
            plugins {
                id 'java-library'
            }

            dependencies {
                implementation libs.my.lib
            }

            tasks.register("checkDeps", CheckDeps) {
                input.from(configurations.runtimeClasspath)
            }

            class CheckDeps extends DefaultTask {
                @InputFiles
                final ConfigurableFileCollection input = project.objects.fileCollection()

                @TaskAction
                void doSomething() {
                    println input.files.name
                }
            }
        """

        when:
        lib.pom.expectGet()
        lib.artifact.expectGet()

        then:
        withConfigurationCache()
        succeeds ':checkDeps'

        then:
        cc.assertStateStored()

        when:
        withConfigurationCache()
        succeeds ':checkDeps'

        then:
        cc.assertStateLoaded()

        when:
        tomlFile << """
my-other-lib = {group = "org.gradle.test", name="lib2", version="1.0"}
"""
        withConfigurationCache()
        succeeds ':checkDeps'

        then:
        cc.assertStateRecreated {
            fileChanged("gradle/libs.versions.toml")
        }
    }

    def "can change the default extension name"() {
        tomlFile << """[libraries]
my-lib = {group = "org.gradle.test", name="lib", version.require="1.0"}
"""
        def lib = mavenHttpRepo.module("org.gradle.test", "lib", "1.0").publish()
        settingsFile << """
            dependencyResolutionManagement {
                defaultLibrariesExtensionName = 'myLibs'
            }
        """
        buildFile << """
            apply plugin: 'java-library'

            dependencies {
                implementation myLibs.my.lib
            }
        """

        when:
        lib.pom.expectGet()
        lib.artifact.expectGet()

        then:
        run ':checkDeps'

        then:
        resolve.expectGraph {
            root(":", ":test:") {
                module('org.gradle.test:lib:1.0')
            }
        }
    }

    def "can use version references"() {
        tomlFile << """[versions]
lib = "1.0"
rich = { strictly = "[1.0, 2.0]", prefer = "1.1" }

[libraries]
my-lib = {group = "org.gradle.test", name="lib", version.ref="lib"}
my-other-lib = {group = "org.gradle.test", name="lib2", version.ref="rich"}
"""
        def lib = mavenHttpRepo.module("org.gradle.test", "lib", "1.0").publish()
        def lib2 = mavenHttpRepo.module("org.gradle.test", "lib2", "1.1").publish()
        buildFile << """
            apply plugin: 'java-library'

            dependencies {
                implementation libs.my.lib
                implementation libs.my.other.lib
            }
        """

        when:
        lib.pom.expectGet()
        lib.artifact.expectGet()
        lib2.rootMetaData.expectGet()
        lib2.pom.expectGet()
        lib2.artifact.expectGet()

        then:
        run ':checkDeps'

        then:
        resolve.expectGraph {
            root(":", ":test:") {
                module('org.gradle.test:lib:1.0')
                edge('org.gradle.test:lib2:{strictly [1.0, 2.0]; prefer 1.1}', 'org.gradle.test:lib2:1.1')
            }
        }
    }

    @VersionCatalogProblemTestFor(
        VersionCatalogProblemId.CATALOG_FILE_DOES_NOT_EXIST
    )
    @Issue("https://github.com/gradle/gradle/issues/15029")
    def "reasonable error message if an imported catalog doesn't exist"() {
        def path = file("missing.toml")
        settingsFile << """
            dependencyResolutionManagement {
                versionCatalogs {
                    libs {
                        from(files("missing.toml"))
                    }
                }
            }
        """

        when:
        fails ':help'

        then:
        verifyContains(failure.error, missingCatalogFile {
            inCatalog('libs')
            missing(path)
        })
    }

    def "can use nested versions, libraries and bundles"() {
        tomlFile << """
[versions]
commons-lib = "1.0"

[libraries]
my-lib = {group = "org.gradle.test", name="lib", version.ref="commons-lib"}
my-lib2 = {group = "org.gradle.test", name="lib2", version.ref="commons-lib"}

[bundles]
my-bundle = ["my-lib"]
other-bundle = ["my-lib", "my-lib2"]

"""
        def lib = mavenHttpRepo.module("org.gradle.test", "lib", "1.0").publish()
        def lib2 = mavenHttpRepo.module("org.gradle.test", "lib2", "1.0").publish()
        buildFile << """
            apply plugin: 'java-library'

            dependencies {
                implementation libs.bundles.my.bundle
                implementation libs.bundles.other.bundle
            }
        """

        when:
        lib.pom.expectGet()
        lib.artifact.expectGet()
        lib2.pom.expectGet()
        lib2.artifact.expectGet()

        then:
        run ':checkDeps'

        then:
        resolve.expectGraph {
            root(":", ":test:") {
                module('org.gradle.test:lib:1.0')
                module('org.gradle.test:lib2:1.0')
            }
        }
    }


    @Issue("https://github.com/gradle/gradle/issues/16845")
    @VersionCatalogProblemTestFor([
        VersionCatalogProblemId.TOML_SYNTAX_ERROR
    ])
    def "should not swallow invalid TOML parse errors"() {
        tomlFile << """
[versions]
// This is an invalid comment format
commons-lib = "1.0"

[libraries]
lib = {group = "org.gradle.test", name="lib", version.ref="commons-lib"}

"""

        when:
        fails 'help'

        then:
        verifyContains(failure.error, parseError {
            inCatalog('libs')
            addError('At line 3, column 1: Unexpected \'/\', expected a newline or end-of-input')
        })
    }

    private GradleExecuter withConfigurationCache() {
        executer.withArgument("--configuration-cache")
    }
}
