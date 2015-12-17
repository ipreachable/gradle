/*
 * Copyright 2015 the original author or authors.
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

package org.gradle.model.internal.manage.schema;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import org.gradle.model.internal.manage.schema.extract.ModelSchemaAspect;
import org.gradle.model.internal.method.WeaklyTypeReferencingMethod;
import org.gradle.model.internal.type.ModelType;

import java.util.Set;

public class NewStructSchema<T> extends AbstractModelSchema<T> {
    private final Set<NewModelProperty<?>> properties;
    private final Set<WeaklyTypeReferencingMethod<?, ?>> nonPropertyMethods;
    private final Set<? extends ModelSchemaAspect> aspects;
    // TODO:LPTR Do we need this?
    private final boolean annotated;

    public NewStructSchema(ModelType<T> type, Iterable<NewModelProperty<?>> properties, Iterable<WeaklyTypeReferencingMethod<?, ?>> nonPropertyMethods, Iterable<? extends ModelSchemaAspect> aspects, boolean annotated) {
        super(type);
        this.properties = ImmutableSortedSet.copyOf(properties);
        this.nonPropertyMethods = ImmutableSortedSet.copyOf(nonPropertyMethods);
        this.aspects = ImmutableSet.copyOf(aspects);
        this.annotated = annotated;
    }

    public Set<NewModelProperty<?>> getProperties() {
        return properties;
    }

    public Set<WeaklyTypeReferencingMethod<?, ?>> getNonPropertyMethods() {
        return nonPropertyMethods;
    }

    public Set<WeaklyTypeReferencingMethod<?, ?>> getAllMethods() {
        ImmutableSortedSet.Builder<WeaklyTypeReferencingMethod<?, ?>> builder = ImmutableSortedSet.naturalOrder();
        for (NewModelProperty<?> property : properties) {
            builder.addAll(property.getAccessorMethods());
        }
        builder.addAll(nonPropertyMethods);
        return builder.build();
    }

    public Set<? extends ModelSchemaAspect> getAspects() {
        return aspects;
    }

    public boolean isAnnotated() {
        return annotated;
    }
}
