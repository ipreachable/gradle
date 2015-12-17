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
import com.google.common.collect.ImmutableSet;
import net.jcip.annotations.ThreadSafe;
import org.gradle.api.Nullable;
import org.gradle.internal.Cast;
import org.gradle.model.internal.method.WeaklyTypeReferencingMethod;
import org.gradle.model.internal.type.ModelType;

import java.util.Set;

@ThreadSafe
public class NewModelProperty<T> implements Comparable<NewModelProperty<?>> {

    private final String name;
    private final ModelType<T> type;
    private final Set<ModelType<?>> declaredBy;
    private final WeaklyTypeReferencingMethod<?, T> getter;
    private final WeaklyTypeReferencingMethod<?, Void> setter;
    private final boolean declaredAsHavingUnmanagedType;
    private ModelSchema<T> schema;

    public NewModelProperty(ModelType<T> type, String name, Set<ModelType<?>> declaredBy,
                            @Nullable WeaklyTypeReferencingMethod<?, T> getter, @Nullable WeaklyTypeReferencingMethod<?, Void> setter,
                            boolean declaredAsHavingUnmanagedType) {
        this.name = name;
        this.type = type;
        this.declaredBy = ImmutableSet.copyOf(declaredBy);
        this.getter = getter;
        this.setter = setter;
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
        return getter != null;
    }

    public boolean isWritable() {
        return setter != null;
    }

    public Set<ModelType<?>> getDeclaredBy() {
        return declaredBy;
    }

    public WeaklyTypeReferencingMethod<?, T> getGetter() {
        return getter;
    }

    public WeaklyTypeReferencingMethod<?, Void> getSetter() {
        return setter;
    }

    public <I> T getPropertyValue(I instance) {
        if (getter == null) {
            throw new IllegalStateException(String.format("Property %s is read only", this));
        }
        return Cast.<WeaklyTypeReferencingMethod<I, T>>uncheckedCast(getter).invoke(instance);
    }

    public Iterable<WeaklyTypeReferencingMethod<?, ?>> getAccessorMethods() {
        ImmutableSet.Builder<WeaklyTypeReferencingMethod<?, ?>> builder = ImmutableSet.builder();
        if (getter != null) {
            builder.add(Cast.<WeaklyTypeReferencingMethod<?, ?>>uncheckedCast(getter));
        }
        if (setter != null) {
            builder.add(Cast.<WeaklyTypeReferencingMethod<?, ?>>uncheckedCast(setter));
        }
        return builder.build();
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
