/*
 * Copyright 2014 the original author or authors.
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

package org.gradle.model.internal.method;

import com.google.common.base.Function;
import com.google.common.collect.*;
import org.apache.commons.lang.builder.EqualsBuilder;
import org.apache.commons.lang.builder.HashCodeBuilder;
import org.gradle.api.GradleException;
import org.gradle.internal.Cast;
import org.gradle.internal.UncheckedException;
import org.gradle.model.internal.type.ModelType;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Comparator;

public class WeaklyTypeReferencingMethod<T, R> implements Comparable<WeaklyTypeReferencingMethod<T, ?>> {

    private final ModelType<T> declaringType;
    private final ModelType<R> returnType;
    private final String name;
    private final ImmutableList<ModelType<?>> paramTypes;
    private final int modifiers;

    private WeaklyTypeReferencingMethod(ModelType<T> declaringType, ModelType<R> returnType, Method method) {
        if (declaringType.getRawClass() != method.getDeclaringClass()) {
            throw new IllegalArgumentException("Unexpected target class.");
        }
        this.declaringType = declaringType;
        this.returnType = returnType;
        this.name = method.getName();
        paramTypes = ImmutableList.copyOf(Iterables.transform(Arrays.asList(method.getGenericParameterTypes()), new Function<Type, ModelType<?>>() {
            public ModelType<?> apply(Type type) {
                return ModelType.of(type);
            }
        }));
        modifiers = method.getModifiers();
    }

    public static <T, R> WeaklyTypeReferencingMethod<T, R> of(ModelType<T> target, ModelType<R> returnType, Method method) {
        return new WeaklyTypeReferencingMethod<T, R>(target, returnType, method);
    }

    public ModelType<T> getDeclaringType() {
        return declaringType;
    }

    public ModelType<R> getReturnType() {
        return returnType;
    }

    public String getName() {
        return name;
    }

    public int getModifiers() {
        return modifiers;
    }

    public Annotation[] getAnnotations() {
        //we could retrieve annotations at construction time and hold references to them but unfortunately
        //in IBM JDK strong references are held from annotation instance to class in which it is used so we have to reflect
        return getMethod().getAnnotations();
    }

    public Type[] getGenericParameterTypes() {
        return Iterables.toArray(Iterables.transform(paramTypes, new Function<ModelType<?>, Type>() {
            public Type apply(ModelType<?> modelType) {
                return modelType.getType();
            }
        }), Type.class);
    }

    public R invoke(T target, Object... args) {
        Method method = getMethod();
        method.setAccessible(true);
        try {
            Object result = method.invoke(target, args);
            return returnType.getConcreteClass().cast(result);
        } catch (InvocationTargetException e) {
            throw UncheckedException.throwAsUncheckedException(e.getCause());
        } catch (Exception e) {
            throw new GradleException(String.format("Could not call %s.%s() on %s", method.getDeclaringClass().getSimpleName(), method.getName(), target), e);
        }
    }

    public Method getMethod() {
        Class<?>[] paramTypesArray = Iterables.toArray(Iterables.transform(paramTypes, new Function<ModelType<?>, Class<?>>() {
            public Class<?> apply(ModelType<?> modelType) {
                return modelType.getRawClass();
            }
        }), Class.class);
        try {
            return declaringType.getRawClass().getDeclaredMethod(name, paramTypesArray);
        } catch (NoSuchMethodException e) {
            throw UncheckedException.throwAsUncheckedException(e);
        }
    }

    @Override
    public int hashCode() {
        return new HashCodeBuilder()
                .append(declaringType)
                .append(returnType)
                .append(name)
                .append(paramTypes)
                .toHashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof WeaklyTypeReferencingMethod)) {
            return false;
        }

        WeaklyTypeReferencingMethod<?, ?> other = Cast.uncheckedCast(obj);

        return new EqualsBuilder()
                .append(declaringType, other.declaringType)
                .append(returnType, other.returnType)
                .append(name, other.name)
                .append(paramTypes, other.paramTypes)
                .isEquals();
    }

    @Override
    public int compareTo(WeaklyTypeReferencingMethod<T, ?> o) {
        Method m1 = getMethod();
        Method m2 = o.getMethod();
        return ComparisonChain.start()
            .compare(m1.getName(), m2.getName())
            .compare(m1.getReturnType().getName(), m2.getReturnType().getName())
            .compare(m1.getParameterTypes(), m2.getParameterTypes(), new Comparator<Class<?>[]>() {
                @Override
                public int compare(Class<?>[] o1, Class<?>[] o2) {
                    int result = 0;
                    UnmodifiableIterator<Class<?>> i1 = Iterators.forArray(o1);
                    UnmodifiableIterator<Class<?>> i2 = Iterators.forArray(o2);
                    while (i1.hasNext() && i2.hasNext()) {
                        result = i1.next().getName().compareTo(i2.next().getName());
                        if (result != 0) {
                            break;
                        }
                    }
                    if (result == 0) {
                        if (i1.hasNext()) {
                            result = 1;
                        } else {
                            result = -1;
                        }
                    }
                    return result;
                }
            })
            .result();
    }
}
