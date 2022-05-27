/*
 * Copyright 2011 the original author or authors.
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

package org.gradle.api.internal.artifacts;

import groovy.lang.Closure;
import org.gradle.api.Project;
import org.gradle.api.artifacts.ClientModule;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.DependencyConstraint;
import org.gradle.api.artifacts.ExternalModuleDependency;
import org.gradle.api.artifacts.FileCollectionDependency;
import org.gradle.api.artifacts.MinimalExternalModuleDependency;
import org.gradle.api.artifacts.ProjectDependency;
import org.gradle.api.artifacts.dsl.DependencyProvider;
import org.gradle.api.capabilities.Capability;
import org.gradle.api.file.FileCollection;
import org.gradle.api.internal.artifacts.dependencies.AbstractModuleDependency;
import org.gradle.api.internal.artifacts.dependencies.DefaultDependencyConstraint;
import org.gradle.api.internal.artifacts.dsl.dependencies.DependencyFactoryInternal;
import org.gradle.api.internal.artifacts.dsl.dependencies.ModuleFactoryDelegate;
import org.gradle.api.internal.artifacts.dsl.dependencies.ProjectFinder;
import org.gradle.api.internal.attributes.ImmutableAttributesFactory;
import org.gradle.api.internal.notations.DependencyNotationParser;
import org.gradle.api.internal.notations.ProjectDependencyFactory;
import org.gradle.api.internal.provider.ProviderInternal;
import org.gradle.api.provider.Provider;
import org.gradle.internal.typeconversion.NotationParser;

import java.util.Map;

public class DefaultDependencyFactory implements DependencyFactoryInternal {
    private final DependencyNotationParser dependencyNotationParser;
    private final NotationParser<Object, DependencyConstraint> dependencyConstraintNotationParser;
    private final NotationParser<Object, ClientModule> clientModuleNotationParser;
    private final NotationParser<Object, Capability> capabilityNotationParser;
    private final ProjectDependencyFactory projectDependencyFactory;
    private final ImmutableAttributesFactory attributesFactory;

    public DefaultDependencyFactory(
        DependencyNotationParser dependencyNotationParser,
        NotationParser<Object, DependencyConstraint> dependencyConstraintNotationParser,
        NotationParser<Object, ClientModule> clientModuleNotationParser,
        NotationParser<Object, Capability> capabilityNotationParser,
        ProjectDependencyFactory projectDependencyFactory,
        ImmutableAttributesFactory attributesFactory
    ) {
        this.dependencyNotationParser = dependencyNotationParser;
        this.dependencyConstraintNotationParser = dependencyConstraintNotationParser;
        this.clientModuleNotationParser = clientModuleNotationParser;
        this.capabilityNotationParser = capabilityNotationParser;
        this.projectDependencyFactory = projectDependencyFactory;
        this.attributesFactory = attributesFactory;
    }

    @Override
    public Dependency createDependency(Object dependencyNotation) {
        Dependency dependency = dependencyNotationParser.getNotationParser().parseNotation(dependencyNotation);
        injectServices(dependency);
        return dependency;
    }

    private void injectServices(Dependency dependency) {
        if (dependency instanceof AbstractModuleDependency) {
            AbstractModuleDependency moduleDependency = (AbstractModuleDependency) dependency;
            moduleDependency.setAttributesFactory(attributesFactory);
            moduleDependency.setCapabilityNotationParser(capabilityNotationParser);
        }
    }

    @Override
    public DependencyConstraint createDependencyConstraint(Object dependencyNotation) {
        DependencyConstraint dependencyConstraint = dependencyConstraintNotationParser.parseNotation(dependencyNotation);
        injectServices(dependencyConstraint);
        return dependencyConstraint;
    }

    private void injectServices(DependencyConstraint dependency) {
        if (dependency instanceof DefaultDependencyConstraint) {
            ((DefaultDependencyConstraint) dependency).setAttributesFactory(attributesFactory);
        }
    }


    @Override
    @SuppressWarnings("rawtypes")
    public ClientModule createModule(Object dependencyNotation, Closure configureClosure) {
        ClientModule clientModule = clientModuleNotationParser.parseNotation(dependencyNotation);
        if (configureClosure != null) {
            configureModule(clientModule, configureClosure);
        }
        return clientModule;
    }

    @Override
    public ProjectDependency createProjectDependencyFromMap(ProjectFinder projectFinder, Map<? extends String, ? extends Object> map) {
        return projectDependencyFactory.createFromMap(projectFinder, map);
    }

    @SuppressWarnings("rawtypes")
    private void configureModule(ClientModule clientModule, Closure configureClosure) {
        ModuleFactoryDelegate moduleFactoryDelegate = new ModuleFactoryDelegate(clientModule, this);
        moduleFactoryDelegate.prepareDelegation(configureClosure);
        configureClosure.call();
    }

    // region DependencyFactory methods

    @Override
    public ExternalModuleDependency fromCharSequence(CharSequence dependencyNotation) {
        return dependencyNotationParser.getStringNotationParser().parseNotation(dependencyNotation.toString());
    }

    @Override
    public ExternalModuleDependency fromMinimal(MinimalExternalModuleDependency dependencyNotation) {
        return dependencyNotationParser.getMinimalExternalModuleDependencyNotationParser().parseNotation(dependencyNotation);
    }

    @Override
    public ExternalModuleDependency fromMap(Map<String, ?> dependencyNotation) {
        return dependencyNotationParser.getMapNotationParser().parseNotation(dependencyNotation);
    }

    @Override
    public FileCollectionDependency fromFileCollection(FileCollection dependencyNotation) {
        return dependencyNotationParser.getFileCollectionNotationParser().parseNotation(dependencyNotation);
    }

    @Override
    public ProjectDependency fromProject(Project dependencyNotation) {
        return dependencyNotationParser.getProjectNotationParser().parseNotation(dependencyNotation);
    }

    @Override
    public <D extends Dependency> DependencyProvider<D> fromDependency(Provider<D> dependencyNotation) {
        return new DefaultDependencyProvider<>((ProviderInternal<D>) dependencyNotation);
    }

    // endregion
}
