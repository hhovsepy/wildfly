/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2012, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.as.controller.transform;

import static org.jboss.as.controller.ControllerMessages.MESSAGES;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP_ADDR;

import java.util.Iterator;
import java.util.Set;

import org.jboss.as.controller.ExpressionResolver;
import org.jboss.as.controller.OperationContext;
import org.jboss.as.controller.OperationFailedException;
import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.PathElement;
import org.jboss.as.controller.ProcessType;
import org.jboss.as.controller.RunningMode;
import org.jboss.as.controller.registry.ImmutableManagementResourceRegistration;
import org.jboss.as.controller.registry.Resource;
import org.jboss.dmr.ModelNode;

/**
 * @author Emanuel Muckenhuber
 */
class ResourceTransformationContextImpl implements ResourceTransformationContext {

    private final Resource root;
    private final PathAddress current;
    private final PathAddress read;
    private final OriginalModel originalModel;
    private final TransformersLogger logger;
    private final boolean skipRuntimeIgnoreCheck;

    static ResourceTransformationContext create(final OperationContext context, final TransformationTarget target, final boolean skipRuntimeIgnoreCheck) {
        return create(context, target, PathAddress.EMPTY_ADDRESS, PathAddress.EMPTY_ADDRESS, skipRuntimeIgnoreCheck);
    }

    static ResourceTransformationContext create(final OperationContext context, final TransformationTarget target, final PathAddress current, final PathAddress read, boolean skipRuntimeIgnoreCheck) {
        final Resource root = Resource.Factory.create();
        final Resource original = context.readResourceFromRoot(PathAddress.EMPTY_ADDRESS, true);
        final ImmutableManagementResourceRegistration registration = context.getRootResourceRegistration().getSubModel(PathAddress.EMPTY_ADDRESS);
        final ExpressionResolver expressionResolver = TransformerExpressionResolver.create(context, target.getTargetType());
        final OriginalModel originalModel = new OriginalModel(original, context.getRunningMode(), context.getProcessType(), target, registration, expressionResolver);
        return new ResourceTransformationContextImpl(root, current, read, originalModel, skipRuntimeIgnoreCheck);
    }

    static ResourceTransformationContext create(TransformationTarget target, Resource model, ImmutableManagementResourceRegistration registration, ExpressionResolver resolver, RunningMode runningMode, ProcessType type, boolean skipRuntimeIgnoreCheck) {
        final Resource root = Resource.Factory.create();
        final OriginalModel originalModel = new OriginalModel(model, runningMode, type, target, registration, resolver);
        return new ResourceTransformationContextImpl(root, PathAddress.EMPTY_ADDRESS, originalModel, skipRuntimeIgnoreCheck);
    }

    ResourceTransformationContextImpl(Resource root, PathAddress address, final OriginalModel originalModel, final boolean skipRuntimeIgnoreCheck) {
        this(root, address, address, originalModel, skipRuntimeIgnoreCheck);
    }

    ResourceTransformationContextImpl(Resource root, PathAddress address, PathAddress read, final OriginalModel originalModel, final boolean skipRuntimeIgnoreCheck) {
        this.root = root;
        this.current = address;
        this.read = read;
        this.originalModel = originalModel;
        this.logger = TransformersLogger.getLogger(originalModel.target);
        this.skipRuntimeIgnoreCheck = skipRuntimeIgnoreCheck;
    }

    public Resource createResource(final PathAddress element) {
        final PathAddress absoluteAddress = this.current.append(element);
        final PathAddress readAddress = this.read.append(element);
        final Resource resource = Resource.Factory.create();
        addTransformedRecursiveResourceFromRoot(absoluteAddress, readAddress, resource);
        return resource;
    }

    public Resource createResource(final PathAddress element, Resource copy) {
        final PathAddress absoluteAddress = this.current.append(element);
        final PathAddress readAddress = this.read.append(element);
        final Resource resource = Resource.Factory.create();
        resource.writeModel(copy.getModel());
        addTransformedRecursiveResourceFromRoot(absoluteAddress, readAddress, resource);
        return resource;
    }

    @Override
    public ResourceTransformationContext addTransformedResource(PathAddress address, Resource toAdd) {
        final PathAddress absoluteAddress = this.current.append(address);
        final PathAddress read = this.read.append(address);
        return addTransformedResourceFromRoot(absoluteAddress, read, toAdd);
    }

    @Override
    public ResourceTransformationContext addTransformedResourceFromRoot(PathAddress absoluteAddress, Resource toAdd) {
        return addTransformedResourceFromRoot(absoluteAddress, absoluteAddress, toAdd);
    }

    public ResourceTransformationContext addTransformedResourceFromRoot(PathAddress absoluteAddress, PathAddress read, Resource toAdd) {
        // Only keep the mode, drop all children
        final Resource copy = Resource.Factory.create();
        if (toAdd != null) {
            copy.writeModel(toAdd.getModel());
        }
        return addTransformedRecursiveResourceFromRoot(absoluteAddress, read, copy);
    }

    @Override
    public void addTransformedRecursiveResource(PathAddress relativeAddress, Resource resource) {
        final PathAddress absoluteAddress = this.current.append(relativeAddress);
        final PathAddress readAddress = this.read.append(relativeAddress);
        addTransformedRecursiveResourceFromRoot(absoluteAddress, readAddress, resource);
    }

    private ResourceTransformationContext addTransformedRecursiveResourceFromRoot(final PathAddress absoluteAddress, final PathAddress read, final Resource toAdd) {
        Resource model = this.root;
        if (absoluteAddress.size() > 0) {
            final Iterator<PathElement> i = absoluteAddress.iterator();
            while (i.hasNext()) {
                final PathElement element = i.next();
                if (element.isMultiTarget()) {
                    throw MESSAGES.cannotWriteTo("*");
                }
                if (!i.hasNext()) {
                    if (model.hasChild(element)) {
                        throw MESSAGES.duplicateResourceAddress(absoluteAddress);
                    } else {
                        model.registerChild(element, toAdd);
                        model = toAdd;
                    }
                } else {
                    model = model.getChild(element);
                    if (model == null) {
                        PathAddress ancestor = PathAddress.EMPTY_ADDRESS;
                        for (PathElement pe : absoluteAddress) {
                            ancestor = ancestor.append(pe);
                            if (element.equals(pe)) {
                                break;
                            }
                        }
                        throw MESSAGES.resourceNotFound(ancestor, absoluteAddress);
                    }
                }
            }
        } else {
            //If this was the root address, replace the resource model
            model.writeModel(toAdd.getModel());
        }
        return new ResourceTransformationContextImpl(root, absoluteAddress, read, originalModel, skipRuntimeIgnoreCheck);
    }

    @Override
    public Resource readTransformedResource(final PathAddress relativeAddress) {
        final PathAddress address = this.current.append(relativeAddress);
        return Resource.Tools.navigate(root, address);
    }

    public TransformerEntry resolveTransformerEntry(PathAddress address) {
        final TransformerEntry entry = originalModel.target.getTransformerEntry(this, address);
        if (entry == null) {
            return TransformerEntry.ALL_DEFAULTS;
        }
        return entry;
    }

    @Override
    public ResourceTransformer resolveTransformer(PathAddress address) {
        final ResourceTransformer transformer = originalModel.target.resolveTransformer(this, address);
        if (transformer == null) {
            final ImmutableManagementResourceRegistration childReg = originalModel.getRegistration(address);
            if (childReg == null) {
                return ResourceTransformer.DISCARD;
            }
            if (childReg.isRemote() || childReg.isRuntimeOnly()) {
                return ResourceTransformer.DISCARD;
            }
            return ResourceTransformer.DEFAULT;
        }
        return transformer;
    }

    protected ResourceTransformer resolveTransformer(TransformerEntry entry, PathAddress address) {
        final ResourceTransformer transformer = entry.getResourceTransformer();
        if (transformer == null) {
            final ImmutableManagementResourceRegistration childReg = originalModel.getRegistration(address);
            if (childReg == null) {
                return ResourceTransformer.DISCARD;
            }
            if (childReg.isRemote() || childReg.isRuntimeOnly()) {
                return ResourceTransformer.DISCARD;
            }
            return ResourceTransformer.DEFAULT;
        }
        return transformer;
    }

    @Override
    public void processChildren(final Resource resource) throws OperationFailedException {
        final Set<String> types = resource.getChildTypes();
        for (final String type : types) {
            for (final Resource.ResourceEntry child : resource.getChildren(type)) {
                processChild(child.getPathElement(), child);
            }
        }
    }

    @Override
    public void processChild(final PathElement element, Resource child) throws OperationFailedException {
        final PathAddress childAddress = read.append(element); // read
        final TransformerEntry entry = resolveTransformerEntry(childAddress);
        final PathAddressTransformer path = entry.getPathTransformation();
        final PathAddress currentAddress = path.transform(element, new PathAddressTransformer.Builder() { // write
            @Override
            public PathAddress getOriginal() {
                return childAddress;
            }

            @Override
            public PathAddress getCurrent() {
                return current;
            }

            @Override
            public PathAddress getRemaining() {
                return PathAddress.EMPTY_ADDRESS.append(element);
            }

            @Override
            public PathAddress next(PathElement... elements) {
                return current.append(elements);
            }
        });
        final ResourceTransformer transformer = resolveTransformer(entry, childAddress);
        final ResourceTransformationContext childContext = new ResourceTransformationContextImpl(root, currentAddress, childAddress, originalModel, skipRuntimeIgnoreCheck);
        transformer.transformResource(childContext, currentAddress, child);
    }

    @Override
    public TransformationTarget getTarget() {
        return originalModel.target;
    }

    @Override
    public ProcessType getProcessType() {
        return originalModel.type;
    }

    @Override
    public RunningMode getRunningMode() {
        return originalModel.mode;
    }

    @Override
    public ImmutableManagementResourceRegistration getResourceRegistration(PathAddress address) {
        final PathAddress a = read.append(address);
        return originalModel.getRegistration(a);
    }

    @Override
    public ImmutableManagementResourceRegistration getResourceRegistrationFromRoot(PathAddress address) {
        return originalModel.getRegistration(address);
    }

    @Override
    public Resource readResource(PathAddress address) {
        final PathAddress a = read.append(address);
        return originalModel.get(a);
    }

    @Override
    public Resource readResourceFromRoot(PathAddress address) {
        return originalModel.get(address);
    }

    @Override
    public ModelNode resolveExpressions(final ModelNode node) throws OperationFailedException {
        return originalModel.expressionResolver.resolveExpressions(node);
    }

    @Override
    public Resource getTransformedRoot() {
        return root;
    }

    @Deprecated
    static ResourceTransformationContext createAliasContext(final PathAddress address, final ResourceTransformationContext context) {
        if (context instanceof ResourceTransformationContextImpl) {
            final ResourceTransformationContextImpl impl = (ResourceTransformationContextImpl) context;
            return new ResourceTransformationContextImpl(impl.root, address, impl.read, impl.originalModel, context.isSkipRuntimeIgnoreCheck());
        } else {
            throw new IllegalArgumentException("wrong context type");
        }
    }

    @Deprecated
    static TransformationContext wrapForOperation(TransformationContext context, ModelNode operation) {
        if(context instanceof ResourceTransformationContextImpl) {
            final ResourceTransformationContextImpl impl = (ResourceTransformationContextImpl) context;
            return new ResourceTransformationContextImpl(impl.root, PathAddress.pathAddress(operation.get(OP_ADDR)), impl.originalModel, context.isSkipRuntimeIgnoreCheck());
        } else {
            return context;
        }
    }

    @Override
    public TransformersLogger getLogger() {
        return logger;
    }


    @Override
    public boolean isSkipRuntimeIgnoreCheck() {
        return skipRuntimeIgnoreCheck;
    }

    static class OriginalModel {

        private final Resource original;
        private final RunningMode mode;
        private final ProcessType type;
        private final TransformationTarget target;
        private final ImmutableManagementResourceRegistration registration;
        private final ExpressionResolver expressionResolver;

        OriginalModel(Resource original, RunningMode mode, ProcessType type, TransformationTarget target, ImmutableManagementResourceRegistration registration, ExpressionResolver expressionResolver) {
            this.original = original;
            this.mode = mode;
            this.type = type;
            this.target = target;
            this.registration = registration;
            this.expressionResolver = expressionResolver;
        }

        Resource get(final PathAddress address) {
            return original.navigate(address);
        }

        ImmutableManagementResourceRegistration getRegistration(PathAddress address) {
            return registration.getSubModel(address);
        }

    }
}
