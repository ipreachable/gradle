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

package org.gradle.model.internal.manage.instance;

import com.google.common.base.Equivalence;
import com.google.common.base.Equivalence.Wrapper;
import com.google.common.base.Function;
import com.google.common.base.Objects;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.*;
import org.gradle.api.Nullable;
import org.gradle.internal.UncheckedException;
import org.gradle.internal.reflect.MethodSignatureEquivalence;
import org.gradle.model.internal.manage.schema.ModelSchema;
import org.gradle.model.internal.manage.schema.ModelSchemaStore;
import org.gradle.model.internal.manage.schema.NewStructSchema;
import org.gradle.model.internal.manage.schema.extract.PropertyAccessorRole;
import org.gradle.model.internal.method.WeaklyTypeReferencingMethod;
import org.gradle.model.internal.type.ModelType;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.ExecutionException;

public class ManagedStructBindingStore {
    private static final Equivalence<Method> METHOD_EQUIVALENCE = new MethodSignatureEquivalence();

    private final ModelSchemaStore schemaStore;

    private final LoadingCache<CacheKey, ManagedStructBinding> bindings = CacheBuilder.newBuilder()
        .weakValues()
        .build(new CacheLoader<CacheKey, ManagedStructBinding>() {
            @Override
            public ManagedStructBinding load(CacheKey key) throws Exception {
                return extract(key.viewTypes, key.delegateType);
            }
        });

    public ManagedStructBindingStore(ModelSchemaStore schemaStore) {
        this.schemaStore = schemaStore;
    }

    public ManagedStructBinding getBinding(ModelType<?> publicType) {
        return getBinding(publicType, Collections.<ModelType<?>>emptySet(), null);
    }

    public ManagedStructBinding getBinding(ModelType<?> publicType, Iterable<? extends ModelType<?>> viewTypes, ModelType<?> delegateType) {
        try {
            ImmutableSortedSet.Builder<ModelType<?>> views = new ImmutableSortedSet.Builder<ModelType<?>>(Ordering.usingToString());
            views.add(publicType);
            views.addAll(viewTypes);
            return bindings.get(new CacheKey(views.build(), delegateType));
        } catch (ExecutionException e) {
            throw UncheckedException.throwAsUncheckedException(e);
        }
    }

    ManagedStructBinding extract(Iterable<? extends ModelType<?>> viewTypes, ModelType<?> delegateType) {
        if (delegateType != null && Modifier.isAbstract(delegateType.getConcreteClass().getModifiers())) {
            throw new IllegalArgumentException(String.format("Delegate '%s' type must be null or a non-abstract type", delegateType));
        }

        // TODO:LPTR Validate view types have no fields

        ImmutableSet.Builder<WeaklyTypeReferencingMethod<?, ?>> viewMethodsBuilder = ImmutableSet.builder();
        ImmutableSet.Builder<NewStructSchema<?>> viewSchemasBuilder = ImmutableSet.builder();
        for (ModelType<?> viewType : viewTypes) {
            ModelSchema<?> viewSchema = schemaStore.getSchema(viewType);
            if (!(viewSchema instanceof NewStructSchema)) {
                throw new IllegalArgumentException(String.format("View type '%s' is not a struct", viewType));
            }
            NewStructSchema<?> structViewSchema = (NewStructSchema<?>) viewSchema;
            viewSchemasBuilder.add(structViewSchema);
            viewMethodsBuilder.addAll(structViewSchema.getAllMethods());
        }
        Set<WeaklyTypeReferencingMethod<?, ?>> viewMethods = viewMethodsBuilder.build();
        Map<Wrapper<Method>, WeaklyTypeReferencingMethod<?, ?>> delegateMethods;
        NewStructSchema<?> delegateStructSchema;
        if (delegateType == null) {
            delegateStructSchema = null;
            delegateMethods = Collections.emptyMap();
        } else {
            ModelSchema<?> delegateSchema = schemaStore.getSchema(delegateType);
            if (!(delegateSchema instanceof NewStructSchema)) {
                throw new IllegalArgumentException(String.format("Delegate type '%s' is not a struct", delegateType));
            }
            delegateStructSchema = (NewStructSchema<?>) delegateSchema;
            delegateMethods = Maps.uniqueIndex(delegateStructSchema.getAllMethods(), new Function<WeaklyTypeReferencingMethod<?, ?>, Wrapper<Method>>() {
                @Override
                public Wrapper<Method> apply(WeaklyTypeReferencingMethod<?, ?> weakMethod) {
                    return METHOD_EQUIVALENCE.wrap(weakMethod.getMethod());
                }
            });
        }

        Multimap<String, MethodBinding> propertyMethodBindings = ArrayListMultimap.create();
        ImmutableSet.Builder<WeaklyTypeReferencingMethod<?, ?>> viewBindingsBuilder = ImmutableSet.builder();
        ImmutableSet.Builder<DelegateMethodBinding> delegateBindingsBuilder = ImmutableSet.builder();

        for (WeaklyTypeReferencingMethod<?, ?> weakViewMethod : viewMethods) {
            Method viewMethod = weakViewMethod.getMethod();
            PropertyAccessorRole propertyRole = PropertyAccessorRole.of(viewMethod);
            WeaklyTypeReferencingMethod<?, ?> weakDelegateMethod = delegateMethods.get(METHOD_EQUIVALENCE.wrap(viewMethod));
            MethodBinding binding;
            if (!Modifier.isAbstract(viewMethod.getModifiers())) {
                if (weakDelegateMethod != null) {
                    throw new IllegalArgumentException(String.format("Method '%s' is both implemented by the view and the delegate type '%s'",
                        viewMethod.toGenericString(), weakDelegateMethod.getMethod().toGenericString()));
                }
                binding = new ViewMethodBinding(weakViewMethod);
                viewBindingsBuilder.add(weakViewMethod);
            } else if (weakDelegateMethod != null) {
                binding = new DelegateMethodBinding(weakViewMethod, weakDelegateMethod);
                delegateBindingsBuilder.add((DelegateMethodBinding) binding);
            } else if (propertyRole != null) {
                binding = new GeneratedPropertyMethodBinding(weakViewMethod, propertyRole);
            } else {
                // TODO:LPTR Maybe use delegateType = Object here?
                if (delegateType == null) {
                    throw new IllegalArgumentException(String.format("Method '%s' is not a property accessor, and it has no implementation",
                        viewMethod.toGenericString()));
                } else {
                    throw new IllegalArgumentException(String.format("Method '%s' is not a property accessor, and it has no implementation or a delegate in type '%s'",
                        viewMethod.toGenericString(), delegateType.getDisplayName()));
                }
            }
            if (propertyRole != null) {
                propertyMethodBindings.put(propertyRole.propertyNameFor(viewMethod), binding);
            }
        }

        ImmutableSortedMap.Builder<String, GeneratedProperty> generatedPropertiesBuilder = ImmutableSortedMap.naturalOrder();
        for (Map.Entry<String, Collection<MethodBinding>> entry : propertyMethodBindings.asMap().entrySet()) {
            String propertyName = entry.getKey();
            Collection<MethodBinding> bindings = entry.getValue();
            Iterator<MethodBinding> iBinding = bindings.iterator();
            Class<? extends MethodBinding> allBindingsType = iBinding.next().getClass();
            while (iBinding.hasNext()) {
                Class<? extends MethodBinding> bindingType = iBinding.next().getClass();
                if (!bindingType.equals(allBindingsType)) {
                    throw new IllegalArgumentException(String.format("The accessor methods belonging to property '%s' should either all have an implementation in the view," +
                        " be provided all by the default implementation, or they should all be without an implementation completely.",
                        propertyName));
                }
            }
            if (allBindingsType == GeneratedPropertyMethodBinding.class) {
                boolean foundGetter = false;
                boolean foundSetter = false;
                ImmutableMap.Builder<PropertyAccessorRole, WeaklyTypeReferencingMethod<?, ?>> accessorsBuilder = ImmutableMap.builder();
                ModelType<?> propertyType = null;
                for (MethodBinding binding : bindings) {
                    GeneratedPropertyMethodBinding propertyBinding = (GeneratedPropertyMethodBinding) binding;
                    PropertyAccessorRole role = propertyBinding.role;
                    switch (role) {
                        case GET_GETTER:
                        case IS_GETTER:
                            foundGetter = true;
                            break;
                        case SETTER:
                            foundSetter = true;
                            break;
                        default:
                            throw new AssertionError();
                    }
                    WeaklyTypeReferencingMethod<?, ?> accessor = propertyBinding.getSource();
                    accessorsBuilder.put(role, accessor);
                    if (propertyType == null) {
                        propertyType = role.propertyTypeFor(accessor.getMethod());
                    } else if (!propertyType.equals(role.propertyTypeFor(accessor.getMethod()))) {
                        throw new IllegalStateException(String.format("Managed property '%s' must have a consistent type.", propertyName));
                    }
                }
                if (foundSetter && !foundGetter) {
                    throw new IllegalArgumentException(String.format("Managed property '%s' must both have an abstract getter as well as a setter.", propertyName));
                }
                generatedPropertiesBuilder.put(propertyName, new GeneratedProperty(propertyName, propertyType, accessorsBuilder.build()));
            }
        }

        return new ManagedStructBinding(
            viewSchemasBuilder.build(),
            delegateStructSchema,
            generatedPropertiesBuilder.build(),
            viewBindingsBuilder.build(),
            delegateBindingsBuilder.build()
        );
    }

    public static class ManagedStructBinding {
        private final Set<NewStructSchema<?>> viewSchemas;
        private final NewStructSchema<?> delegateSchema;
        private final Map<String, GeneratedProperty> generatedProperties;
        private final Collection<WeaklyTypeReferencingMethod<?, ?>> viewBindings;
        private final Collection<DelegateMethodBinding> delegateBindings;

        protected ManagedStructBinding(
            Iterable<NewStructSchema<?>> viewSchemas,
            @Nullable NewStructSchema<?> delegateSchema,
            Map<String, GeneratedProperty> generatedProperties,
            Iterable<WeaklyTypeReferencingMethod<?, ?>> viewBindings,
            Iterable<DelegateMethodBinding> delegateBindings
        ) {
            this.viewSchemas = ImmutableSet.copyOf(viewSchemas);
            this.delegateSchema = delegateSchema;
            this.generatedProperties = ImmutableSortedMap.copyOf(generatedProperties, Ordering.natural());
            this.viewBindings = ImmutableSet.copyOf(viewBindings);
            this.delegateBindings = ImmutableSet.copyOf(delegateBindings);
        }

        public Set<NewStructSchema<?>> getViewSchemas() {
            return viewSchemas;
        }

        @Nullable
        public NewStructSchema<?> getDelegateSchema() {
            return delegateSchema;
        }

        public Map<String, GeneratedProperty> getGeneratedProperties() {
            return generatedProperties;
        }

        public Collection<WeaklyTypeReferencingMethod<?, ?>> getViewBindings() {
            return viewBindings;
        }

        public Collection<DelegateMethodBinding> getDelegateBindings() {
            return delegateBindings;
        }
    }

    public abstract static class MethodBinding {
        private final WeaklyTypeReferencingMethod<?, ?> source;

        public MethodBinding(WeaklyTypeReferencingMethod<?, ?> source) {
            this.source = source;
        }

        public WeaklyTypeReferencingMethod<?, ?> getSource() {
            return source;
        }
    }

    public static class ViewMethodBinding extends MethodBinding {
        public ViewMethodBinding(WeaklyTypeReferencingMethod<?, ?> source) {
            super(source);
        }
    }

    public static class DelegateMethodBinding extends MethodBinding {
        private final WeaklyTypeReferencingMethod<?, ?> target;

        public DelegateMethodBinding(WeaklyTypeReferencingMethod<?, ?> source, WeaklyTypeReferencingMethod<?, ?> target) {
            super(source);
            this.target = target;
        }

        public WeaklyTypeReferencingMethod<?, ?> getTarget() {
            return target;
        }
    }

    public static class GeneratedPropertyMethodBinding extends MethodBinding {
        private final PropertyAccessorRole role;

        public GeneratedPropertyMethodBinding(WeaklyTypeReferencingMethod<?, ?> source, PropertyAccessorRole role) {
            super(source);
            this.role = role;
        }
    }

    public static class GeneratedProperty {
        private final String name;
        private final ModelType<?> type;
        private final Map<PropertyAccessorRole, WeaklyTypeReferencingMethod<?, ?>> accessors;

        public GeneratedProperty(String name, ModelType<?> type, Map<PropertyAccessorRole, WeaklyTypeReferencingMethod<?, ?>> accessors) {
            this.name = name;
            this.type = type;
            this.accessors = accessors;
        }

        public String getName() {
            return name;
        }

        public ModelType<?> getType() {
            return type;
        }

        public Map<PropertyAccessorRole, WeaklyTypeReferencingMethod<?, ?>> getAccessors() {
            return accessors;
        }
    }

    private static class CacheKey {
        private final SortedSet<ModelType<?>> viewTypes;
        private final ModelType<?> delegateType;

        public CacheKey(Iterable<? extends ModelType<?>> viewTypes, ModelType<?> delegateType) {
            this.viewTypes = ImmutableSortedSet.copyOf(Ordering.usingToString(), viewTypes);
            this.delegateType = delegateType;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            CacheKey key = (CacheKey) o;
            return Objects.equal(viewTypes, key.viewTypes) &&
                Objects.equal(delegateType, key.delegateType);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(viewTypes, delegateType);
        }
    }
}
