/*
 * Copyright 2018 the original author or authors.
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

package org.gradle.api.internal.artifacts.transform;

import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.ResolutionStrategy;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.result.DependencyResult;
import org.gradle.api.artifacts.result.ResolvedComponentResult;
import org.gradle.api.artifacts.result.ResolvedDependencyResult;
import org.gradle.api.artifacts.result.ResolvedVariantResult;
import org.gradle.api.capabilities.Capability;
import org.gradle.api.file.FileCollection;
import org.gradle.api.internal.DomainObjectContext;
import org.gradle.api.internal.artifacts.ResolverResults;
import org.gradle.api.internal.artifacts.configurations.ResolutionBackedFileCollection;
import org.gradle.api.internal.artifacts.configurations.ResolutionHost;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.ArtifactSelectionSpec;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.SelectedArtifactSet;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.VisitedArtifactSet;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.graph.results.VisitedGraphResults;
import org.gradle.api.internal.attributes.AttributesFactory;
import org.gradle.api.internal.attributes.ImmutableAttributes;
import org.gradle.api.internal.capabilities.ImmutableCapability;
import org.gradle.api.internal.file.FileCollectionFactory;
import org.gradle.api.internal.file.FileCollectionInternal;
import org.gradle.api.internal.lambdas.SerializableLambdas;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.internal.tasks.NodeExecutionContext;
import org.gradle.api.internal.tasks.TaskDependencyFactory;
import org.gradle.api.internal.tasks.TaskDependencyResolveContext;
import org.gradle.api.internal.tasks.WorkNodeAction;
import org.gradle.api.specs.Spec;
import org.gradle.api.specs.Specs;
import org.gradle.execution.plan.PostExecutionNodeAwareActionNode;
import org.gradle.execution.plan.TaskNode;
import org.gradle.execution.plan.TaskNodeFactory;
import org.gradle.internal.Describables;
import org.gradle.internal.Try;
import org.gradle.internal.component.external.model.ImmutableCapabilities;
import org.gradle.internal.model.CalculatedValue;
import org.gradle.internal.model.CalculatedValueContainer;
import org.gradle.internal.model.CalculatedValueContainerFactory;
import org.gradle.internal.model.ValueCalculator;
import org.gradle.operations.dependencies.configurations.ConfigurationIdentity;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public class DefaultTransformUpstreamDependenciesResolver implements TransformUpstreamDependenciesResolver {
    public static final TransformDependencies NO_RESULT = new TransformDependencies() {
        @Override
        public Optional<FileCollection> getFiles() {
            return Optional.of(FileCollectionFactory.empty());
        }
    };
    public static final TransformUpstreamDependencies NO_DEPENDENCIES = new TransformUpstreamDependencies() {

        @Nullable
        @Override
        public ConfigurationIdentity getConfigurationIdentity() {
            return null;
        }

        @Override
        public FileCollection selectedArtifacts() {
            return FileCollectionFactory.empty();
        }

        @Override
        public void finalizeIfNotAlready() {
        }

        @Override
        public Try<TransformDependencies> computeArtifacts() {
            return Try.successful(NO_RESULT);
        }

        @Override
        public void visitDependencies(TaskDependencyResolveContext context) {
        }
    };

    private final ResolutionHost resolutionHost;
    private final ConfigurationIdentity configurationIdentity;
    private final ImmutableAttributes requestAttributes;
    private final ResolutionStrategy.SortOrder artifactDependencySortOrder;

    private final VisitedGraphResults initialVisitedGraph;
    private final VisitedArtifactSet initialVisitedArtifacts;
    private final CalculatedValue<VisitedGraphResults> completeGraphResults;
    private final CalculatedValue<VisitedArtifactSet> completeArtifactResults;

    // Services
    private final DomainObjectContext owner;
    private final CalculatedValueContainerFactory calculatedValueContainerFactory;
    private final AttributesFactory attributesFactory;
    private final TaskDependencyFactory taskDependencyFactory;

    /**
     * Construct a resolver used to resolve build dependencies of upstream dependencies for an artifact transform.
     * <p>
     * The {@code fullGraphResults} are required to calculate the true build dependencies of transforms
     * with dependencies, as the incomplete graph used to initially determine upstream transforms does
     * not represent the final dependency graph.
     * <p>
     * See {@link org.gradle.integtests.resolve.transform.ArtifactTransformWithDependenciesParallelIntegrationTest}
     * for the test that exercises the scenario that necessitates this behavior.
     */
    public DefaultTransformUpstreamDependenciesResolver(
        ResolutionHost resolutionHost,
        @Nullable ConfigurationIdentity configurationIdentity,
        ImmutableAttributes requestAttributes,
        ResolutionStrategy.SortOrder artifactDependencySortOrder,

        VisitedGraphResults partialVisitedGraph,
        VisitedArtifactSet partialVisitedArtifacts,
        CalculatedValue<ResolverResults> fullGraphResults,

        // Services
        DomainObjectContext owner,
        CalculatedValueContainerFactory calculatedValueContainerFactory,
        AttributesFactory attributesFactory,
        TaskDependencyFactory taskDependencyFactory
    ) {
        this.resolutionHost = resolutionHost;
        this.configurationIdentity = configurationIdentity;
        this.requestAttributes = requestAttributes;
        this.artifactDependencySortOrder = artifactDependencySortOrder;

        this.initialVisitedArtifacts = partialVisitedArtifacts;
        this.initialVisitedGraph = partialVisitedGraph;
        this.completeGraphResults = calculatedValueContainerFactory.create(Describables.of("complete graph results for", resolutionHost.getDisplayName()), context -> {
            // TODO: We should acquire the project lock here, since this will resolve a configuration, which requires a project lock.
            fullGraphResults.finalizeIfNotAlready();
            return fullGraphResults.get().getVisitedGraph();
        });
        this.completeArtifactResults = calculatedValueContainerFactory.create(Describables.of("complete artifact results for", resolutionHost.getDisplayName()), context -> {
            // TODO: We should acquire the project lock here, since this will resolve a configuration, which requires a project lock.
            fullGraphResults.finalizeIfNotAlready();
            return fullGraphResults.get().getVisitedArtifacts();
        });

        this.owner = owner;
        this.attributesFactory = attributesFactory;
        this.calculatedValueContainerFactory = calculatedValueContainerFactory;
        this.taskDependencyFactory = taskDependencyFactory;
    }

    /**
     * Construct a resolver used to resolve the complete set of upstream dependencies for an artifact transform.
     */
    public DefaultTransformUpstreamDependenciesResolver(
        ResolutionHost resolutionHost,
        @Nullable ConfigurationIdentity configurationIdentity,
        ImmutableAttributes requestAttributes,
        ResolutionStrategy.SortOrder artifactDependencySortOrder,

        VisitedGraphResults visitedGraph,
        VisitedArtifactSet visitedArtifacts,

        // Services
        DomainObjectContext owner,
        CalculatedValueContainerFactory calculatedValueContainerFactory,
        AttributesFactory attributesFactory,
        TaskDependencyFactory taskDependencyFactory
    ) {
        this.resolutionHost = resolutionHost;
        this.configurationIdentity = configurationIdentity;
        this.requestAttributes = requestAttributes;
        this.artifactDependencySortOrder = artifactDependencySortOrder;

        this.initialVisitedGraph = visitedGraph;
        this.initialVisitedArtifacts = visitedArtifacts;
        this.completeGraphResults = calculatedValueContainerFactory.create(Describables.of("complete graph results for", resolutionHost.getDisplayName()), context -> visitedGraph);
        this.completeArtifactResults = calculatedValueContainerFactory.create(Describables.of("complete artifact results for", resolutionHost.getDisplayName()), context -> visitedArtifacts);

        this.owner = owner;
        this.attributesFactory = attributesFactory;
        this.calculatedValueContainerFactory = calculatedValueContainerFactory;
        this.taskDependencyFactory = taskDependencyFactory;
    }

    @Override
    public TransformUpstreamDependencies dependenciesFor(ComponentIdentifier componentIdentifier, ImmutableCapabilities capabilities, TransformStep transformStep) {
        if (!transformStep.requiresDependencies()) {
            return NO_DEPENDENCIES;
        }
        return new TransformUpstreamDependenciesImpl(componentIdentifier, capabilities, configurationIdentity, transformStep, calculatedValueContainerFactory, initialVisitedGraph, initialVisitedArtifacts);
    }

    private FileCollectionInternal getCompleteTransformDependencies(ComponentIdentifier componentIdentifier, ImmutableCapabilities capabilities, ImmutableAttributes fromAttributes) {
        completeGraphResults.finalizeIfNotAlready();
        completeArtifactResults.finalizeIfNotAlready();

        SelectedArtifactSet selectedArtifacts = selectDependencyArtifacts(
            componentIdentifier,
            capabilities,
            fromAttributes,
            completeGraphResults.get(),
            completeArtifactResults.get()
        );

        return new ResolutionBackedFileCollection(
            selectedArtifacts,
            false,
            resolutionHost,
            taskDependencyFactory
        );
    }

    private SelectedArtifactSet selectDependencyArtifacts(
        ComponentIdentifier componentIdentifier,
        ImmutableCapabilities capabilities,
        ImmutableAttributes fromAttributes,
        VisitedGraphResults visitedGraph,
        VisitedArtifactSet visitedArtifacts
    ) {
        Set<Capability> dependencyCapabilities = computeDependencies(componentIdentifier, capabilities, visitedGraph);

        Spec<ImmutableCapabilities> filter = SerializableLambdas.spec(specCapabilities -> {
            if (specCapabilities.isEmpty()) {
                return false;
            }

            for (Capability capability : specCapabilities) {
                if (!dependencyCapabilities.contains(capability)) {
                    return false;
                }
            }

            return true;
        });

        ImmutableAttributes fullAttributes = attributesFactory.concat(requestAttributes, fromAttributes);
        return visitedArtifacts.select(new ArtifactSelectionSpec(
            fullAttributes, Specs.satisfyAll(), filter, false, false, artifactDependencySortOrder
        ));
    }

    private static Set<Capability> computeDependencies(ComponentIdentifier componentIdentifier, ImmutableCapabilities capabilities, VisitedGraphResults visitedGraph) {
        ResolvedComponentResult root = visitedGraph.getResolutionResult().getRootSource().get();
        List<DependencyResult> targetVariantDependencies = findVariantDependencies(root, componentIdentifier, capabilities);

        if (targetVariantDependencies == null) {
            throw new AssertionError("Could not find component " + componentIdentifier + " with capabilities " + capabilities + " in provided results.");
        }

        Set<Capability> buildDependencies = new HashSet<>();
        collectReachableComponents(buildDependencies, new HashSet<>(), targetVariantDependencies);
        return buildDependencies;
    }

    /**
     * Search the graph for a component with the given identifier, starting from the given root component.
     *
     * @return null if the component is not found.
     */
    @Nullable
    public static List<DependencyResult> findVariantDependencies(ResolvedComponentResult rootComponent, ComponentIdentifier componentIdentifier, ImmutableCapabilities capabilities) {
        Set<ResolvedComponentResult> seen = new HashSet<>();
        Deque<ResolvedComponentResult> pending = new ArrayDeque<>();
        pending.push(rootComponent);

        while (!pending.isEmpty()) {
            ResolvedComponentResult component = pending.pop();

            if (component.getId().equals(componentIdentifier)) {
                for (ResolvedVariantResult variant : component.getVariants()) {
                    boolean allCapabilitiesMatch = true;

                    for (ImmutableCapability capability : capabilities) {
                        if (!variant.getCapabilities().contains(capability)) {
                            allCapabilitiesMatch = false;
                            break;
                        }
                    }

                    if (allCapabilitiesMatch) {
                        return component.getDependenciesForVariant(variant);
                    }
                }
            }

            for (DependencyResult d : component.getDependencies()) {
                if (d instanceof ResolvedDependencyResult) {
                    ResolvedDependencyResult resolved = (ResolvedDependencyResult) d;
                    ResolvedComponentResult selected = resolved.getSelected();
                    if (seen.add(selected)) {
                        pending.push(selected);
                    }
                }
            }
        }

        return null;
    }

    private static void collectReachableComponents(Set<Capability> dependencyCapabilities, Set<ComponentIdentifier> visited, Collection<? extends DependencyResult> dependencies) {
        for (DependencyResult dependency : dependencies) {
            if (dependency instanceof ResolvedDependencyResult && !dependency.isConstraint()) {
                ResolvedDependencyResult resolvedDependency = (ResolvedDependencyResult) dependency;
                ResolvedComponentResult selected = resolvedDependency.getSelected();
                dependencyCapabilities.addAll(resolvedDependency.getResolvedVariant().getCapabilities());
                if (visited.add(selected.getId())) {
                    // Do not traverse if seen already
                    collectReachableComponents(dependencyCapabilities, visited, selected.getDependencies());
                }
            }
        }
    }

    /**
     * Represents a work node that prepares the upstream dependencies of a particular transform applied to a particular artifact.
     * This is a separate node so that this work can access project state to do the resolution and to discover additional dependencies for the transform
     * during resolution of upstream dependencies. It also allows the work of resolution to be attributed separately to the work of the transform.
     */
    public static abstract class FinalizeTransformDependencies implements ValueCalculator<TransformDependencies> {
        public abstract FileCollection selectedArtifacts();

        @Override
        public TransformDependencies calculateValue(NodeExecutionContext context) {
            FileCollection files = selectedArtifacts();
            // Trigger resolution, including any failures
            files.getFiles();
            return new DefaultTransformDependencies(files);
        }
    }

    /**
     * A work node used in builds where the upstream dependencies must be resolved. This implementation is not used when the work graph is loaded from the configuration cache,
     * as the dependencies have already been resolved in that case.
     */
    public class FinalizeTransformDependenciesFromSelectedArtifacts extends FinalizeTransformDependencies {
        private final ComponentIdentifier componentIdentifier;
        private final ImmutableCapabilities capabilities;
        private final ImmutableAttributes fromAttributes;

        private final VisitedGraphResults initialVisitedGraph;
        private final VisitedArtifactSet initialVisitedArtifacts;

        public FinalizeTransformDependenciesFromSelectedArtifacts(
            ComponentIdentifier componentIdentifier,
            ImmutableCapabilities capabilities,
            ImmutableAttributes fromAttributes,
            VisitedGraphResults initialVisitedGraph,
            VisitedArtifactSet initialVisitedArtifacts
        ) {
            this.componentIdentifier = componentIdentifier;
            this.capabilities = capabilities;
            this.fromAttributes = fromAttributes;
            this.initialVisitedGraph = initialVisitedGraph;
            this.initialVisitedArtifacts = initialVisitedArtifacts;
        }

        @Override
        public FileCollectionInternal selectedArtifacts() {
            return getCompleteTransformDependencies(componentIdentifier, capabilities, fromAttributes);
        }

        @Override
        public boolean usesMutableProjectState() {
            return owner.getProject() != null;
        }

        @Override
        public ProjectInternal getOwningProject() {
            return owner.getProject();
        }

        @Nullable
        @Override
        public WorkNodeAction getPreExecutionAction() {
            // TODO: If the initial visited graph/artifacts are from a complete resolution,
            // we do not need to finalize the transform dependencies here, as our dependencies are
            // already derived from a complete graph.

            // Before resolving, need to determine the full set of upstream dependencies that need to be built.
            // The full set is usually known when the work graph is built. However, in certain cases where a project dependency conflicts with an external dependency, this is not known
            // until the full graph resolution, which can happen at execution time.
            return new CalculateFinalDependencies();
        }

        @Override
        public void visitDependencies(TaskDependencyResolveContext context) {
            // If the initial visited graph/artifacts are derived from a partial graph resolution,
            // these dependencies will only represent an approximate set of build dependencies.
            context.add(selectDependencyArtifacts(
                componentIdentifier,
                capabilities,
                fromAttributes,
                initialVisitedGraph,
                initialVisitedArtifacts
            ));
        }

        public class CalculateFinalDependencies implements PostExecutionNodeAwareActionNode {
            final List<TaskNode> tasks = new ArrayList<>();

            @Override
            public boolean usesMutableProjectState() {
                return FinalizeTransformDependenciesFromSelectedArtifacts.this.usesMutableProjectState();
            }

            @Nullable
            @Override
            public Project getOwningProject() {
                return FinalizeTransformDependenciesFromSelectedArtifacts.this.getOwningProject();
            }

            @Override
            public void run(NodeExecutionContext context) {
                TaskNodeFactory taskNodeFactory = context.getService(TaskNodeFactory.class);
                selectedArtifacts().visitDependencies(new CollectingTaskDependencyResolveContext(tasks, taskNodeFactory));
            }

            @Override
            public List<TaskNode> getPostExecutionNodes() {
                return tasks;
            }
        }
    }

    private static class CollectingTaskDependencyResolveContext implements TaskDependencyResolveContext {
        private final TaskNodeFactory taskNodeFactory;
        private final Collection<TaskNode> tasks;

        public CollectingTaskDependencyResolveContext(Collection<TaskNode> tasks, TaskNodeFactory taskNodeFactory) {
            this.tasks = tasks;
            this.taskNodeFactory = taskNodeFactory;
        }

        @Override
        public void add(Object dependency) {
            if (dependency instanceof Task) {
                tasks.add(taskNodeFactory.getNode((Task) dependency));
            }
        }

        @Override
        public void visitFailure(Throwable failure) {
        }

        @Nullable
        @Override
        public Task getTask() {
            return null;
        }
    }

    private class TransformUpstreamDependenciesImpl implements TransformUpstreamDependencies {
        private final ComponentIdentifier componentIdentifier;
        private final ImmutableCapabilities capabilities;
        private final ConfigurationIdentity configurationIdentity;
        private final CalculatedValueContainer<TransformDependencies, FinalizeTransformDependencies> transformDependencies;
        private final ImmutableAttributes fromAttributes;

        public TransformUpstreamDependenciesImpl(
            ComponentIdentifier componentIdentifier,
            ImmutableCapabilities capabilities,
            @Nullable ConfigurationIdentity configurationIdentity,
            TransformStep transformStep,
            CalculatedValueContainerFactory calculatedValueContainerFactory,
            VisitedGraphResults initialVisitedGraph,
            VisitedArtifactSet initialVisitedArtifacts
        ) {
            this.componentIdentifier = componentIdentifier;
            this.capabilities = capabilities;
            this.configurationIdentity = configurationIdentity;
            this.fromAttributes = transformStep.getFromAttributes();
            this.transformDependencies = calculatedValueContainerFactory.create(Describables.of("dependencies for", componentIdentifier, fromAttributes),
                new FinalizeTransformDependenciesFromSelectedArtifacts(componentIdentifier, capabilities, transformStep.getFromAttributes(), initialVisitedGraph, initialVisitedArtifacts));
        }

        @Nullable
        @Override
        public ConfigurationIdentity getConfigurationIdentity() {
            return configurationIdentity;
        }

        @Override
        public FileCollection selectedArtifacts() {
            return getCompleteTransformDependencies(componentIdentifier, capabilities, fromAttributes);
        }

        @Override
        public Try<TransformDependencies> computeArtifacts() {
            return transformDependencies.getValue();
        }

        @Override
        public void visitDependencies(TaskDependencyResolveContext context) {
            context.add(transformDependencies);
        }

        @Override
        public void finalizeIfNotAlready() {
            transformDependencies.finalizeIfNotAlready();
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        DefaultTransformUpstreamDependenciesResolver that = (DefaultTransformUpstreamDependenciesResolver) o;
        return resolutionHost.equals(that.resolutionHost);
    }

    @Override
    public int hashCode() {
        return resolutionHost.hashCode();
    }
}
