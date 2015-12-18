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

package org.gradle.model.internal.manage.schema.extract;

import com.google.common.collect.*;
import org.gradle.api.Nullable;
import org.gradle.model.Unmanaged;
import org.gradle.model.internal.type.ModelType;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

public class ModelPropertyExtractionContext {

    private final String propertyName;
    private Map<PropertyAccessorRole, PropertyAccessorExtractionContext> accessors;

    public ModelPropertyExtractionContext(String propertyName) {
        this.propertyName = propertyName;
        this.accessors = Maps.newEnumMap(PropertyAccessorRole.class);
    }

    public String getPropertyName() {
        return propertyName;
    }

    public void addAccessor(PropertyAccessorExtractionContext accessor) {
        PropertyAccessorRole role = accessor.getRole();
        // TODO:LPTR What happens when the property has multiple accessors in the same role but with different type?
//        if (accessors.containsKey(role)) {
//            throw new IllegalStateException("Accessor already registered: " + role + " " + accessor);
//        }
        accessors.put(role, accessor);
    }

    @Nullable
    public PropertyAccessorExtractionContext getAccessor(PropertyAccessorRole type) {
        return accessors.get(type);
    }

    public Collection<PropertyAccessorExtractionContext> getAccessors() {
        return accessors.values();
    }

    public void dropInvalidAccessor(PropertyAccessorRole type, ImmutableCollection.Builder<Method> droppedMethods) {
        PropertyAccessorExtractionContext removedAccessor = accessors.remove(type);
        if (removedAccessor != null) {
            droppedMethods.add(removedAccessor.getMostSpecificDeclaration());
        }
    }

    public Set<ModelType<?>> getDeclaredBy() {
        ImmutableSortedSet.Builder<ModelType<?>> declaredBy = new ImmutableSortedSet.Builder<ModelType<?>>(Ordering.usingToString());
        for (PropertyAccessorExtractionContext accessor : accessors.values()) {
            for (Method method : accessor.getDeclaringMethods()) {
                declaredBy.add(ModelType.declaringType(method));
            }
        }
        return declaredBy.build();
    }

    public boolean isDeclaredAsUnmanaged() {
        return isDeclaredAsUnmanaged(getAccessor(PropertyAccessorRole.GET_GETTER))
            || isDeclaredAsUnmanaged(getAccessor(PropertyAccessorRole.IS_GETTER));
    }

    private boolean isDeclaredAsUnmanaged(PropertyAccessorExtractionContext accessor) {
        if (accessor == null) {
            return false;
        }
        for (Method method : accessor.getDeclaringMethods()) {
            if (method.isAnnotationPresent(Unmanaged.class)) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    public PropertyAccessorExtractionContext mergeGetters() {
        PropertyAccessorExtractionContext getGetter = getAccessor(PropertyAccessorRole.GET_GETTER);
        PropertyAccessorExtractionContext isGetter = getAccessor(PropertyAccessorRole.IS_GETTER);
        if (getGetter == null && isGetter == null) {
            return null;
        }
        Iterable<Method> getMethods = getGetter != null ? getGetter.getDeclaringMethods() : Collections.<Method>emptyList();
        Iterable<Method> isMethods = isGetter != null ? isGetter.getDeclaringMethods() : Collections.<Method>emptyList();
        return new PropertyAccessorExtractionContext(PropertyAccessorRole.GET_GETTER, Iterables.concat(getMethods, isMethods));
    }
}
