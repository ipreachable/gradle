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

import com.google.common.base.Objects;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import net.jcip.annotations.ThreadSafe;
import org.gradle.internal.Cast;
import org.gradle.model.internal.manage.schema.extract.PropertyAccessorRole;
import org.gradle.model.internal.method.WeaklyTypeReferencingMethod;
import org.gradle.model.internal.type.ModelType;

import java.util.Map;
import java.util.Set;

import static org.gradle.model.internal.manage.schema.extract.PropertyAccessorRole.*;

@ThreadSafe
public class NewModelProperty<T> implements Comparable<NewModelProperty<?>> {

    private final String name;
    private final ModelType<T> type;
    private final Set<ModelType<?>> declaredBy;
    private final ImmutableMap<PropertyAccessorRole, WeaklyTypeReferencingMethod<?, ?>> accessors;
    private final boolean declaredAsHavingUnmanagedType;
    private ModelSchema<T> schema;

    public NewModelProperty(ModelType<T> type, String name, Set<ModelType<?>> declaredBy,
                            Map<PropertyAccessorRole, WeaklyTypeReferencingMethod<?, ?>> accessors,
                            boolean declaredAsHavingUnmanagedType) {
        this.name = name;
        this.type = type;
        this.declaredBy = ImmutableSet.copyOf(declaredBy);
        this.accessors = Maps.immutableEnumMap(accessors);
        this.declaredAsHavingUnmanagedType = declaredAsHavingUnmanagedType;
    }

    public String getName() {
        return name;
    }

    public ModelType<T> getType() {
        return type;
    }

    public ModelSchema<T> getSchema() {
        return schema;
    }

    public void setSchema(ModelSchema<T> schema) {
        this.schema = schema;
    }

    public boolean isReadable() {
        return accessors.containsKey(IS_GETTER) || accessors.containsKey(GET_GETTER);
    }

    public boolean isWritable() {
        return accessors.containsKey(SETTER);
    }

    public Set<ModelType<?>> getDeclaredBy() {
        return declaredBy;
    }

    public WeaklyTypeReferencingMethod<?, ?> getAccessor(PropertyAccessorRole role) {
        return accessors.get(role);
    }

    public <I> T getPropertyValue(I instance) {
        WeaklyTypeReferencingMethod<?, ?> getter = accessors.get(GET_GETTER);
        if (getter == null) {
            getter = accessors.get(IS_GETTER);
        }
        if (getter == null) {
            throw new IllegalStateException(String.format("Property %s is read only", this));
        }
        return Cast.<WeaklyTypeReferencingMethod<I, T>>uncheckedCast(getter).invoke(instance);
    }

    public Iterable<WeaklyTypeReferencingMethod<?, ?>> getAccessorMethods() {
        return accessors.values();
    }

    public boolean isDeclaredAsHavingUnmanagedType() {
        return declaredAsHavingUnmanagedType;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        NewModelProperty<?> that = (NewModelProperty<?>) o;

        return Objects.equal(this.name, that.name)
            && Objects.equal(this.type, that.type)
            && this.declaredAsHavingUnmanagedType == that.declaredAsHavingUnmanagedType
            && isWritable() == that.isWritable();
    }

    @Override
    public int hashCode() {
        int result = name.hashCode();
        result = 31 * result + type.hashCode();
        result = 31 * result + Boolean.valueOf(isWritable()).hashCode();
        result = 31 * result + Boolean.valueOf(declaredAsHavingUnmanagedType).hashCode();
        return result;
    }

    @Override
    public String toString() {
        return String.format("%s(%s)", getName(), getType().getDisplayName());
    }

    @Override
    public int compareTo(NewModelProperty<?> o) {
        return name.compareTo(o.getName());
    }
}
